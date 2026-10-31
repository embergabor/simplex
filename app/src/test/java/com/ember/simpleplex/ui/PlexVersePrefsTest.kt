package com.ember.simpleplex.ui

import com.ember.simpleplex.plex.Library
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlexVersePrefsTest {

    private val setup = VerseSetup(
        servers = listOf("A", "B", "C"),
        libraries = mapOf(
            "A/3" to LibraryChoice(Category.MOVIES),
            "A/5" to LibraryChoice(Category.SERIES, kids = true),
            "B/1" to LibraryChoice(Category.VIDEOS),
            "B/2" to LibraryChoice(Category.NONE),
        ),
        kidsSeparate = true,
    )

    @Test fun roundTrips() {
        assertEquals(setup, VerseSetup.fromJson(setup.toJson()))
    }

    @Test fun setUpOnlyWithLibraries() {
        assertTrue(setup.isSetUp)
        assertFalse(VerseSetup().isSetUp)
    }

    @Test fun newServersGoLastAndGoneOnesDrop() {
        assertEquals(listOf("A", "C", "D"), setup.ordered(listOf("D", "A", "C")))
    }

    @Test fun defaultsFollowTheLibraryKind() {
        assertEquals(Category.SERIES, setup.choice("C", Library("1", "TV", Library.Kind.SHOWS)).category)
        assertEquals(Category.VIDEOS, setup.choice("C", Library("2", "Home", Library.Kind.OTHER)).category)
        assertEquals(Category.NONE, setup.choice("B", Library("2", "Films", Library.Kind.MOVIES)).category)
    }

    @Test fun unknownCategoryReadsAsNone() {
        val s = VerseSetup.fromJson("""{"libraries": {"A/1": {"category": "PHOTOS"}}}""")
        assertEquals(LibraryChoice(Category.NONE), s.libraries["A/1"])
    }
}
