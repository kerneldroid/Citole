package com.marotidev.citole.data.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.marotidev.citole.MainActivity
import com.marotidev.citole.R
import com.marotidev.citole.data.repository.RecommendationRepository
import com.marotidev.citole.data.repository.TrackLogRepository
import com.marotidev.citole.data.state.PlaybackStateHolder
import com.marotidev.citole.engine.CitoleEngine
import com.marotidev.citole.engine.RustAudioPlayer
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var recommendationRepository: RecommendationRepository
    @Inject lateinit var trackLogRepository: TrackLogRepository
    @Inject lateinit var playbackStateHolder: PlaybackStateHolder
    @Inject lateinit var audioService: AudioService

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var mediaSession: MediaSession? = null
    private var player: Player? = null
    var rustPlayer: RustAudioPlayer? = null
        private set
    @Volatile var isRustActive: Boolean = false
        private set
    private var currentRustPath: String? = null

    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var noisyReceiver: BroadcastReceiver? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        registerNoisyReceiver()

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId("citole_channel")
                .build()
                .also { it.setSmallIcon(R.drawable.ic_citole_inverse) }
        )

        rustPlayer = RustAudioPlayer(this).apply {
            setOnCompletionListener {
                val nextIndex = (playbackStateHolder.currentIndex.value + 1)
                if (nextIndex < playbackStateHolder.playerQueue.value.size + playbackStateHolder.generatedQueue.value.size) {
                    val item = if (nextIndex < playbackStateHolder.playerQueue.value.size) playbackStateHolder.playerQueue.value[nextIndex]
                    else playbackStateHolder.generatedQueue.value[nextIndex - playbackStateHolder.playerQueue.value.size]
                    playTrackWithEngine(item.track.uri.toString(), nextIndex)
                } else {
                    playbackStateHolder.currentlyPlaying.value?.let {
                        trackLogRepository.updateLogTimeValues(playbackStateHolder.queueId.value, it.track.id, System.currentTimeMillis(), durationMsForRust())
                    }
                    abandonFocus()
                }
            }
        }

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        player?.let {
            mediaSession = MediaSession.Builder(this, it)
                .setSessionActivity(pendingIntent)
                .setCallback(RustDelegatingCallback())
                .build()

            it.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val index = player?.currentMediaItemIndex ?: 0
                    playbackStateHolder.currentIndex.value = index
                    if (index >= playbackStateHolder.playerQueue.value.size) {
                        trackLogRepository.addInitialEmptyQueueLog(
                            playbackStateHolder.queueId.value,
                            playbackStateHolder.generatedQueue.value.map { item -> item.track }
                        )
                        playbackStateHolder.playerQueue.update { queue -> queue + playbackStateHolder.generatedQueue.value }
                        playbackStateHolder.generatedQueue.update { emptyList() }

                        playbackStateHolder.queueSnapshotAtRegeneration.value = playbackStateHolder.playerQueue.value.map { queueItem -> queueItem.track.id }
                    }

                    if (playbackStateHolder.currentIndex.value > playbackStateHolder.playerQueue.value.size - 4) {
                        serviceScope.launch {
                            playbackStateHolder.checkExtendQueue(recommendationRepository, player, audioService, force = true)
                        }
                    }

                    playbackStateHolder.currentlyPlaying.value = playbackStateHolder.playerQueue.value.getOrNull(index)

                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    @Player.DiscontinuityReason reason: Int
                ) {
                    super.onPositionDiscontinuity(oldPosition, newPosition, reason)
                    val trackChanged = oldPosition.mediaItemIndex != newPosition.mediaItemIndex

                    if (trackChanged) {
                        val finishedTrack = oldPosition.mediaItem ?: return
                        val finalPositionMs = oldPosition.positionMs

                        trackLogRepository.updateLogTimeValues(
                            playbackStateHolder.queueId.value,
                            finishedTrack.mediaId.toLongOrNull() ?: 0L,
                            playbackEndedMs = System.currentTimeMillis(), playbackDurationMs = finalPositionMs
                        )
                    }
                }

                override fun onPlaybackStateChanged(@Player.State playbackState: Int) {
                    super.onPlaybackStateChanged(playbackState)

                    if (playbackState == Player.STATE_ENDED) {
                        playbackStateHolder.currentlyPlaying.value?.let {
                            trackLogRepository.updateLogTimeValues(
                                playbackStateHolder.queueId.value, trackId = it.track.id,
                                System.currentTimeMillis(), player?.duration ?: 0
                            )
                        }
                    }
                }
            })
        }
    }

    private fun registerNoisyReceiver() {
        noisyReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                    if (isRustActive) rustPlayer?.pause() else (player as? ExoPlayer)?.pause()
                }
            }
        }
        try {
            registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        } catch (_: Throwable) {}
    }

    private fun requestFocus(): Boolean {
        val am = audioManager ?: return true
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setOnAudioFocusChangeListener { focus ->
                        when (focus) {
                            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                                if (isRustActive) rustPlayer?.pause() else (player as? ExoPlayer)?.pause()
                            }
                            AudioManager.AUDIOFOCUS_GAIN -> {
                                if (isRustActive) rustPlayer?.resume() else (player as? ExoPlayer)?.play()
                            }
                        }
                    }
                    .build()
                focusRequest = req
                am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    { focus ->
                        when (focus) {
                            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                                if (isRustActive) rustPlayer?.pause() else (player as? ExoPlayer)?.pause()
                            }
                            AudioManager.AUDIOFOCUS_GAIN -> {
                                if (isRustActive) rustPlayer?.resume() else (player as? ExoPlayer)?.play()
                            }
                        }
                    },
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        } catch (_: Throwable) { true }
    }

    private fun abandonFocus() {
        try {
            val am = audioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        } catch (_: Throwable) {}
        focusRequest = null
    }

    private fun isStreaming(path: String): Boolean {
        val l = path.lowercase()
        return l.startsWith("http://") || l.startsWith("https://") || l.startsWith("rtsp://") || l.startsWith("rtmp://")
    }

    private fun getFileSize(path: String): Long? {
        return try {
            when {
                path.startsWith("content://") -> contentResolver.openAssetFileDescriptor(Uri.parse(path), "r")?.use { it.length.takeIf { v -> v >= 0 } }
                path.startsWith("file://") -> File(Uri.parse(path).path ?: path).let { if (it.exists()) it.length() else null }
                else -> File(path).let { if (it.exists()) it.length() else null }
            }
        } catch (_: Throwable) { null }
    }

    fun shouldUseRust(path: String): Boolean {
        if (!CitoleEngine.isAvailable()) return false
        if (isStreaming(path)) return false
        val size = getFileSize(path)
        if (size != null && size >= 64L * 1024L * 1024L) return false
        val fmt = CitoleEngine.probeFormatSafe(path)
        return RustAudioPlayer.canHandle(fmt, path, this)
    }

    fun playTrackWithEngine(path: String, queueIndex: Int): Boolean {
        val useRust = shouldUseRust(path)
        return if (useRust) {
            try {
                (player as? ExoPlayer)?.pause()
                requestFocus()
                isRustActive = true
                currentRustPath = path
                playbackStateHolder.currentIndex.value = queueIndex
                playbackStateHolder.currentlyPlaying.value = when {
                    queueIndex < playbackStateHolder.playerQueue.value.size -> playbackStateHolder.playerQueue.value[queueIndex]
                    else -> {
                        val gi = queueIndex - playbackStateHolder.playerQueue.value.size
                        playbackStateHolder.generatedQueue.value.getOrNull(gi)
                    }
                }
                val ok = rustPlayer?.play(path) ?: false
                if (!ok) {
                    isRustActive = false
                    abandonFocus()
                    playViaExo(queueIndex)
                    return false
                }
                true
            } catch (_: Throwable) {
                isRustActive = false
                abandonFocus()
                playViaExo(queueIndex)
                false
            }
        } else {
            isRustActive = false
            rustPlayer?.stop()
            abandonFocus()
            playViaExo(queueIndex)
            false
        }
    }

    private fun playViaExo(index: Int) {
        try {
            requestFocus()
            player?.seekTo(index, 0)
            player?.prepare()
            player?.play()
        } catch (_: Throwable) {}
    }

    fun pauseWithEngine() {
        if (isRustActive) rustPlayer?.pause() else (player as? ExoPlayer)?.pause()
    }

    fun resumeWithEngine() {
        if (isRustActive) {
            requestFocus()
            rustPlayer?.resume()
        } else {
            requestFocus()
            (player as? ExoPlayer)?.play()
        }
    }

    fun seekWithEngine(ms: Long) {
        if (isRustActive) rustPlayer?.seekTo(ms) else player?.seekTo(ms)
    }

    fun stopRust() {
        rustPlayer?.stop()
        isRustActive = false
        currentRustPath = null
        abandonFocus()
    }

    fun isRustPlaying(): Boolean = isRustActive && (rustPlayer?.isPlaying == true)
    fun rustPosition(): Long = rustPlayer?.currentPosition ?: 0L
    fun rustDuration(): Long = rustPlayer?.duration ?: 0L
    private fun durationMsForRust(): Long = rustPlayer?.duration ?: 0L

    fun exoPlayer(): Player? = player

    override fun onDestroy() {
        try { noisyReceiver?.let { unregisterReceiver(it) } } catch (_: Throwable) {}
        noisyReceiver = null
        abandonFocus()
        rustPlayer?.release()
        rustPlayer = null
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    private inner class RustDelegatingCallback : MediaSession.Callback {
        override fun onPlay(mediaSession: MediaSession, controller: MediaSession.ControllerInfo): Int {
            if (isRustActive) {
                resumeWithEngine()
                return 0
            }
            return super.onPlay(mediaSession, controller)
        }

        override fun onPause(mediaSession: MediaSession, controller: MediaSession.ControllerInfo): Int {
            if (isRustActive) {
                pauseWithEngine()
                return 0
            }
            return super.onPause(mediaSession, controller)
        }

        override fun onSeekTo(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            positionMs: Long
        ): Int {
            if (isRustActive) {
                seekWithEngine(positionMs)
                return 0
            }
            return super.onSeekTo(mediaSession, controller, positionMs)
        }

        override fun onStop(mediaSession: MediaSession, controller: MediaSession.ControllerInfo): Int {
            if (isRustActive) {
                stopRust()
                return 0
            }
            return super.onStop(mediaSession, controller)
        }
    }
}
