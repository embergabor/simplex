@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.ember.simpleplex.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.ember.simpleplex.player.Describe
import com.ember.simpleplex.player.PlexTracks
import com.ember.simpleplex.plex.Episodes
import com.ember.simpleplex.plex.Item
import com.ember.simpleplex.plex.PlexClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// --- Hold-OK menu ---

/**
 * [favorite]: whether the item is a favourite, or null where favourites aren't offered.
 * [continueWatching]: opened from Home's Continue watching row, where OK resumes.
 */
@Composable
fun ItemMenu(
    plex: PlexClient,
    item: Item,
    nav: Nav,
    favorite: Boolean? = null,
    continueWatching: Boolean = false,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val bump = LocalRefreshBump.current
    val first = remember { FocusRequester() }
    var failed by remember { mutableStateOf<String?>(null) }
    var showInfo by remember { mutableStateOf(false) }

    /** Runs a change on the server; stays open with the reason if it failed, so it doesn't look like success. */
    fun change(what: String, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
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
                if (item.type != "show") {
                    if (continueWatching) add("Play from beginning" to { onDismiss(); nav.play(listOf(item.ratingKey)) })
                    else if (item.viewOffset > 0) add("Resume from ${formatDuration(item.viewOffset)}" to { onDismiss(); nav.play(listOf(item.ratingKey), resume = true) })
                }
                add("Mark as watched" to { change("mark it watched") { plex.setWatched(item.ratingKey, true) } })
                add("Mark as unwatched" to { change("mark it unwatched") { plex.setWatched(item.ratingKey, false) } })
                if (favorite != null) {
                    add((if (favorite) "Remove from favorites" else "Add to favorites") to {
                        change("save the favourite") { plex.setLabel(item.ratingKey, Episodes.FAVORITE, !favorite) }
                    })
                }
                if (continueWatching) {
                    add("Remove from Continue watching" to { change("remove it") { plex.removeFromContinueWatching(item.ratingKey) } })
                    item.grandparentRatingKey?.takeIf { item.isEpisode }?.let { showKey ->
                        add("Go to show" to {
                            scope.launch {
                                try {
                                    val show = plex.metadata(showKey)
                                    onDismiss()
                                    nav.push(Screen.Show(show))
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
private fun InfoPopup(plex: PlexClient, item: Item, onDismiss: () -> Unit) {
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
                    Text(value, fontSize = 11.sp, color = Palette.Ink)
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
        val mainAudio = audio.firstOrNull { it.selected } ?: audio.firstOrNull()
        return listOfNotNull(
            "When" to whenLine(item),
            streams.firstOrNull { it.streamType == 1 }?.let { "Video" to Describe.video(it) },
            mainAudio?.let { "Audio" to Describe.trackLabel(it) + more(audio.size - 1) },
            "Subtitles" to (subtitles.map(Describe::language).distinct().let { langs ->
                if (langs.isEmpty()) "None" else langs.take(3).joinToString(", ") + more(langs.size - 3)
            }),
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

    private fun more(n: Int) = if (n > 0) " + $n more" else ""

    private fun date(iso: String) = runCatching {
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
