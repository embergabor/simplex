package com.ember.simpleplex.player

import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import com.ember.simpleplex.plex.Stream

/** How the current file reaches the TV. */
enum class Delivery { DIRECT, AUDIO_CONVERTED, BURNING }

/** What actually goes out of the TV's audio output: a bitstream (passthrough) or decoded PCM. */
data class AudioOutput(val encoding: Int, val channels: Int)

/** What Plex's converted stream delivers, as ExoPlayer sees it. */
data class AudioInput(val mimeType: String?, val channels: Int)

/** The lines of the info box shown with the player controls. */
data class PlaybackInfo(val video: String, val audio: String, val subtitle: String, val markers: List<String>)

@OptIn(UnstableApi::class)
object Describe {

    fun info(
        video: Stream?,
        audio: Stream?,
        subtitle: Stream?,
        delivery: Delivery,
        input: AudioInput?,
        output: AudioOutput?,
    ) = PlaybackInfo(
        video = video(video),
        audio = audio(audio, delivery, input, output),
        subtitle = subtitle?.let { subtitleCodec(it.codec) } ?: "Off",
        markers = when (delivery) {
            Delivery.DIRECT -> listOf("Direct play")
            Delivery.AUDIO_CONVERTED -> listOf("Audio transcode")
            Delivery.BURNING -> listOf("Audio transcode", "Video transcode", "Subtitles burned in")
        },
    )

    fun video(s: Stream?): String {
        if (s == null) return "—"
        val range = when {
            s.doviProfile != null -> if (s.doviProfile > 0) "Dolby Vision P${s.doviProfile}" else "Dolby Vision"
            s.colorTrc == "smpte2084" -> "HDR10"
            s.colorTrc == "arib-std-b67" -> "HLG"
            else -> "SDR"
        }
        return listOfNotNull(
            if (s.width != null && s.height != null) "${s.width}×${s.height}${scan(s.scanType)}" else null,
            s.frameRate?.let { "${fps(it)} fps" },
            range,
        ).joinToString(" · ")
    }

    fun audio(s: Stream?, delivery: Delivery, input: AudioInput?, output: AudioOutput?): String {
        if (s == null) return "—"
        val atmos = if (s.displayTitle.contains("Atmos", ignoreCase = true)) " Atmos" else ""
        val source = "${codecName(s.codec, s.profile)} ${s.channels?.let(::channels) ?: ""}".trim() + atmos
        val converted = if (delivery != Delivery.DIRECT && input != null) {
            " → Plex ${mimeName(input.mimeType)} ${channels(input.channels)}"
        } else ""
        val out = when {
            output == null -> ""
            Util.isEncodingLinearPcm(output.encoding) -> " → decoded, PCM ${channels(output.channels)}"
            else -> " → passthrough"
        }
        return source + converted + out
    }

    private fun scan(type: String?) = when (type?.lowercase()) {
        "progressive" -> "p"
        "interlaced" -> "i"
        else -> ""
    }

    fun codecName(codec: String?, profile: String? = null): String = when (codec?.lowercase()) {
        "truehd" -> "TrueHD"
        "eac3" -> "E-AC3"
        "ac3" -> "AC3"
        "dca-ma" -> "DTS-HD MA"
        "dca", "dts" -> when (profile?.lowercase()) {
            "ma" -> "DTS-HD MA"
            "hra" -> "DTS-HD HRA"
            else -> "DTS"
        }
        "aac" -> "AAC"
        "flac" -> "FLAC"
        "mp3" -> "MP3"
        "opus" -> "Opus"
        "vorbis" -> "Vorbis"
        null -> "?"
        else -> if (codec.startsWith("pcm")) "PCM" else codec.uppercase()
    }

    fun subtitleCodec(codec: String?): String = when (codec?.lowercase()) {
        "srt", "subrip" -> "SRT"
        "ass", "ssa" -> "ASS"
        "pgs", "hdmv_pgs_subtitle" -> "PGS"
        "vobsub", "dvd_subtitle" -> "VobSub"
        "mov_text", "tx3g" -> "MP4 text"
        "webvtt", "vtt" -> "WebVTT"
        null -> "?"
        else -> codec.uppercase()
    }

    fun channels(n: Int): String = when (n) {
        1 -> "Mono"
        2 -> "Stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> "${n}ch"
    }

    private fun mimeName(mime: String?) = when (mime) {
        MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "E-AC3"
        MimeTypes.AUDIO_AC3 -> "AC3"
        MimeTypes.AUDIO_AAC -> "AAC"
        else -> mime?.substringAfter('/')?.uppercase() ?: "?"
    }

    private fun fps(f: Float): String = when {
        kotlin.math.abs(f - 23.976f) < 0.01f -> "23.976"
        kotlin.math.abs(f - 29.97f) < 0.01f -> "29.97"
        kotlin.math.abs(f - 59.94f) < 0.01f -> "59.94"
        f % 1f == 0f -> f.toInt().toString()
        else -> "%.3f".format(f).trimEnd('0').trimEnd('.')
    }

    /** Channel count from an Android channel mask (AudioFormat.CHANNEL_OUT_*). */
    fun channelCount(mask: Int) = Integer.bitCount(mask)
}
