package com.ember.simpleplex.player

import androidx.media3.common.C
import com.ember.simpleplex.plex.Part
import com.ember.simpleplex.plex.Stream
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioTest {

    /** The bitstreams the A80J passes to the receiver over eARC (no TrueHD; PCM only as stereo). */
    private val a80j = setOf(C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_E_AC3_JOC, C.ENCODING_DTS, C.ENCODING_DTS_HD)
    private val tv: (Int) -> Boolean = { it in a80j }

    private var nextId = 1L
    private fun audio(codec: String, channels: Int, lang: String = "eng", profile: String? = null) =
        Stream(nextId++, 2, codec, profile, nextId.toInt(), lang, codec, false, false, null, null, null, null, null, null, null, null, channels)

    private fun part(vararg s: Stream) = Part(1, "/f.mkv", s.toList(), previews = false)

    @Test fun lpcmSurroundUsesSameLanguageCompatTrack() {
        val lpcm = audio("pcm_s24le", 6)
        val ac3 = audio("ac3", 6)
        val otherLanguageDts = audio("dca", 6, lang = "hun")
        val plan = PlexTracks.planAudio(part(lpcm, otherLanguageDts, ac3), lpcm, tv)
        assertEquals(ac3, plan.stream)
        assertEquals(PlexTracks.AudioChoice.PLAY, plan.choice)
    }

    @Test fun prefersLosslessDtsHdOverAc3() {
        val truehd = audio("truehd", 8)
        val ac3 = audio("ac3", 6)
        val dtsHd = audio("dca", 8, profile = "ma")
        assertEquals(dtsHd, PlexTracks.planAudio(part(truehd, ac3, dtsHd), truehd, tv).stream)
    }

    @Test fun asksWhenNothingPassesThrough() {
        for (codec in listOf("pcm_s16le", "truehd", "flac", "aac", "opus")) {
            val s = audio(codec, 6)
            val plan = PlexTracks.planAudio(part(s), s, tv)
            assertEquals(codec, PlexTracks.AudioChoice.ASK, plan.choice)
        }
    }

    @Test fun stereoAndBitstreamsJustPlay() {
        for (s in listOf(audio("aac", 2), audio("pcm_s16le", 2), audio("dca", 6, profile = "ma"), audio("eac3", 6), audio("ac3", 6))) {
            assertEquals(s.codec, PlexTracks.AudioChoice.PLAY, PlexTracks.planAudio(part(s), s, tv).choice)
        }
    }
}
