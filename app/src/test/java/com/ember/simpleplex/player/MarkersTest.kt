package com.ember.simpleplex.player

import com.ember.simpleplex.plex.Item
import com.ember.simpleplex.plex.Marker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkersTest {

    private fun episode(duration: Long, vararg markers: Marker) = Item(
        "1", "episode", "E", null, null, null, null, 1, 1, 0, 0, duration, 0, 0, null, emptyList(), markers = markers.toList(),
    )

    private val intro = Marker("intro", 1_000, 54_000)
    private val midCredits = Marker("credits", 600_000, 620_000)
    private val endCredits = Marker("credits", 3_500_000, 3_600_000, final = true)

    @Test fun introButtonUntilItsLastSecond() {
        val e = episode(3_600_000, intro, midCredits, endCredits)
        assertEquals(intro, Markers.active(e, 1_000))
        assertNull(Markers.active(e, 53_500))
        assertNull(Markers.active(e, 610_000)) // credits in the middle get no button
        assertEquals(endCredits, Markers.active(e, 3_550_000))
    }

    @Test fun watchedInEndCreditsOrPast95Percent() {
        val withCredits = episode(3_600_000, endCredits)
        assertFalse(Markers.watched(withCredits, 3_499_000))
        assertTrue(Markers.watched(withCredits, 3_500_000))
        val without = episode(1_000_000)
        assertFalse(Markers.watched(without, 949_000))
        assertTrue(Markers.watched(without, 950_000))
    }
}
