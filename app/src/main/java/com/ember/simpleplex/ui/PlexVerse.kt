@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.ember.simpleplex.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import com.ember.simpleplex.plex.Library
import com.ember.simpleplex.plex.PlexClient
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The menu at the top of Home, PlexVerse and Kids: a capsule in the middle, tvOS style, with Search,
 * Home | PlexVerse | Kids, and Settings. Kids only once PlexVerse is set up with kids kept separate;
 * on Kids, no Search or Settings.
 */
@Composable
fun TopMenu(current: Screen, nav: Nav, modifier: Modifier = Modifier) {
    val setup = PlexVersePrefs(LocalContext.current).setup
    val kids = setup.isSetUp && setup.kidsSeparate
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            Modifier.background(Palette.Control.copy(alpha = 0.75f), CircleShape).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (current != Screen.Kids) MenuItem(null, Icons.Search, "Search", selected = false) { nav.push(Screen.Search) }
            MenuItem("Home", null, null, current == Screen.Home) { nav.push(Screen.Home) }
            MenuItem("PlexVerse", null, null, current == Screen.PlexVerse) { nav.push(Screen.PlexVerse) }
            if (kids) MenuItem("Kids", Icons.Smile, null, current == Screen.Kids, kids = true) { nav.push(Screen.Kids) }
            if (current != Screen.Kids) MenuItem(null, Icons.Settings, "Settings", selected = false) { nav.push(Screen.Settings) }
        }
    }
}

/** One capsule in [TopMenu]: underlined when it's the current screen, filled only when focused; Kids in its orange. */
@Composable
private fun MenuItem(label: String?, icon: ImageVector?, description: String?, selected: Boolean, kids: Boolean = false, onClick: () -> Unit) {
    // The orange reads on the dark ground; on the light one a deeper orange does.
    val kidsText = if (Palette.Ground.luminance() < 0.5f) Palette.Kids else Color(0xFF9A5B00)
    val fill = if (kids) Palette.Kids else Palette.Ink
    val onFill = if (kids) Color(0xFF2A1A00) else Palette.OnInk
    Button(
        onClick = onClick,
        modifier = Modifier.height(32.dp),
        contentPadding = PaddingValues(horizontal = if (label == null) 12.dp else 15.dp),
        shape = ButtonDefaults.shape(CircleShape),
        scale = ButtonDefaults.scale(focusedScale = 1.1f),
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (kids) kidsText else Palette.Ink,
            focusedContainerColor = fill,
            focusedContentColor = onFill,
        ),
    ) {
        val line = LocalContentColor.current
        Row(
            Modifier.drawBehind {
                if (selected) drawRoundRect(line, Offset(0f, size.height + 2.dp.toPx()), Size(size.width, 2.dp.toPx()), CornerRadius(1.dp.toPx()))
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) Icon(icon, contentDescription = description, modifier = Modifier.size(if (label == null) 15.dp else 13.dp))
            if (icon != null && label != null) Spacer(Modifier.width(6.dp))
            if (label != null) Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Every server's libraries as one per category. Shows the setup until there is one. */
@Composable
fun PlexVerseScreen(plex: PlexClient, nav: Nav) {
    val context = LocalContext.current
    // Read once on opening, so the first setup stays on screen while servers answer, until Done.
    var setUp by remember { mutableStateOf(PlexVersePrefs(context).setup.isSetUp) }
    if (!setUp) return PlexVerseSetupScreen(plex, nav, onDone = { setUp = true })

    val store = remember { VerseStore.get(context, plex) }
    LaunchedEffect(Unit) { store.refresh() }
    val states by store.servers.collectAsState()
    // The very first open waits for the servers (or for "Continue"); later ones start from the saved copy.
    var waiting by remember { mutableStateOf(!store.hasCache()) }
    val pending = states.values.count { it == ServerState.Waiting }
    if (waiting && (pending > 0 || states.isEmpty())) return PrefetchPanel(plex, states) { waiting = false }

    val memory = rememberFocusMemory()
    WithBackdrop {
        Column(Modifier.fillMaxSize().padding(vertical = 36.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            TopMenu(Screen.PlexVerse, nav)
            UpdatingBar(states)
            ContinueRow(plex, store, kids = false, nav, memory)
            Row(Modifier.padding(horizontal = 48.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                IconTile(Icons.Film, "Movies", { nav.push(Screen.VerseGrid(Category.MOVIES)) }, Modifier.size(180.dp, 108.dp).remembersFocus(memory, "movies", default = true))
                IconTile(Icons.Tv, "Series", { nav.push(Screen.VerseGrid(Category.SERIES)) }, Modifier.size(180.dp, 108.dp).remembersFocus(memory, "series"))
                IconTile(Icons.Folder, "Videos", { nav.push(Screen.VerseVideos) }, Modifier.size(180.dp, 108.dp).remembersFocus(memory, "videos"))
                IconTile(Icons.Edit, "Customize", { nav.push(Screen.PlexVerseSetup) }, Modifier.size(108.dp, 108.dp).remembersFocus(memory, "customize"))
            }
        }
    }
}

/** What's in progress on any server, newest first, like Home's row. Kids get no hold-OK menu. */
@Composable
private fun ContinueRow(plex: PlexClient, store: VerseStore, kids: Boolean, nav: Nav, memory: FocusMemory, leading: (@Composable () -> Unit)? = null) {
    LaunchedEffect(LocalRefresh.current) { store.refreshContinuing() }
    val lists by store.lists.collectAsState()
    val continuing by store.continuing.collectAsState()
    val connected by store.connected.collectAsState()
    val row = remember(lists, continuing) { store.continuingRow(kids, lists, continuing) }
    var menuFor by remember { mutableStateOf<Continuing?>(null) }
    // A title just watched comes in first; the row would otherwise stay scrolled on the old first card.
    val rowState = rememberLazyListState()
    LaunchedEffect(row.firstOrNull()?.sources?.first()?.ref) { rowState.scrollToItem(0) }
    // Back from the player, focus returns to the card the video started from (see PlayedFrom).
    val playedFrom = rememberPlayedFrom()
    val keys = listOfNotNull("leading".takeIf { leading != null }) + row.map { it.sources.first().ref }
    LaunchedEffect(keys) { playedFrom.follow(rowState, keys) }
    if (row.isEmpty() && leading == null) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (row.isNotEmpty()) SectionTitle("Continue watching", Modifier.padding(horizontal = 48.dp))
        PinnedRow {
            LazyRow(
                state = rowState,
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // Kids' Surprise me! comes first, as one of the cards.
                if (leading != null) item(key = "leading") { leading() }
                items(row, key = { it.sources.first().ref }) { c ->
                    val item = c.item
                    WideCard(
                        imageUrl = connected[c.sources.first().machineId]?.image(if (item.isEpisode) item.thumb else item.art ?: item.thumb, 400, 225),
                    backdrop = connected[c.sources.first().machineId]?.image(item.art ?: item.thumb, 640, 360),
                        title = if (item.isEpisode) item.grandparentTitle ?: item.title else item.title,
                        subtitle = if (item.isEpisode) "${item.episodeLabel} · ${item.title}" else if (item.viewOffset > 0) timeLeft(item) else null,
                        progress = item.progress,
                        watched = false,
                        onClick = { playedFrom.played(c.sources.first().ref); nav.play(listOf(c.sources.joinToString(",") { it.ref }), resume = true) },
                        onLongClick = if (kids) null else ({ menuFor = c }),
                        width = 200.dp,
                        modifier = Modifier
                            .focusRequester(playedFrom.requester(c.sources.first().ref))
                            .remembersFocus(memory, "c" + c.sources.first().ref, default = leading == null && c == row.first()),
                    )
                }
            }
        }
    }
    menuFor?.let { c ->
        val copies = c.sources.mapNotNull { s -> connected[s.machineId]?.let { Copy(it, s.item) } }
        if (copies.isNotEmpty()) ItemMenu(copies.first().server, c.item, nav, continueWatching = true, copies = copies, playFrom = true) { menuFor = null }
    }
}

/** The first fetch from every server: a bar, then a line per server. */
@Composable
private fun PrefetchPanel(plex: PlexClient, states: Map<String, ServerState>, onContinue: () -> Unit) {
    val names = remember { plex.cachedServers.associate { it.machineId to it.name } }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionTitle("Getting the lists from your servers")
        Orbits(states.values.toList(), Modifier.align(Alignment.CenterHorizontally).size(360.dp, 200.dp))
        ServersBar(states.values.count { it != ServerState.Waiting }, states.size.coerceAtLeast(1))
        for ((id, state) in states) {
            val line = when (state) {
                ServerState.Waiting -> "…"
                is ServerState.Done -> "✓ ${state.items} titles"
                is ServerState.Failed -> "✗ ${state.reason}"
            }
            Text("${names[id] ?: id}   $line", fontSize = 13.sp, color = Palette.Ink)
        }
        PillButton("Continue with what's here", onContinue, Modifier.padding(top = 10.dp).remembersFocus(rememberFocusMemory(), "continue", default = true))
    }
}

/**
 * The TV with each server circling it as a Plex chevron, a planet on one of three tilted orbits.
 * Waiting servers pulse, answered ones shine, failed ones turn grey. Planets pass behind the TV.
 */
@Composable
private fun Orbits(states: List<ServerState>, modifier: Modifier, tv: Dp = 72.dp, planet: Dp = 9.dp) {
    val spin = rememberInfiniteTransition(label = "orbits")
    val turn by spin.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "turn")
    val pulse by spin.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")
    val tvIcon = rememberVectorPainter(Icons.Tv)
    val ink = Palette.Ink
    val ring = Palette.Divider
    val failed = Palette.Secondary
    Canvas(modifier) {
        val rings = (0 until minOf(3, states.size.coerceAtLeast(1)))
        fun radii(r: Int) = Size(size.width * (0.26f + 0.11f * r), size.height * (0.17f + 0.08f * r))
        for (r in rings) {
            val (rx, ry) = radii(r)
            drawOval(ring, topLeft = center - Offset(rx, ry), size = Size(2 * rx, 2 * ry), style = Stroke(1.dp.toPx()))
        }
        // Inner orbits turn faster, like planets. Index spreads servers on the same ring apart.
        val planets = states.mapIndexed { i, state ->
            val r = i % 3
            val (rx, ry) = radii(r)
            val a = 2 * PI.toFloat() * (turn * (3 - r) + i / states.size.toFloat())
            Triple(center + Offset(cos(a) * rx, sin(a) * ry), sin(a), state)
        }
        fun planet(at: Offset, depth: Float, state: ServerState) {
            val s = planet.toPx() * (1 + depth / 3) // nearer is bigger
            val (color, alpha) = when (state) {
                ServerState.Waiting -> Palette.Plex to pulse
                is ServerState.Done -> Palette.Plex to 1f
                is ServerState.Failed -> failed to 0.6f
            }
            drawChevron(at, s, color, alpha)
        }
        planets.filter { it.second < 0 }.forEach { planet(it.first, it.second, it.third) }
        val tvSize = Size(tv.toPx(), tv.toPx())
        translate(center.x - tvSize.width / 2, center.y - tvSize.height / 2) {
            with(tvIcon) { draw(tvSize, colorFilter = ColorFilter.tint(ink)) }
        }
        planets.filter { it.second >= 0 }.forEach { planet(it.first, it.second, it.third) }
    }
}

/** "Updating · 3/5 servers" with small orbits while a refresh runs; nothing otherwise. */
@Composable
fun UpdatingBar(states: Map<String, ServerState>, modifier: Modifier = Modifier.padding(horizontal = 48.dp)) {
    val done = states.values.count { it != ServerState.Waiting }
    if (done == states.size) return
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Orbits(states.values.toList(), Modifier.size(52.dp, 26.dp), tv = 12.dp, planet = 2.5.dp)
        Text("Updating · $done/${states.size} servers", fontSize = 11.sp, color = Palette.Secondary)
    }
}

/**
 * One category's titles from every server, merged, A–Z. ×N marks a title that's on N servers.
 * Without a [category] it's the Kids grid: kids' movies and series mixed, bigger posters, series
 * marked with a TV, and no hold-OK menu.
 */
@Composable
fun VerseGridScreen(plex: PlexClient, category: Category?, nav: Nav) {
    val context = LocalContext.current
    val store = remember { VerseStore.get(context, plex) }
    LaunchedEffect(Unit) { store.refresh() }
    val states by store.servers.collectAsState()
    val lists by store.lists.collectAsState()
    val connected by store.connected.collectAsState()
    val kids = category == null
    val entries = remember(lists) { if (category == null) store.kidsEntries(lists) else store.entries(category, lists) }
    val memory = rememberFocusMemory()
    val title = when (category) {
        null -> "Kids"
        Category.SERIES -> "Series"
        else -> "Movies"
    }
    var menuFor by remember { mutableStateOf<VerseEntry?>(null) }
    var surprise by remember { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    // The poster from the first source whose server has answered.
    val poster: (VerseEntry) -> String? = { entry ->
        entry.sources.firstOrNull { it.machineId in connected }?.let { connected[it.machineId]?.image(it.item.thumb, 248, 372) }
    }
    // Before the posters: the title, and on Kids the top menu with Continue watching.
    val headers = if (kids) 2 else 1
    PreloadImages(grid, entries, connected) { i -> entries.getOrNull(i - headers)?.let(poster) }

    Backdrop(kids) {
        LazyVerticalGrid(
            state = grid,
            columns = GridCells.Fixed(if (kids) 5 else 6),
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = if (kids) 36.dp else 40.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            if (kids) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    // The grid has its own side margins, so the menu goes without the extra ones.
                    Box(Modifier.layout { m, c ->
                        val bleed = 48.dp.roundToPx()
                        val p = m.measure(c.copy(maxWidth = c.maxWidth + 2 * bleed))
                        layout(c.maxWidth, p.height) { p.place(-bleed, 0) }
                    }) {
                        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            TopMenu(Screen.Kids, nav)
                            ContinueRow(plex, store, kids = true, nav, memory, leading = if (entries.isEmpty()) null else {
                                { SurpriseButton({ surprise = true }, Modifier.remembersFocus(memory, "surprise", default = true)) }
                            })
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScreenTitle(title, "A–Z")
                    UpdatingBar(states, Modifier)
                    if (entries.isEmpty() && states.values.none { it == ServerState.Waiting }) {
                        Text("Nothing here yet.", fontSize = 13.sp, color = Palette.Secondary)
                    }
                }
            }
            items(entries, key = { it.key }) { entry ->
                PosterCard(
                    title = entry.item.title,
                    imageUrl = poster(entry),
                backdrop = entry.sources.firstOrNull { it.machineId in connected }?.let { connected[it.machineId]?.image(it.item.art ?: it.item.thumb, 640, 360) },
                    progress = entry.item.progress,
                    watched = entry.item.watched,
                    unwatched = entry.item.unwatchedEpisodes,
                    badge = if (entry.sources.size > 1) "×${entry.sources.size}" else null,
                    cornerIcon = if (kids && entry.item.type == "show") Icons.Tv else null,
                    onClick = {
                        if (entry.item.type == "show") nav.push(Screen.Show(entry.item, entry.sources))
                        else nav.play(listOf(entry.sources.joinToString(",") { it.ref }))
                    },
                    // The menu acts on the copies on servers that answered, so it needs one.
                    onLongClick = { if (!kids && entry.sources.any { it.machineId in connected }) menuFor = entry },
                    modifier = Modifier.remembersFocus(memory, entry.key, default = entry == entries.first()),
                )
            }
        }
    }
    menuFor?.let { entry ->
        val copies = entry.sources.mapNotNull { s -> connected[s.machineId]?.let { Copy(it, s.item) } }
        ItemMenu(copies.first().server, entry.item, nav, copies = copies, playFrom = true) { menuFor = null }
    }
    if (surprise) Surprise(entries, connected, nav) { surprise = false }
}

/**
 * The "other videos" libraries of every server, one tile each in server order. They aren't merged:
 * they're folders on disk, and two "Training" folders needn't hold the same videos.
 */
@Composable
fun VerseVideosScreen(plex: PlexClient, nav: Nav) {
    val context = LocalContext.current
    val store = remember { VerseStore.get(context, plex) }
    LaunchedEffect(Unit) { store.refresh() }
    val states by store.servers.collectAsState()
    val connected by store.connected.collectAsState()
    val setup = remember { PlexVersePrefs(context).setup }
    val names = remember { plex.cachedServers.associate { it.machineId to it.name } }
    val libraries = setup.ordered(setup.libraries.keys.map { it.substringBefore('/') }.distinct()).flatMap { id ->
        setup.storedFor(id).filter { it.value.category == Category.VIDEOS }.map { (key, choice) -> Triple(id, key.substringAfter('/'), choice.title) }
    }
    val memory = rememberFocusMemory()

    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ScreenTitle("Videos")
                UpdatingBar(states, Modifier)
                if (libraries.isEmpty()) Text("No libraries are set to Videos. Change that in PlexVerse → Customize.", fontSize = 13.sp, color = Palette.Secondary)
            }
        }
        items(libraries, key = { (id, section, _) -> "$id/$section" }) { (id, section, title) ->
            val server = connected[id]
            val failed = states[id] is ServerState.Failed
            IconTile(
                icon = Icons.Folder,
                label = listOfNotNull(title, names[id], "not reachable".takeIf { failed }).joinToString("\n"),
                onClick = {
                    if (server != null) nav.push(Screen.Folder(Library(section, title, Library.Kind.OTHER), null, emptyList(), id))
                },
                modifier = Modifier.height(130.dp).alpha(if (failed) 0.5f else 1f)
                    .remembersFocus(memory, "$id/$section", default = id == libraries.first().first && section == libraries.first().second),
            )
        }
    }
}

private sealed interface Reach {
    data object Waiting : Reach
    data class Done(val libraries: List<Library>) : Reach
    data object Failed : Reach
}

private fun Category.label() = when (this) {
    Category.MOVIES -> "Movies"
    Category.SERIES -> "Series"
    Category.VIDEOS -> "Videos"
    Category.NONE -> "None"
}

/**
 * Setup and Customize: the servers in the order they're tried, and which category each of their
 * libraries goes into. Every choice is saved as it's made. [onDone] adds a Done button (first setup).
 */
@Composable
fun PlexVerseSetupScreen(plex: PlexClient, nav: Nav, onDone: (() -> Unit)? = null) {
    // Installs signed in before the account token was kept need it once to see every server.
    var linked by remember { mutableStateOf(plex.accountToken != null) }
    if (!linked) return SignInScreen(plex, "Sign in once more to see every server", plex::linkAccount) { linked = true }

    val context = LocalContext.current
    val prefs = remember { PlexVersePrefs(context) }
    var setup by remember { mutableStateOf(prefs.setup) }
    val save: (VerseSetup) -> Unit = { setup = it; prefs.setup = it }
    var servers by remember { mutableStateOf(plex.cachedServers) }
    var listError by remember { mutableStateOf<String?>(null) }
    val reach = remember { mutableStateMapOf<String, Reach>() }

    // The saved list first, then plex.tv's, which also brings servers shared since.
    LaunchedEffect(Unit) {
        try {
            servers = plex.servers()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (servers.isEmpty()) listError = "Couldn't list your servers on plex.tv.\n${e.message}"
        }
    }
    LaunchedEffect(servers) {
        save(setup.copy(servers = setup.ordered(servers.map { it.machineId })))
        for (server in servers) {
            if (reach[server.machineId] is Reach.Done) continue
            reach[server.machineId] = Reach.Waiting
            launch {
                val libraries = try {
                    withTimeoutOrNull(15_000) { plex.server(server.machineId)?.libraries() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                reach[server.machineId] = libraries?.let { Reach.Done(it) } ?: Reach.Failed
                if (libraries != null) save(setup.withLibraries(server.machineId, libraries))
            }
        }
    }

    listError?.let { return ErrorMessage(it) }
    val ordered = setup.ordered(servers.map { it.machineId }).mapNotNull { id -> servers.find { it.machineId == id } }
    val move: (String, Int) -> Unit = { id, by ->
        val list = setup.servers.toMutableList()
        val from = list.indexOf(id)
        val to = from + by
        if (from >= 0 && to in list.indices) {
            list.add(to, list.removeAt(from))
            save(setup.copy(servers = list))
        }
    }
    // The server picked up by holding OK: ▲ and ▼ move it, OK or Back puts it down.
    var moving by remember { mutableStateOf<String?>(null) }
    // It's picked up while OK is still held; OK only puts it down after that press is released.
    var armed by remember { mutableStateOf(false) }
    BackHandler(enabled = moving != null) { moving = null }
    val memory = rememberFocusMemory()
    val answered = servers.count { reach[it.machineId].let { r -> r != null && r != Reach.Waiting } }
    val pad = Modifier.padding(horizontal = 48.dp)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "top") { TopMenu(Screen.PlexVerse, nav, Modifier.padding(bottom = 10.dp)) }
        item(key = "serversTitle") {
            Column(pad, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionTitle("Servers")
                Text("The first one is tried first. Hold OK on a server, then ▲ ▼ to move it and OK to put it down (or press ◀ ▶).", fontSize = 12.sp, color = Palette.Secondary)
                if (servers.isNotEmpty() && answered < servers.size) ServersBar(answered, servers.size)
            }
        }
        itemsIndexed(ordered, key = { _, s -> "s" + s.machineId }) { i, server ->
            Row(pad, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val picked = moving == server.machineId
                PillButton(
                    label = "${i + 1}. ${server.name}",
                    onClick = {},
                    onLongClick = { moving = server.machineId; armed = false },
                    icon = if (picked) Icons.Move else null,
                    modifier = Modifier.width(260.dp)
                        .remembersFocus(memory, "s" + server.machineId, default = i == 0)
                        .onPreviewKeyEvent { e ->
                            if (picked && e.key in okKeys) {
                                when {
                                    e.type == KeyEventType.KeyUp -> armed = true
                                    // A fresh press means the hold already ended.
                                    armed || e.nativeKeyEvent.repeatCount == 0 -> moving = null
                                }
                                return@onPreviewKeyEvent true
                            }
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (e.key) {
                                Key.DirectionLeft -> { move(server.machineId, -1); true }
                                Key.DirectionRight -> { move(server.machineId, 1); true }
                                Key.DirectionUp -> if (picked) { move(server.machineId, -1); true } else false
                                Key.DirectionDown -> if (picked) { move(server.machineId, 1); true } else false
                                else -> false
                            }
                        },
                )
                val state = when (reach[server.machineId]) {
                    is Reach.Done -> "reachable"
                    Reach.Failed -> "not reachable"
                    else -> "checking…"
                }
                Text("${if (server.owned) "yours" else "shared"} · $state", fontSize = 12.sp, color = Palette.Secondary)
            }
        }
        item(key = "kids") {
            Column(pad.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Kids")
                PillButton(
                    label = "Keep kids' movies and series separate",
                    onClick = { save(setup.copy(kidsSeparate = !setup.kidsSeparate)) },
                    selected = setup.kidsSeparate,
                    modifier = Modifier.remembersFocus(memory, "kidsSeparate"),
                )
            }
        }
        for (server in ordered) {
            val r = reach[server.machineId]
            item(key = "h" + server.machineId) {
                Column(pad.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SectionTitle(server.name)
                    when (r) {
                        Reach.Failed -> Text("Can't reach this server right now", fontSize = 12.sp, color = Palette.Secondary)
                        is Reach.Done -> if (r.libraries.isEmpty()) Text("No movie, TV or video libraries", fontSize = 12.sp, color = Palette.Secondary)
                        else -> Text("Checking…", fontSize = 12.sp, color = Palette.Secondary)
                    }
                }
            }
            if (r is Reach.Done) {
                items(r.libraries, key = { "l" + server.machineId + "/" + it.key }) { lib ->
                    val choice = setup.choice(server.machineId, lib)
                    LibraryRow(lib.title, choice, setup.kidsSeparate, pad) { c ->
                        save(setup.copy(libraries = setup.libraries + (VerseSetup.key(server.machineId, lib.key) to c)))
                    }
                }
            } else {
                // Saved choices of a server that isn't answering, shown but not editable.
                items(setup.storedFor(server.machineId), key = { "g" + it.key }) { (_, choice) ->
                    LibraryRow(choice.title, choice, setup.kidsSeparate, pad.alpha(0.5f), onChoose = null)
                }
            }
        }
        if (onDone != null && setup.isSetUp) {
            item(key = "done") { PillButton("Done", onDone, pad.padding(top = 20.dp)) }
        }
    }
}

/** "3 of 5 servers responded", with a bar. */
@Composable
private fun ServersBar(answered: Int, total: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$answered of $total servers responded", fontSize = 12.sp, color = Palette.Secondary)
        Box(Modifier.width(260.dp).height(4.dp).background(Palette.Control, RoundedCornerShape(2.dp))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(answered.toFloat() / total).background(Palette.Ink, RoundedCornerShape(2.dp)))
        }
    }
}

/** A library's name and its category pills; read-only (just the choice) when [onChoose] is null. */
@Composable
private fun LibraryRow(title: String, choice: LibraryChoice, kidsSeparate: Boolean, modifier: Modifier, onChoose: ((LibraryChoice) -> Unit)?) {
    val kidsApply = kidsSeparate && (choice.category == Category.MOVIES || choice.category == Category.SERIES)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.width(220.dp), fontSize = 13.sp, color = Palette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (onChoose == null) {
            Text(choice.category.label() + if (kidsApply && choice.kids) " · Kids" else "", fontSize = 12.sp, color = Palette.Secondary)
            return@Row
        }
        for (c in Category.entries) PillButton(c.label(), { onChoose(choice.copy(category = c)) }, selected = choice.category == c)
        if (kidsApply) {
            Spacer(Modifier.width(10.dp))
            PillButton("Kids", { onChoose(choice.copy(kids = !choice.kids)) }, selected = choice.kids)
        }
    }
}

/** Kids' movies and series in one grid, from every server. */
@Composable
fun KidsScreen(plex: PlexClient, nav: Nav) = VerseGridScreen(plex, null, nav)

/** [WithBackdrop] around [content] on Kids; Movies and Series go without. */
@Composable
private fun Backdrop(on: Boolean, content: @Composable () -> Unit) = if (on) WithBackdrop(content) else content()
