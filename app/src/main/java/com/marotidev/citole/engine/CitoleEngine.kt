package com.marotidev.citole.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull

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

    private var nativeAvailable: Boolean = false

    init {
        nativeAvailable = try {
            System.loadLibrary("citole_engine")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    fun isAvailable(): Boolean = nativeAvailable

    @JvmStatic
    external fun probeFormat(path: String): Int

    @JvmStatic
    external fun decodeToPcm(path: String): ByteArray?

    @JvmStatic
    external fun getInfo(path: String): String?

    fun probeFormatSafe(path: String): Format {
        if (!nativeAvailable) return probeFallback(path)
        return try {
            val ord = probeFormat(path)
            Format.fromOrdinal(ord)
        } catch (_: Throwable) {
            probeFallback(path)
        }
    }

    fun decodeToPcmSafe(path: String): ByteArray? {
        if (!nativeAvailable) return null
        return try {
            decodeToPcm(path)
        } catch (_: Throwable) {
            null
        }
    }

    fun getInfoSafe(path: String): TrackInfo? {
        if (!nativeAvailable) return fallbackInfo(path)
        return try {
            val json = getInfo(path) ?: return fallbackInfo(path)
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
        val json = """{"path":"$path","format":"${fmt.name}","ordinal":${fmt.ordinalValue}}"""
        return TrackInfo(fmt, null, null, null, null, json)
    }

    private fun parseInfo(json: String, path: String): TrackInfo {
        return try {
            val obj = Json.parseToJsonElement(json) as? JsonObject
            val ord = obj?.get("ordinal")?.jsonPrimitive?.intOrNull ?: -1
            val fmt = Format.fromOrdinal(ord)
            val sr = obj?.get("sampleRate")?.jsonPrimitive?.intOrNull
            val ch = obj?.get("channels")?.jsonPrimitive?.intOrNull
            val frames = obj?.get("frames")?.jsonPrimitive?.content?.toLongOrNull()
            val dur = obj?.get("durationSecs")?.jsonPrimitive?.content?.toDoubleOrNull()
            TrackInfo(fmt, sr, ch, frames, dur, json)
        } catch (_: Exception) {
            TrackInfo(Format.Unknown, null, null, null, null, json)
        }
    }
}
