package com.ember.simpleplex.ui

import com.ember.simpleplex.plex.Item
import java.text.Normalizer

/** How a movie or show library's grid is ordered. */
enum class GridSort(val label: String) {
    TITLE("A–Z"),
    ADDED("Recently added"),
    YEAR("Year");

    /** [items] come from Plex already in title order. */
    fun apply(items: List<Item>): List<Item> = when (this) {
        TITLE -> items
        ADDED -> items.sortedByDescending { it.addedAt }
        YEAR -> items.sortedWith(compareByDescending<Item> { it.year ?: 0 }) // stable: title order within a year
    }

    companion object {
        /** The letter an item files under: A–Z with accents dropped ("Ö" → O), "#" for anything else. */
        fun letter(item: Item): Char {
            val c = Normalizer.normalize(item.titleSort.trim().take(1), Normalizer.Form.NFD).firstOrNull()?.uppercaseChar() ?: return '#'
            return if (c in 'A'..'Z') c else '#'
        }

        /** Each letter that occurs, with the position of its first item in [items] (in title order). */
        fun letters(items: List<Item>): List<Pair<Char, Int>> {
            val seen = linkedMapOf<Char, Int>()
            items.forEachIndexed { i, item -> seen.getOrPut(letter(item)) { i } }
            return seen.toList()
        }
    }
}
