package com.ember.simpleplex.plex

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Talks to plex.tv: sign-in and the servers the account can reach. Each server is a [PlexServer]. */
class PlexClient private constructor(context: Context) {

    private val prefs = context.getSharedPreferences("plex", Context.MODE_PRIVATE)

    val clientId: String = prefs.getString("clientId", null)
        ?: UUID.randomUUID().toString().also { prefs.edit().putString("clientId", it).apply() }

    /** The plex.tv account token, for listing servers. Missing on installs signed in before it was kept. */
    var accountToken: String?
        get() = prefs.getString("accountToken", null)
        private set(v) { prefs.edit().putString("accountToken", v).apply() }

    /** The server the app signed in to; Home, Search and the libraries use it. */
    var home: PlexServer? = run {
        val uri = prefs.getString("serverUri", null)
        val token = prefs.getString("token", null)
        if (uri != null && token != null) PlexServer(uri, token, prefs.getString("machineId", null), this, prefs.getString("serverName", null) ?: "") else null
    }
        private set

    val isSignedIn get() = home != null

    internal val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val probeHttp = http.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    /** For reports that must outlive a screen (timeline, watched state). Each runs on its own, so one failing never stops another. */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun fire(block: suspend () -> Unit) {
        background.launch { runCatching { block() } }
    }

    internal fun request(url: String, token: String?) = Request.Builder().url(url).plexHeaders(token)

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

    internal suspend fun call(req: Request, client: OkHttpClient = http): String = withContext(Dispatchers.IO) {
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Plex answered ${r.code} for ${req.url.encodedPath}")
            r.body.string()
        }
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
        this.accountToken = accountToken
        for (server in fetchServers(accountToken).sortedByDescending { it.owned }) {
            for (uri in server.uris) {
                if (probe(uri, server.accessToken)) {
                    prefs.edit()
                        .putString("serverUri", uri)
                        .putString("token", server.accessToken)
                        .putString("machineId", server.machineId)
                        .putString("serverName", server.name)
                        .apply()
                    home = PlexServer(uri, server.accessToken, server.machineId, this, server.name)
                    return
                }
            }
        }
        throw IOException("Couldn't reach a Plex server on this account")
    }

    /** Keeps the account token of an install signed in before it was kept, once plex.tv accepts it. */
    suspend fun linkAccount(accountToken: String) {
        fetchServers(accountToken)
        this.accountToken = accountToken
    }

    // --- Every server the account can reach ---

    /** A server from plex.tv: the account's own or one shared with it, with its own access token. */
    data class ServerInfo(val machineId: String, val name: String, val owned: Boolean, val accessToken: String, val uris: List<String>)

    /** The servers as last listed by [servers], so PlexVerse can start without waiting on plex.tv. */
    val cachedServers: List<ServerInfo>
        get() = prefs.getString("resources", null)?.let { runCatching { parseResources(it, accountToken ?: "") }.getOrNull() }.orEmpty()

    /** Lists the servers on plex.tv (needs [accountToken]) and keeps the answer for [cachedServers]. */
    suspend fun servers(): List<ServerInfo> = fetchServers(requireNotNull(accountToken) { "Not signed in to plex.tv" })

    private suspend fun fetchServers(accountToken: String): List<ServerInfo> {
        val json = call(request("$PLEX_TV/resources?includeHttps=1&includeRelay=1", accountToken).build())
        val servers = parseResources(json, accountToken)
        prefs.edit().putString("resources", json).apply()
        // Installs from before the home server's id was kept: it's the one with the same token.
        val h = home
        if (h != null && h.machineId == null) servers.find { it.accessToken == h.token }?.let { s ->
            prefs.edit().putString("machineId", s.machineId).putString("serverName", s.name).apply()
            home = PlexServer(h.uri, h.token, s.machineId, this, s.name)
        }
        return servers
    }

    private val connected = ConcurrentHashMap<String, PlexServer>()

    /**
     * A connection to one server, picked the first time it's needed: every candidate address is tried
     * at once and the first that answers is kept (also as a hint for next time). Null if none answers.
     */
    suspend fun server(machineId: String): PlexServer? {
        connected[machineId]?.let { return it }
        home?.takeIf { it.machineId == machineId }?.let { return it }
        val info = (cachedServers.find { it.machineId == machineId } ?: runCatching { servers() }.getOrNull()?.find { it.machineId == machineId }) ?: return null
        val hint = prefs.getString("uri-$machineId", null)
        val candidates = listOfNotNull(hint) + info.uris
        val uri = channelFlow {
            candidates.distinct().forEach { launch { if (probe(it, info.accessToken)) send(it) } }
        }.firstOrNull() ?: return null
        prefs.edit().putString("uri-$machineId", uri).apply()
        return PlexServer(uri, info.accessToken, machineId, this, info.name).also { connected[machineId] = it }
    }

    private suspend fun probe(uri: String, token: String) = runCatching {
        call(Request.Builder().url("$uri/identity").plexHeaders(token).build(), probeHttp)
    }.isSuccess

    companion object {
        const val PRODUCT = "Simplex"
        private const val PLEX_TV = "https://plex.tv/api/v2"

        /**
         * The servers in a plex.tv /resources answer, each with its connection addresses: local first,
         * then remote, then relay. [accountToken] stands in for a server that lists no token of its own.
         */
        internal fun parseResources(json: String, accountToken: String): List<ServerInfo> =
            JSONArray(json).objects()
                .filter { it.str("provides")?.contains("server") == true }
                .mapNotNull { server ->
                    val uris = buildList {
                        val conns = server.optJSONArray("connections").objects().sortedBy { c ->
                            when {
                                c.bool("local") && !c.bool("relay") -> 0
                                !c.bool("relay") -> 1
                                else -> 2
                            }
                        }
                        for (c in conns) {
                            c.str("uri")?.let { add(it.trimEnd('/')) }
                            // plex.direct names can be blocked by router DNS rebinding protection; plain http works locally.
                            val address = c.str("address")
                            if (c.bool("local") && address != null) add("http://$address:${c.int("port") ?: 32400}")
                        }
                    }
                    ServerInfo(
                        machineId = server.str("clientIdentifier") ?: return@mapNotNull null,
                        name = server.str("name") ?: "",
                        owned = server.bool("owned"),
                        accessToken = server.str("accessToken") ?: accountToken,
                        uris = uris,
                    )
                }

        fun parseItems(json: String): List<Item> = (JSONObject(json).optJSONObject("MediaContainer") ?: JSONObject()).items()

        @Volatile private var instance: PlexClient? = null

        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: PlexClient(context.applicationContext).also { instance = it }
        }
    }
}
