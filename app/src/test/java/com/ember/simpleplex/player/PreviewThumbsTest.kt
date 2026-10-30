package com.ember.simpleplex.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PreviewThumbsTest {

    /** A BIF file with fake "images" of the given sizes, one every [separation] ms. */
    private fun bif(sizes: List<Int>, separation: Int): File {
        val header = 64
        val index = (sizes.size + 1) * 8
        val buf = ByteBuffer.allocate(header + index + sizes.sum()).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(byteArrayOf(0x89.toByte(), 'B'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A))
        buf.putInt(8, 0).putInt(12, sizes.size).putInt(16, separation)
        var offset = header + index
        sizes.forEachIndexed { i, size ->
            buf.putInt(header + i * 8, i).putInt(header + i * 8 + 4, offset)
            offset += size
        }
        buf.putInt(header + sizes.size * 8, -1).putInt(header + sizes.size * 8 + 4, offset)
        return File.createTempFile("test", ".bif").apply { writeBytes(buf.array()); deleteOnExit() }
    }

    @Test fun findsTheFrameShowingAtATime() {
        val thumbs = PreviewThumbs.parse(bif(listOf(10, 20, 30), separation = 2000))!!
        assertEquals(0, thumbs.frameAt(0))
        assertEquals(0, thumbs.frameAt(1999))
        assertEquals(1, thumbs.frameAt(2000))
        assertEquals(1, thumbs.frameAt(3999))
        assertEquals(2, thumbs.frameAt(4000))
        assertEquals(2, thumbs.frameAt(9_000_000))
        assertEquals(0, thumbs.frameAt(-5))
    }

    @Test fun zeroSeparationMeansOneSecond() {
        val thumbs = PreviewThumbs.parse(bif(listOf(5, 5), separation = 0))!!
        assertEquals(1, thumbs.frameAt(1000))
    }

    @Test fun hdrToneMapCurve() {
        val lut = PreviewThumbs.pqToSdr
        assertEquals(0, lut[0])
        assertEquals(255, lut[255])
        for (i in 1..255) assertTrue("not rising at $i", lut[i] >= lut[i - 1])
        // PQ code for ~100 nits (about 130) becomes a mid-bright grey instead of near-black-grey.
        assertTrue("100 nits -> ${lut[130]}", lut[130] in 140..175)
        // ~1000 nits (code ~192) and above reach white.
        assertTrue("1000 nits -> ${lut[192]}", lut[192] >= 250)
    }

    @Test fun rejectsOtherFiles() {
        val notBif = File.createTempFile("test", ".jpg").apply { writeBytes(ByteArray(200)); deleteOnExit() }
        assertNull(PreviewThumbs.parse(notBif))
    }
}
