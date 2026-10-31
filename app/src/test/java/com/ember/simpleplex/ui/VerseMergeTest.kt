package com.ember.simpleplex.ui

import com.ember.simpleplex.plex.Item
import org.junit.Assert.assertEquals
import org.junit.Test

class VerseMergeTest {

    private fun item(key: String, title: String, year: Int? = null, guids: List<String> = emptyList(), viewOffset: Long = 0, lastViewedAt: Long = 0) = Item(
        ratingKey = key, type = "movie", title = title, thumb = null, art = null, grandparentRatingKey = null,
        grandparentTitle = null, parentIndex = null, index = null, viewOffset = viewOffset, viewCount = 0,
        duration = 1000, leafCount = 0, viewedLeafCount = 0, librarySectionId = null, media = emptyList(),
        year = year, guids = guids, lastViewedAt = lastViewedAt,
    )

    @Test fun sharedGuidMatchesAcrossServers() {
        val merged = Verse.merge(
            listOf(
                Source("A", item("1", "Alien", 1979, listOf("plex://movie/x", "imdb://tt0078748"))),
                // Different plex guid (another agent), same imdb id.
                Source("B", item("9", "Alien", 1979, listOf("com.plexapp.agents.imdb://tt0078748", "imdb://tt0078748"))),
                Source("B", item("5", "Aliens", 1986, listOf("imdb://tt0090605"))),
            ),
            order = listOf("A", "B"),
        )
        assertEquals(listOf("Alien", "Aliens"), merged.map { it.item.title })
        assertEquals(listOf("A", "B"), merged.first().sources.map { it.machineId })
    }

    @Test fun localIdsNeverMatch() {
        val merged = Verse.merge(
            listOf(Source("A", item("1", "Holiday", 2020, listOf("local://1"))), Source("B", item("1", "Trip", 2021, listOf("local://1")))),
            order = listOf("A", "B"),
        )
        assertEquals(2, merged.size)
    }

    @Test fun withoutGuidsTitleAndYearMatch() {
        val merged = Verse.merge(
            listOf(
                Source("A", item("1", "Amélie", 2001)),
                Source("B", item("2", "amelie", 2001)),
                Source("B", item("3", "Amélie", 2019)),
            ),
            order = listOf("A", "B"),
        )
        assertEquals(listOf(2, 1), merged.map { it.sources.size })
    }

    @Test fun serverOrderIsKept() {
        val merged = Verse.merge(
            listOf(Source("A", item("1", "Up", 2009, listOf("tmdb://14160"))), Source("C", item("2", "Up", 2009, listOf("tmdb://14160"))), Source("B", item("3", "Up", 2009, listOf("tmdb://14160")))),
            order = listOf("C", "A", "B"),
        )
        assertEquals(listOf("C", "A", "B"), merged.single().sources.map { it.machineId })
        assertEquals("2", merged.single().item.ratingKey)
    }

    @Test fun newestWatchedCopyGivesTheProgress() {
        val merged = Verse.merge(
            listOf(
                Source("A", item("1", "Up", 2009, listOf("tmdb://14160"), viewOffset = 100, lastViewedAt = 10)),
                Source("B", item("2", "Up", 2009, listOf("tmdb://14160"), viewOffset = 500, lastViewedAt = 20)),
            ),
            order = listOf("A", "B"),
        ).single()
        assertEquals("1", merged.item.ratingKey)
        assertEquals(500L, merged.item.viewOffset)
    }

    @Test fun sortedByTitleSort() {
        val merged = Verse.merge(
            listOf(Source("A", item("1", "Zorro")), Source("A", item("2", "The Abyss").copy(titleSort = "Abyss"))),
            order = listOf("A"),
        )
        assertEquals(listOf("The Abyss", "Zorro"), merged.map { it.item.title })
    }
}
