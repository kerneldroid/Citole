package com.marotidev.citole.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.os.ConditionVariable
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class RustAudioPlayer(private val context: Context) {

    companion object {
        const val MAX_RUST_FILE_BYTES: Long = 64L * 1024L * 1024L
        private const val CHUNK_BYTES = 8192

        fun canHandle(format: CitoleEngine.Format): Boolean {
            if (!CitoleEngine.isAvailable()) return false
            return format != CitoleEngine.Format.Unknown
        }

        fun canHandle(format: CitoleEngine.Format, path: String?, ctx: Context? = null): Boolean {
            if (!CitoleEngine.isAvailable()) return false
            if (format == CitoleEngine.Format.Unknown) return false
            if (path != null) {
                val size = getFileSize(path, ctx)
                if (size != null && size > MAX_RUST_FILE_BYTES) return false
            }
            return true
        }

        fun canHandleForPath(path: String, ctx: Context? = null): Boolean {
            if (!CitoleEngine.isAvailable()) return false
            val fmt = CitoleEngine.probeFormatSafe(path)
            if (fmt == CitoleEngine.Format.Unknown) return false
            val size = getFileSize(path, ctx)
            if (size != null && size > MAX_RUST_FILE_BYTES) return false
            return true
        }

        private fun getFileSize(path: String, ctx: Context?): Long? {
            return try {
                when {
                    path.startsWith("content://") && ctx != null -> {
                        ctx.contentResolver.openAssetFileDescriptor(Uri.parse(path), "r")?.use { it.length.takeIf { l -> l >= 0 } }
                    }
                    path.startsWith("file://") -> File(Uri.parse(path).path ?: path).let { if (it.exists()) it.length() else null }
                    else -> File(path).let { if (it.exists()) it.length() else null }
                }
            } catch (_: Throwable) { null }
        }
    }

    @Volatile var isPlaying: Boolean = false
        private set

    @Volatile private var durationMs: Long = 0L
    val duration: Long get() = durationMs

    private val positionMsAtomic = AtomicLong(0L)
    val currentPosition: Long get() = positionMsAtomic.get()

    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var playbackThread: Thread? = null
    private val stopFlag = AtomicBoolean(false)
    private val pauseFlag = AtomicBoolean(false)
    private val pauseGate = ConditionVariable(false)

    @Volatile private var pcmBytes: ByteArray? = null
    @Volatile private var pcmBuffer: ByteBuffer? = null
    @Volatile private var sampleRate: Int = 44100
    @Volatile private var channels: Int = 2
    @Volatile private var bytesPerFrame: Int = 4
    @Volatile private var seekRequestMs: Long = -1L

    @Volatile private var onCompletion: (() -> Unit)? = null
    @Volatile private var onError: ((Throwable) -> Unit)? = null

    fun setOnCompletionListener(listener: (() -> Unit)?) { onCompletion = listener }
    fun setOnErrorListener(listener: ((Throwable) -> Unit)?) { onError = listener }

    fun setVolume(volume: Float) {
        try {
            audioTrack?.setVolume(volume.coerceIn(0f, 1f))
        } catch (_: Throwable) {}
    }

    fun setPlaybackParams(speed: Float, pitch: Float = 1f) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val track = audioTrack ?: return
            val params = track.playbackParams ?: PlaybackParams()
            params.speed = speed.coerceIn(0.25f, 4f)
            params.pitch = pitch.coerceIn(0.5f, 2f)
            track.playbackParams = params
        } catch (_: Throwable) {}
    }

    fun setPreferredDevice(device: AudioDeviceInfo?) {
        try {
            audioTrack?.preferredDevice = device
        } catch (_: Throwable) {}
    }

    private fun resolveToFilePath(input: String): String? {
        return try {
            when {
                input.startsWith("/") && File(input).exists() -> input
                input.startsWith("file://") -> Uri.parse(input).path
                input.startsWith("content://") -> copyContentToCache(input)
                File(input).exists() -> input
                else -> copyContentToCache(input)
            }
        } catch (_: Throwable) { null }
    }

    private fun copyContentToCache(uriString: String): String? {
        return try {
            val uri = Uri.parse(uriString)
            val input = context.contentResolver.openInputStream(uri) ?: return null
            val tmp = File(context.cacheDir, "rust_pcm_${System.currentTimeMillis()}_${uriString.hashCode().toString(16)}.bin")
            input.use { ins ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(8192)
                    var r: Int
                    var total = 0L
                    while (ins.read(buf).also { r = it } != -1) {
                        if (total + r > MAX_RUST_FILE_BYTES + 1024 * 1024) break
                        out.write(buf, 0, r)
                        total += r
                    }
                }
            }
            tmp.absolutePath
        } catch (_: Throwable) { null }
    }

    fun play(path: String): Boolean {
        val resolved = resolveToFilePath(path) ?: path
        val fmt = CitoleEngine.probeFormatSafe(resolved)
        if (!canHandle(fmt, resolved, context)) return false
        val sizeCheck = getFileSize(resolved, context)
        if (sizeCheck != null && sizeCheck > MAX_RUST_FILE_BYTES) return false
        val info = CitoleEngine.getInfoSafe(resolved)
        val sr = info?.sampleRate ?: 44100
        val ch = info?.channels ?: 2
        val direct = try { CitoleEngine.decodeToPcmDirect(resolved) } catch (_: Throwable) { null }
        if (direct != null && direct.remaining() > 0 && ch in 1..2) {
            return playDirectInternal(direct, sr, ch)
        }
        val pcm = CitoleEngine.decodeToPcmSafe(resolved) ?: return false
        if (pcm.isEmpty()) return false
        return playPcm(pcm, sr, ch)
    }

    fun playPcm(pcm: ByteArray, sr: Int, ch: Int): Boolean {
        stop()
        return try {
            sampleRate = if (sr > 0) sr else 44100
            channels = if (ch in 1..8) ch else 2
            bytesPerFrame = channels * 2
            var frames = if (bytesPerFrame > 0) pcm.size / bytesPerFrame else 0
            var actualPcm = pcm
            var actualChannels = channels
            if (channels != 1 && channels != 2) {
                actualPcm = remixToStereo(pcm, channels)
                actualChannels = 2
                bytesPerFrame = 4
                frames = actualPcm.size / bytesPerFrame
            }
            durationMs = if (sampleRate > 0) frames * 1000L / sampleRate else 0L
            pcmBytes = actualPcm
            pcmBuffer = null
            channels = actualChannels
            positionMsAtomic.set(0L)
            seekRequestMs = -1L
            stopFlag.set(false)
            pauseFlag.set(false)
            pauseGate.open()
            val track = createTrack(sampleRate, actualChannels) ?: return false
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                try { track.release() } catch (_: Throwable) {}
                return false
            }
            audioTrack = track
            try { track.preferredDevice = null } catch (_: Throwable) {}
            track.play()
            isPlaying = true
            val bytes = actualPcm
            val total = bytes.size
            playbackThread = thread(start = true, name = "RustAudioPlayer") {
                var offset = 0
                try {
                    while (offset < total && !stopFlag.get()) {
                        if (pauseFlag.get()) {
                            pauseGate.block()
                            if (stopFlag.get()) break
                        }
                        val seek = seekRequestMs
                        if (seek >= 0) {
                            seekRequestMs = -1L
                            val targetFrame = (seek * sampleRate / 1000L).coerceIn(0L, frames.toLong())
                            offset = (targetFrame * bytesPerFrame).toInt().coerceIn(0, total)
                            positionMsAtomic.set(targetFrame * 1000L / sampleRate)
                        }
                        val remaining = total - offset
                        if (remaining <= 0) break
                        val chunk = minOf(CHUNK_BYTES, remaining)
                        val written = track.write(bytes, offset, chunk)
                        if (written < 0) break
                        if (written > 0) {
                            offset += written
                            val framesPlayed = offset / bytesPerFrame
                            positionMsAtomic.set(framesPlayed * 1000L / sampleRate)
                        } else {
                            Thread.sleep(5)
                        }
                    }
                    if (!stopFlag.get() && !pauseFlag.get()) {
                        positionMsAtomic.set(durationMs)
                    }
                } catch (e: Throwable) {
                    onError?.invoke(e)
                } finally {
                    try { track.stop() } catch (_: Throwable) {}
                    isPlaying = false
                    val completed = !stopFlag.get() && !pauseFlag.get() && offset >= total
                    if (completed) onCompletion?.invoke()
                }
            }
            true
        } catch (e: Throwable) {
            onError?.invoke(e)
            stop()
            false
        }
    }

    private fun playDirectInternal(buffer: ByteBuffer, sr: Int, ch: Int): Boolean {
        stop()
        return try {
            sampleRate = if (sr > 0) sr else 44100
            channels = if (ch in 1..2) ch else 2
            bytesPerFrame = channels * 2
            buffer.rewind()
            val total = buffer.remaining()
            val frames = if (bytesPerFrame > 0) total / bytesPerFrame else 0
            durationMs = if (sampleRate > 0) frames * 1000L / sampleRate else 0L
            pcmBuffer = buffer
            pcmBytes = null
            positionMsAtomic.set(0L)
            seekRequestMs = -1L
            stopFlag.set(false)
            pauseFlag.set(false)
            pauseGate.open()
            val track = createTrack(sampleRate, channels) ?: return false
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                try { track.release() } catch (_: Throwable) {}
                return false
            }
            audioTrack = track
            try { track.preferredDevice = null } catch (_: Throwable) {}
            track.play()
            isPlaying = true
            playbackThread = thread(start = true, name = "RustAudioPlayer") {
                var offset = 0
                try {
                    while (offset < total && !stopFlag.get()) {
                        if (pauseFlag.get()) {
                            pauseGate.block()
                            if (stopFlag.get()) break
                        }
                        val seek = seekRequestMs
                        if (seek >= 0) {
                            seekRequestMs = -1L
                            val targetFrame = (seek * sampleRate / 1000L).coerceIn(0L, frames.toLong())
                            offset = (targetFrame * bytesPerFrame).toInt().coerceIn(0, total)
                            positionMsAtomic.set(targetFrame * 1000L / sampleRate)
                            try { buffer.position(offset) } catch (_: Throwable) {}
                        }
                        val remaining = total - offset
                        if (remaining <= 0) break
                        val chunk = minOf(CHUNK_BYTES, remaining)
                        buffer.position(offset)
                        buffer.limit(offset + chunk)
                        val written = track.write(buffer, chunk, AudioTrack.WRITE_BLOCKING)
                        buffer.limit(total)
                        if (written < 0) break
                        if (written > 0) {
                            offset += written
                            val framesPlayed = offset / bytesPerFrame
                            positionMsAtomic.set(framesPlayed * 1000L / sampleRate)
                        } else {
                            Thread.sleep(5)
                        }
                    }
                    if (!stopFlag.get() && !pauseFlag.get()) {
                        positionMsAtomic.set(durationMs)
                    }
                } catch (e: Throwable) {
                    onError?.invoke(e)
                } finally {
                    try { track.stop() } catch (_: Throwable) {}
                    isPlaying = false
                    val completed = !stopFlag.get() && !pauseFlag.get() && offset >= total
                    if (completed) onCompletion?.invoke()
                }
            }
            true
        } catch (e: Throwable) {
            onError?.invoke(e)
            stop()
            false
        }
    }

    private fun createTrack(sr: Int, ch: Int): AudioTrack? {
        return try {
            val channelConfig = if (ch == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val minBuf = AudioTrack.getMinBufferSize(sr, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
            val bufSize = maxOf(if (minBuf > 0) minBuf * 2 else CHUNK_BYTES * 4, CHUNK_BYTES * 4)
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val fmt = AudioFormat.Builder()
                .setSampleRate(sr)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(channelConfig)
                .build()
            val builder = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
            builder.build()
        } catch (_: Throwable) { null }
    }

    private fun remixToStereo(src: ByteArray, srcChannels: Int): ByteArray {
        if (srcChannels <= 2) return src
        val frames = src.size / (srcChannels * 2)
        val out = ByteArray(frames * 4)
        var si = 0
        var di = 0
        repeat(frames) {
            val l0 = src[si].toInt() and 0xFF or ((src[si + 1].toInt() shl 8))
            val r0 = if (srcChannels >= 2) src[si + 2].toInt() and 0xFF or ((src[si + 3].toInt() shl 8)) else l0
            out[di] = (l0 and 0xFF).toByte()
            out[di + 1] = ((l0 shr 8) and 0xFF).toByte()
            out[di + 2] = (r0 and 0xFF).toByte()
            out[di + 3] = ((r0 shr 8) and 0xFF).toByte()
            si += srcChannels * 2
            di += 4
        }
        return out
    }

    fun pause() {
        if (!isPlaying) return
        pauseFlag.set(true)
        pauseGate.close()
        try { audioTrack?.pause() } catch (_: Throwable) {}
        isPlaying = false
    }

    fun resume() {
        if (!pauseFlag.get()) return
        pauseFlag.set(false)
        pauseGate.open()
        try { audioTrack?.play() } catch (_: Throwable) {}
        isPlaying = true
    }

    fun seekTo(ms: Long) {
        val clamped = ms.coerceIn(0L, durationMs)
        seekRequestMs = clamped
        if (!isPlaying && !pauseFlag.get()) {
            val frames = when {
                pcmBuffer != null -> (pcmBuffer?.capacity() ?: 0) / bytesPerFrame
                pcmBytes != null -> (pcmBytes?.size ?: 0) / bytesPerFrame
                else -> 0
            }
            val targetFrame = (clamped * sampleRate / 1000L).coerceIn(0L, frames.toLong())
            positionMsAtomic.set(targetFrame * 1000L / sampleRate)
        }
        if (pauseFlag.get()) {
            positionMsAtomic.set(clamped)
        }
    }

    fun stop() {
        stopFlag.set(true)
        pauseFlag.set(false)
        pauseGate.open()
        try { audioTrack?.stop() } catch (_: Throwable) {}
        try { audioTrack?.flush() } catch (_: Throwable) {}
        playbackThread?.let {
            try { it.join(800) } catch (_: Throwable) {}
        }
        playbackThread = null
        try { audioTrack?.release() } catch (_: Throwable) {}
        audioTrack = null
        pcmBytes = null
        pcmBuffer = null
        isPlaying = false
        positionMsAtomic.set(0L)
        seekRequestMs = -1L
    }

    fun release() {
        stop()
        onCompletion = null
        onError = null
    }
}
