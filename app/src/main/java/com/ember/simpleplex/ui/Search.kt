@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.ember.simpleplex.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.ember.simpleplex.plex.Item
import com.ember.simpleplex.plex.Library
import com.ember.simpleplex.plex.PlexClient
import com.ember.simpleplex.ui.SearchMatch.Text as Searchable
import android.content.Context
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Episodes listed per show at most; a broader word than that needs narrowing down anyway. */
private const val MAX_EPISODES = 100

/** One library's content, prepared for matching. Episodes (sorted by show, season, episode) only for TV. */
private class LibraryIndex(
    val library: Library,
    val items: List<Pair<Item, Searchable>>,
    val episodes: List<Triple<Item, Searchable, Searchable>>,
)

/** One library's matches: titles, then matching episodes per show. */
private class Found(val library: Library, val items: List<Item>, val shows: List<Pair<String, List<Item>>>)

/** A library's lists as Plex's raw JSON: titles, and for TV every episode with its description. */
private class LibraryLists(val library: Library, val titles: String, val episodes: String?)

/**
 * The copy of the searched libraries kept on the TV, so Search can match straight away and refresh
 * quietly. One pair of files per library, named by the server too, so another server never mixes in.
 */
private class SearchCache(context: Context, plex: PlexClient) {
    private val dir = File(context.cacheDir, "search")
    private val server = (plex.serverUri ?: "").hashCode().toUInt().toString(16)

    private fun file(lib: Library, kind: String) = File(dir, "$server-${lib.key}-$kind.json")

    /** Null if any library has no saved copy yet. */
    fun read(libraries: List<Library>): List<LibraryLists>? = libraries.map { lib ->
        val titles = file(lib, "titles").takeIf { it.exists() }?.readText() ?: return null
        val episodes = if (lib.kind == Library.Kind.SHOWS) file(lib, "episodes").takeIf { it.exists() }?.readText() ?: return null else null
        LibraryLists(lib, titles, episodes)
    }

    fun write(lists: List<LibraryLists>) {
        dir.mkdirs()
        for (l in lists) {
            save(file(l.library, "titles"), l.titles)
            l.episodes?.let { save(file(l.library, "episodes"), it) }
        }
    }

    /** Via a temporary file, so a copy is never half-written. */
    private fun save(f: File, text: String) {
        val tmp = File(f.path + ".part")
        tmp.writeText(text)
        tmp.renameTo(f)
    }
}

private suspend fun fetch(plex: PlexClient, libraries: List<Library>): List<LibraryLists> = coroutineScope {
    libraries.map { lib ->
        async {
            val episodes = if (lib.kind == Library.Kind.SHOWS) async { plex.libraryJson(lib.key, episodes = true) } else null
            LibraryLists(lib, plex.libraryJson(lib.key, episodes = false), episodes?.await())
        }
    }.awaitAll()
}

private fun index(lists: List<LibraryLists>): List<LibraryIndex> = lists.map { l ->
    val episodes = l.episodes?.let(PlexClient::parseItems).orEmpty()
        .sortedWith(compareBy({ it.grandparentTitle }, { it.parentIndex ?: 0 }, { it.index ?: 0 }))
        .map { Triple(it, Searchable(it.title + "\n" + (it.summary ?: "")), Searchable(it.grandparentTitle ?: "")) }
    LibraryIndex(l.library, PlexClient.parseItems(l.titles).map { it to Searchable(it.title) }, episodes)
}

/**
 * Search in the libraries shown on Home (hidden ones are left out). Movies, shows and other videos
 * match by title; episodes also by their description, so "bolivia" finds the Top Gear special. Matching
 * runs on the TV against a saved copy of the libraries, which is refreshed in the background on opening.
 */
@Composable
fun SearchScreen(plex: PlexClient, nav: Nav) {
    val context = LocalContext.current
    val hidden = remember { LibraryPrefs(context).hidden }
    val cache = remember { SearchCache(context, plex) }
    var loaded by remember { mutableStateOf<Result<List<LibraryIndex>>?>(null) }
    LaunchedEffect(Unit) {
        // The saved copy first, if there is one, then the current lists from Plex.
        val libraries = try {
            plex.libraries().filter { it.key !in hidden }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loaded = Result.failure(e)
            return@LaunchedEffect
        }
        withContext(Dispatchers.Default) { runCatching { cache.read(libraries)?.let(::index) }.getOrNull() }
            ?.let { loaded = Result.success(it) }
        try {
            val fresh = fetch(plex, libraries)
            val indexed = withContext(Dispatchers.Default) { index(fresh) }
            loaded = Result.success(indexed)
            withContext(Dispatchers.IO) { runCatching { cache.write(fresh) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline: keep searching the saved copy if there is one.
            if (loaded == null) loaded = Result.failure(e)
        }
    }

    var query by rememberSaveable { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<Item?>(null) }
    val field = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var fieldFocused by remember { mutableStateOf(false) }

    val terms = remember(query) { SearchMatch.terms(query) }
    val searching = query.trim().length >= 2
    val found = remember(terms, loaded) {
        val libraries = loaded?.getOrNull() ?: return@remember null
        libraries.mapNotNull { lib ->
            val items = lib.items.filter { (_, text) -> SearchMatch.matches(terms, text) }.map { it.first }
            val shows = lib.episodes
                .filter { (_, own, show) -> SearchMatch.matches(terms, own, show) }
                .map { it.first }
                .groupBy { it.grandparentRatingKey }
                .map { (_, eps) -> (eps.first().grandparentTitle ?: "") to eps.take(MAX_EPISODES) }
            if (items.isEmpty() && shows.isEmpty()) null else Found(lib.library, items, shows)
        }
    }

    LaunchedEffect(Unit) {
        if (query.isEmpty()) {
            withFrameNanos { }
            runCatching { field.requestFocus() }
            keyboard?.show()
        }
    }

    // 12 columns: posters take 2 (six to a row), video thumbnails 3 (four to a row).
    LazyVerticalGrid(
        columns = GridCells.Fixed(12),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "field") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .background(Palette.Tile, RoundedCornerShape(12.dp))
                    .then(if (fieldFocused) Modifier.border(3.dp, Palette.Ink, RoundedCornerShape(12.dp)) else Modifier)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Search, contentDescription = null, tint = Palette.Secondary, modifier = Modifier.size(20.dp))
                Box(Modifier.padding(start = 12.dp).weight(1f)) {
                    if (query.isEmpty()) {
                        Text("Titles, or words from an episode's description", fontSize = 16.sp, color = Palette.Secondary, maxLines = 1)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Palette.Ink),
                        cursorBrush = SolidColor(Palette.Ink),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        // Search on the keyboard: close it and go down to the results.
                        keyboardActions = KeyboardActions(onSearch = {
                            keyboard?.hide()
                            focus.moveFocus(FocusDirection.Down)
                        }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(field)
                            .onFocusChanged { fieldFocused = it.isFocused },
                    )
                }
            }
        }

        val failure = loaded?.exceptionOrNull()
        when {
            failure != null -> hint("Couldn't load your libraries.\n${failure.message}")
            !searching -> {}
            found == null -> hint("Loading your libraries…")
            found.isEmpty() -> hint("Nothing found for “${query.trim()}”.")
            else -> for (f in found) results(plex, nav, f) { menuFor = it }
        }
    }
    menuFor?.let { ItemMenu(plex, it, nav) { menuFor = null } }
}

private fun LazyGridScope.hint(text: String) = item(span = { GridItemSpan(maxLineSpan) }) {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, fontSize = 15.sp, color = Palette.Secondary)
    }
}

/** One library's matches: its titles, then (for TV) the matching episodes. */
private fun LazyGridScope.results(plex: PlexClient, nav: Nav, f: Found, onMenu: (Item) -> Unit) {
    val lib = f.library
    item(span = { GridItemSpan(maxLineSpan) }, key = "h" + lib.key) {
        SectionTitle(lib.title, Modifier.padding(top = 8.dp))
    }
    val wide = lib.kind == Library.Kind.OTHER
    items(f.items, key = { lib.key + "/" + it.ratingKey }, span = { GridItemSpan(if (wide) 3 else 2) }) { item ->
        val open = { if (item.type == "show") nav.push(Screen.Show(item)) else nav.play(listOf(item.ratingKey)) }
        if (wide) {
            WideCard(
                imageUrl = plex.image(item.thumb, 396, 223),
                title = item.title,
                subtitle = null,
                badge = if (item.duration > 0) formatDuration(item.duration) else null,
                progress = item.progress,
                watched = item.watched,
                onClick = open,
                onLongClick = { onMenu(item) },
            )
        } else {
            PosterCard(
                title = item.title,
                imageUrl = plex.image(item.thumb, 248, 372),
                progress = item.progress,
                watched = item.watched,
                onClick = open,
                onLongClick = { onMenu(item) },
            )
        }
    }
    // One row per show, its matching episodes in season order, with the same cards as the show's page.
    // Playing an episode continues with the rest of its show.
    for ((show, episodes) in f.shows) {
        item(span = { GridItemSpan(maxLineSpan) }, key = lib.key + "/s" + episodes.first().grandparentRatingKey) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SectionTitle("$show · ${episodes.size} episode${if (episodes.size == 1) "" else "s"}")
                LazyRow(
                    contentPadding = PaddingValues(vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    rowItems(episodes, key = { it.ratingKey }) { ep ->
                        WideCard(
                            imageUrl = plex.image(ep.thumb, 352, 198),
                            title = ep.title,
                            subtitle = ep.episodeLabel,
                            subtitleFirst = true,
                            progress = ep.progress,
                            watched = ep.watched,
                            onClick = { nav.play(listOf(ep.ratingKey)) },
                            onLongClick = { onMenu(ep) },
                            width = 176.dp,
                        )
                    }
                }
            }
        }
    }
}
