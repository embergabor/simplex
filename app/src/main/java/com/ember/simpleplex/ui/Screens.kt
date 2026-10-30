@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.ember.simpleplex.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

sealed interface Screen {
    data object Home : Screen
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
    val libs by load(Unit) { plex.libraries() }
    val cont by load(refresh) { plex.continueWatching() }
    val memory = rememberFocusMemory()
    var menuFor by remember { mutableStateOf<Item?>(null) }

    val libraries = libs?.getOrElse { return Message("Couldn't reach your Plex server.\n${it.message}") } ?: return
    val continuing = cont?.getOrNull() ?: if (cont == null) return else emptyList()

    Column(Modifier.fillMaxSize().padding(vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        if (continuing.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SectionTitle("Continue watching", Modifier.padding(horizontal = 48.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 48.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    items(continuing, key = { it.ratingKey }) { item ->
                        WideCard(
                            imageUrl = plex.image(if (item.isEpisode) item.thumb else item.art ?: item.thumb, 480, 270),
                            title = if (item.isEpisode) item.grandparentTitle ?: item.title else item.title,
                            subtitle = if (item.isEpisode) "${item.episodeLabel} · ${item.title}" else if (item.viewOffset > 0) timeLeft(item) else null,
                            progress = item.progress,
                            watched = false,
                            onClick = { nav.play(listOf(item.ratingKey)) },
                            onLongClick = { menuFor = item },
                            width = 200.dp,
                            modifier = Modifier.remembersFocus(memory, "c" + item.ratingKey, default = item == continuing.first()),
                        )
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionTitle("Libraries", Modifier.padding(horizontal = 48.dp))
            Row(Modifier.padding(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                libraries.forEachIndexed { i, lib ->
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
                            .remembersFocus(memory, "l" + lib.key, default = continuing.isEmpty() && i == 0),
                    )
                }
            }
        }
    }
    menuFor?.let { ItemMenu(plex, it, nav) { menuFor = null } }
}

// --- Movies / TV shows grid ---

@Composable
fun GridScreen(plex: PlexClient, library: Library, nav: Nav) {
    val refresh = LocalRefresh.current
    val result by load(library.key, refresh) { plex.libraryItems(library.key) }
    val memory = rememberFocusMemory()
    var menuFor by remember { mutableStateOf<Item?>(null) }
    val items = result?.getOrElse { return Message("Couldn't load ${library.title}.\n${it.message}") } ?: return
    if (items.isEmpty()) return Message("${library.title} is empty.")

    LazyVerticalGrid(
        columns = GridCells.Fixed(6),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.padding(bottom = 4.dp)) { ScreenTitle(library.title, "A–Z") }
        }
        items(items, key = { it.ratingKey }) { item ->
            PosterCard(
                title = item.title,
                imageUrl = plex.image(item.thumb, 248, 372),
                progress = item.progress,
                watched = item.watched,
                onClick = {
                    if (library.kind == Library.Kind.SHOWS) nav.push(Screen.Show(item)) else nav.play(listOf(item.ratingKey))
                },
                onLongClick = { menuFor = item },
                modifier = Modifier.remembersFocus(memory, item.ratingKey, default = item == items.first()),
            )
        }
    }
    menuFor?.let { ItemMenu(plex, it, nav) { menuFor = null } }
}

// --- One show: Play / Shuffle, then one row per season ---

@Composable
fun ShowScreen(plex: PlexClient, show: Item, nav: Nav) {
    val refresh = LocalRefresh.current
    val result by load(show.ratingKey, refresh) { Episodes.ordered(plex.episodes(show.ratingKey)) }
    val memory = rememberFocusMemory()
    var menuFor by remember { mutableStateOf<Item?>(null) }
    val episodes = result?.getOrElse { return Message("Couldn't load ${show.title}.\n${it.message}") } ?: return
    if (episodes.isEmpty()) return Message("${show.title} has no episodes.")

    val next = Episodes.nextUp(episodes)
    val seasons = episodes.groupBy { it.parentIndex ?: 0 }.entries.toList()
    val playFrom: (Int) -> Unit = { i -> nav.play(episodes.drop(i).map { it.ratingKey }) }

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
                            onClick = { playFrom(next) },
                            modifier = Modifier.remembersFocus(memory, "play", default = true),
                        )
                        PillButton(
                            label = "Shuffle",
                            icon = Icons.Shuffle,
                            onClick = { nav.play(episodes.shuffled().map { it.ratingKey }, fromStart = true, shuffle = true) },
                            modifier = Modifier.remembersFocus(memory, "shuffle"),
                        )
                    }
                }
            }
        }
        seasons.forEachIndexed { si, (number, eps) ->
            item(key = "season$number") {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (si > 0) {
                        Box(Modifier.padding(horizontal = 48.dp).padding(bottom = 16.dp).fillMaxWidth().height(1.dp).background(Palette.Divider))
                    }
                    SectionTitle(if (number == 0) "Specials" else "Season $number", Modifier.padding(horizontal = 48.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        items(eps, key = { it.ratingKey }) { ep ->
                            WideCard(
                                imageUrl = plex.image(ep.thumb, 352, 198),
                                title = ep.title,
                                subtitle = "Episode ${ep.index ?: ""}",
                                subtitleFirst = true,
                                progress = ep.progress,
                                watched = ep.watched,
                                onClick = { playFrom(episodes.indexOf(ep)) },
                                onLongClick = { menuFor = ep },
                                width = 176.dp,
                                modifier = Modifier.remembersFocus(memory, ep.ratingKey),
                            )
                        }
                    }
                }
            }
        }
    }
    menuFor?.let { ItemMenu(plex, it, nav) { menuFor = null } }
}

// --- Other videos, by folder ---

@Composable
fun FolderScreen(plex: PlexClient, library: Library, key: String?, path: List<String>, nav: Nav) {
    val refresh = LocalRefresh.current
    val result by load(key, refresh) { plex.folder(library.key, key) }
    val memory = rememberFocusMemory()
    var menuFor by remember { mutableStateOf<Item?>(null) }
    val entries = result?.getOrElse { return Message("Couldn't load this folder.\n${it.message}") } ?: return
    if (entries.isEmpty()) return Message("This folder is empty.")

    val folders = entries.filterIsInstance<FolderEntry.Folder>()
    val videos = entries.filterIsInstance<FolderEntry.Video>().map { it.item }
    val firstKey = folders.firstOrNull()?.key ?: videos.first().ratingKey

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
    menuFor?.let { ItemMenu(plex, it, nav) { menuFor = null } }
}
