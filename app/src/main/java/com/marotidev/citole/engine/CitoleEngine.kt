package com.marotidev.citole.engine

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

object CitoleEngine {

    enum class Format(val ordinalValue: Int) {
        AacLc(0),
        HeAacV1(1),
        HeAacV2(2),
        XHeAac(3),
        Mp3(4),
        Flac(5),
        Vorbis(6),
        Opus(7),
        AmrNb(8),
        AmrWb(9),
        Pcm(10),
        Wav(11),
        Midi(12),
        Unknown(-1);

        companion object {
            fun fromOrdinal(o: Int): Format = entries.find { it.ordinalValue == o } ?: Unknown
        }
    }

    data class PcmResult(
        val bytes: ByteArray,
        val sampleRate: Int = 44100,
        val channels: Int = 2
    )

    data class TrackInfo(
        val format: Format,
        val sampleRate: Int?,
        val channels: Int?,
        val frames: Long?,
        val durationSecs: Double?,
        val rawJson: String
    )

    companion object {
        private val libLoaded = AtomicBoolean(false)
        @Volatile private var libOk = false

        init {
            ensureLoaded()
        }

        private fun ensureLoaded() {
            if (libLoaded.compareAndSet(false, true)) {
                libOk = try {
                    System.loadLibrary("citole_engine")
                    true
                } catch (_: UnsatisfiedLinkError) {
                    false
                } catch (_: SecurityException) {
                    false
                }
            }
        }

        internal fun isLibOk(): Boolean = libOk
    }

    init {
        isLibOk()
    }

    @JvmName("isAvailable")
    @JvmStatic
    private external fun nativeIsAvailable(): Boolean

    @JvmName("probeFormat")
    @JvmStatic
    private external fun nativeProbeFormat(path: String): Int

    @JvmName("decodeToPcm")
    @JvmStatic
    private external fun nativeDecodeToPcm(path: String): ByteArray?

    @JvmName("decodeToPcmDirect")
    @JvmStatic
    private external fun nativeDecodeToPcmDirect(path: String): ByteBuffer?

    @JvmName("getInfo")
    @JvmStatic
    private external fun nativeGetInfo(path: String): String?

    @JvmName("isAvailableWrapper")
    fun isAvailable(): Boolean {
        if (!isLibOk()) return false
        return try {
            nativeIsAvailable()
        } catch (_: Throwable) {
            false
        }
    }

    @JvmName("probeFormatTyped")
    fun probeFormat(path: String): Format? {
        if (!isAvailable()) {
            val f = probeFallback(path)
            return f.takeIf { it != Format.Unknown }
        }
        return try {
            val ord = nativeProbeFormat(path)
            val f = Format.fromOrdinal(ord)
            if (f == Format.Unknown) probeFallback(path).takeIf { it != Format.Unknown } else f
        } catch (_: Throwable) {
            probeFallback(path).takeIf { it != Format.Unknown }
        }
    }

    fun probeFormatSafe(path: String): Format {
        return probeFormat(path) ?: probeFallback(path)
    }

    @JvmName("decodeToPcmDirectWrapper")
    fun decodeToPcmDirect(path: String): ByteBuffer? {
        if (!isAvailable()) return null
        return try {
            val buf = nativeDecodeToPcmDirect(path)
            if (buf != null && buf.remaining() > 0) buf else null
        } catch (_: Throwable) {
            null
        }
    }

    fun decodeToPcmSafe(path: String): ByteArray? {
        if (!isAvailable()) return null
        return try {
            nativeDecodeToPcm(path)
        } catch (_: Throwable) {
            null
        }
    }

    @JvmName("getTrackInfoWrapper")
    fun getTrackInfo(path: String): TrackInfo? {
        if (!isAvailable()) return fallbackInfo(path).takeIf { it.format != Format.Unknown }
        return try {
            val json = nativeGetInfo(path) ?: return fallbackInfo(path).takeIf { it.format != Format.Unknown }
            parseInfo(json, path)
        } catch (_: Throwable) {
            fallbackInfo(path).takeIf { it.format != Format.Unknown }
        }
    }

    fun getInfoSafe(path: String): TrackInfo? {
        if (!isAvailable()) return fallbackInfo(path)
        return try {
            val json = nativeGetInfo(path) ?: return fallbackInfo(path)
            parseInfo(json, path)
        } catch (_: Throwable) {
            fallbackInfo(path)
        }
    }

    private fun probeFallback(path: String): Format {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "aac", "adts", "m4a", "mp4", "3gp", "3gpp", "m4b" -> Format.AacLc
            "mp3" -> Format.Mp3
            "flac" -> Format.Flac
            "ogg", "oga" -> Format.Vorbis
            "opus" -> Format.Opus
            "amr" -> Format.AmrNb
            "awb" -> Format.AmrWb
            "wav" -> Format.Wav
            "pcm", "raw", "aiff", "aif" -> Format.Pcm
            "mid", "midi", "smf" -> Format.Midi
            else -> Format.Unknown
        }
    }

    private fun fallbackInfo(path: String): TrackInfo {
        val fmt = probeFallback(path)
        val esc = path.replace("\\", "\\\\").replace("\"", "\\\"")
        val json = """{"path":"$esc","format":"${fmt.name}","ordinal":${fmt.ordinalValue}}"""
        return TrackInfo(fmt, null, null, null, null, json)
    }

    private fun parseInfo(json: String, path: String): TrackInfo {
        return try {
            val ord = extractInt(json, "ordinal") ?: -1
            val fmt = Format.fromOrdinal(ord).let { if (it == Format.Unknown) probeFallback(path) else it }
            val sr = extractInt(json, "sampleRate")
            val ch = extractInt(json, "channels")
            val frames = extractLong(json, "frames")
            val dur = extractDouble(json, "durationSecs")
            TrackInfo(fmt, sr, ch, frames, dur, json)
        } catch (_: Exception) {
            TrackInfo(Format.Unknown, null, null, null, null, json)
        }
    }

    private fun extractInt(json: String, key: String): Int? {
        val v = extractNumberString(json, key) ?: return null
        if (v == "null") return null
        return v.toIntOrNull()
    }

    private fun extractLong(json: String, key: String): Long? {
        val v = extractNumberString(json, key) ?: return null
        if (v == "null") return null
        return v.toLongOrNull()
    }

    private fun extractDouble(json: String, key: String): Double? {
        val v = extractNumberString(json, key) ?: return null
        if (v == "null") return null
        return v.toDoubleOrNull()
    }

    private fun extractNumberString(json: String, key: String): String? {
        val idx = json.indexOf("\"$key\"")
        if (idx == -1) return null
        var colon = json.indexOf(':', idx + key.length + 2)
        if (colon == -1) return null
        colon++
        while (colon < json.length && json[colon].isWhitespace()) colon++
        if (colon >= json.length) return null
        if (json.startsWith("null", colon)) return "null"
        val start = colon
        var end = start
        while (end < json.length && (json[end].isDigit() || json[end] == '-' || json[end] == '+' || json[end] == '.' || json[end] == 'e' || json[end] == 'E')) {
            end++
        }
        if (start == end) return null
        return json.substring(start, end)
    }
}
