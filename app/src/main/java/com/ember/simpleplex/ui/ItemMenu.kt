@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.ember.simpleplex.ui

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.ember.simpleplex.player.Describe
import com.ember.simpleplex.player.PlexTracks
import com.ember.simpleplex.plex.Episodes
import com.ember.simpleplex.plex.Item
import com.ember.simpleplex.plex.PlexClient
import com.ember.simpleplex.plex.PlexServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// --- Hold-OK menu ---

/**
 * [favorite]: whether the item is a favourite, or null where favourites aren't offered.
 * [continueWatching]: opened from a Continue watching row, where OK resumes.
 * [copies]: the item on every server that has it; watched, favourite and Continue watching are changed
 * on each of them. [playFrom] lists them so one can be picked by hand.
 */
@Composable
fun ItemMenu(
    plex: PlexServer,
    item: Item,
    nav: Nav,
    favorite: Boolean? = null,
    continueWatching: Boolean = false,
    copies: List<Copy> = listOf(Copy(plex, item)),
    playFrom: Boolean = false,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bump = LocalRefreshBump.current
    val first = remember { FocusRequester() }
    var failed by remember { mutableStateOf<String?>(null) }
    var showInfo by remember { mutableStateOf(false) }

    /** Runs a change on the server; stays open with the reason if it failed, so it doesn't look like success. */
    fun change(what: String, block: suspend (Copy) -> Unit) {
        scope.launch {
            try {
                // In its own scope, so a server that fails is caught below instead of failing the screen's scope.
                coroutineScope { copies.map { c -> async { block(c) } }.awaitAll() }
                bump()
                onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = "Couldn't $what. ${e.message}"
            }
        }
    }

    if (showInfo) return InfoPopup(plex, item, onDismiss = onDismiss)

    // The menu opens while OK is still held: the remote keeps repeating OK and then releases it, which
    // would press the menu's first button and close it. OK only counts again after that release.
    var armed by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        // Frosted: the screen behind blurs (Android 12 and up, where the TV allows it; just dimmed before).
        val view = LocalView.current
        val blurPx = with(LocalDensity.current) { 20.dp.roundToPx() }
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window
            if (Build.VERSION.SDK_INT >= 31 && window != null) {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.attributes = window.attributes.apply { blurBehindRadius = blurPx }
            }
        }
        Column(
            Modifier
                .onPreviewKeyEvent { e ->
                    if (armed || e.key !in okKeys) return@onPreviewKeyEvent false
                    if (e.type == KeyEventType.KeyUp) { armed = true; return@onPreviewKeyEvent true }
                    // A fresh press means the hold already ended somewhere else.
                    if (e.nativeKeyEvent.repeatCount == 0) { armed = true; false } else true
                }
                .width(320.dp).background(Palette.Tile, RoundedCornerShape(14.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(item.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, modifier = Modifier.padding(start = 4.dp, bottom = 7.dp))
            // OK on a card resumes only in Continue watching; the menu offers the other way to start.
            val buttons = buildList<Pair<String, () -> Unit>> {
                val entry = queueEntry(copies)
                if (item.type != "show") {
                    if (continueWatching) add("Play from beginning" to { onDismiss(); nav.play(listOf(entry)) })
                    else if (item.viewOffset > 0) add("Resume from ${formatDuration(item.viewOffset)}" to { onDismiss(); nav.play(listOf(entry), resume = true) })
                    if (playFrom && copies.size > 1) for (c in copies) {
                        add("Play from ${c.server.name.ifEmpty { "server" }}" to { onDismiss(); nav.play(listOf(c.ref), resume = continueWatching) })
                    }
                }
                add("Mark as watched" to { change("mark it watched") { c -> c.server.setWatched(c.item.ratingKey, true) } })
                add("Mark as unwatched" to { change("mark it unwatched") { c -> c.server.setWatched(c.item.ratingKey, false) } })
                if (favorite != null) {
                    add((if (favorite) "Remove from favorites" else "Add to favorites") to {
                        change("save the favourite") { c -> c.server.setLabel(c.item.ratingKey, Episodes.FAVORITE, !favorite) }
                    })
                }
                if (continueWatching) {
                    add("Remove from Continue watching" to { change("remove it") { c -> c.server.removeFromContinueWatching(c.item.ratingKey) } })
                    item.grandparentRatingKey?.takeIf { item.isEpisode }?.let { showKey ->
                        add("Go to show" to {
                            scope.launch {
                                try {
                                    val show = plex.metadata(showKey)
                                    onDismiss()
                                    // A show on another server than the home one opens there.
                                    val home = PlexClient.get(context).home?.machineId
                                    val id = plex.machineId
                                    nav.push(Screen.Show(show, if (id == null || id == home) null else listOf(Source(id, show))))
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    failed = "Couldn't open the show. ${e.message}"
                                }
                            }
                        })
                    }
                }
                if (item.type != "show") add("Info" to { showInfo = true })
            }
            buttons.forEachIndexed { i, (label, action) ->
                PillButton(label, action, Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier))
            }
            failed?.let { Text(it, fontSize = 11.sp, color = Palette.Secondary, modifier = Modifier.padding(start = 4.dp, top = 4.dp)) }
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { first.requestFocus() }
        }
    }
}

/** A short summary of a video, never a detail page: when and how long, formats, subtitles, the file. */
@Composable
private fun InfoPopup(plex: PlexServer, item: Item, onDismiss: () -> Unit) {
    // Lists don't carry streams or the file, so the full metadata is fetched; the list's item until then.
    val full by produceState(item, item.ratingKey) { value = runCatching { plex.metadata(item.ratingKey) }.getOrDefault(item) }
    val close = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(520.dp).background(Palette.Tile, RoundedCornerShape(14.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(item.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
            ItemInfo.lines(full).forEach { (label, value) ->
                Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(label, modifier = Modifier.width(64.dp), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Palette.Secondary)
                    Text(value, modifier = Modifier.weight(1f), fontSize = 11.sp, color = Palette.Ink)
                }
            }
            PillButton("Close", onDismiss, Modifier.padding(top = 6.dp).focusRequester(close))
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { close.requestFocus() }
        }
    }
}

object ItemInfo {

    /** Label and value lines for [InfoPopup]. */
    fun lines(item: Item): List<Pair<String, String>> {
        val part = item.part
        val streams = part?.streams.orEmpty()
        val audio = PlexTracks.audioStreams(part ?: return listOf("When" to whenLine(item)))
        val subtitles = PlexTracks.subtitleStreams(part)
        return listOfNotNull(
            "When" to whenLine(item),
            streams.firstOrNull { it.streamType == 1 }?.let { "Video" to Describe.video(it) },
            // Every audio track on its own line; every subtitle language, wrapping as needed.
            audio.takeIf { it.isNotEmpty() }?.let { "Audio" to it.joinToString("\n", transform = Describe::trackLabel) },
            "Subtitles" to (subtitles.map(Describe::language).distinct().joinToString(", ").ifEmpty { "None" }),
            part.file?.let { file ->
                "File" to listOfNotNull(part.size?.let(::size), file.substringAfterLast('/')).joinToString(" · ")
            },
        )
    }

    /** "1988 · 1h 26m"; for episodes "S2 · E4 · aired 12 May 2015 · 58m". */
    fun whenLine(item: Item): String = listOfNotNull(
        item.episodeLabel,
        if (item.isEpisode) item.airDate?.let(::date)?.let { "aired $it" } else item.year?.toString(),
        item.duration.takeIf { it > 0 }?.let(::length),
    ).joinToString(" · ")

    /** "12 May 2015" from Plex's "2015-05-12". */
    fun date(iso: String) = runCatching {
        LocalDate.parse(iso.take(10)).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
    }.getOrNull()

    fun length(ms: Long): String {
        val minutes = (ms + 30_000) / 60_000
        return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
    }

    fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(Locale.ROOT, bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.0f MB".format(Locale.ROOT, bytes / (1L shl 20).toDouble())
        else -> "${bytes / 1024} kB"
    }
}
