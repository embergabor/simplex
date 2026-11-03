package com.ember.simpleplex.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlurTest {
    @Test
    fun aPlainPictureStaysTheSame() {
        val pixels = IntArray(20 * 10) { 0xFF336699.toInt() }
        Blur.boxBlur(pixels, 20, 10, 3)
        assertTrue(pixels.all { it == 0xFF336699.toInt() })
    }

    @Test
    fun aBrightDotSpreadsEvenly() {
        val w = 31
        val pixels = IntArray(w * w) { 0xFF000000.toInt() }
        pixels[15 * w + 15] = 0xFFFFFFFF.toInt()
        Blur.boxBlur(pixels, w, w, 2)
        val red = { x: Int, y: Int -> (pixels[y * w + x] shr 16) and 0xFF }
        assertTrue(red(15, 15) < 255)
        // Each pass rounds down, so across and down can differ by one.
        val close = { a: Int, b: Int -> assertTrue("$a vs $b", kotlin.math.abs(a - b) <= 1) }
        close(red(13, 15), red(17, 15))
        close(red(15, 13), red(15, 17))
        close(red(13, 15), red(15, 13))
        assertTrue(red(15, 15) >= red(14, 15) && red(14, 15) >= red(12, 15))
        assertEquals(0xFF, pixels[0] ushr 24)
    }
}
