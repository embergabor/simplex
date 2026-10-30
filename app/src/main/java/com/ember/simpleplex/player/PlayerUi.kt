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
import androidx.compose.foundation.Image
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    seekTo: (Long) -> Unit,
    previews: PreviewThumbs?,
    info: PlaybackInfo,
    onAudio: (Stream) -> Unit,
    onSubtitle: (Stream?) -> Unit,
    askBurn: Boolean,
    onBurnAnswer: (Boolean) -> Unit,
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

    // Scrubbing: left/right move a preview playhead while the video keeps playing; OK jumps there,
    // Back cancels. Controls hiding (5 s without a key) cancels it too.
    var scrubAt by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(visible) { if (!visible) scrubAt = null }
    var lastStepAt by remember { mutableLongStateOf(0L) }
    val duration = item?.duration?.takeIf { it > 0 } ?: player.duration.coerceAtLeast(1)

    fun scrub(e: android.view.KeyEvent, direction: Int) {
        val from = scrubAt ?: position()
        val step = if (e.repeatCount == 0) {
            10_000L
        } else {
            // Holding the button speeds up smoothly: 30 s of video per second, +60 s/s for every second
            // held, capped so a whole film takes about 20 seconds.
            val held = (e.eventTime - e.downTime) / 1000f
            val speed = minOf(30_000f + 60_000f * held, maxOf(duration / 20f, 30_000f))
            (speed * (e.eventTime - lastStepAt).coerceIn(0, 200) / 1000f).toLong()
        }
        lastStepAt = e.eventTime
        scrubAt = (from + direction * step).coerceIn(0, duration)
    }
    fun commitScrub() {
        scrubAt?.let(seekTo)
        scrubAt = null
    }
    fun cancelScrub() {
        scrubAt = null
    }
    BackHandler(enabled = scrubAt != null) { cancelScrub() }

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
                    Key.MediaFastForward -> { scrub(e.nativeKeyEvent, 1); visible = true; true }
                    Key.MediaRewind -> { scrub(e.nativeKeyEvent, -1); visible = true; true }
                    else -> if (visible) false else {
                        // Hidden controls: left/right start scrubbing, anything else (OK too) just shows the controls.
                        when (e.key) {
                            Key.DirectionLeft -> scrub(e.nativeKeyEvent, -1)
                            Key.DirectionRight -> scrub(e.nativeKeyEvent, 1)
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
            InfoBox(info, Modifier.align(Alignment.TopStart).padding(start = 40.dp, top = 28.dp))
            Controls(
                item, duration, audio, subtitle, isPlaying, position, scrubAt, previews, scrub,
                modifier = Modifier.align(Alignment.BottomCenter),
                onPick = { picker = it },
                onKey = { e ->
                    when (e.key) {
                        Key.DirectionLeft -> { scrub(e.nativeKeyEvent, -1); true }
                        Key.DirectionRight -> { scrub(e.nativeKeyEvent, 1); true }
                        // OK jumps to the scrub position or resumes; pausing is the remote's play/pause button.
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            if (scrubAt != null) commitScrub() else if (!player.isPlaying) player.play()
                            true
                        }
                        else -> false
                    }
                },
                onLeaveBar = ::cancelScrub,
            )
        }
    }

    if (askBurn) BurnQuestion(onBurnAnswer)

    when (picker) {
        2 -> TrackPicker("Audio", audioOptions, audio, allowOff = false, onDismiss = { picker = null }) { onAudio(it!!) }
        3 -> TrackPicker("Subtitles", subtitleOptions, subtitle, allowOff = true, onDismiss = { picker = null }) { onSubtitle(it) }
    }
}

@Composable
private fun Controls(
    item: Item,
    duration: Long,
    audio: Stream?,
    subtitle: Stream?,
    isPlaying: Boolean,
    position: () -> Long,
    scrubAt: Long?,
    previews: PreviewThumbs?,
    bar: FocusRequester,
    modifier: Modifier,
    onPick: (Int) -> Unit,
    onKey: (KeyEvent) -> Boolean,
    onLeaveBar: () -> Unit,
) {
    var pos by remember { mutableLongStateOf(position()) }
    LaunchedEffect(item) {
        while (true) {
            pos = position()
            delay(250)
        }
    }
    val shown = scrubAt ?: pos
    val fraction = (shown.toFloat() / duration).coerceIn(0f, 1f)
    var barFocused by remember { mutableStateOf(false) }

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
                .focusRequester(bar)
                .onFocusChanged {
                    barFocused = it.isFocused
                    if (!it.isFocused) onLeaveBar()
                }
                .onKeyEvent { e -> e.type == KeyEventType.KeyDown && onKey(e) }
                .focusable(),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Palette.Divider)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(Palette.Ink))
            }
            val knob = if (barFocused) 16.dp else 12.dp
            Box(
                Modifier
                    .offset(x = maxWidth * fraction - knob / 2)
                    .size(knob)
                    .border(3.dp, Color.White, CircleShape)
                    .background(Palette.Ink, CircleShape)
            )
            if (scrubAt != null) {
                // Floats above the bar, over the title row and the video. Measured without the bar's
                // 20 dp height limit, centered on the playhead and kept inside the bar's width.
                val barWidth = maxWidth
                Column(
                    Modifier.layout { measurable, _ ->
                        val p = measurable.measure(Constraints())
                        val x = (barWidth.toPx() * fraction - p.width / 2f).coerceIn(0f, (barWidth.toPx() - p.width).coerceAtLeast(0f))
                        layout(0, 0) { p.place(x.toInt(), -p.height - 18.dp.roundToPx()) }
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (previews != null) PreviewImage(previews, scrubAt, width = 224.dp)
                    Text(
                        formatDuration(scrubAt),
                        modifier = Modifier.background(Palette.Ink, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatDuration(shown), fontSize = 10.sp, fontWeight = FontWeight.Medium, color = Palette.Secondary)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Icon(if (isPlaying) Icons.Pause else Icons.Play, contentDescription = if (isPlaying) "Playing" else "Paused", tint = Palette.Ink, modifier = Modifier.size(14.dp))
            }
            Text("−" + formatDuration((duration - shown).coerceAtLeast(0)), fontSize = 10.sp, fontWeight = FontWeight.Medium, color = Palette.Secondary)
        }
    }
}

/** Small technical readout in the top-left corner, shown only together with the controls. */
@Composable
private fun InfoBox(info: PlaybackInfo, modifier: Modifier) {
    Column(
        modifier
            .background(Color(0xCC3A3A3E), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        InfoLine("Video", info.video)
        InfoLine("Audio", info.audio)
        InfoLine("Subs", info.subtitle)
        Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            info.markers.forEach {
                Text(
                    it,
                    modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                    fontSize = 8.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, modifier = Modifier.width(34.dp), fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.6f))
        Text(value, fontSize = 9.sp, color = Color.White)
    }
}

@Composable
private fun PreviewImage(previews: PreviewThumbs, at: Long, width: Dp) {
    val frame = previews.frameAt(at)
    // Keeps showing the previous frame until the next one is decoded, so scrubbing doesn't flicker.
    val image by produceState<ImageBitmap?>(null, frame) {
        value = withContext(Dispatchers.IO) { previews.image(frame) } ?: value
    }
    // Sized from the thumbnail itself (16:9, 4:3, ...); 16:9 until the first one is decoded.
    val aspect = image?.let { it.width.toFloat() / it.height } ?: (16f / 9f)
    Box(
        Modifier
            .width(width)
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black)
            .border(2.dp, Color.White, RoundedCornerShape(8.dp)),
    ) {
        image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
    }
}

/** TrueHD this TV can't play: Plex converts the audio, and subtitles only survive that burned in. */
@Composable
private fun BurnQuestion(onAnswer: (Boolean) -> Unit) {
    val focus = remember { FocusRequester() }
    Dialog(onDismissRequest = { onAnswer(false) }) {
        Column(
            Modifier.width(420.dp).background(Palette.Tile, RoundedCornerShape(14.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Subtitles for this file", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, modifier = Modifier.padding(start = 4.dp))
            Text(
                "This TV can't play the file's TrueHD audio, so Plex converts it. Subtitles can only be " +
                    "kept by burning them into the picture, which means Plex converts the video too.",
                fontSize = 12.sp, color = Palette.Secondary, modifier = Modifier.padding(horizontal = 4.dp),
            )
            PillButton("No subtitles", { onAnswer(false) }, Modifier.fillMaxWidth().focusRequester(focus))
            PillButton("Burn in subtitles", { onAnswer(true) }, Modifier.fillMaxWidth())
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { focus.requestFocus() }
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
