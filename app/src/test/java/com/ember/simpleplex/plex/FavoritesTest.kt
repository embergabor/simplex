package com.ember.simpleplex.plex

import org.junit.Assert.assertEquals
import org.junit.Test

class FavoritesTest {

    private val json = """
        {"MediaContainer": {"librarySectionID": 2, "Metadata": [
            {"ratingKey": "11", "type": "episode", "parentIndex": 1, "index": 1, "Label": [{"tag": "Fav"}, {"tag": "cars"}]},
            {"ratingKey": "12", "type": "episode", "parentIndex": 1, "index": 2},
            {"ratingKey": "21", "type": "episode", "parentIndex": 2, "index": 1, "Label": [{"tag": "cars"}]},
            {"ratingKey": "22", "type": "episode", "parentIndex": 2, "index": 2}
        ]}}
    """.trimIndent()

    @Test fun labelsAreRead() {
        assertEquals(listOf("Fav", "cars"), PlexClient.parseItems(json).first().labels)
    }

    @Test fun favoritesFromLabelsOrTheLabelledList() {
        val episodes = Episodes.ordered(PlexClient.parseItems(json))
        // 11 by its own label (any case), 22 only from Plex's labelled list; in show order.
        assertEquals(listOf("11", "22"), Episodes.favorites(episodes, setOf("22")).map { it.ratingKey })
    }
}
