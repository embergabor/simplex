package com.ember.simpleplex.player

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.ember.simpleplex.plex.Part
import com.ember.simpleplex.plex.PlexClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Plex's "video preview thumbnails" for one file. Plex stores them as a BIF file (the Roku format):
 * a 64-byte header, then an index of (timestamp, offset) pairs, then the JPEGs back to back.
 */
class PreviewThumbs private constructor(
    private val file: File,
    private val times: LongArray,
    private val offsets: LongArray,
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
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }.getOrNull()

    companion object {
        private val MAGIC = byteArrayOf(0x89.toByte(), 'B'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)

        /** Downloads (once, kept in the cache) and indexes the thumbnails, or null if Plex has none. */
        suspend fun load(plex: PlexClient, part: Part, cacheDir: File): PreviewThumbs? = withContext(Dispatchers.IO) {
            if (!part.previews) return@withContext null
            runCatching {
                val file = File(cacheDir, "previews-${part.id}.bif")
                if (!file.exists()) plex.download("/library/parts/${part.id}/indexes/sd", file)
                parse(file)
            }.getOrNull()
        }

        fun parse(file: File): PreviewThumbs? = RandomAccessFile(file, "r").use { f ->
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
            PreviewThumbs(file, times, offsets)
        }
    }
}
