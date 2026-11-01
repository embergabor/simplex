package com.ember.simpleplex.plex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChaptersTest {

    @Test
    fun `chapters in start order, untitled ones numbered`() {
        val json = """{"MediaContainer":{"Metadata":[{"ratingKey":"1","title":"Film","type":"movie","Chapter":[
            {"index":2,"startTimeOffset":132132,"endTimeOffset":447280,"tag":"Breakfast","thumb":"/library/media/9/chapterImages/2"},
            {"index":1,"startTimeOffset":0,"endTimeOffset":132132,"thumb":"/library/media/9/chapterImages/1"},
            {"index":3,"endTimeOffset":500000}
        ]}]}}"""
        val chapters = PlexClient.parseItems(json).first().chapters
        assertEquals(listOf(0L, 132132L), chapters.map { it.start })
        assertEquals(listOf("Chapter 1", "Breakfast"), chapters.map { it.label })
        assertEquals("/library/media/9/chapterImages/2", chapters[1].thumb)
    }

    @Test
    fun `no chapters`() {
        val json = """{"MediaContainer":{"Metadata":[{"ratingKey":"1","title":"Film","type":"movie"}]}}"""
        assertTrue(PlexClient.parseItems(json).first().chapters.isEmpty())
    }
}
