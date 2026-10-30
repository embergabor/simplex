package com.ember.simpleplex.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTest {

    /** What Media3's PgsParser produces from a palette entry (full-range BT.601). */
    private fun media3Rgb(y: Int, cb: Int, cr: Int, alpha: Int = 0xFF): Int {
        val r = (y + 1.402 * (cr - 128)).toInt().coerceIn(0, 255)
        val g = (y - 0.34414 * (cb - 128) - 0.71414 * (cr - 128)).toInt().coerceIn(0, 255)
        val b = (y + 1.772 * (cb - 128)).toInt().coerceIn(0, 255)
        return (alpha shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun grey(v: Int, alpha: Int = 0xFF) = (alpha shl 24) or (v shl 16) or (v shl 8) or v
    private fun rgb(c: Int) = listOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)

    @Test fun pgsLevelsWhiteAndBlack() {
        assertEquals(listOf(255, 255, 255), rgb(SubtitleParsers.toFullRange(media3Rgb(235, 128, 128))))
        assertEquals(listOf(0, 0, 0), rgb(SubtitleParsers.toFullRange(media3Rgb(16, 128, 128))))
        assertEquals(listOf(128, 128, 128), rgb(SubtitleParsers.toFullRange(media3Rgb(126, 128, 128))))
    }

    @Test fun pgsYellowStaysYellow() {
        // BT.709 limited-range yellow (Y=219, Cb=16, Cr=138) is RGB 255,255,0.
        val yellow = rgb(SubtitleParsers.toFullRange(media3Rgb(219, 16, 138)))
        assertTrue("got $yellow", yellow[0] >= 250 && yellow[1] >= 250 && yellow[2] <= 6)
    }

    @Test fun greyAuthoredPgsBecomesWhite() {
        // The Top Gear Blu-ray: white stored at Y=192, black at Y=16 (measured on the TV as 205 / 0).
        val pixels = intArrayOf(media3Rgb(192, 128, 128), media3Rgb(16, 128, 128), 0)
        SubtitleParsers.fixPixels(pixels, limitedRange = true)
        assertEquals(listOf(255, 255, 255), rgb(pixels[0]))
        assertEquals(listOf(0, 0, 0), rgb(pixels[1]))
        assertEquals(0, pixels[2]) // transparent stays transparent
    }

    @Test fun vobsubLightGreyBecomesWhiteAndColorsScaleTogether() {
        val darkYellow = (0xFF shl 24) or (200 shl 16) or (180 shl 8) or 20
        val pixels = intArrayOf(grey(235), grey(0), darkYellow, grey(117))
        SubtitleParsers.fixPixels(pixels, limitedRange = false)
        assertEquals(listOf(255, 255, 255), rgb(pixels[0]))
        assertEquals(listOf(0, 0, 0), rgb(pixels[1]))
        assertEquals(listOf(217, 195, 22), rgb(pixels[2])) // same 255/235 gain
        assertEquals(listOf(127, 127, 127), rgb(pixels[3]))
    }

    @Test fun leavesAloneWhatIsNotMeantAsWhite() {
        val onlyColors = intArrayOf((0xFF shl 24) or (200 shl 16) or (200 shl 8) or 20)
        val copy = onlyColors.copyOf(); SubtitleParsers.fixPixels(copy, limitedRange = false)
        assertEquals(onlyColors.toList(), copy.toList()) // no light grey: unchanged

        val dark = intArrayOf(grey(90), grey(0))
        val darkCopy = dark.copyOf(); SubtitleParsers.fixPixels(darkCopy, limitedRange = false)
        assertEquals(dark.toList(), darkCopy.toList()) // lightest grey under 50%: unchanged
    }

    @Test fun alphaIsKept() {
        val pixels = intArrayOf(grey(205, alpha = 0x80), grey(205))
        SubtitleParsers.fixPixels(pixels, limitedRange = false)
        assertEquals(0x80, pixels[0] ushr 24)
        assertEquals(listOf(255, 255, 255), rgb(pixels[0]))
    }
}
