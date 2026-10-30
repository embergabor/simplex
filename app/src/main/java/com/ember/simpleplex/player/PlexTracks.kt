package com.ember.simpleplex.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import com.ember.simpleplex.plex.Part
import com.ember.simpleplex.plex.Stream

/**
 * Maps Plex's audio/subtitle streams onto ExoPlayer's tracks.
 * Embedded streams line up by their order in the file; sidecar subtitles are found by the id we gave them.
 */
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
    fun apply(player: Player, part: Part, audio: Stream?, subtitle: Stream?) {
        val groups = player.currentTracks.groups
        val params = player.trackSelectionParameters.buildUpon()

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
