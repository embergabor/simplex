package com.ember.simpleplex.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.ember.simpleplex.plex.Part
import com.ember.simpleplex.plex.Stream

/**
 * Maps Plex's audio/subtitle streams onto ExoPlayer's tracks.
 * Embedded streams line up by their order in the file; sidecar subtitles are found by the id we gave them.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object PlexTracks {

    fun externalId(streamId: Long) = "plex-ext-$streamId"

    fun subtitleMime(codec: String?): String? = when (codec?.lowercase()) {
        "srt", "subrip" -> MimeTypes.APPLICATION_SUBRIP
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "vtt", "webvtt" -> MimeTypes.TEXT_VTT
        "pgs" -> MimeTypes.APPLICATION_PGS
        else -> null
    }

    fun audioStreams(part: Part) = part.streams.filter { it.streamType == 2 }

    /** The bitstream format a Plex audio stream would be passed through as, or null if it's always decoded. */
    private fun encoding(s: Stream): Int? {
        val codec = s.codec?.lowercase()
        val profile = s.profile?.lowercase()
        return when {
            codec == "truehd" -> C.ENCODING_DOLBY_TRUEHD
            codec == "eac3" -> C.ENCODING_E_AC3
            codec == "ac3" -> C.ENCODING_AC3
            codec == "dca-ma" || (codec == "dca" && (profile == "ma" || profile == "hra")) -> C.ENCODING_DTS_HD
            codec == "dca" || codec == "dts" -> C.ENCODING_DTS
            else -> null
        }
    }

    /** Best compatibility track first: lossless DTS-HD, then E-AC3, DTS, AC3. */
    private val compatRank = mapOf(C.ENCODING_DTS_HD to 4, C.ENCODING_E_AC3 to 3, C.ENCODING_DTS to 2, C.ENCODING_AC3 to 1)

    enum class AudioChoice {
        /** Play the stream as it is: passed through, or stereo anyway. */
        PLAY,

        /** Multichannel audio that would only reach the receiver as stereo: Plex converts it, however it decides to. */
        CONVERT,

        /** Like [CONVERT], but the file has a compatibility track too: the viewer picks one or the other. */
        ASK,
    }

    /** [compat]: with [AudioChoice.ASK], the same-language track that passes through. */
    data class AudioPlan(val stream: Stream?, val choice: AudioChoice, val compat: Stream? = null)

    /**
     * Multichannel audio this TV can't pass through as a bitstream (LPCM, TrueHD, FLAC, AAC, Opus 5.1/7.1)
     * would be decoded here, and the TV only outputs stereo PCM to the receiver.
     */
    fun reachesAsStereo(s: Stream, passthrough: (Int) -> Boolean): Boolean =
        (s.channels ?: 2) > 2 && encoding(s)?.let(passthrough) != true

    /**
     * Audio that would only reach the receiver as stereo (TrueHD, LPCM, FLAC, AAC, ... 5.1/7.1): Plex converts
     * it, or, when the file has a same-language track that passes through (a compatibility track), the
     * viewer is asked which. Everything else plays as chosen.
     */
    fun planAudio(part: Part, chosen: Stream?, passthrough: (Int) -> Boolean): AudioPlan {
        if (chosen == null || !reachesAsStereo(chosen, passthrough)) return AudioPlan(chosen, AudioChoice.PLAY)
        // No receiver (TV speakers, headphones): nothing goes out as surround anyway, so decode it here.
        if (compatRank.keys.none(passthrough)) return AudioPlan(chosen, AudioChoice.PLAY)
        val compat = audioStreams(part)
            .filter { it.id != chosen.id && it.languageCode == chosen.languageCode }
            .mapNotNull { s -> encoding(s)?.takeIf { it in compatRank && passthrough(it) }?.let { s to compatRank.getValue(it) } }
            .maxByOrNull { it.second }
            ?.first
        return if (compat != null) AudioPlan(chosen, AudioChoice.ASK, compat) else AudioPlan(chosen, AudioChoice.CONVERT)
    }

    fun subtitleStreams(part: Part) = part.streams.filter { it.streamType == 3 }

    /** The subtitle to show when the viewer chose none: a forced track in the audio's language. */
    fun forcedFor(part: Part, audio: Stream?): Stream? = subtitleStreams(part).firstOrNull {
        it.forced && (audio?.languageCode == null || it.languageCode == audio.languageCode)
    }

    private fun Tracks.Group.isExternal() =
        (0 until length).any { getTrackFormat(it).id?.contains("plex-ext-") == true }

    private fun Tracks.Group.hasId(id: String) =
        (0 until length).any { getTrackFormat(it).id?.contains(id) == true }

    /** Applies the choice; [subtitle] null means off (a forced track still shows). */
    fun apply(player: Player, part: Part, audio: Stream?, subtitle: Stream?, onlyChosen: Boolean = false) {
        val groups = player.currentTracks.groups
        val params = player.trackSelectionParameters.buildUpon()

        // Plex's converted stream carries just the chosen audio and subtitle: take what's there.
        if (onlyChosen) {
            groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO }?.let { params.setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, 0)) }
            val text = groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT }
            params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, subtitle == null || text == null)
            text?.let { params.setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, 0)) }
            player.trackSelectionParameters = params.build()
            return
        }

        val audioGroups = groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val plexAudio = audioStreams(part).filter { !it.isExternal }.sortedBy { it.index ?: Int.MAX_VALUE }
        val ai = plexAudio.indexOfFirst { it.id == audio?.id }
        if (ai >= 0 && plexAudio.size == audioGroups.size) {
            params.setOverrideForType(TrackSelectionOverride(audioGroups[ai].mediaTrackGroup, 0))
        } else if (audio?.languageCode != null) {
            params.setPreferredAudioLanguage(audio.languageCode)
        }

        val shown = subtitle ?: forcedFor(part, audio)
        if (shown == null) {
            params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            val textGroups = groups.filter { it.type == C.TRACK_TYPE_TEXT }
            val group = if (shown.isExternal) {
                textGroups.firstOrNull { it.hasId(externalId(shown.id)) }
            } else {
                val embedded = textGroups.filter { !it.isExternal() }
                val plexEmbedded = subtitleStreams(part).filter { !it.isExternal }.sortedBy { it.index ?: Int.MAX_VALUE }
                embedded.getOrNull(plexEmbedded.indexOf(shown)).takeIf { plexEmbedded.size == embedded.size }
            }
            if (group != null) {
                params.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            } else {
                params.clearOverridesOfType(C.TRACK_TYPE_TEXT).setPreferredTextLanguage(shown.languageCode)
            }
        }
        player.trackSelectionParameters = params.build()
    }

    /** True when the chosen audio can't be played here, neither passed through nor decoded. */
    fun audioUnplayable(tracks: Tracks): Boolean {
        val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (audio.isEmpty()) return false
        val selected = audio.firstOrNull { it.isSelected }
        return if (selected != null) !selected.isTrackSupported(0) else audio.none { it.isSupported }
    }
}
