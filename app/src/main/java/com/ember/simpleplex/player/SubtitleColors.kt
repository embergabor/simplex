package com.ember.simpleplex.player

import android.graphics.Bitmap
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import kotlin.math.roundToInt

/**
 * Makes image subtitles (Blu-ray PGS, DVD VobSub) as white as text subtitles.
 *
 * 1. PGS stores colors as limited-range BT.709 YCbCr (white Y=235). Media3 converts them as full-range
 *    BT.601, so they come out too dark (black as 16, white as 235). They're re-converted properly.
 * 2. Discs often author the "white" as a light grey (e.g. 80%), and rippers convert DVD palettes the
 *    same way. A disc player draws it into the video picture, where the TV's processing makes it look
 *    white; Simplex draws it on the TV's graphics layer, which isn't brightened, so it looks grey.
 *    So each subtitle is scaled until its lightest grey is pure white, like SRT text. All its colors
 *    scale together: outlines stay black, colored text keeps its hue.
 */
@OptIn(UnstableApi::class)
class SubtitleParsers(
    private val delegate: SubtitleParser.Factory = DefaultSubtitleParserFactory(),
) : SubtitleParser.Factory by delegate {

    override fun create(format: Format): SubtitleParser {
        val parser = delegate.create(format)
        return when (format.sampleMimeType) {
            MimeTypes.APPLICATION_PGS -> ImageFix(parser, limitedRange = true)
            MimeTypes.APPLICATION_VOBSUB -> ImageFix(parser, limitedRange = false)
            else -> parser
        }
    }

    private class ImageFix(private val inner: SubtitleParser, private val limitedRange: Boolean) : SubtitleParser by inner {
        override fun parse(
            data: ByteArray,
            offset: Int,
            length: Int,
            outputOptions: SubtitleParser.OutputOptions,
            output: Consumer<CuesWithTiming>,
        ) {
            inner.parse(data, offset, length, outputOptions) { c ->
                output.accept(CuesWithTiming(c.cues.map(::fix), c.startTimeUs, c.durationUs))
            }
        }

        private fun fix(cue: Cue): Cue {
            val bitmap = cue.bitmap ?: return cue
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            fixPixels(pixels, limitedRange)
            return cue.buildUpon().setBitmap(Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)).build()
        }
    }

    companion object {
        fun fixPixels(pixels: IntArray, limitedRange: Boolean) {
            if (limitedRange) {
                val cache = HashMap<Int, Int>() // PGS uses at most 256 palette colors
                for (i in pixels.indices) {
                    val p = pixels[i]
                    if (p ushr 24 != 0) pixels[i] = cache.getOrPut(p) { toFullRange(p) }
                }
            }
            brighten(pixels)
        }

        /** Undoes Media3's full-range BT.601 conversion and applies limited-range BT.709. */
        fun toFullRange(argb: Int): Int {
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            // Back to the stored YCbCr (exact inverse of Media3's formula).
            val y = 0.299 * r + 0.587 * g + 0.114 * b
            val cb = 128 - 0.168736 * r - 0.331264 * g + 0.5 * b
            val cr = 128 + 0.5 * r - 0.418688 * g - 0.081312 * b
            // Limited-range BT.709 to full-range RGB.
            val yy = 1.164384 * (y - 16)
            val r2 = yy + 1.792741 * (cr - 128)
            val g2 = yy - 0.213249 * (cb - 128) - 0.532909 * (cr - 128)
            val b2 = yy + 2.112402 * (cb - 128)
            return (argb and 0xFF000000.toInt()) or (clamp(r2) shl 16) or (clamp(g2) shl 8) or clamp(b2)
        }

        /**
         * Scales colors so the lightest grey becomes 255. Only when that grey is clearly meant as white
         * (at least 50%); a subtitle with no light grey (all colored, all dark) is left alone.
         */
        fun brighten(pixels: IntArray) {
            var lightest = 0
            for (p in pixels) {
                if (p ushr 24 < 128) continue
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val hi = maxOf(r, g, b)
                if (hi - minOf(r, g, b) <= 24 && hi > lightest) lightest = hi
            }
            if (lightest < 128 || lightest >= 255) return
            val gain = 255.0 / lightest
            for (i in pixels.indices) {
                val p = pixels[i]
                if (p ushr 24 == 0) continue
                pixels[i] = (p and 0xFF000000.toInt()) or
                    (clamp(((p shr 16) and 0xFF) * gain) shl 16) or
                    (clamp(((p shr 8) and 0xFF) * gain) shl 8) or
                    clamp((p and 0xFF) * gain)
            }
        }

        private fun clamp(v: Double) = v.roundToInt().coerceIn(0, 255)
    }
}
