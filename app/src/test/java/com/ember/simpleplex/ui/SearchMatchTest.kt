package com.ember.simpleplex.ui

import com.ember.simpleplex.ui.SearchMatch.Text
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchMatchTest {
    private fun match(query: String, own: String, context: String? = null) =
        SearchMatch.matches(SearchMatch.terms(query), Text(own), context?.let(::Text))

    @Test fun allWordsAnyOrderIgnoringCaseAndAccents() {
        assertTrue(match("grand TOUR", "The Grand Tour"))
        assertTrue(match("tour grand", "The Grand Tour"))
        assertTrue(match("kerekes", "Kérékes"))
        assertFalse(match("grand prix", "The Grand Tour"))
    }

    @Test fun wordsMatchAcrossSpaces() {
        assertTrue(match("topgear", "Top Gear"))
        assertTrue(match("grandtour", "The Grand Tour (2016)"))
    }

    @Test fun episodeMatchesOnItsDescriptionAndShowName() {
        val bolivia = "Episode 6\nJeremy, Richard and James drive across Bolivia in cheap 4x4s."
        assertTrue(match("bolivia", bolivia, "Top Gear"))
        assertTrue(match("topgear bolivia", bolivia, "Top Gear"))
        assertFalse(match("grand tour bolivia", bolivia, "Top Gear"))
    }

    @Test fun showNameAloneDoesNotListEveryEpisode() {
        assertFalse(match("top gear", "Episode 6\nA race across Bolivia.", "Top Gear"))
        assertFalse(match("", "anything"))
    }
}
