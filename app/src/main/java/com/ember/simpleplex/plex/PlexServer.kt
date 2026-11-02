package com.ember.simpleplex.plex

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID

/**
 * One Plex Media Server, reached at [uri] with that server's own access [token]. The address can move
 * (plex.direct names carry the server's IP, and home connections get new public IPs): a request that
 * can't reach the server looks up where it is now and tries once more there.
 */
class PlexServer(uri: String, val token: String, val machineId: String?, private val client: PlexClient, val name: String = "") {

    @Volatile var uri: String = uri
        internal set

    private suspend fun <T> reaching(block: suspend () -> T): T {
        val tried = uri
        return try {
            block()
        } catch (e: IOException) {
            if (client.relocate(this, tried)) block() else throw e
        }
    }

    /** An item on this server as the player's queue takes it: "machineId|ratingKey", or the bare key if the id isn't known. */
    fun ref(ratingKey: String) = machineId?.let { "$it|$ratingKey" } ?: ratingKey

    /** For reports that must outlive a screen (timeline, watched state). */
    fun fire(block: suspend PlexServer.() -> Unit) = client.fire { block() }

    private suspend fun get(path: String): JSONObject = reaching {
        JSONObject(client.call(client.request(uri + path, token).build())).optJSONObject("MediaContainer") ?: JSONObject()
    }

    private suspend fun send(path: String, method: String = "GET") {
        val body = if (method == "GET") null else ByteArray(0).toRequestBody()
        reaching { client.call(client.request(uri + path, token).method(method, body).build()) }
    }

    // --- Library ---

    suspend fun libraries(): List<Library> = get("/library/sections").optJSONArray("Directory").objects().mapNotNull { d ->
        val kind = when (d.str("type")) {
            "show" -> Library.Kind.SHOWS
            "movie" -> if (d.str("agent")?.contains("none") == true) Library.Kind.OTHER else Library.Kind.MOVIES
            else -> return@mapNotNull null // music and photos are left out on purpose
        }
        Library(d.str("key") ?: return@mapNotNull null, d.str("title") ?: "", kind)
    }

    suspend fun libraryItems(sectionKey: String) = get("/library/sections/$sectionKey/all?sort=titleSort&includeGuids=1").items()

    /**
     * A library's list as Plex's raw JSON, for search to keep a copy on the TV: its titles, or with
     * [episodes] every episode of a TV library (with descriptions). Read back with [parseItems].
     */
    suspend fun libraryJson(sectionKey: String, episodes: Boolean): String {
        val path = "/library/sections/$sectionKey/all?" + if (episodes) "type=4" else "sort=titleSort&includeGuids=1"
        return reaching { client.call(client.request(uri + path, token).build()) }
    }

    /** A show's episodes. Their library comes from the list itself, since the episodes may not say. */
    suspend fun episodes(showKey: String): List<Item> {
        val mc = get("/library/metadata/$showKey/allLeaves")
        val section = mc.str("librarySectionID")
        return mc.items().map { if (it.librarySectionId == null) it.copy(librarySectionId = section) else it }
    }

    /**
     * The items of one Plex [type] in a library that carry [label]: 4 for a TV library's episodes, 1 for
     * the videos of a movie or "other videos" library. Asked the way Plex's own filters do: the label's
     * id first, then the items with that id. Empty when nothing has it yet.
     */
    suspend fun labelled(sectionKey: String, label: String, type: Int): List<Item> {
        val tag = get("/library/sections/$sectionKey/label?type=$type").optJSONArray("Directory").objects()
            .firstOrNull { it.str("title").equals(label, ignoreCase = true) } ?: return emptyList()
        val id = (tag.str("key") ?: return emptyList()).substringAfterLast('=').substringAfterLast('/')
        return get("/library/sections/$sectionKey/all?type=$type&label=${enc(id)}&sort=titleSort").items()
            // A list that carries labels must have this one; otherwise Plex ignored the filter.
            .filter { it.labels.isEmpty() || it.labels.any { l -> l.equals(label, ignoreCase = true) } }
    }

    /** Adds or removes a label on one item, keeping its other labels, and checks that Plex kept the change. */
    suspend fun setLabel(ratingKey: String, label: String, on: Boolean) {
        val item = metadata(ratingKey)
        val has = item.labels.any { it.equals(label, ignoreCase = true) }
        if (has == on) return
        val section = item.librarySectionId ?: throw IOException("Plex didn't say which library this is in")
        val type = when (item.type) {
            "movie" -> 1
            "show" -> 2
            "season" -> 3
            "episode" -> 4
            else -> throw IOException("Plex can't label a ${item.type}")
        }
        val tags = if (on) {
            (item.labels + label).mapIndexed { i, l -> "label[$i].tag.tag=${enc(l)}" }.joinToString("&")
        } else {
            "label[].tag.tag-=${enc(label)}"
        }
        send("/library/sections/$section/all?type=$type&id=$ratingKey&$tags&label.locked=1", "PUT")
        val saved = metadata(ratingKey).labels.any { it.equals(label, ignoreCase = true) }
        if (saved != on) throw IOException("Plex didn't keep the change")
    }

    /** Full metadata including streams, intro/credits markers and chapters, needed before playback. */
    suspend fun metadata(ratingKey: String): Item {
        val mc = get("/library/metadata/$ratingKey?includeMarkers=1&includeChapters=1")
        val item = mc.items().first()
        return if (item.librarySectionId == null) item.copy(librarySectionId = mc.str("librarySectionID")) else item
    }

    suspend fun continueWatching(): List<Item> {
        val items = runCatching { get("/hubs/continueWatching/items").items() }
            .getOrElse { get("/library/onDeck").items() }
        return items.filter { it.type == "movie" || it.type == "episode" || it.type == "clip" }
    }

    /** One level of an "Other Videos" library as it sits on disk. [key] is null for the library root. */
    suspend fun folder(sectionKey: String, key: String?): List<FolderEntry> {
        val mc = get(key ?: "/library/sections/$sectionKey/folder")
        val all = mc.optJSONArray("Directory").objects() + mc.optJSONArray("Metadata").objects()
        return all.mapNotNull { o ->
            val k = o.str("key") ?: return@mapNotNull null
            if (o.str("ratingKey") == null || (k.contains("/folder") && !o.has("Media"))) {
                FolderEntry.Folder(o.str("title") ?: "", k)
            } else {
                FolderEntry.Video(parseItem(o))
            }
        }
    }

    // --- URLs ---

    fun image(path: String?, width: Int, height: Int): String? = path?.let {
        "$uri/photo/:/transcode?width=$width&height=$height&minSize=1&upscale=1&url=${enc(it)}&X-Plex-Token=$token"
    }

    fun fileUrl(key: String) = "$uri$key${if ('?' in key) '&' else '?'}X-Plex-Token=$token"

    /** Saves a server file (e.g. preview thumbnails) to [dest], via a temporary file so it's never half-written. */
    suspend fun download(path: String, dest: File) = withContext(Dispatchers.IO) {
        val req = client.request(uri + path, token).header("Accept", "*/*").build()
        client.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Plex answered ${r.code} for $path")
            val tmp = File(dest.path + ".part")
            tmp.outputStream().use { out -> r.body.byteStream().copyTo(out) }
            if (!tmp.renameTo(dest)) throw IOException("Couldn't save $dest")
        }
    }

    // --- Playback state ---

    suspend fun timeline(ratingKey: String, state: String, timeMs: Long, durationMs: Long) =
        send("/:/timeline?ratingKey=$ratingKey&key=%2Flibrary%2Fmetadata%2F$ratingKey&state=$state&time=$timeMs&duration=$durationMs&identifier=com.plexapp.plugins.library")

    /** Saves where playback stopped, without showing as "now playing" (for the copies on other servers). */
    suspend fun setProgress(ratingKey: String, timeMs: Long) =
        send("/:/progress?key=$ratingKey&identifier=com.plexapp.plugins.library&time=$timeMs&state=stopped")

    /** Takes an item off Continue watching without marking it watched (until it's played again). */
    suspend fun removeFromContinueWatching(ratingKey: String) =
        send("/actions/removeFromContinueWatching?ratingKey=$ratingKey", "PUT")

    suspend fun setWatched(ratingKey: String, watched: Boolean) =
        send("/:/${if (watched) "scrobble" else "unscrobble"}?identifier=com.plexapp.plugins.library&key=$ratingKey")

    /** Remembers the audio/subtitle choice on the server. subtitleId 0 means off. */
    suspend fun selectStreams(partId: Long, audioId: Long?, subtitleId: Long?) {
        val params = buildList {
            audioId?.let { add("audioStreamID=$it") }
            subtitleId?.let { add("subtitleStreamID=$it") }
            add("allParts=1")
        }.joinToString("&")
        send("/library/parts/$partId?$params", "PUT")
    }

    /**
     * [mkv]: a Matroska stream, which can't be seeked (a seek starts a new conversion); otherwise HLS.
     * [subtitleBurned]: Plex had to burn the subtitle in after all, converting the video.
     */
    data class Converted(val url: String, val mkv: Boolean, val videoCopied: Boolean, val subtitleBurned: Boolean)

    /**
     * Asks Plex for a stream with the audio converted (to 5.1 E-AC3 or AC3, for passthrough); Plex copies
     * the video when it can. With [keepSubtitle], the subtitle selected on the server comes along as its
     * own track (text or image) in a Matroska stream: Plex's HLS can't carry image subtitles, and only
     * burns text ones in. Without it, an HLS stream with no subtitles. [videoCodecs]: the ones beyond H.264
     * the TV decodes, so Plex copies only video that plays and converts the rest.
     */
    suspend fun convertedStream(ratingKey: String, offsetMs: Long, keepSubtitle: Boolean, videoCodecs: List<String>): Converted {
        val more = videoCodecs.joinToString(",")
        val session = UUID.randomUUID().toString()
        val platform = if (keepSubtitle) "Generic" else "Android"
        val limits = listOf(
            "add-limitation(scope=videoAudioCodec&scopeName=eac3&type=upperBound&name=audio.channels&value=6)",
            "add-limitation(scope=videoAudioCodec&scopeName=ac3&type=upperBound&name=audio.channels&value=6)",
        )
        val profile = if (keepSubtitle) {
            // Plex's Android profile forces subtitles to be burned in; its generic one (asked for with the
            // "Generic" platform below) keeps them when the target container can hold them.
            val mkv = "type=videoProfile&context=streaming&protocol=http&container=mkv"
            listOf("add-transcode-target($mkv&videoCodec=${listOf("h264", more).filter { it.isNotEmpty() }.joinToString(",")}&audioCodec=eac3,ac3&subtitleCodec=pgs,vobsub,srt,ass)") + limits
        } else {
            // Plex starts from its built-in profile for the platform, whose HLS target only allows H.264 video
            // and stereo AAC audio. These extend that target instead of adding a second one (which Plex ignores).
            val hls = "type=videoProfile&context=streaming&protocol=hls"
            listOfNotNull(
                more.takeIf { it.isNotEmpty() }?.let { "append-transcode-target-codec($hls&videoCodec=$it)" },
                "add-transcode-target-audio-codec($hls&audioCodec=eac3)",
                "add-transcode-target-audio-codec($hls&audioCodec=ac3)",
            ) + limits
        }.joinToString("+")
        val query = listOf(
            "hasMDE" to "1",
            "path" to "/library/metadata/$ratingKey",
            "mediaIndex" to "0",
            "partIndex" to "0",
            "protocol" to if (keepSubtitle) "http" else "hls",
            "fastSeek" to "1",
            "directPlay" to "0",
            "directStream" to "1",
            "directStreamAudio" to "0",
            "subtitles" to if (keepSubtitle) "embedded" else "none",
            "videoQuality" to "100",
            "maxVideoBitrate" to "200000",
            "videoResolution" to "3840x2160",
            "location" to "lan",
            "offset" to (offsetMs / 1000).toString(),
            "session" to session,
            "X-Plex-Session-Identifier" to session,
            "X-Plex-Client-Profile-Extra" to profile,
            "X-Plex-Client-Identifier" to client.clientId,
            "X-Plex-Product" to PlexClient.PRODUCT,
            "X-Plex-Platform" to platform,
            "X-Plex-Token" to token,
        ).let { if (keepSubtitle) it + ("container" to "mkv") else it }
            .joinToString("&") { (k, v) -> "$k=${enc(v)}" }

        // Sent with the same platform header, so it can't override the query's.
        val decision = reaching {
            val req = client.request("$uri/video/:/transcode/universal/decision?$query", token).header("X-Plex-Platform", platform).build()
            JSONObject(client.call(req)).optJSONObject("MediaContainer") ?: JSONObject()
        }
        val streams = decision.items().firstOrNull()?.part?.streams.orEmpty()
        val reason = listOfNotNull(
            decision.str("generalDecisionText"),
            decision.str("transcodeDecisionText"),
        ).distinct().joinToString(" ")
        Log.i("Simplex", "decision keepSubtitle=$keepSubtitle: $reason | " +
            streams.joinToString { "${it.streamType}:${it.codec}/${it.channels ?: "-"}=${it.decision}" })
        val video = streams.firstOrNull { it.streamType == 1 }
            ?: throw IOException("Plex didn't offer a stream. $reason")
        val endpoint = if (keepSubtitle) "start.mkv" else "start.m3u8"
        return Converted(
            url = "$uri/video/:/transcode/universal/$endpoint?$query",
            mkv = keepSubtitle,
            videoCopied = video.decision == "copy",
            subtitleBurned = streams.any { it.streamType == 3 && it.decision == "burn" },
        )
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
