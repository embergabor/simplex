package com.ember.simpleplex.player

import com.ember.simpleplex.plex.Item
import com.ember.simpleplex.plex.Marker

object Markers {

    /** The credits that run to the end: Plex's "final" ones, else the last credits marker. */
    fun endCredits(item: Item): Marker? =
        item.markers.filter { it.isCredits }.let { credits -> credits.firstOrNull { it.final } ?: credits.maxByOrNull { it.start } }

    /**
     * The marker whose button shows at [positionMs]: an intro (until its last second), or the end credits.
     * Earlier credits markers (mid-episode) get no button.
     */
    fun active(item: Item, positionMs: Long): Marker? {
        item.markers.firstOrNull { it.isIntro && positionMs >= it.start && positionMs < it.end - 1000 }?.let { return it }
        return endCredits(item)?.takeIf { positionMs >= it.start && positionMs < it.end }
    }

    /** Stopped this far, the video counts as watched: in its end credits, or past 95% without them. */
    fun watched(item: Item, positionMs: Long): Boolean {
        if (item.duration <= 0) return false
        val credits = endCredits(item)
        return if (credits != null) positionMs >= credits.start else positionMs >= item.duration * 95 / 100
    }
}
