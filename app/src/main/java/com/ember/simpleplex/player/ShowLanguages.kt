package com.ember.simpleplex.player

import android.content.Context
import com.ember.simpleplex.plex.Part
import com.ember.simpleplex.plex.Stream

/**
 * The audio and subtitle language picked for a show, kept on the TV and applied to its episodes.
 * [subtitle] "" means subtitles off; null means nothing was picked (Plex's choice stands).
 */
data class Languages(val audio: String?, val subtitle: String?) {

    /** The audio track in the remembered language: the one Plex selected if it matches, else the first. */
    fun audioFor(part: Part, plexChoice: Stream?): Stream? {
        val lang = audio ?: return plexChoice
        if (plexChoice?.languageCode == lang) return plexChoice
        return PlexTracks.audioStreams(part).firstOrNull { it.languageCode == lang } ?: plexChoice
    }

    /** The subtitle: off, the one Plex selected if it matches, else the first full (not forced) one. */
    fun subtitleFor(part: Part, plexChoice: Stream?): Stream? {
        val lang = subtitle ?: return plexChoice
        if (lang.isEmpty()) return null
        if (plexChoice?.languageCode == lang) return plexChoice
        val inLanguage = PlexTracks.subtitleStreams(part).filter { it.languageCode == lang }
        return inLanguage.firstOrNull { !it.forced } ?: inLanguage.firstOrNull() ?: plexChoice
    }
}

class ShowLanguages(context: Context) {
    private val prefs = context.getSharedPreferences("showLanguages", Context.MODE_PRIVATE)

    operator fun get(showKey: String) = Languages(prefs.getString("$showKey.audio", null), prefs.getString("$showKey.subtitle", null))

    fun setAudio(showKey: String, language: String?) {
        if (language != null) prefs.edit().putString("$showKey.audio", language).apply()
    }

    /** [subtitle] null: off. */
    fun setSubtitle(showKey: String, subtitle: Stream?) {
        val language = if (subtitle == null) "" else subtitle.languageCode ?: return
        prefs.edit().putString("$showKey.subtitle", language).apply()
    }
}
