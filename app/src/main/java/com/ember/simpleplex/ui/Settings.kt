@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.ember.simpleplex.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil3.SingletonImageLoader
import com.ember.simpleplex.plex.PlexClient
import com.ember.simpleplex.plex.WakeOnLan
import com.ember.simpleplex.update.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Section(val title: String, val icon: ImageVector, val about: String) {
    Update("Update", Icons.Next, "A newer version is on GitHub. Android asks to confirm the install (and, the first time, to allow Simplex to install apps)."),
    Appearance("Appearance", Icons.Sun, "Light, dark, or dark between sunset and sunrise where the server is. OK switches."),
    Libraries("Libraries on Home", Icons.Folder, "Checked libraries are shown on Home; hidden ones keep their place for when they come back."),
    Server("Server", Icons.Tv, "The Plex server this TV plays from, and waking it up when it's asleep."),
    Storage("Storage", Icons.Disk, "Preview thumbnails, the search copy of your libraries and artwork, kept on the TV to load faster. OK clears them."),
    Account("Account", Icons.Account, "Signing out lets you sign in again and pick up a different server."),
}

/**
 * The sections as a list on the right, tvOS style, with a big picture of the focused one on the left.
 * OK switches or clears in place; sections with more than one control open in the list's place.
 */
@Composable
fun SettingsScreen(plex: PlexClient, nav: Nav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val memory = rememberFocusMemory()
    val prefs = remember { LibraryPrefs(context) }
    var hidden by remember { mutableStateOf(prefs.hidden) }
    val libraries by load(Unit) { requireNotNull(plex.home).libraries() }
    var cleared by remember { mutableStateOf(0) }
    val cacheSize by produceState<Long?>(null, cleared) { value = withContext(Dispatchers.IO) { Caches.size(context) } }
    val update by produceState<Updates.Release?>(null) { value = Updates.newerCached() }
    var installing by remember { mutableStateOf<String?>(null) }

    var focused by remember { mutableStateOf(Section.Appearance) }
    var open by remember { mutableStateOf<Section?>(null) }
    val back = remember { FocusRequester() }
    val inside = remember { FocusRequester() }
    BackHandler(enabled = open != null) { open = null }
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (!opened) return@LaunchedEffect // first visit: remembersFocus picks the row
        withFrameNanos { }
        runCatching { if (open != null) inside.requestFocus() else back.requestFocus() }
    }

    val libs = libraries?.getOrNull()
    fun value(section: Section): String = when (section) {
        Section.Update -> installing ?: "Install ${update?.version}"
        Section.Appearance -> when (Palette.mode) {
            ThemeMode.Light -> "Light"
            ThemeMode.Dark -> "Dark"
            ThemeMode.Auto -> "Auto · dark after sunset"
        }
        Section.Libraries -> when {
            libraries?.isFailure == true -> "Server unreachable"
            libs == null -> "…"
            else -> "${libs.count { it.key !in hidden }} of ${libs.size} shown"
        }
        Section.Server -> plex.home?.uri?.substringAfter("://")?.substringBefore(":") ?: "—"
        Section.Storage -> cacheSize?.let(ItemInfo::size) ?: "…"
        Section.Account -> "Signed in"
    }
    fun click(section: Section) {
        when (section) {
            Section.Update -> if (installing == null) update?.let { release ->
                installing = "Downloading…"
                scope.launch {
                    installing = try {
                        Updates.install(context, release)
                        "Waiting for Android…"
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        "Couldn't install: ${e.message}"
                    }
                }
            }
            Section.Appearance -> {
                // Picking Auto looks up the place again.
                val next = ThemeMode.entries[(Palette.mode.ordinal + 1) % ThemeMode.entries.size]
                if (next == ThemeMode.Auto) ThemePrefs(context).place = null
                Palette.mode = next
            }
            Section.Storage -> scope.launch {
                withContext(Dispatchers.IO) { Caches.clear(context) }
                cleared++
            }
            else -> { opened = true; open = section }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Text(
            "Settings", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Ink,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 26.dp),
        )

        val shown = open ?: focused
        Column(
            Modifier.width(435.dp).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        ) {
            Box(Modifier.size(165.dp).background(Palette.Tile, RoundedCornerShape(36.dp)), contentAlignment = Alignment.Center) {
                Icon(shown.icon, contentDescription = null, tint = Palette.Ink, modifier = Modifier.size(90.dp))
            }
            Text(
                shown.about, fontSize = 12.sp, color = Palette.Secondary, textAlign = TextAlign.Center,
                lineHeight = 17.sp, modifier = Modifier.width(255.dp),
            )
        }

        Column(
            Modifier.padding(start = 450.dp, top = 78.dp, end = 48.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when (open) {
                null -> {
                    Section.entries.filter { it != Section.Update || update != null }.forEach { section ->
                        SettingsRow(
                            section.title, value(section), section.icon, { click(section) },
                            Modifier
                                .then(if (section == focused) Modifier.focusRequester(back) else Modifier)
                                .onFocusChanged { if (it.isFocused) focused = section }
                                .remembersFocus(memory, section.name, default = section == if (update != null) Section.Update else Section.Appearance),
                        )
                    }
                    Text(
                        Build.describe(context) + " · the TV's own screensaver is used",
                        fontSize = 10.sp, color = Palette.Secondary, modifier = Modifier.padding(start = 15.dp, top = 8.dp),
                    )
                }
                Section.Libraries -> {
                    SectionTitle(Section.Libraries.title)
                    when {
                        libraries?.isFailure == true -> Note("Couldn't reach your Plex server to list the libraries.")
                        libs == null -> Note("Loading…")
                        else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            libs.forEachIndexed { i, lib ->
                                PillButton(
                                    label = lib.title,
                                    selected = lib.key !in hidden,
                                    onClick = {
                                        hidden = if (lib.key in hidden) hidden - lib.key else hidden + lib.key
                                        prefs.hidden = hidden
                                    },
                                    modifier = if (i == 0) Modifier.focusRequester(inside) else Modifier,
                                )
                            }
                        }
                    }
                }
                Section.Server -> {
                    SectionTitle(Section.Server.title)
                    Note("Connected to ${plex.home?.uri ?: "—"}.")
                    Note("The server's network (MAC) address, for the Wake server button when it's asleep. Plex doesn't report it and Android doesn't let apps look it up, so it's typed here, e.g. 20:07:11:1a:19:86 (the separators can be left out).")
                    MacField(plex, Modifier.focusRequester(inside))
                }
                Section.Account -> {
                    SectionTitle(Section.Account.title)
                    Note("Signed in to plex.tv.")
                    Row { PillButton("Sign out", { plex.signOut(); nav.signOut() }, Modifier.focusRequester(inside)) }
                }
                else -> {}
            }
        }
    }
}

/** One line of the Settings list: icon, name, the current value, and a chevron. Light, dark when focused. */
@Composable
private fun SettingsRow(label: String, value: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.width(450.dp).height(40.dp),
        contentPadding = PaddingValues(horizontal = 15.dp),
        shape = ButtonDefaults.shape(RoundedCornerShape(9.dp)),
        scale = ButtonDefaults.scale(focusedScale = 1.04f),
        colors = ButtonDefaults.colors(
            containerColor = Palette.Tile,
            contentColor = Palette.Ink,
            focusedContainerColor = Palette.Ink,
            focusedContentColor = Palette.OnInk,
        ),
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            val dim = LocalContentColor.current.copy(alpha = 0.65f)
            Text(value, fontSize = 11.sp, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("›", fontSize = 15.sp, color = dim)
        }
    }
}

/** The saved MAC address, edited with the TV keyboard (OK opens it), and a button to try it. */
@Composable
private fun MacField(plex: PlexClient, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { WakeOnLan.Prefs(context) }
    val keyboard = LocalSoftwareKeyboardController.current
    var saved by remember { mutableStateOf(prefs.mac) }
    var text by remember { mutableStateOf(saved?.let(WakeOnLan::format).orEmpty()) }
    var typing by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    fun save() {
        val mac = WakeOnLan.parseMac(text)
        status = when {
            text.isBlank() -> { prefs.mac = null; saved = null; "Cleared." }
            mac == null -> "That isn't a MAC address: 12 hex digits, like 20:07:11:1a:19:86."
            else -> { prefs.mac = mac; saved = mac; text = WakeOnLan.format(mac); "Saved." }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier
                .width(260.dp)
                .height(44.dp)
                .background(Palette.Tile, RoundedCornerShape(10.dp))
                .then(if (focused) Modifier.border(3.dp, Palette.Ink, RoundedCornerShape(10.dp)) else Modifier)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) Text("Not set", fontSize = 14.sp, color = Palette.Secondary)
            BasicTextField(
                value = text,
                onValueChange = { text = it.take(17); status = null },
                singleLine = true,
                textStyle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Palette.Ink),
                cursorBrush = SolidColor(Palette.Ink),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, showKeyboardOnFocus = false),
                keyboardActions = KeyboardActions(onDone = { save(); typing = false; keyboard?.hide() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged {
                        focused = it.isFocused
                        if (!it.isFocused && typing) { typing = false; save() }
                    }
                    // OK opens the keyboard, so moving through Settings doesn't.
                    .onPreviewKeyEvent { e ->
                        if (e.key !in okKeys || typing) return@onPreviewKeyEvent false
                        if (e.type == KeyEventType.KeyDown) { typing = true; keyboard?.show() }
                        true
                    },
            )
        }
        saved?.let { mac ->
            PillButton("Wake now", {
                scope.launch {
                    status = runCatching { WakeOnLan.send(mac, plex.home?.uri) }
                        .fold({ "Sent. A sleeping server takes a little while to come up." }, { "Couldn't send: ${it.message}" })
                }
            })
        }
    }
    status?.let { Note(it) }
}

@Composable
private fun Note(text: String) = Text(text, fontSize = 12.sp, color = Palette.Secondary)

/** What's kept in the app's cache folder: preview thumbnails, the search copy, artwork. */
object Caches {
    fun size(context: Context): Long = context.cacheDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun clear(context: Context) {
        // Artwork through Coil, which keeps an index of its files; everything else directly.
        runCatching { SingletonImageLoader.get(context).diskCache?.clear() }
        runCatching { SingletonImageLoader.get(context).memoryCache?.clear() }
        context.cacheDir.listFiles()?.filter { !it.name.startsWith("coil") }?.forEach(File::deleteRecursively)
    }
}

/** "Simplex 0.1 (3) · debug · installed 31 Oct 2026 19:40". */
object Build {
    fun describe(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val debug = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val installed = SimpleDateFormat("d MMM yyyy HH:mm", Locale.ENGLISH).format(Date(info.lastUpdateTime))
        return "Simplex ${info.versionName} (${info.longVersionCode}) · ${if (debug) "debug" else "release"} · installed $installed"
    }
}
