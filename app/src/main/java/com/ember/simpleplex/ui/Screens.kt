@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.ember.simpleplex.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.launch
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.ember.simpleplex.plex.Episodes
import com.ember.simpleplex.plex.FolderEntry
import com.ember.simpleplex.plex.Item
import com.ember.simpleplex.plex.Library
import com.ember.simpleplex.plex.PlexClient
import androidx.compose.runtime.produceState
import com.ember.simpleplex.update.Updates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

sealed interface Screen {
    data object Home : Screen
    data object Search : Screen
    data object Settings : Screen
    data class Grid(val library: Library) : Screen
    data class Show(val show: Item) : Screen
    data class Folder(val library: Library, val key: String?, val path: List<String>) : Screen
}

// --- Sign in ---

@Composable
fun SignInScreen(plex: PlexClient, onDone: () -> Unit) {
    var code by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        status = null
        try {
            val pin = plex.createPin()
            code = pin.code
            while (true) {
                delay(2000)
                val accountToken = plex.pinToken(pin.id) ?: continue
                code = null
                status = "Finding your server…"
                plex.connect(accountToken)
                onDone()
                break
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            code = null
            status = (e.message ?: "Something went wrong") + "\nPress OK to try again."
        }
    }

    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
    ) {
        Text("Connect to your Plex server", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
        val c = code
        if (c != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("On your phone or computer, go to", fontSize = 13.sp, color = Palette.Secondary)
                Text("plex.tv/link", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
                Text("and enter this code", fontSize = 13.sp, color = Palette.Secondary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                c.forEach { ch ->
                    Box(
                        Modifier.size(60.dp, 75.dp).background(Palette.Tile, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Text(ch.toString(), fontSize = 38.sp, fontWeight = FontWeight.Bold, color = Palette.Ink) }
                }
            }
            Text("This screen closes by itself once you've entered the code.", fontSize = 11.sp, color = Palette.Secondary)
        }
        status?.let { s ->
            Text(s, fontSize = 13.sp, color = Palette.Secondary)
            if (s.endsWith("try again.")) PillButton("Try again", { attempt++ })
        }
    }
}

// --- Home ---

@Composable
fun HomeScreen(plex: PlexClient, nav: Nav) {
    val refresh = LocalRefresh.current
    val libs by load(refresh) { plex.libraries() }
    val cont by load(refresh) { plex.continueWatching() }
    val memory = rememberFocusMemory()
    var menuFor by remember { mutableStateOf<Item?>(null) }

    val context = LocalContext.current
    val update by produceState<Updates.Release?>(null) { value = Updates.newerCached() }
    // Read again on every visit: Settings changes it.
    val hidden = remember(refresh) { LibraryPrefs(context).hidden }

    val allLibraries = libs?.getOrElse { return ErrorMessage("Couldn't reach your Plex server.\n${it.message}") } ?: return
    val libraries = allLibraries.filter { it.key !in hidden }
    // Hiding a library also hides what you were watching in it.
    val continuing = (cont?.getOrNull() ?: if (cont == null) return else emptyList())
        .filter { it.librarySectionId == null || it.librarySectionId !in hidden }

    // Scrolls if the rows don't all fit, following focus.
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (continuing.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SectionTitle("Continue watching", Modifier.padding(horizontal = 48.dp))
                val first = remember { FocusRequester() }
                val rowState = rememberLazyListState()
                LazyRow(
                    state = rowState,
                    modifier = Modifier.entersOnFirst(rowState, first),
                    contentPadding = PaddingValues(horizontal = 48.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    items(continuing, key = { it.ratingKey }) { item ->
                        WideCard(
                            imageUrl = plex.image(if (item.isEpisode) item.thumb else item.art ?: item.thumb, 480, 270),
                            title = if (item.isEpisode) item.grandparentTitle ?: item.title else item.title,
                            subtitle = if (item.isEpisode) "${item.episodeLabel} · ${item.title}" else if (item.viewOffset > 0) timeLeft(item) else null,
                            progress = item.progress,
                            watched = false,
                            // The one place, with the show page's Play, that continues where it was left.
                            onClick = { nav.play(listOf(item.ratingKey), resume = true) },
                            onLongClick = { menuFor = item },
                            width = 200.dp,
                            modifier = Modifier
                                .then(if (item == continuing.first()) Modifier.focusRequester(first) else Modifier)
                                .remembersFocus(memory, "c" + item.ratingKey, default = item == continuing.first()),
                        )
                    }
                }
            }
        }
        SearchBox(
            onOpen = { nav.push(Screen.Search) },
            modifier = Modifier.padding(horizontal = 48.dp).remembersFocus(memory, "search"),
        )
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionTitle("Libraries", Modifier.padding(horizontal = 48.dp))
            val first = remember { FocusRequester() }
            val rowState = rememberLazyListState()
            LazyRow(
                state = rowState,
                modifier = Modifier.entersOnFirst(rowState, first),
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                itemsIndexed(libraries, key = { _, lib -> lib.key }) { i, lib ->
                    IconTile(
                        icon = when (lib.kind) {
                            Library.Kind.MOVIES -> Icons.Film
                            Library.Kind.SHOWS -> Icons.Tv
                            Library.Kind.OTHER -> Icons.Folder
                        },
                        label = lib.title,
                        onClick = {
                            nav.push(if (lib.kind == Library.Kind.OTHER) Screen.Folder(lib, null, emptyList()) else Screen.Grid(lib))
                        },
                        modifier = Modifier.size(180.dp, 108.dp)
                            .then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                            .remembersFocus(memory, "l" + lib.key, default = continuing.isEmpty() && i == 0),
                    )
                }
                update?.let { release ->
                    item(key = "update") {
                        IconTile(
                            icon = Icons.Next,
                            label = "Update ${release.version}",
                            onClick = { nav.push(Screen.Settings) },
                            modifier = Modifier.size(150.dp, 108.dp)
                                .then(if (libraries.isEmpty()) Modifier.focusRequester(first) else Modifier)
                                .remembersFocus(memory, "update"),
                        )
                    }
                }
                item(key = "settings") {
                    IconTile(
                        icon = Icons.Settings,
                        label = "Settings",
                        onClick = { nav.push(Screen.Settings) },
                        modifier = Modifier.size(108.dp, 108.dp)
                            .then(if (libraries.isEmpty() && update == null) Modifier.focusRequester(first) else Modifier)
                            .remembersFocus(memory, "settings", default = allLibraries.isEmpty()),
                    )
                }
            }
        }
    }
    menuFor?.let { ItemMenu(plex, it, nav, continueWatching = true) { menuFor = null } }
}

// --- Movies / TV shows grid ---

@Composable
fun GridScreen(plex: PlexClient, library: Library, nav: Nav) {
    val refresh = LocalRefresh.current
    val result by load(library.key, refresh) { plex.libraryItems(library.key) }
    val memory = rememberFocusMemory()
    var menuFor by remember { mutableStateOf<Item?>(null) }
    val prefs = LibraryPrefs(LocalContext.current)
    var sort by remember { mutableStateOf(prefs.sort(library.key)) }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    // A letter picked in the A–Z column: its first poster takes focus once it's on screen.
    var jumpTo by remember { mutableStateOf<String?>(null) }
    val all = result?.getOrElse { return ErrorMessage("Couldn't load ${library.title}.\n${it.message}") } ?: return
    if (all.isEmpty()) return Message("${library.title} is empty.")
    val items = remember(all, sort) { sort.apply(all) }
    val letters = remember(items, sort) { if (sort == GridSort.TITLE) GridSort.letters(items) else emptyList() }

    Row(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(6),
            state = grid,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 48.dp, end = if (letters.isEmpty()) 48.dp else 20.dp, top = 40.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    Modifier.padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.weight(1f)) { ScreenTitle(library.title) }
                    GridSort.entries.forEach { option ->
                        PillButton(option.label, {
                            sort = option
                            prefs.setSort(library.key, option)
                            scope.launch { grid.scrollToItem(0) }
                        }, selected = option == sort)
                    }
                }
            }
            items(items, key = { it.ratingKey }) { item ->
                val jump = remember { FocusRequester() }
                if (jumpTo == item.ratingKey) {
                    LaunchedEffect(Unit) {
                        withFrameNanos { }
                        runCatching { jump.requestFocus() }
                        jumpTo = null
                    }
                }
                PosterCard(
                    title = item.title,
                    imageUrl = plex.image(item.thumb, 248, 372),
                    progress = item.progress,
                    watched = item.watched,
                    onClick = {
                        if (library.kind == Library.Kind.SHOWS) nav.push(Screen.Show(item)) else nav.play(listOf(item.ratingKey))
                    },
                    onLongClick = { menuFor = item },
                    modifier = Modifier.focusRequester(jump).remembersFocus(memory, item.ratingKey, default = item == items.first()),
                )
            }
        }
        if (letters.isNotEmpty()) {
            LetterColumn(letters, Modifier.padding(top = 40.dp, bottom = 24.dp, end = 16.dp)) { first ->
                jumpTo = items[first].ratingKey
                // +1 for the title row.
                scope.launch { grid.scrollToItem(first + 1) }
            }
        }
    }
    menuFor?.let { ItemMenu(plex, it, nav) { menuFor = null } }
}

/** The letters a sorted grid has, top to bottom; OK jumps to the first title under one. */
@Composable
private fun LetterColumn(letters: List<Pair<Char, Int>>, modifier: Modifier, onJump: (Int) -> Unit) {
    Column(modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceEvenly) {
        letters.forEach { (letter, first) ->
            Surface(
                onClick = { onJump(first) },
                modifier = Modifier.size(22.dp, 17.dp),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp)),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.15f),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    contentColor = Palette.Secondary,
                    focusedContainerColor = Palette.Ink,
                    focusedContentColor = Color.White,
                ),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(letter.toString(), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// --- One show: Play / Shuffle, its favourite episodes, then one row per season ---

@Composable
fun ShowScreen(plex: PlexClient, show: Item, nav: Nav) {
    val refresh = LocalRefresh.current
    val result by load(show.ratingKey, refresh) {
        val episodes = Episodes.ordered(plex.episodes(show.ratingKey))
        val section = episodes.firstNotNullOfOrNull { it.librarySectionId } ?: show.librarySectionId
        // Favourites are extra: if Plex can't list them, the show still opens.
        val labelled = section?.let { runCatching { plex.labelled(it, Episodes.FAVORITE, type = 4) }.getOrNull() }.orEmpty().map { it.ratingKey }.toSet()
        episodes to Episodes.favorites(episodes, labelled)
    }
    val memory = rememberFocusMemory()
    var menuFor by remember { mutableStateOf<Item?>(null) }
    val (episodes, favorites) = result?.getOrElse { return ErrorMessage("Couldn't load ${show.title}.\n${it.message}") } ?: return
    if (episodes.isEmpty()) return Message("${show.title} has no episodes.")

    val next = Episodes.nextUp(episodes)
    val seasons = episodes.groupBy { it.parentIndex ?: 0 }.entries.toList()
    val playFrom = { i: Int, resume: Boolean -> nav.play(episodes.drop(i).map { it.ratingKey }, resume = resume) }

    LazyColumn(contentPadding = PaddingValues(vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(key = "header") {
            Row(
                Modifier.padding(horizontal = 48.dp).padding(bottom = 14.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                AsyncImage(
                    model = plex.image(show.thumb, 160, 240),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(80.dp, 120.dp).clip(RoundedCornerShape(7.dp)).background(Palette.Placeholder),
                )
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(show.title, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PillButton(
                            label = "Play ${episodes[next].episodeLabel}",
                            icon = Icons.Play,
                            onClick = { playFrom(next, true) },
                            modifier = Modifier.remembersFocus(memory, "play", default = true),
                        )
                        PillButton(
                            label = "Shuffle",
                            icon = Icons.Shuffle,
                            onClick = { nav.play(episodes.shuffled().map { it.ratingKey }, shuffle = true) },
                            modifier = Modifier.remembersFocus(memory, "shuffle"),
                        )
                        if (favorites.isNotEmpty()) {
                            PillButton(
                                label = "Shuffle favorites",
                                icon = Icons.Shuffle,
                                onClick = { nav.play(favorites.shuffled().map { it.ratingKey }, shuffle = true) },
                                modifier = Modifier.remembersFocus(memory, "shuffleFavorites"),
                            )
                        }
                    }
                }
            }
        }
        // Played from here, favourites play on to the next favourite.
        if (favorites.isNotEmpty()) {
            item(key = "favorites") {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SectionTitle("Favorites", Modifier.padding(horizontal = 48.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        itemsIndexed(favorites, key = { _, ep -> ep.ratingKey }) { i, ep ->
                            WideCard(
                                imageUrl = plex.image(ep.thumb, 352, 198),
                                title = ep.title,
                                subtitle = ep.episodeLabel,
                                subtitleFirst = true,
                                progress = ep.progress,
                                watched = ep.watched,
                                onClick = { nav.play(favorites.map { it.ratingKey }, startAt = i, keepQueue = true) },
                                onLongClick = { menuFor = ep },
                                width = 176.dp,
                                modifier = Modifier.remembersFocus(memory, "fav" + ep.ratingKey),
                            )
                        }
                    }
                }
            }
        }
        seasons.forEachIndexed { si, (number, eps) ->
            item(key = "season$number") {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (si > 0 || favorites.isNotEmpty()) {
                        Box(Modifier.padding(horizontal = 48.dp).padding(bottom = 16.dp).fillMaxWidth().height(1.dp).background(Palette.Divider))
                    }
                    SectionTitle(if (number == 0) "Specials" else "Season $number", Modifier.padding(horizontal = 48.dp))
                    // Moving up or down into a season lands on its first episode, not the one below the last.
                    val firstEpisode = remember { FocusRequester() }
                    val rowState = rememberLazyListState()
                    LazyRow(
                        state = rowState,
                        modifier = Modifier.entersOnFirst(rowState, firstEpisode),
                        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        items(eps, key = { it.ratingKey }) { ep ->
                            WideCard(
                                imageUrl = plex.image(ep.thumb, 352, 198),
                                title = ep.title,
                                subtitle = listOfNotNull("Episode ${ep.index ?: ""}", ep.airDate?.let(ItemInfo::date)).joinToString(" · "),
                                subtitleFirst = true,
                                progress = ep.progress,
                                watched = ep.watched,
                                onClick = { playFrom(episodes.indexOf(ep), false) },
                                onLongClick = { menuFor = ep },
                                width = 176.dp,
                                modifier = Modifier
                                    .then(if (ep == eps.first()) Modifier.focusRequester(firstEpisode) else Modifier)
                                    .remembersFocus(memory, ep.ratingKey),
                            )
                        }
                    }
                }
            }
        }
    }
    menuFor?.let { m -> ItemMenu(plex, m, nav, favorite = favorites.any { it.ratingKey == m.ratingKey }) { menuFor = null } }
}

// --- Other videos, by folder ---

@Composable
fun FolderScreen(plex: PlexClient, library: Library, key: String?, path: List<String>, nav: Nav) {
    val refresh = LocalRefresh.current
    val result by load(key, refresh) {
        // The library's favourites, A–Z: a row on the library's top level, and known in every folder for
        // the hold-OK menu. They're extra: if Plex can't list them, the folder still opens.
        val favorites = runCatching { plex.labelled(library.key, Episodes.FAVORITE, type = 1) }.getOrNull().orEmpty()
        plex.folder(library.key, key) to favorites
    }
    val memory = rememberFocusMemory()
    var menuFor by remember { mutableStateOf<Item?>(null) }
    val (entries, favorites) = result?.getOrElse { return ErrorMessage("Couldn't load this folder.\n${it.message}") } ?: return
    if (entries.isEmpty()) return Message("This folder is empty.")

    val folders = entries.filterIsInstance<FolderEntry.Folder>()
    val videos = entries.filterIsInstance<FolderEntry.Video>().map { it.item }
    val favoritesRow = if (key == null) favorites else emptyList()
    val firstKey = favoritesRow.firstOrNull()?.let { "fav" + it.ratingKey } ?: folders.firstOrNull()?.key ?: videos.first().ratingKey

    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(library.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
                path.forEach {
                    Text("›", fontSize = 15.sp, color = Palette.Secondary)
                    Text(it, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Secondary)
                }
            }
        }
        // Played from here, favourites play on to the next favourite.
        if (favoritesRow.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "favorites") {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SectionTitle("Favorites")
                    LazyRow(
                        // Bleeds to the screen edges, so cards scroll past the grid's margins.
                        modifier = Modifier.layout { measurable, constraints ->
                            val bleed = 48.dp.roundToPx()
                            val p = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + 2 * bleed))
                            layout(constraints.maxWidth, p.height) { p.place(-bleed, 0) }
                        },
                        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        itemsIndexed(favoritesRow, key = { _, v -> v.ratingKey }) { i, v ->
                            WideCard(
                                imageUrl = plex.image(v.thumb, 396, 223),
                                title = v.title,
                                subtitle = null,
                                badge = if (v.duration > 0) formatDuration(v.duration) else null,
                                progress = v.progress,
                                watched = v.watched,
                                onClick = { nav.play(favoritesRow.map { it.ratingKey }, startAt = i, keepQueue = true) },
                                onLongClick = { menuFor = v },
                                width = 198.dp,
                                modifier = Modifier.remembersFocus(memory, "fav" + v.ratingKey, default = "fav" + v.ratingKey == firstKey),
                            )
                        }
                    }
                }
            }
        }
        items(folders, key = { "f" + it.key }) { f ->
            IconTile(
                icon = Icons.Folder,
                label = f.title,
                horizontal = true,
                onClick = { nav.push(Screen.Folder(library, f.key, path + f.title)) },
                modifier = Modifier.fillMaxWidth().height(60.dp).remembersFocus(memory, f.key, default = f.key == firstKey),
            )
        }
        items(videos, key = { "v" + it.ratingKey }) { v ->
            WideCard(
                imageUrl = plex.image(v.thumb, 396, 223),
                title = v.title,
                subtitle = null,
                badge = if (v.duration > 0) formatDuration(v.duration) else null,
                progress = v.progress,
                watched = v.watched,
                onClick = { nav.play(videos.drop(videos.indexOf(v)).map { it.ratingKey }) },
                onLongClick = { menuFor = v },
                modifier = Modifier.remembersFocus(memory, v.ratingKey, default = v.ratingKey == firstKey),
            )
        }
    }
    menuFor?.let { m -> ItemMenu(plex, m, nav, favorite = favorites.any { it.ratingKey == m.ratingKey }) { menuFor = null } }
}
