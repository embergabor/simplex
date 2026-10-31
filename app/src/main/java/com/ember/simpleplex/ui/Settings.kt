@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.ember.simpleplex.ui

import android.content.Context
import android.content.pm.ApplicationInfo
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

/** Libraries on Home, caches, the server, and which build this is. Reached from Home's Settings tile. */
@Composable
fun SettingsScreen(plex: PlexClient, nav: Nav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val memory = rememberFocusMemory()
    val prefs = remember { LibraryPrefs(context) }
    var hidden by remember { mutableStateOf(prefs.hidden) }
    val libraries by load(Unit) { plex.libraries() }
    var cleared by remember { mutableStateOf(0) }
    val cacheSize by produceState<Long?>(null, cleared) { value = withContext(Dispatchers.IO) { Caches.size(context) } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenTitle("Settings")

        SectionTitle("Libraries on Home", Modifier.padding(top = 10.dp))
        val libs = libraries?.getOrNull()
        when {
            libraries?.isFailure == true -> Note("Couldn't reach your Plex server to list the libraries.")
            libs == null -> Note("Loading…")
            else -> {
                Note("Checked libraries are shown on Home; hidden ones keep their place for when they come back.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    libs.forEachIndexed { i, lib ->
                        PillButton(
                            label = lib.title,
                            selected = lib.key !in hidden,
                            onClick = {
                                hidden = if (lib.key in hidden) hidden - lib.key else hidden + lib.key
                                prefs.hidden = hidden
                            },
                            modifier = Modifier.remembersFocus(memory, "lib" + lib.key, default = i == 0),
                        )
                    }
                }
            }
        }

        SectionTitle("Storage", Modifier.padding(top = 10.dp))
        Note("Preview thumbnails, the search copy of your libraries and artwork, kept on the TV to load faster.")
        PillButton(
            label = "Clear caches" + (cacheSize?.let { " · ${ItemInfo.size(it)}" } ?: ""),
            onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { Caches.clear(context) }
                    cleared++
                }
            },
            modifier = Modifier.remembersFocus(memory, "caches"),
        )

        SectionTitle("Server", Modifier.padding(top = 10.dp))
        Note("Connected to ${plex.serverUri ?: "—"}. Signing out lets you sign in again and pick up a different server.")
        PillButton("Sign out", { plex.signOut(); nav.signOut() }, Modifier.remembersFocus(memory, "signout"))

        SectionTitle("Wake server", Modifier.padding(top = 10.dp))
        Note("The server's network (MAC) address, for the Wake server button when it's asleep. Plex doesn't report it and Android doesn't let apps look it up, so it's typed here, e.g. 20:07:11:1a:19:86 (the separators can be left out).")
        MacField(plex, Modifier.remembersFocus(memory, "mac"))

        SectionTitle("About", Modifier.padding(top = 10.dp))
        Note(Build.describe(context))
        val update by produceState<Updates.Release?>(null) { value = Updates.newerCached() }
        var installing by remember { mutableStateOf<String?>(null) }
        update?.let { release ->
            Note("Version ${release.version} is available on GitHub. Android asks to confirm the install (and, the first time, to allow Simplex to install apps).")
            PillButton(
                label = installing ?: "Install ${release.version}",
                onClick = {
                    if (installing == null) {
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
                },
                modifier = Modifier.remembersFocus(memory, "update", default = true),
            )
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
                    status = runCatching { WakeOnLan.send(mac, plex.serverUri) }
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
