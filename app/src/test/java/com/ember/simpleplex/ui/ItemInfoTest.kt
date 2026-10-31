package com.ember.simpleplex.ui

import com.ember.simpleplex.plex.Item
import org.junit.Assert.assertEquals
import org.junit.Test

class ItemInfoTest {

    private fun item(type: String, duration: Long, year: Int? = null, airDate: String? = null) = Item(
        "1", type, "T", null, null, null, null, 2, 4, 0, 0, duration, 0, 0, null, emptyList(), year = year, airDate = airDate,
    )

    @Test fun whenLine() {
        assertEquals("1988 · 1h 26m", ItemInfo.whenLine(item("movie", 86 * 60_000L, year = 1988)))
        assertEquals("S2 · E4 · aired 12 May 2015 · 58m", ItemInfo.whenLine(item("episode", 58 * 60_000L, airDate = "2015-05-12")))
    }

    @Test fun sizes() {
        assertEquals("27.8 GB", ItemInfo.size(29_854_259_132))
        assertEquals("700 MB", ItemInfo.size(700L * 1024 * 1024))
    }
}
