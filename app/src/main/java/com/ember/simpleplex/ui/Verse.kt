package com.ember.simpleplex.ui

import android.content.Context
import com.ember.simpleplex.plex.Item
import com.ember.simpleplex.plex.PlexClient
import com.ember.simpleplex.plex.PlexServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** One copy of a title, on the server with [machineId]. */
data class Source(val machineId: String, val item: Item) {
    val ref get() = "$machineId|${item.ratingKey}"
}

/** One copy of an item on a server that's been reached. */
data class Copy(val server: PlexServer, val item: Item) {
    val ref get() = server.ref(item.ratingKey)
}

/** A player queue entry that tries [copies] in order. */
fun queueEntry(copies: List<Copy>) = copies.joinToString(",") { it.ref }

/**
 * A title merged across servers. [sources] are in server preference order; [item] is the first one's,
 * with the progress and watched state of the most recently watched copy.
 */
data class VerseEntry(val key: String, val item: Item, val sources: List<Source>)

/** A Continue watching card: the copy to show, and the sources to play it from, in order. */
data class Continuing(val item: Item, val sources: List<Source>)

object Verse {
    // Ids that mean the same title on every server. Others (local://, the "none" agent) are per server.
    private val shared = Regex("^(plex|imdb|tmdb|tvdb)://")

    private fun normalize(title: String) = SearchMatch.terms(title).joinToString(" ")

    /** A–Z order: the sort title (without "The"), ignoring case and accents. */
    fun sortKey(item: Item) = normalize(item.titleSort ?: item.title)

    /** What a title is matched by: its shared ids, or without any, its title and year. */
    fun matchKeys(item: Item): List<String> =
        item.guids.filter { shared.containsMatchIn(it) }.ifEmpty { listOf("title:${normalize(item.title)}|${item.year ?: ""}") }

    /** An episode's: its shared ids, or without any, its season and number. */
    fun episodeKeys(item: Item): List<String> =
        item.guids.filter { shared.containsMatchIn(it) }.ifEmpty { listOf("episode:${item.parentIndex ?: 0}|${item.index ?: 0}") }

    /** Groups [sources] that share any of their [keys] (a union-find over the keys), sorted A–Z. */
    fun merge(sources: List<Source>, order: List<String>, keys: (Item) -> List<String> = ::matchKeys): List<VerseEntry> {
        val parent = IntArray(sources.size) { it }
        fun root(i: Int): Int {
            var x = i
            while (parent[x] != x) x = parent[x].also { parent[x] = parent[parent[x]] }
            return x
        }
        val owner = HashMap<String, Int>()
        sources.forEachIndexed { i, s ->
            for (k in keys(s.item)) owner.putIfAbsent(k, i)?.let { parent[root(i)] = root(it) }
        }
        val rank = order.withIndex().associate { (i, id) -> id to i }
        return sources.indices.groupBy(::root).values.map { members ->
            val group = members.map { sources[it] }.sortedBy { rank[it.machineId] ?: Int.MAX_VALUE }
            val first = group.first().item
            val newest = group.maxBy { it.item.lastViewedAt }.item
            VerseEntry(
                key = keys(first).first(),
                item = first.copy(viewOffset = newest.viewOffset, viewCount = newest.viewCount, viewedLeafCount = newest.viewedLeafCount),
                sources = group,
            )
        }.sortedBy { sortKey(it.item) }
    }
}

/**
 * The Continue watching row from every server's in-progress items. A movie plays with every copy of
 * its merged title in [titles]; an episode or video on several servers shows once, from the preferred
 * server. Newest first.
 */
fun Verse.continuing(inProgress: List<Source>, order: List<String>, titles: List<VerseEntry>): List<Continuing> {
    val rank = order.withIndex().associate { (i, id) -> id to i }
    val titleOf = HashMap<String, VerseEntry>()
    for (e in titles) for (s in e.sources) titleOf[s.ref] = e
    return inProgress
        .groupBy { s -> titleOf[s.ref]?.key ?: episodeKeys(s.item).firstOrNull { !it.startsWith("episode:") } ?: s.ref }
        .values
        .sortedByDescending { group -> group.maxOf { it.item.lastViewedAt } }
        .map { group ->
            val first = group.minBy { rank[it.machineId] ?: Int.MAX_VALUE }
            Continuing(first.item, titleOf[first.ref]?.sources ?: listOf(first))
        }
}

sealed interface ServerState {
    data object Waiting : ServerState
    data class Done(val items: Int) : ServerState
    data class Failed(val reason: String) : ServerState
}

/**
 * PlexVerse's lists from every server: the copy saved on the TV straight away, then each server's
 * current lists as they arrive, all servers at once. Kept for the whole app, so grids open instantly.
 */
class VerseStore private constructor(context: Context, private val plex: PlexClient) {
    private val prefs = PlexVersePrefs(context)
    private val dir = File(context.cacheDir, "verse")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var started = 0L

    private val _servers = MutableStateFlow<Map<String, ServerState>>(emptyMap())
    /** Each server in the setup, by machine id, and how its fetch went. */
    val servers: StateFlow<Map<String, ServerState>> = _servers

    private val _lists = MutableStateFlow<Map<String, List<Item>>>(emptyMap())
    /** Each library's titles, by "machineId/sectionKey". */
    val lists: StateFlow<Map<String, List<Item>>> = _lists

    private val _continuing = MutableStateFlow<Map<String, List<Item>>>(emptyMap())
    /** Each server's Continue watching items, by machine id. */
    val continuing: StateFlow<Map<String, List<Item>>> = _continuing

    private val _connected = MutableStateFlow<Map<String, PlexServer>>(emptyMap())
    /** The servers reached so far, for their images. */
    val connected: StateFlow<Map<String, PlexServer>> = _connected

    private fun file(key: String) = File(dir, "verse-${key.replace('/', '-')}.json")

    /** Whether any list was saved before, i.e. whether this isn't the very first open. */
    fun hasCache() = prefs.setup.libraries.keys.any { file(it).exists() }

    /** Fetches every server again, unless that's already running or happened in the last few minutes. */
    fun refresh() {
        // ponytail: "once per visit" is approximated by 5 minutes, so Back from a grid doesn't refetch.
        if (job?.isActive == true || System.currentTimeMillis() - started < 5 * 60_000) return
        started = System.currentTimeMillis()
        job = scope.launch {
            val setup = prefs.setup
            val chosen = setup.libraries.filterValues { it.category != Category.NONE }.keys
            // Only Movies and Series are listed; Videos are browsed by folder, so only their server counts.
            val listed = setup.libraries.filterValues { it.category == Category.MOVIES || it.category == Category.SERIES }.keys
            val ids = chosen.map { it.substringBefore('/') }.distinct()
            _servers.value = ids.associateWith { ServerState.Waiting }
            if (_lists.value.isEmpty()) {
                _lists.value = withContext(Dispatchers.IO) {
                    listed.mapNotNull { k -> runCatching { k to PlexClient.parseItems(file(k).readText()) }.getOrNull() }.toMap()
                }
            }
            // Also picks up servers shared recently and tokens that changed.
            launch { runCatching { plex.servers() } }
            for (id in ids) launch {
                val state = try {
                    withTimeoutOrNull(15_000) { fetch(id, listed.filter { it.startsWith("$id/") }) }
                        ?: ServerState.Failed("not reachable")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ServerState.Failed(e.message ?: "failed")
                }
                _servers.update { it + (id to state) }
            }
        }
    }

    /** Asks the reached servers again for what's in progress, e.g. after the player closes. */
    fun refreshContinuing() {
        for ((id, server) in _connected.value) scope.launch {
            runCatching { server.continueWatching() }.onSuccess { c -> _continuing.update { it + (id to c) } }
        }
    }

    /**
     * PlexVerse's Continue watching: items from libraries with a category, kids' ones only with [kids]
     * (when kids are kept separate), merged across servers.
     */
    fun continuingRow(kids: Boolean, lists: Map<String, List<Item>>, continuing: Map<String, List<Item>>): List<Continuing> {
        val setup = prefs.setup
        val inProgress = continuing.flatMap { (id, items) ->
            items.filter { item ->
                val c = item.librarySectionId?.let { setup.libraries[VerseSetup.key(id, it)] }
                c != null && c.category != Category.NONE && (setup.kidsSeparate && c.kids) == kids
            }.map { Source(id, it) }
        }
        val order = setup.ordered(continuing.keys)
        return Verse.continuing(inProgress, order, entries(Category.MOVIES, lists, kids))
    }

    private suspend fun fetch(id: String, keys: List<String>): ServerState {
        val server = plex.server(id) ?: return ServerState.Failed("not reachable")
        _connected.update { it + (id to server) }
        // Extra: if it fails, the lists still count.
        scope.launch { runCatching { server.continueWatching() }.onSuccess { c -> _continuing.update { it + (id to c) } } }
        val fresh = coroutineScope {
            keys.map { k -> async { k to server.libraryJson(k.substringAfter('/'), episodes = false) } }.awaitAll()
        }
        val parsed = withContext(Dispatchers.Default) { fresh.associate { (k, json) -> k to PlexClient.parseItems(json) } }
        _lists.update { it + parsed }
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            for ((k, json) in fresh) runCatching {
                val tmp = File(file(k).path + ".part")
                tmp.writeText(json)
                tmp.renameTo(file(k))
            }
        }
        return ServerState.Done(parsed.values.sumOf { it.size })
    }

    /**
     * One category's titles merged across servers. With kids kept separate, kids' libraries are left
     * out, or with [kids] they're the only ones.
     */
    fun entries(category: Category, lists: Map<String, List<Item>>, kids: Boolean = false): List<VerseEntry> {
        val setup = prefs.setup
        val keys = setup.libraries.filter { (_, c) -> c.category == category && (setup.kidsSeparate && c.kids) == kids }.keys
        val sources = keys.flatMap { k -> lists[k].orEmpty().map { Source(k.substringBefore('/'), it) } }
        return Verse.merge(sources, setup.ordered(sources.map { it.machineId }.distinct()))
    }

    /** Kids' movies and series together, A–Z. Merged separately, since a film and a show never match. */
    fun kidsEntries(lists: Map<String, List<Item>>): List<VerseEntry> =
        (entries(Category.MOVIES, lists, kids = true) + entries(Category.SERIES, lists, kids = true)).sortedBy { Verse.sortKey(it.item) }

    companion object {
        @Volatile private var instance: VerseStore? = null

        fun get(context: Context, plex: PlexClient) = instance ?: synchronized(this) {
            instance ?: VerseStore(context.applicationContext, plex).also { instance = it }
        }
    }
}
