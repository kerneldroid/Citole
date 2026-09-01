package com.marotidev.citole.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.ConditionVariable
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class RustAudioPlayer(private val context: Context) {

    companion object {
        const val MAX_RUST_FILE_BYTES: Long = 32L * 1024L * 1024L
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
    @Volatile private var sampleRate: Int = 44100
    @Volatile private var channels: Int = 2
    @Volatile private var bytesPerFrame: Int = 4
    @Volatile private var seekRequestMs: Long = -1L

    @Volatile private var onCompletion: (() -> Unit)? = null
    @Volatile private var onError: ((Throwable) -> Unit)? = null

    fun setOnCompletionListener(listener: (() -> Unit)?) { onCompletion = listener }
    fun setOnErrorListener(listener: ((Throwable) -> Unit)?) { onError = listener }

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
        val pcm = CitoleEngine.decodeToPcmSafe(resolved) ?: return false
        if (pcm.isEmpty()) return false
        val sr = info?.sampleRate ?: 44100
        val ch = info?.channels ?: 2
        return playPcm(pcm, sr, ch)
    }

    fun playPcm(pcm: ByteArray, sr: Int, ch: Int): Boolean {
        stop()
        return try {
            sampleRate = if (sr > 0) sr else 44100
            channels = if (ch in 1..8) ch else 2
            bytesPerFrame = channels * 2
            val frames = if (bytesPerFrame > 0) pcm.size / bytesPerFrame else 0
            durationMs = if (sampleRate > 0) frames * 1000L / sampleRate else 0L
            pcmBytes = pcm
            positionMsAtomic.set(0L)
            seekRequestMs = -1L
            stopFlag.set(false)
            pauseFlag.set(false)
            pauseGate.open()
            val channelConfig = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val actualChannels = if (channels == 1) 1 else 2
            val actualPcm = if (channels != 1 && channels != 2) remixToStereo(pcm, channels) else pcm
            if (channels != 1 && channels != 2) {
                bytesPerFrame = 4
                pcmBytes = actualPcm
                this.channels = 2
            }
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
            val bufSize = maxOf(minBuf * 2, CHUNK_BYTES * 4)
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val fmt = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(channelConfig)
                .build()
            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            audioTrack = track
            track.play()
            isPlaying = true
            val bytes = pcmBytes ?: actualPcm
            playbackThread = thread(start = true, name = "RustAudioPlayer") {
                var offset = 0
                val total = bytes.size
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
            val frames = if (durationMs > 0) (pcmBytes?.size ?: 0) / bytesPerFrame else 0
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
