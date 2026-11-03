package com.ember.simpleplex.ui

import android.graphics.Bitmap

/**
 * The backdrop picture blurred once, when it arrives, so drawing it costs no more than any picture. Shrunk
 * to [WIDTH] first, so it's quick; drawn scaled up, a blurred picture loses nothing by being small.
 */
object Blur {
    private const val WIDTH = 320

    /** About what a 24 dp blur of the picture filling a 1080p screen looks like, at [WIDTH]. */
    private const val RADIUS = 5

    fun backdrop(source: Bitmap): Bitmap {
        val height = (source.height * WIDTH / source.width.toFloat()).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(source.copy(Bitmap.Config.ARGB_8888, false), WIDTH, height, true)
        val pixels = IntArray(WIDTH * height)
        small.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, height)
        boxBlur(pixels, WIDTH, height, RADIUS)
        return Bitmap.createBitmap(pixels, WIDTH, height, Bitmap.Config.ARGB_8888)
    }

    /** Three box blurs across and down, close to a Gaussian; edges repeat the border pixels. In place. */
    fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int) {
        val scratch = IntArray(pixels.size)
        repeat(3) {
            pass(pixels, scratch, width, height, radius, across = true)
            pass(scratch, pixels, width, height, radius, across = false)
        }
    }

    private fun pass(from: IntArray, to: IntArray, width: Int, height: Int, radius: Int, across: Boolean) {
        val lines = if (across) height else width
        val length = if (across) width else height
        val window = 2 * radius + 1
        for (line in 0 until lines) {
            fun at(i: Int) = from[if (across) line * width + i.coerceIn(0, length - 1) else i.coerceIn(0, length - 1) * width + line]
            var a = 0; var r = 0; var g = 0; var b = 0
            for (i in -radius..radius) {
                val p = at(i)
                a += p ushr 24; r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
            }
            for (i in 0 until length) {
                to[if (across) line * width + i else i * width + line] =
                    (a / window shl 24) or (r / window shl 16) or (g / window shl 8) or (b / window)
                val leaving = at(i - radius)
                val coming = at(i + radius + 1)
                a += (coming ushr 24) - (leaving ushr 24)
                r += ((coming shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
                g += ((coming shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
                b += (coming and 0xFF) - (leaving and 0xFF)
            }
        }
    }
}
