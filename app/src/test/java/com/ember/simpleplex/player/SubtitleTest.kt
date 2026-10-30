package com.ember.simpleplex.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTest {

    /** What Media3's PgsParser produces from a palette entry (full-range BT.601). */
    private fun media3Rgb(y: Int, cb: Int, cr: Int): Int {
        val r = (y + 1.402 * (cr - 128)).toInt().coerceIn(0, 255)
        val g = (y - 0.34414 * (cb - 128) - 0.71414 * (cr - 128)).toInt().coerceIn(0, 255)
        val b = (y + 1.772 * (cb - 128)).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun rgb(c: Int) = listOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)

    @Test fun pgsWhiteBecomesFullWhite() {
        val before = media3Rgb(235, 128, 128)
        assertEquals(listOf(235, 235, 235), rgb(before)) // the grey Media3 shows today
        assertEquals(listOf(255, 255, 255), rgb(SubtitleParsers.convert(before)))
    }

    @Test fun pgsBlackBecomesFullBlack() {
        assertEquals(listOf(0, 0, 0), rgb(SubtitleParsers.convert(media3Rgb(16, 128, 128))))
    }

    @Test fun pgsMidGreyAndYellow() {
        assertEquals(listOf(128, 128, 128), rgb(SubtitleParsers.convert(media3Rgb(126, 128, 128))))
        // BT.709 limited-range yellow (Y=219, Cb=16, Cr=138) is RGB 255,255,0.
        val yellow = rgb(SubtitleParsers.convert(media3Rgb(219, 16, 138)))
        assertTrue("got $yellow", yellow[0] >= 250 && yellow[1] >= 250 && yellow[2] <= 6)
    }

    @Test fun alphaIsKept() {
        val halfWhite = (media3Rgb(235, 128, 128) and 0x00FFFFFF) or (0x80 shl 24)
        assertEquals(0x80, SubtitleParsers.convert(halfWhite) ushr 24)
    }
}
