package com.ember.simpleplex.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.ember.simpleplex.plex.Part
import com.ember.simpleplex.plex.PlexServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Plex's "video preview thumbnails" for one file. Plex stores them as a BIF file (the Roku format):
 * a 64-byte header, then an index of (timestamp, offset) pairs, then the JPEGs back to back.
 */
class PreviewThumbs private constructor(
    private val file: File,
    private val times: LongArray,
    private val offsets: LongArray,
    /** Plex makes thumbnails of HDR (PQ) video without tone-mapping them; do it here. */
    private val toneMap: Boolean,
) {
    /** The frame shown at [ms]: the last one that starts at or before it. */
    fun frameAt(ms: Long): Int {
        var i = times.binarySearch(ms)
        if (i < 0) i = -i - 2
        return i.coerceIn(0, times.size - 1)
    }

    fun image(frame: Int): ImageBitmap? = runCatching {
        RandomAccessFile(file, "r").use { f ->
            f.seek(offsets[frame])
            val bytes = ByteArray((offsets[frame + 1] - offsets[frame]).toInt())
            f.readFully(bytes)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            if (toneMap) {
                val w = bitmap.width
                val h = bitmap.height
                val pixels = IntArray(w * h)
                bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                toneMapPixels(pixels)
                Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
            } else {
                bitmap.asImageBitmap()
            }
        }
    }.getOrNull()

    companion object {
        private val MAGIC = byteArrayOf(0x89.toByte(), 'B'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)

        /** Downloads (once, kept in the cache) and indexes the thumbnails, or null if Plex has none. */
        suspend fun load(plex: PlexServer, part: Part, cacheDir: File): PreviewThumbs? = withContext(Dispatchers.IO) {
            if (!part.previews) return@withContext null
            val video = part.streams.firstOrNull { it.streamType == 1 }
            // HDR10 and Dolby Vision with an HDR10 base. Profile 5 uses its own colors, which this can't fix.
            val pq = video?.colorTrc == "smpte2084" && video.doviProfile != 5
            runCatching {
                val file = File(cacheDir, "previews-${plex.machineId ?: ""}-${part.id}.bif")
                if (!file.exists()) plex.download("/library/parts/${part.id}/indexes/sd", file)
                parse(file, toneMap = pq)
            }.getOrNull()
        }

        /**
         * The cheapest tone-mapping: one lookup per color channel. PQ code value to brightness in nits,
         * a Reinhard curve that rolls 1000 nits off to white (with 203 nits as normal white), then
         * back to a display gamma. No gamut conversion, so HDR thumbnails stay a little muted.
         */
        val pqToSdr: IntArray = IntArray(256) { code ->
            val e = (code / 255.0).pow(1 / 78.84375)
            val nits = 10000 * (maxOf(e - 0.8359375, 0.0) / (18.8515625 - 18.6875 * e)).pow(1 / 0.1593017578125)
            val x = nits / 203
            val white = 1000.0 / 203
            val y = x * (1 + x / (white * white)) / (1 + x)
            (y.coerceIn(0.0, 1.0).pow(1 / 2.2) * 255).roundToInt()
        }

        fun toneMapPixels(pixels: IntArray) {
            for (i in pixels.indices) {
                val p = pixels[i]
                pixels[i] = (p and 0xFF000000.toInt()) or
                    (pqToSdr[(p shr 16) and 0xFF] shl 16) or
                    (pqToSdr[(p shr 8) and 0xFF] shl 8) or
                    pqToSdr[p and 0xFF]
            }
        }

        fun parse(file: File, toneMap: Boolean = false): PreviewThumbs? = RandomAccessFile(file, "r").use { f ->
            val header = ByteArray(64)
            f.readFully(header)
            if (!header.copyOfRange(0, 8).contentEquals(MAGIC)) return null
            val h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val count = h.getInt(12).toLong() and 0xFFFFFFFFL
            val separation = (h.getInt(16).toLong() and 0xFFFFFFFFL).takeIf { it > 0 } ?: 1000L
            if (count <= 0 || count > 1_000_000) return null

            // count entries plus a final one whose offset marks the end of the last image.
            val index = ByteArray(((count + 1) * 8).toInt())
            f.readFully(index)
            val b = ByteBuffer.wrap(index).order(ByteOrder.LITTLE_ENDIAN)
            val times = LongArray(count.toInt()) { (b.getInt(it * 8).toLong() and 0xFFFFFFFFL) * separation }
            val offsets = LongArray(count.toInt() + 1) { b.getInt(it * 8 + 4).toLong() and 0xFFFFFFFFL }
            PreviewThumbs(file, times, offsets, toneMap)
        }
    }
}
