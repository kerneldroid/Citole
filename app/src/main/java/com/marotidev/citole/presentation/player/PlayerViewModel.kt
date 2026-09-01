package com.marotidev.citole.presentation.player

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.common.util.concurrent.MoreExecutors
import com.marotidev.citole.data.local.PlaylistTrack
import com.marotidev.citole.data.repository.PlaylistRepository
import com.marotidev.citole.data.repository.RecommendationRepository
import com.marotidev.citole.data.service.AudioService
import com.marotidev.citole.data.service.PlaybackService
import com.marotidev.citole.data.repository.TrackLogRepository
import com.marotidev.citole.data.state.PlaybackStateHolder
import com.marotidev.citole.data.state.QueueItem
import com.marotidev.citole.engine.CitoleEngine
import com.marotidev.citole.engine.RustAudioPlayer
import com.materialkolor.ktx.themeColor
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val application: Application,
    private val audioService: AudioService,
    private val trackLogRepository: TrackLogRepository,
    private val playbackStateHolder: PlaybackStateHolder,
    private val recommendationRepository: RecommendationRepository,
    private val playlistRepository: PlaylistRepository
) : ViewModel() {

    val currentlyPlaying = playbackStateHolder.currentlyPlaying

    val playerQueue = playbackStateHolder.playerQueue
    val generatedQueue = playbackStateHolder.generatedQueue
    val currentIndex = playbackStateHolder.currentIndex

    var playing by mutableStateOf(false)
        private set

    var progress by mutableLongStateOf(0L)

    private var player: MediaController? = null
    private var rustPlayer: RustAudioPlayer? = null

    private val _useRust = MutableStateFlow(false)
    val useRust: StateFlow<Boolean> = _useRust.asStateFlow()

    private val _currentFormat = MutableStateFlow<CitoleEngine.Format?>(null)
    val currentFormat: StateFlow<CitoleEngine.Format?> = _currentFormat.asStateFlow()

    private val _currentFormatLive = MutableLiveData<CitoleEngine.Format?>()
    val currentFormatLive: LiveData<CitoleEngine.Format?> = _currentFormatLive

    private val _rustDuration = MutableStateFlow(0L)
    val rustDuration: StateFlow<Long> = _rustDuration.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private var currentRustPath: String? = null

    var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF)

    var isFavorite = combine(
        playlistRepository.getPlaylistTracksFromId(1),
        currentlyPlaying
    ) { favoriteTracks, playing ->
        val ids = favoriteTracks.map { it.trackId }
        playing?.track?.id in ids
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    private var progressJob: Job? = null
    private var rustHandler: Handler? = null
    private var rustProgressRunnable: Runnable? = null

    private var systemPrimaryColor = Color.Cyan

    private val _themeColor = MutableStateFlow(Color.Gray)
    val themeColor: StateFlow<Color> = _themeColor.asStateFlow()

    private val imageLoader = ImageLoader(application)


    private suspend fun updateColorFromAlbumArt(artworkUri: Uri?, context: Context) {
        if (artworkUri == null) {
            _themeColor.value = systemPrimaryColor
            return
        }

        val request = ImageRequest.Builder(context)
            .data(artworkUri)
            .size(64)
            .allowHardware(false)
            .build()

        val result = imageLoader.execute(request)
        if (result is SuccessResult) {

            val seedColor = withContext(Dispatchers.Default) {
                val bitmap = result.drawable.toBitmap().asImageBitmap()
                bitmap.themeColor(fallback = systemPrimaryColor)
            }
            _themeColor.value = seedColor
        }
        else {
            _themeColor.value = systemPrimaryColor
        }
    }

    init {
        rustPlayer = RustAudioPlayer(application).apply {
            setOnCompletionListener {
                viewModelScope.launch {
                    val next = playbackStateHolder.currentIndex.value + 1
                    if (next < playerQueue.value.size) {
                        skipInQueue(next)
                    } else if (generatedQueue.value.isNotEmpty()) {
                        val item = generatedQueue.value.firstOrNull()
                        if (item != null) skipToGeneratedInQueue(item)
                    } else {
                        playing = false
                        stopProgressUpdate()
                    }
                }
            }
        }
        val sessionToken = SessionToken(
            application,
            ComponentName(application, PlaybackService::class.java)
        )
        val controllerFuture = MediaController.Builder(application, sessionToken).buildAsync()
        controllerFuture.addListener(
            {
                player = controllerFuture.get()

                playing = if (_useRust.value) rustPlayer?.isPlaying ?: false else player?.isPlaying ?: false
                if (playing) {
                    startProgressUpdate()
                }

                setupListeners()
            },
            MoreExecutors.directExecutor()
        )
    }

    private fun isStreaming(path: String): Boolean {
        val l = path.lowercase()
        return l.startsWith("http://") || l.startsWith("https://") || l.startsWith("rtsp://")
    }

    private fun getFileSize(path: String): Long? {
        return try {
            when {
                path.startsWith("content://") -> application.contentResolver.openAssetFileDescriptor(Uri.parse(path), "r")?.use { it.length.takeIf { v -> v >= 0 } }
                path.startsWith("file://") -> File(Uri.parse(path).path ?: path).let { if (it.exists()) it.length() else null }
                else -> File(path).let { if (it.exists()) it.length() else null }
            }
        } catch (_: Throwable) { null }
    }

    private fun shouldUseRust(path: String): Boolean {
        if (!CitoleEngine.isAvailable()) return false
        if (isStreaming(path)) return false
        val size = getFileSize(path)
        if (size != null && size >= 64L * 1024L * 1024L) return false
        val fmt = CitoleEngine.probeFormatSafe(path)
        return RustAudioPlayer.canHandle(fmt, path, application)
    }

    private fun trackPath(track: AudioService.TrackData): String = track.uri.toString()

    private fun playViaRust(path: String, index: Int): Boolean {
        val ok = rustPlayer?.play(path) ?: false
        if (ok) {
            _useRust.value = true
            currentRustPath = path
            val fmt = CitoleEngine.probeFormatSafe(path)
            _currentFormat.value = fmt
            _currentFormatLive.postValue(fmt)
            val info = CitoleEngine.getInfoSafe(path)
            val dur = info?.durationSecs?.let { (it * 1000).toLong() } ?: rustPlayer?.duration ?: 0L
            _rustDuration.value = dur
            _duration.value = dur
            playbackStateHolder.currentIndex.value = index
            playbackStateHolder.currentlyPlaying.value = playerQueue.value.getOrNull(index)
                ?: generatedQueue.value.getOrNull(index - playerQueue.value.size)
            playing = true
            startProgressUpdate()
        }
        return ok
    }

    private fun playViaExo(tracks: List<AudioService.TrackData>, startIndex: Int, startPosition: Long) {
        _useRust.value = false
        _currentFormat.value = tracks.getOrNull(startIndex)?.let { CitoleEngine.probeFormatSafe(trackPath(it)) }
        _currentFormatLive.postValue(_currentFormat.value)
        _rustDuration.value = 0L
        _duration.value = tracks.getOrNull(startIndex)?.duration ?: 0L
        rustPlayer?.stop()
        stopRustHandler()
        val mediaItems = tracks.map { with(audioService) { it.toMediaItem() } }
        player?.setMediaItems(mediaItems, startIndex, startPosition)
        player?.prepare()
        player?.play()
        playing = true
        startProgressUpdate()
    }

    private fun setupListeners() {
        player?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_ENDED -> {
                        stopProgressUpdate()
                    }
                }
            }

            override fun onRepeatModeChanged(mode: Int) {
                repeatMode = mode
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady) {
                    startProgressUpdate()
                } else {
                    stopProgressUpdate()
                }
                playing = playWhenReady
            }
        })

        viewModelScope.launch {
            playbackStateHolder.currentlyPlaying
                .map { queueItem -> queueItem?.track?.artworkUri }
                .distinctUntilChanged()
                .collect { artworkUri ->
                    updateColorFromAlbumArt(artworkUri, application)
                }
        }

        viewModelScope.launch {
            _useRust.collect { isRust ->
                if (isRust) _currentFormatLive.postValue(_currentFormat.value)
            }
        }
    }


    fun updateDefaultColor(color: Color) {
        if (playbackStateHolder.currentlyPlaying.value == null) {
            systemPrimaryColor = color
            _themeColor.value = color
        }
    }

    private fun startProgressUpdate() {
        progressJob?.cancel()
        stopRustHandler()
        if (_useRust.value) {
            val handler = Handler(Looper.getMainLooper())
            rustHandler = handler
            val runnable = object : Runnable {
                override fun run() {
                    progress = rustPlayer?.currentPosition ?: 0L
                    _duration.value = rustPlayer?.duration ?: _rustDuration.value
                    handler.postDelayed(this, 50)
                }
            }
            rustProgressRunnable = runnable
            handler.post(runnable)
            progressJob = viewModelScope.launch {
                while (true) {
                    delay(50.milliseconds)
                    if (!_useRust.value) break
                }
            }
        } else {
            progressJob = viewModelScope.launch {
                while (true) {
                    progress = player?.currentPosition ?: 0L
                    player?.duration?.let { if (it > 0) _duration.value = it }
                    delay(200.milliseconds)
                }
            }
        }
    }

    private fun stopRustHandler() {
        rustHandler?.removeCallbacks(rustProgressRunnable ?: return)
        rustHandler = null
        rustProgressRunnable = null
    }

    private fun stopProgressUpdate() {
        progressJob?.cancel()
        stopRustHandler()
    }

    fun playQueue(tracks: List<AudioService.TrackData>, startIndex: Int = 0, startPosition: Long = 0,
                  givenQueueId: Long? = null) {
        if (givenQueueId == null) {
            val queueId = System.currentTimeMillis()
            playbackStateHolder.queueId.value = queueId
            trackLogRepository.addInitialEmptyQueueLog(queueId, tracks)
        } else {
            playbackStateHolder.queueId.value = givenQueueId
        }
        playbackStateHolder.playerQueue.update {
            tracks.map { track -> QueueItem(track) }
        }
        val startTrack = tracks.getOrNull(startIndex)
        val startPath = startTrack?.let { trackPath(it) } ?: ""
        if (startTrack != null && shouldUseRust(startPath)) {
            val ok = playViaRust(startPath, startIndex)
            if (ok) {
                if (tracks.size > 1) {
                    val mediaItems = tracks.map { with(audioService) { it.toMediaItem() } }
                    try { player?.setMediaItems(mediaItems, startIndex, 0) } catch (_: Throwable) {}
                }
                if (startPosition > 0) {
                    rustPlayer?.seekTo(startPosition)
                    progress = startPosition
                }
                return
            }
        }
        playViaExo(tracks, startIndex, startPosition)
    }

    fun addToQueue(track: AudioService.TrackData, index: Int = playerQueue.value.size) {
        val mediaItem = with(audioService) {track.toMediaItem()}
        if (playerQueue.value.isEmpty()) {
            playbackStateHolder.queueId.value = System.currentTimeMillis()

            playbackStateHolder.playerQueue.update {
                it + QueueItem(track)
            }

            player?.setMediaItem(mediaItem)
            player?.prepare()
            player?.play()
            progress = 0
        } else {
            playbackStateHolder.playerQueue.update { currentQueue ->
                currentQueue.toMutableList().apply {
                    add(index, QueueItem(track))
                }
            }
            player?.addMediaItem(index, mediaItem)
            
            syncQueueLogIndexes()
        }

        trackLogRepository.addEmptyPlayLog(track, index, playbackStateHolder.queueId.value)
    }

    fun removeFromPlayerQueue(index: Int) {
        trackLogRepository.deleteLogFromQueue(index, playbackStateHolder.queueId.value)

        playbackStateHolder.playerQueue.update { currentQueue ->
            currentQueue.toMutableList().apply {
                removeAt(index)
            }
        }

        syncQueueLogIndexes()

        player?.removeMediaItem(index)
    }

    fun removeFromGeneratedQueue(item: QueueItem) {
        val from = generatedQueue.value.indexOf(item)
        player?.removeMediaItem(from + playerQueue.value.size)
        playbackStateHolder.generatedQueue.update { currentQueue ->
            currentQueue.toMutableList().apply {
                remove(item)
            }
        }
    }

    fun playerQueueItemReorder(item: QueueItem, items: List<Any>) {
        val from = playerQueue.value.indexOf(item)
        val to = items.indexOf(item)
        if (from == -1 || to == -1 || to >= playerQueue.value.size) return

        player?.moveMediaItem(from, to)
        playbackStateHolder.playerQueue.update { currentQueue ->
            currentQueue.toMutableList().apply {
                if (remove(item)) {
                    add(to, item)
                }
            }
        }

        syncQueueLogIndexes()
    }

    fun generatedQueueToPlayerQueue(item: QueueItem, items: List<Any>) {
        val from = generatedQueue.value.indexOf(item)
        val to = items.indexOf(item)
        if (from == -1 || to == -1 || to > playerQueue.value.size) return

        player?.moveMediaItem(from + playerQueue.value.size, to)

        trackLogRepository.addEmptyPlayLog(item.track, to, playbackStateHolder.queueId.value)

        playbackStateHolder.playerQueue.update { currentQueue ->
            currentQueue.toMutableList().apply {
                add(to, item.copy(isGenerated = false))
            }
        }

        playbackStateHolder.generatedQueue.update { currentQueue ->
            currentQueue.toMutableList().apply {
                remove(item)
            }
        }

        syncQueueLogIndexes()
    }

    fun decideReorderType(item: QueueItem, items: List<Any>) {
        if (item in generatedQueue.value) {
            generatedQueueToPlayerQueue(item, items)
        } else {
            playerQueueItemReorder(item, items)
        }
    }

    fun skipInQueue(newIndex: Int) {
        val item = playerQueue.value.getOrNull(newIndex) ?: generatedQueue.value.getOrNull(newIndex - playerQueue.value.size)
        val path = item?.let { trackPath(it.track) } ?: ""
        if (item != null && shouldUseRust(path)) {
            val ok = playViaRust(path, newIndex)
            if (ok) return
        }
        _useRust.value = false
        _currentFormat.value = item?.let { CitoleEngine.probeFormatSafe(trackPath(it.track)) }
        _currentFormatLive.postValue(_currentFormat.value)
        rustPlayer?.stop()
        stopRustHandler()
        player?.seekTo(newIndex, 0L)
        player?.play()
        playing = true
        startProgressUpdate()
    }

    fun skipToGeneratedInQueue(item: QueueItem) {
        viewModelScope.launch {
            playbackStateHolder.generatedQueue.update { currentQueue ->
                currentQueue.filterNot { it.id == item.id }
            }
            playbackStateHolder.playerQueue.update { currentQueue ->
                currentQueue + item.copy(isGenerated = false)
            }

            with (audioService) {
                player?.addMediaItem(item.track.toMediaItem())
            }
            player?.seekTo(playerQueue.value.size - 1, 0)

            delay(1000.milliseconds)

            val newTracks = recommendationRepository.extendQueue(playerQueue.value.map {queueItem -> queueItem.track.id }, 12)

            playbackStateHolder.generatedQueue.update {
                newTracks.map {track -> QueueItem(track, isGenerated = true) }
            }

            player?.removeMediaItems(playerQueue.value.size, playerQueue.value.size + generatedQueue.value.size)
            with (audioService) {
                player?.addMediaItems(newTracks.map {track -> track.toMediaItem()})
            }
        }
    }

    fun checkExtendQueue() {
        viewModelScope.launch {
            playbackStateHolder.checkExtendQueue(recommendationRepository, player, audioService)
        }
    }

    fun syncQueueLogIndexes() {
        playerQueue.value.forEachIndexed { index, item ->
            trackLogRepository.updateLogQueueIndex(playbackStateHolder.queueId.value,
                item.track.id, index)
        }
    }

    fun toggleRepeat()  {
        player?.repeatMode = when (player?.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun toggleFavorite()  {
        currentlyPlaying.value?.track?.let { playing ->
            viewModelScope.launch {
                val ids = playlistRepository.getPlaylistTracksFromId(1).first().map { it.trackId }
                if (playing.id in ids) {
                    playlistRepository.removeFromPlaylist(playing.id, 1)
                } else {
                    playlistRepository.addToPlaylist(
                        PlaylistTrack(playing.id, 1, ids.size)
                    )
                }
            }
        }
    }

    fun togglePlayPause() {
        if (_useRust.value) {
            if (rustPlayer?.isPlaying == true) {
                rustPlayer?.pause()
                playing = false
                stopProgressUpdate()
            } else {
                rustPlayer?.resume()
                playing = true
                startProgressUpdate()
            }
        } else {
            if (player?.isPlaying == true) {
                player?.pause()
            } else {
                player?.play()
            }
        }
    }

    fun seekTo(position: Long) {
        val clamped = position.coerceIn(0L, _duration.value.takeIf { it > 0 } ?: Long.MAX_VALUE)
        if (_useRust.value) {
            rustPlayer?.seekTo(clamped)
        } else {
            player?.seekTo(clamped)
        }
        progress = clamped
    }

    fun skipNext() {
        if (_useRust.value) {
            val next = playbackStateHolder.currentIndex.value + 1
            if (next < playerQueue.value.size || next < playerQueue.value.size + generatedQueue.value.size) skipInQueue(next)
            progress = 0
        } else {
            if (player?.hasNextMediaItem() ?: false) {
                player?.seekToNext()
                progress = 0
            }
        }
    }

    fun skipPrevious() {
        if (_useRust.value) {
            val prev = (playbackStateHolder.currentIndex.value - 1).coerceAtLeast(0)
            skipInQueue(prev)
            progress = 0
        } else {
            player?.seekToPrevious()
            progress = 0
        }
    }

    fun dismissPlayer() {
        playbackStateHolder.currentlyPlaying.value?.let {
            trackLogRepository.updateLogTimeValues(playbackStateHolder.queueId.value, trackId = it.track.id,
                System.currentTimeMillis(), progress)
        }
        playbackStateHolder.playerQueue.update { emptyList() }
        playbackStateHolder.generatedQueue.update { emptyList() }
        progress = 0
        playbackStateHolder.currentIndex.value = 0
        playbackStateHolder.currentlyPlaying.value = null
        _useRust.value = false
        _currentFormat.value = null
        _currentFormatLive.postValue(null)
        _rustDuration.value = 0L
        _duration.value = 0L
        currentRustPath = null
        rustPlayer?.stop()
        player?.stop()
        player?.clearMediaItems()
        stopProgressUpdate()
        playing = false
    }

    override fun onCleared() {
        super.onCleared()
        stopProgressUpdate()
        rustPlayer?.release()
        rustPlayer = null
        player?.release()
    }
}
