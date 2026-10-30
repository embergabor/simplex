@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.ember.simpleplex.player

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.ember.simpleplex.plex.Item
import com.ember.simpleplex.plex.Stream
import com.ember.simpleplex.ui.Icons
import com.ember.simpleplex.ui.Palette
import com.ember.simpleplex.ui.PillButton
import com.ember.simpleplex.ui.formatDuration
import kotlinx.coroutines.delay

private fun Stream.shortName() = displayTitle.substringBefore(" (")

private fun Player.togglePlay() = if (isPlaying) pause() else play()

@AndroidXOptIn(markerClass = [UnstableApi::class])
@Composable
fun PlayerScreen(
    player: Player,
    item: Item?,
    audioOptions: List<Stream>,
    subtitleOptions: List<Stream>,
    audio: Stream?,
    subtitle: Stream?,
    error: String?,
    position: () -> Long,
    onAudio: (Stream) -> Unit,
    onSubtitle: (Stream?) -> Unit,
) {
    var visible by remember { mutableStateOf(true) }
    var lastInput by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var picker by remember { mutableStateOf<Int?>(null) } // 2 = audio, 3 = subtitles
    val root = remember { FocusRequester() }
    val scrub = remember { FocusRequester() }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(visible, lastInput, isPlaying, picker) {
        if (visible && isPlaying && picker == null) {
            delay(5000)
            visible = false
        }
    }
    LaunchedEffect(visible, item) {
        withFrameNanos { }
        runCatching { if (visible && item != null) scrub.requestFocus() else root.requestFocus() }
    }
    BackHandler(enabled = visible && error == null) { visible = false }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(root)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                lastInput = System.nanoTime()
                when (e.key) {
                    Key.Back -> false
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> { player.togglePlay(); visible = true; true }
                    Key.MediaFastForward -> { player.seekForward(); visible = true; true }
                    Key.MediaRewind -> { player.seekBack(); visible = true; true }
                    else -> if (visible) false else {
                        // Hidden controls: OK pauses, left/right skip, anything else just shows the controls.
                        when (e.key) {
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> player.togglePlay()
                            Key.DirectionLeft -> player.seekBack()
                            Key.DirectionRight -> player.seekForward()
                            else -> {}
                        }
                        visible = true
                        true
                    }
                }
            }
            .focusable(),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    this.player = player
                    isFocusable = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    subtitleView?.apply {
                        setStyle(
                            CaptionStyleCompat(
                                android.graphics.Color.WHITE, android.graphics.Color.TRANSPARENT,
                                android.graphics.Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                                android.graphics.Color.BLACK, null,
                            )
                        )
                        setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.1f)
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (error != null) {
            Column(
                Modifier.align(Alignment.Center).padding(48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(error, fontSize = 16.sp, color = Color.White, textAlign = TextAlign.Center)
                Text("Press Back to return", fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f))
            }
        } else if (visible && item != null) {
            Controls(
                player, item, audio, subtitle, isPlaying, position, scrub,
                modifier = Modifier.align(Alignment.BottomCenter),
                onPick = { picker = it },
            )
        }
    }

    when (picker) {
        2 -> TrackPicker("Audio", audioOptions, audio, allowOff = false, onDismiss = { picker = null }) { onAudio(it!!) }
        3 -> TrackPicker("Subtitles", subtitleOptions, subtitle, allowOff = true, onDismiss = { picker = null }) { onSubtitle(it) }
    }
}

@Composable
private fun Controls(
    player: Player,
    item: Item,
    audio: Stream?,
    subtitle: Stream?,
    isPlaying: Boolean,
    position: () -> Long,
    scrub: FocusRequester,
    modifier: Modifier,
    onPick: (Int) -> Unit,
) {
    var pos by remember { mutableLongStateOf(position()) }
    LaunchedEffect(item) {
        while (true) {
            pos = position()
            delay(250)
        }
    }
    val duration = item.duration.takeIf { it > 0 } ?: player.duration.coerceAtLeast(1)
    val fraction = (pos.toFloat() / duration).coerceIn(0f, 1f)
    var scrubFocused by remember { mutableStateOf(false) }

    Column(
        modifier
            .padding(horizontal = 40.dp, vertical = 32.dp)
            .fillMaxWidth()
            .background(Color(0xF0FAFAFC), RoundedCornerShape(14.dp))
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (item.isEpisode) {
                    Text("${item.grandparentTitle ?: ""} · ${item.episodeLabel}", fontSize = 10.sp, color = Palette.Secondary)
                }
                Text(item.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, maxLines = 1)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Subtitles: ${subtitle?.shortName() ?: "Off"}", { onPick(3) }, icon = Icons.Subtitles)
                PillButton("Audio: ${audio?.shortName() ?: "—"}", { onPick(2) }, icon = Icons.Audio)
            }
        }
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(20.dp)
                .focusRequester(scrub)
                .onFocusChanged { scrubFocused = it.isFocused }
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (e.key) {
                        Key.DirectionLeft -> { player.seekBack(); true }
                        Key.DirectionRight -> { player.seekForward(); true }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { player.togglePlay(); true }
                        else -> false
                    }
                }
                .focusable(),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Palette.Divider)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(Palette.Ink))
            }
            val knob = if (scrubFocused) 16.dp else 12.dp
            Box(
                Modifier
                    .offset(x = maxWidth * fraction - knob / 2)
                    .size(knob)
                    .border(3.dp, Color.White, CircleShape)
                    .background(Palette.Ink, CircleShape)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatDuration(pos), fontSize = 10.sp, fontWeight = FontWeight.Medium, color = Palette.Secondary)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Icon(if (isPlaying) Icons.Pause else Icons.Play, contentDescription = if (isPlaying) "Playing" else "Paused", tint = Palette.Ink, modifier = Modifier.size(14.dp))
            }
            Text("−" + formatDuration((duration - pos).coerceAtLeast(0)), fontSize = 10.sp, fontWeight = FontWeight.Medium, color = Palette.Secondary)
        }
    }
}

@Composable
private fun TrackPicker(
    title: String,
    options: List<Stream>,
    current: Stream?,
    allowOff: Boolean,
    onDismiss: () -> Unit,
    onPick: (Stream?) -> Unit,
) {
    val entries: List<Stream?> = (if (allowOff) listOf<Stream?>(null) else emptyList()) + options
    val focus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(380.dp).background(Palette.Tile, RoundedCornerShape(14.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, modifier = Modifier.padding(start = 4.dp))
            LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(entries) { s ->
                    val selected = s?.id == current?.id
                    PillButton(
                        label = s?.displayTitle ?: "Off",
                        selected = selected,
                        onClick = { onPick(s); onDismiss() },
                        modifier = Modifier.fillMaxWidth().then(if (selected) Modifier.focusRequester(focus) else Modifier),
                    )
                }
            }
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { focus.requestFocus() }
        }
    }
}
