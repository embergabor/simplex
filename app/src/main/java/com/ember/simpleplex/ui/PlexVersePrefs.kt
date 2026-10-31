package com.ember.simpleplex.ui

import android.content.Context
import com.ember.simpleplex.plex.Library
import org.json.JSONArray
import org.json.JSONObject

/** Which PlexVerse category a server library goes into. */
enum class Category {
    MOVIES, SERIES, VIDEOS, NONE;

    companion object {
        fun default(kind: Library.Kind) = when (kind) {
            Library.Kind.MOVIES -> MOVIES
            Library.Kind.SHOWS -> SERIES
            Library.Kind.OTHER -> VIDEOS
        }
    }
}

data class LibraryChoice(val category: Category, val kids: Boolean = false)

/**
 * PlexVerse's setup: the servers in the order they're tried, and each library's category, keyed by
 * "machineId/sectionKey" (section keys repeat on every server). [LibraryChoice.kids] only counts with [kidsSeparate].
 */
data class VerseSetup(
    val servers: List<String> = emptyList(),
    val libraries: Map<String, LibraryChoice> = emptyMap(),
    val kidsSeparate: Boolean = false,
) {
    val isSetUp get() = libraries.isNotEmpty()

    /** [machineIds] in preference order; servers not in the list yet (shared later) go to the end. */
    fun ordered(machineIds: Collection<String>): List<String> =
        servers.filter { it in machineIds } + machineIds.filter { it !in servers }

    fun choice(machineId: String, library: Library) =
        libraries[key(machineId, library.key)] ?: LibraryChoice(Category.default(library.kind))

    fun toJson(): String = JSONObject()
        .put("servers", JSONArray(servers))
        .put("libraries", JSONObject().apply {
            for ((k, c) in libraries) put(k, JSONObject().put("category", c.category.name).put("kids", c.kids))
        })
        .put("kidsSeparate", kidsSeparate)
        .toString()

    companion object {
        fun key(machineId: String, sectionKey: String) = "$machineId/$sectionKey"

        fun fromJson(json: String): VerseSetup {
            val o = JSONObject(json)
            val servers = o.optJSONArray("servers") ?: JSONArray()
            val libs = o.optJSONObject("libraries") ?: JSONObject()
            return VerseSetup(
                servers = (0 until servers.length()).map { servers.getString(it) },
                libraries = libs.keys().asSequence().associateWith { k ->
                    val c = libs.getJSONObject(k)
                    LibraryChoice(
                        runCatching { Category.valueOf(c.optString("category")) }.getOrDefault(Category.NONE),
                        c.optBoolean("kids"),
                    )
                },
                kidsSeparate = o.optBoolean("kidsSeparate"),
            )
        }
    }
}

/** [VerseSetup], kept on the TV. */
class PlexVersePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    var setup: VerseSetup
        get() = prefs.getString("plexVerse", null)?.let { runCatching { VerseSetup.fromJson(it) }.getOrNull() } ?: VerseSetup()
        set(value) { prefs.edit().putString("plexVerse", value.toJson()).apply() }
}
