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
 * Blu-ray (PGS) subtitles store their colors as limited-range BT.709 YCbCr, where white is Y=235.
 * Media3 converts them as full-range BT.601, so white comes out as 92% grey (235,235,235) and black
 * as dark grey. This re-converts every PGS bitmap so white is 255 and black is 0, like other players.
 * Text subtitles (SRT, ASS, WebVTT) and DVD (VobSub) subtitles are left alone: they're already RGB.
 */
@OptIn(UnstableApi::class)
class SubtitleParsers(
    private val delegate: SubtitleParser.Factory = DefaultSubtitleParserFactory(),
) : SubtitleParser.Factory by delegate {

    override fun create(format: Format): SubtitleParser {
        val parser = delegate.create(format)
        return if (format.sampleMimeType == MimeTypes.APPLICATION_PGS) PgsLevels(parser) else parser
    }

    private class PgsLevels(private val inner: SubtitleParser) : SubtitleParser by inner {
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
            return cue.buildUpon().setBitmap(toFullRange(bitmap)).build()
        }
    }

    companion object {
        /** Undoes Media3's full-range BT.601 conversion and applies limited-range BT.709. */
        fun toFullRange(src: Bitmap): Bitmap {
            val w = src.width
            val h = src.height
            val pixels = IntArray(w * h)
            src.getPixels(pixels, 0, w, 0, 0, w, h)
            val cache = HashMap<Int, Int>() // PGS uses at most 256 palette colors
            for (i in pixels.indices) {
                val p = pixels[i]
                if (p ushr 24 == 0) continue
                pixels[i] = cache.getOrPut(p) { convert(p) }
            }
            return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        }

        fun convert(argb: Int): Int {
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
            return (argb and 0xFF000000.toInt()) or
                (clamp(r2) shl 16) or (clamp(g2) shl 8) or clamp(b2)
        }

        private fun clamp(v: Double) = v.roundToInt().coerceIn(0, 255)
    }
}
