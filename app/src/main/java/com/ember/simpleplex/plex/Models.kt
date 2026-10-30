package com.ember.simpleplex.plex

import org.json.JSONArray
import org.json.JSONObject

data class Library(val key: String, val title: String, val kind: Kind) {
    enum class Kind { MOVIES, SHOWS, OTHER }
}

data class Item(
    val ratingKey: String,
    val type: String,
    val title: String,
    val thumb: String?,
    val art: String?,
    val grandparentRatingKey: String?,
    val grandparentTitle: String?,
    val parentIndex: Int?,
    val index: Int?,
    val viewOffset: Long,
    val viewCount: Int,
    val duration: Long,
    val leafCount: Int,
    val viewedLeafCount: Int,
    val media: List<Media>,
) {
    val isEpisode get() = type == "episode"

    val watched
        get() = if (type == "show") leafCount > 0 && viewedLeafCount >= leafCount
        else viewCount > 0 && viewOffset == 0L

    val progress
        get() = if (duration > 0 && viewOffset > 0) (viewOffset.toFloat() / duration).coerceIn(0f, 1f) else 0f

    val part get() = media.firstOrNull()?.parts?.firstOrNull()

    /** "S2 · E4" for episodes, null otherwise. */
    val episodeLabel get() = if (isEpisode) "S${parentIndex ?: 0} · E${index ?: 0}" else null
}

data class Media(val parts: List<Part>)

data class Part(
    val id: Long,
    val key: String,
    val streams: List<Stream>,
    /** Plex generated video preview thumbnails for this file. */
    val previews: Boolean,
)

data class Stream(
    val id: Long,
    /** 1 = video, 2 = audio, 3 = subtitle. */
    val streamType: Int,
    val codec: String?,
    /** Codec detail, e.g. "ma" for DTS-HD Master Audio. */
    val profile: String?,
    val index: Int?,
    val languageCode: String?,
    val displayTitle: String,
    val selected: Boolean,
    val forced: Boolean,
    /** Set for subtitle files stored next to the video (sidecar files). */
    val key: String?,
    /** Only present in transcode decisions: "copy", "transcode", ... */
    val decision: String?,
    val width: Int?,
    val height: Int?,
    val frameRate: Float?,
    /** Transfer function: "smpte2084" (PQ, HDR10), "arib-std-b67" (HLG), else SDR. */
    val colorTrc: String?,
    /** Dolby Vision profile, when the video has Dolby Vision. */
    val doviProfile: Int?,
    val channels: Int?,
) {
    val isExternal get() = key != null
}

sealed interface FolderEntry {
    data class Folder(val title: String, val key: String) : FolderEntry
    data class Video(val item: Item) : FolderEntry
}

object Episodes {
    /** Season order with specials (season 0) last, then episode order. */
    fun ordered(list: List<Item>): List<Item> = list.sortedWith(
        compareBy<Item>({ (it.parentIndex ?: 0).let { s -> if (s == 0) Int.MAX_VALUE else s } }, { it.index ?: 0 })
    )

    /** Index of the episode "Play" should start with: one in progress, else the one after the last watched. */
    fun nextUp(ordered: List<Item>): Int {
        val inProgress = ordered.indexOfFirst { it.viewOffset > 0 }
        if (inProgress >= 0) return inProgress
        val next = ordered.indexOfLast { it.viewCount > 0 } + 1
        return if (next < ordered.size) next else 0
    }
}

// --- JSON helpers. Plex JSON is loose about types, so read everything defensively. ---

internal fun JSONObject.str(name: String): String? =
    if (has(name) && !isNull(name)) get(name).toString() else null

internal fun JSONObject.bool(name: String): Boolean = when (val v = opt(name)) {
    is Boolean -> v
    is Number -> v.toInt() != 0
    is String -> v == "1" || v.equals("true", ignoreCase = true)
    else -> false
}

internal fun JSONObject.int(name: String): Int? = str(name)?.toDoubleOrNull()?.toInt()
internal fun JSONObject.long(name: String): Long? = str(name)?.toDoubleOrNull()?.toLong()

internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

internal fun JSONObject.items(): List<Item> = optJSONArray("Metadata").objects().map(::parseItem)

internal fun parseItem(o: JSONObject) = Item(
    ratingKey = o.str("ratingKey") ?: "",
    type = o.str("type") ?: "",
    title = o.str("title") ?: "",
    thumb = o.str("thumb"),
    art = o.str("art"),
    grandparentRatingKey = o.str("grandparentRatingKey"),
    grandparentTitle = o.str("grandparentTitle"),
    parentIndex = o.int("parentIndex"),
    index = o.int("index"),
    viewOffset = o.long("viewOffset") ?: 0,
    viewCount = o.int("viewCount") ?: 0,
    duration = o.long("duration") ?: 0,
    leafCount = o.int("leafCount") ?: 0,
    viewedLeafCount = o.int("viewedLeafCount") ?: 0,
    media = o.optJSONArray("Media").objects().map { m ->
        Media(
            parts = m.optJSONArray("Part").objects().map { p ->
                Part(
                    id = p.long("id") ?: 0,
                    key = p.str("key") ?: "",
                    streams = p.optJSONArray("Stream").objects().map(::parseStream),
                    previews = p.str("indexes")?.contains("sd") == true,
                )
            },
        )
    },
)

private fun parseStream(s: JSONObject) = Stream(
    id = s.long("id") ?: 0,
    streamType = s.int("streamType") ?: 0,
    codec = s.str("codec"),
    profile = s.str("profile"),
    index = s.int("index"),
    languageCode = s.str("languageCode"),
    displayTitle = s.str("extendedDisplayTitle") ?: s.str("displayTitle") ?: s.str("language") ?: "Unknown",
    selected = s.bool("selected"),
    forced = s.bool("forced"),
    key = s.str("key"),
    decision = s.str("decision"),
    width = s.int("width"),
    height = s.int("height"),
    frameRate = s.str("frameRate")?.toFloatOrNull(),
    colorTrc = s.str("colorTrc"),
    doviProfile = if (s.bool("DOVIPresent")) s.int("DOVIProfile") ?: 0 else null,
    channels = s.int("channels"),
)
