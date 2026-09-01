/*
Copyright (C) <2026>  <Balint Maroti>

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.

*/

package com.marotidev.citole.data.service

import android.app.PendingIntent
import android.content.Intent
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.internal.toLongOrDefault
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

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

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
                .build()

            it.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val index = player?.currentMediaItemIndex ?: 0
                    playbackStateHolder.currentIndex.value = index
                    if (index >= playbackStateHolder.playerQueue.value.size) {
                        //entered generated tracks
                        trackLogRepository.addInitialEmptyQueueLog(
                            playbackStateHolder.queueId.value,
                            playbackStateHolder.generatedQueue.value.map {item -> item.track }
                        )
                        playbackStateHolder.playerQueue.update {queue -> queue + playbackStateHolder.generatedQueue.value }
                        playbackStateHolder.generatedQueue.update { emptyList() }

                        playbackStateHolder.queueSnapshotAtRegeneration.value = playbackStateHolder.playerQueue.value.map {queueItem -> queueItem.track.id }
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

                        trackLogRepository.updateLogTimeValues(playbackStateHolder.queueId.value,
                            finishedTrack.mediaId.toLongOrDefault(0),
                            playbackEndedMs = System.currentTimeMillis(), playbackDurationMs = finalPositionMs)
                    }
                }

                override fun onPlaybackStateChanged(@Player.State playbackState: Int) {
                    super.onPlaybackStateChanged(playbackState)

                    if (playbackState == Player.STATE_ENDED) {
                        playbackStateHolder.currentlyPlaying.value?.let {
                            trackLogRepository.updateLogTimeValues(playbackStateHolder.queueId.value, trackId = it.track.id,
                                System.currentTimeMillis(), player?.duration ?: 0)
                        }
                    }
                }
            })
        }
    }

    fun shouldUseRust(path: String): Boolean {
        if (!CitoleEngine.isAvailable()) return false
        val fmt = CitoleEngine.probeFormatSafe(path)
        return RustAudioPlayer.canHandle(fmt, path, this)
    }

    fun playTrackWithEngine(path: String, queueIndex: Int): Boolean {
        val useRust = shouldUseRust(path)
        return if (useRust) {
            try {
                (player as? ExoPlayer)?.pause()
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
                    playViaExo(queueIndex)
                    return false
                }
                true
            } catch (_: Throwable) {
                isRustActive = false
                playViaExo(queueIndex)
                false
            }
        } else {
            isRustActive = false
            rustPlayer?.stop()
            playViaExo(queueIndex)
            false
        }
    }

    private fun playViaExo(index: Int) {
        try {
            player?.seekTo(index, 0)
            player?.prepare()
            player?.play()
        } catch (_: Throwable) {}
    }

    fun pauseWithEngine() {
        if (isRustActive) rustPlayer?.pause() else (player as? ExoPlayer)?.pause()
    }

    fun resumeWithEngine() {
        if (isRustActive) rustPlayer?.resume() else (player as? ExoPlayer)?.play()
    }

    fun seekWithEngine(ms: Long) {
        if (isRustActive) rustPlayer?.seekTo(ms) else player?.seekTo(ms)
    }

    fun stopRust() {
        rustPlayer?.stop()
        isRustActive = false
        currentRustPath = null
    }

    fun isRustPlaying(): Boolean = isRustActive && (rustPlayer?.isPlaying == true)
    fun rustPosition(): Long = rustPlayer?.currentPosition ?: 0L
    fun rustDuration(): Long = rustPlayer?.duration ?: 0L
    private fun durationMsForRust(): Long = rustPlayer?.duration ?: 0L

    fun exoPlayer(): Player? = player

    override fun onDestroy() {
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

    //this always accepts connection requests
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession
}