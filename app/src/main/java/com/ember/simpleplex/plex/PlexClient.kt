package com.ember.simpleplex.plex

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Talks to plex.tv (sign-in, server discovery) and to the user's Plex Media Server. */
class PlexClient private constructor(context: Context) {

    private val prefs = context.getSharedPreferences("plex", Context.MODE_PRIVATE)

    val clientId: String = prefs.getString("clientId", null)
        ?: UUID.randomUUID().toString().also { prefs.edit().putString("clientId", it).apply() }

    var serverUri: String?
        get() = prefs.getString("serverUri", null)
        private set(v) { prefs.edit().putString("serverUri", v).apply() }

    /** The server's access token (not the plex.tv account token). */
    var token: String?
        get() = prefs.getString("token", null)
        private set(v) { prefs.edit().putString("token", v).apply() }

    val isSignedIn get() = serverUri != null && token != null

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val probeHttp = http.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    /** For reports that must outlive a screen (timeline, watched state). */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun fire(block: suspend PlexClient.() -> Unit) {
        background.launch { runCatching { block() } }
    }

    private fun Request.Builder.plexHeaders(token: String?) = apply {
        header("Accept", "application/json")
        header("X-Plex-Product", PRODUCT)
        header("X-Plex-Version", "0.1")
        header("X-Plex-Client-Identifier", clientId)
        header("X-Plex-Platform", "Android")
        header("X-Plex-Device", "Android TV")
        header("X-Plex-Device-Name", Build.MODEL)
        if (token != null) header("X-Plex-Token", token)
    }

    private suspend fun call(req: Request, client: OkHttpClient = http): String = withContext(Dispatchers.IO) {
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Plex answered ${r.code} for ${req.url.encodedPath}")
            r.body.string()
        }
    }

    private suspend fun get(path: String): JSONObject {
        val req = Request.Builder().url(requireNotNull(serverUri) + path).plexHeaders(token).build()
        return JSONObject(call(req)).optJSONObject("MediaContainer") ?: JSONObject()
    }

    private suspend fun send(path: String, method: String = "GET") {
        val body = if (method == "GET") null else ByteArray(0).toRequestBody()
        call(Request.Builder().url(requireNotNull(serverUri) + path).method(method, body).plexHeaders(token).build())
    }

    // --- Sign-in: plex.tv/link PIN flow ---

    data class Pin(val id: Long, val code: String)

    suspend fun createPin(): Pin {
        val body = "strong=false".toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val j = JSONObject(call(Request.Builder().url("$PLEX_TV/pins").post(body).plexHeaders(null).build()))
        return Pin(j.getLong("id"), j.getString("code"))
    }

    /** The account token once the code was entered at plex.tv/link, else null. */
    suspend fun pinToken(id: Long): String? {
        val j = JSONObject(call(Request.Builder().url("$PLEX_TV/pins/$id").plexHeaders(null).build()))
        return j.str("authToken")?.takeIf { it.isNotEmpty() }
    }

    /** Finds the account's server and the first connection that answers, preferring the local network. */
    suspend fun connect(accountToken: String) {
        val req = Request.Builder().url("$PLEX_TV/resources?includeHttps=1&includeRelay=1").plexHeaders(accountToken).build()
        val servers = JSONArray(call(req)).objects()
            .filter { it.str("provides")?.contains("server") == true }
            .sortedByDescending { it.bool("owned") }
        for (server in servers) {
            val serverToken = server.str("accessToken") ?: accountToken
            val candidates = buildList {
                val conns = server.optJSONArray("connections").objects().sortedBy { c ->
                    when {
                        c.bool("local") && !c.bool("relay") -> 0
                        !c.bool("relay") -> 1
                        else -> 2
                    }
                }
                for (c in conns) {
                    c.str("uri")?.let(::add)
                    // plex.direct names can be blocked by router DNS rebinding protection; plain http works locally.
                    val address = c.str("address")
                    if (c.bool("local") && address != null) add("http://$address:${c.int("port") ?: 32400}")
                }
            }
            for (uri in candidates) {
                if (probe(uri, serverToken)) {
                    serverUri = uri.trimEnd('/')
                    token = serverToken
                    return
                }
            }
        }
        throw IOException("Couldn't reach a Plex server on this account")
    }

    private suspend fun probe(uri: String, token: String) = runCatching {
        call(Request.Builder().url("$uri/identity").plexHeaders(token).build(), probeHttp)
    }.isSuccess

    // --- Library ---

    suspend fun libraries(): List<Library> = get("/library/sections").optJSONArray("Directory").objects().mapNotNull { d ->
        val kind = when (d.str("type")) {
            "show" -> Library.Kind.SHOWS
            "movie" -> if (d.str("agent")?.contains("none") == true) Library.Kind.OTHER else Library.Kind.MOVIES
            else -> return@mapNotNull null // music and photos are left out on purpose
        }
        Library(d.str("key") ?: return@mapNotNull null, d.str("title") ?: "", kind)
    }

    suspend fun libraryItems(sectionKey: String) = get("/library/sections/$sectionKey/all?sort=titleSort").items()

    /** Titles containing [query] in each of [libraries], asked in parallel; libraries with no match are left out. */
    suspend fun search(libraries: List<Library>, query: String): List<Pair<Library, List<Item>>> = coroutineScope {
        libraries.map { lib ->
            async { lib to get("/library/sections/${lib.key}/all?sort=titleSort&title=${enc(query)}").items() }
        }.awaitAll().filter { it.second.isNotEmpty() }
    }

    suspend fun episodes(showKey: String) = get("/library/metadata/$showKey/allLeaves").items()

    /** Full metadata including streams, needed before playback. */
    suspend fun metadata(ratingKey: String) = get("/library/metadata/$ratingKey").items().first()

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
        "$serverUri/photo/:/transcode?width=$width&height=$height&minSize=1&upscale=1&url=${enc(it)}&X-Plex-Token=$token"
    }

    fun fileUrl(key: String) = "$serverUri$key${if ('?' in key) '&' else '?'}X-Plex-Token=$token"

    /** Saves a server file (e.g. preview thumbnails) to [dest], via a temporary file so it's never half-written. */
    suspend fun download(path: String, dest: File) = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(requireNotNull(serverUri) + path).plexHeaders(token).header("Accept", "*/*").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Plex answered ${r.code} for $path")
            val tmp = File(dest.path + ".part")
            tmp.outputStream().use { out -> r.body.byteStream().copyTo(out) }
            if (!tmp.renameTo(dest)) throw IOException("Couldn't save $dest")
        }
    }

    // --- Playback state ---

    suspend fun timeline(ratingKey: String, state: String, timeMs: Long, durationMs: Long) =
        send("/:/timeline?ratingKey=$ratingKey&key=%2Flibrary%2Fmetadata%2F$ratingKey&state=$state&time=$timeMs&duration=$durationMs&identifier=com.plexapp.plugins.library")

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

    data class Converted(val url: String, val videoCopied: Boolean)

    /**
     * Asks Plex for an HLS stream with the audio converted (to 5.1 E-AC3 or AC3, for passthrough) and,
     * if [burnSubtitles], the selected subtitle burned in. Plex decides the rest: it copies the video
     * when it can and converts it when it must (always when burning).
     */
    suspend fun convertedStream(ratingKey: String, offsetMs: Long, burnSubtitles: Boolean): Converted {
        val session = UUID.randomUUID().toString()
        // Plex starts from its built-in profile for the platform, whose HLS target only allows H.264 video
        // and stereo AAC audio. These extend that target instead of adding a second one (which Plex ignores).
        val hls = "type=videoProfile&context=streaming&protocol=hls"
        val profile = listOf(
            "append-transcode-target-codec($hls&videoCodec=hevc,mpeg2video,vc1,vp9,av1)",
            "add-transcode-target-audio-codec($hls&audioCodec=eac3)",
            "add-transcode-target-audio-codec($hls&audioCodec=ac3)",
            "add-limitation(scope=videoAudioCodec&scopeName=eac3&type=upperBound&name=audio.channels&value=6)",
            "add-limitation(scope=videoAudioCodec&scopeName=ac3&type=upperBound&name=audio.channels&value=6)",
        ).joinToString("+")
        val query = listOf(
            "hasMDE" to "1",
            "path" to "/library/metadata/$ratingKey",
            "mediaIndex" to "0",
            "partIndex" to "0",
            "protocol" to "hls",
            "fastSeek" to "1",
            "directPlay" to "0",
            "directStream" to "1",
            "directStreamAudio" to "0",
            "subtitles" to if (burnSubtitles) "burn" else "none",
            "videoQuality" to "100",
            "maxVideoBitrate" to "200000",
            "videoResolution" to "3840x2160",
            "location" to "lan",
            "offset" to (offsetMs / 1000).toString(),
            "session" to session,
            "X-Plex-Session-Identifier" to session,
            "X-Plex-Client-Profile-Extra" to profile,
            "X-Plex-Client-Identifier" to clientId,
            "X-Plex-Product" to PRODUCT,
            "X-Plex-Platform" to "Android",
            "X-Plex-Token" to (token ?: ""),
        ).joinToString("&") { (k, v) -> "$k=${enc(v)}" }

        val decision = get("/video/:/transcode/universal/decision?$query")
        val streams = decision.items().firstOrNull()?.part?.streams.orEmpty()
        val reason = listOfNotNull(
            decision.str("generalDecisionText"),
            decision.str("transcodeDecisionText"),
        ).distinct().joinToString(" ")
        Log.i(TAG, "decision burn=$burnSubtitles: $reason | " +
            streams.joinToString { "${it.streamType}:${it.codec}/${it.channels ?: "-"}=${it.decision}" })
        val video = streams.firstOrNull { it.streamType == 1 }
            ?: throw IOException("Plex didn't offer a stream. $reason")
        return Converted("$serverUri/video/:/transcode/universal/start.m3u8?$query", video.decision == "copy")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        const val PRODUCT = "Simplex"
        private const val TAG = "Simplex"
        private const val PLEX_TV = "https://plex.tv/api/v2"

        @Volatile private var instance: PlexClient? = null

        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: PlexClient(context.applicationContext).also { instance = it }
        }
    }
}
