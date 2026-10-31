package com.ember.simpleplex.player

import com.ember.simpleplex.plex.Part
import com.ember.simpleplex.plex.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguagesTest {

    private var nextId = 1L
    private fun s(type: Int, codec: String, lang: String, forced: Boolean = false) =
        Stream(nextId++, type, codec, null, nextId.toInt(), lang, codec, false, forced, null, null, null, null, null, null, null, null, 2)

    private val engAudio = s(2, "ac3", "eng")
    private val jpnAudio = s(2, "dca", "jpn")
    private val engForced = s(3, "pgs", "eng", forced = true)
    private val engFull = s(3, "pgs", "eng")
    private val hunSub = s(3, "srt", "hun")
    private val part = Part(1, "/f.mkv", listOf(engAudio, jpnAudio, engForced, engFull, hunSub), previews = false)

    @Test fun nothingRememberedKeepsPlexChoice() {
        val none = Languages(null, null)
        assertEquals(engAudio, none.audioFor(part, engAudio))
        assertEquals(hunSub, none.subtitleFor(part, hunSub))
    }

    @Test fun rememberedLanguagesPickTracks() {
        val l = Languages("jpn", "eng")
        assertEquals(jpnAudio, l.audioFor(part, engAudio))
        // The full English track, not the forced one.
        assertEquals(engFull, l.subtitleFor(part, hunSub))
    }

    @Test fun subtitlesOffAndMissingLanguage() {
        assertNull(Languages(null, "").subtitleFor(part, hunSub))
        assertEquals(engAudio, Languages("fra", null).audioFor(part, engAudio))
    }
}
