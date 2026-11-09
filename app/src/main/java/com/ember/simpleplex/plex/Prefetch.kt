package com.ember.simpleplex.plex

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * A video's full metadata (its streams, markers, chapters), fetched while focus rests on its card, so OK
 * plays without waiting for it. Used once, and for a minute at most: the progress in it can change.
 * Main thread only.
 */
object Prefetch {
    private const val FRESH_MS = 60_000L
    private const val KEPT = 8

    private class Entry(val at: Long, val item: Deferred<Item>)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val entries = LinkedHashMap<String, Entry>()

    fun start(server: PlexServer, ratingKey: String) {
        if (entries[ratingKey]?.let(::fresh) == true) return
        entries[ratingKey] = Entry(SystemClock.elapsedRealtime(), scope.async { server.metadata(ratingKey) })
        while (entries.size > KEPT) entries.remove(entries.keys.first())
    }

    /** The prefetched metadata if it's fresh and arrived, else fetched now. */
    suspend fun metadata(server: PlexServer, ratingKey: String): Item {
        val entry = entries.remove(ratingKey)?.takeIf(::fresh)
        return entry?.let { runCatching { it.item.await() }.getOrNull() } ?: server.metadata(ratingKey)
    }

    private fun fresh(entry: Entry) = SystemClock.elapsedRealtime() - entry.at < FRESH_MS
}
