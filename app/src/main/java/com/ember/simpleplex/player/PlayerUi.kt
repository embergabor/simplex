@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.ember.simpleplex.player

import android.view.ViewGroup
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.ember.simpleplex.plex.Chapter
import com.ember.simpleplex.plex.Item
import com.ember.simpleplex.plex.Marker
import com.ember.simpleplex.plex.Stream
import com.ember.simpleplex.ui.Icons
import com.ember.simpleplex.ui.Palette
import com.ember.simpleplex.ui.IconPillButton
import com.ember.simpleplex.ui.PillButton
import com.ember.simpleplex.ui.WideCard
import com.ember.simpleplex.ui.formatDuration
import kotlinx.coroutines.delay

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
    /** Previous/next episode; null where there's none. */
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
    /** How an audio track would play here, for the track box. */
    planAudio: (Stream) -> PlexTracks.AudioPlan,
    /** Closes the player. */
    onExit: () -> Unit,
    /** Compatibility track or Plex converting; answered with true for the compatibility track. */
    question: AudioQuestion?,
    onAnswer: (Boolean) -> Unit,
    /** Waiting on Plex (the video's details, a conversion starting) before the player has anything to load. */
    preparing: Boolean,
    /** A chapter's thumbnail path as an image address. */
    chapterImage: (String?) -> String?,
) {
    var visible by remember { mutableStateOf(true) }
    // Down from the play controls opens the file's chapters under them; closed with the controls.
    var chaptersOpen by remember(item) { mutableStateOf(false) }
    LaunchedEffect(visible) { if (!visible) chaptersOpen = false }
    var lastInput by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var buffering by remember { mutableStateOf(player.playbackState == Player.STATE_BUFFERING) }
    var picker by remember { mutableStateOf<Int?>(null) } // 2 = audio, 3 = subtitles
    val root = remember { FocusRequester() }
    val scrub = remember { FocusRequester() }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlaybackStateChanged(state: Int) { buffering = state == Player.STATE_BUFFERING }
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
    // Keyed on whether there's an item, not which: skipping episodes keeps focus on the skip button.
    LaunchedEffect(visible, item != null) {
        withFrameNanos { }
        runCatching { if (visible && item != null) scrub.requestFocus() else root.requestFocus() }
    }
    // Intro and end-credits buttons, from Plex's markers: shown while the controls are hidden, OK presses them.
    var marker by remember(item) { mutableStateOf<Marker?>(null) }
    LaunchedEffect(item) {
        if (item == null || item.markers.isEmpty()) return@LaunchedEffect
        while (true) {
            marker = Markers.active(item, position())
            delay(500)
        }
    }
    // The end credits skip to the next episode; without one there's nothing to skip to.
    val markerAction: (() -> Unit)? = marker?.let { m ->
        if (m.isIntro) ({ seekTo(m.end) }) else onNext
    }

    // Scrubbing: left/right move a preview playhead while the video keeps playing; OK jumps there.
    // Controls hiding (Back, or 5 s without a key) cancels it.
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

    // The remote's audio and subtitle buttons step through the tracks. The box shows each pick at once;
    // it's applied once the button rests for a moment, so stepping past tracks doesn't restart anything.
    // The steps are positions in the lists, kept per video until a track is picked from the menu.
    var audioStep by remember(item) { mutableStateOf<Int?>(null) }
    var subtitleStep by remember(item) { mutableStateOf<Int?>(null) } // 0 = off
    var audioPresses by remember { mutableIntStateOf(0) }
    var subtitlePresses by remember { mutableIntStateOf(0) }
    var trackBox by remember { mutableStateOf<TrackInfo?>(null) }
    var trackBoxShownAt by remember { mutableLongStateOf(0L) }
    val subtitleEntries = listOf<Stream?>(null) + subtitleOptions

    fun stepAudio() {
        if (audioOptions.isEmpty()) return
        val from = audioStep ?: audioOptions.indexOfFirst { it.id == audio?.id }
        val i = (from + 1).mod(audioOptions.size)
        audioStep = i
        val plan = planAudio(audioOptions[i])
        trackBox = Describe.audioTrack(audioOptions[i], i + 1, audioOptions.size, plan.compat, plan.choice)
        trackBoxShownAt = System.nanoTime()
        audioPresses++
    }
    fun stepSubtitle() {
        val from = subtitleStep ?: subtitleEntries.indexOfFirst { it?.id == subtitle?.id }
        val i = (from + 1).mod(subtitleEntries.size)
        subtitleStep = i
        trackBox = Describe.subtitleTrack(subtitleEntries[i], i, subtitleOptions.size)
        trackBoxShownAt = System.nanoTime()
        subtitlePresses++
    }
    LaunchedEffect(audioPresses) {
        delay(700)
        val pick = audioStep?.let(audioOptions::getOrNull) ?: return@LaunchedEffect
        if (pick.id != audio?.id) onAudio(pick)
    }
    LaunchedEffect(subtitlePresses) {
        delay(700)
        val i = subtitleStep ?: return@LaunchedEffect
        val pick = subtitleEntries.getOrNull(i)
        if (pick?.id != subtitle?.id) onSubtitle(pick)
    }
    // Long enough to see the switch take effect (a Plex conversion takes a few seconds to start).
    LaunchedEffect(trackBoxShownAt) {
        delay(6000)
        trackBox = null
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(root)
            .onPreviewKeyEvent { e ->
                // Back is handled here, both press and release, so focus never steps out of the controls
                // one level per press: the first press closes the controls, the next one stops playback.
                if (e.key == Key.Back) {
                    if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) {
                        if (chaptersOpen) {
                            // Chapters first: back to the progress bar, controls still open.
                            chaptersOpen = false
                            runCatching { scrub.requestFocus() }
                        } else if (visible && item != null && error == null) visible = false else onExit()
                    }
                    return@onPreviewKeyEvent true
                }
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                lastInput = System.nanoTime()
                // Logged so remotes' button codes can be checked (adb logcat -s Simplex:I).
                if (e.nativeKeyEvent.repeatCount == 0) android.util.Log.i("Simplex", "key ${e.nativeKeyEvent.keyCode}")
                when (e.nativeKeyEvent.keyCode) {
                    android.view.KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> { if (error == null) stepAudio(); return@onPreviewKeyEvent true }
                    android.view.KeyEvent.KEYCODE_CAPTIONS -> { if (error == null) stepSubtitle(); return@onPreviewKeyEvent true }
                }
                when (e.key) {
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> { player.togglePlay(); visible = true; true }
                    Key.MediaFastForward -> { scrub(e.nativeKeyEvent, 1); visible = true; true }
                    Key.MediaRewind -> { scrub(e.nativeKeyEvent, -1); visible = true; true }
                    Key.MediaNext -> { onNext?.invoke(); true }
                    Key.MediaPrevious -> { onPrevious?.invoke(); true }
                    else -> if (visible) false else {
                        // Hidden controls: OK presses a skip button that's showing.
                        if (e.key in okKeys && markerAction != null && e.nativeKeyEvent.repeatCount == 0) {
                            markerAction()
                            return@onPreviewKeyEvent true
                        }
                        // Left/right start scrubbing, anything else (OK too) just shows the controls.
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
                                android.graphics.Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                                android.graphics.Color.BLACK, null,
                            )
                        )
                        setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.1f)
                    }
                }
            },
            // While the controls are open, text subtitles sit above the control panel instead of behind it.
            update = { view ->
                val controlsShown = visible && item != null && error == null
                view.subtitleView?.setBottomPaddingFraction(
                    if (controlsShown) 0.38f else SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION
                )
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
        } else if (trackBox != null && !(visible && item != null)) {
            // The audio/subtitle buttons show just the info box, with the track they stepped to.
            InfoBox(info, trackBox, Modifier.align(Alignment.TopStart).padding(start = 40.dp, top = 28.dp))
        }
        // Only after half a second, so quick waits don't flash it.
        var spinner by remember { mutableStateOf(false) }
        val waiting = error == null && question == null && (preparing || buffering)
        LaunchedEffect(waiting) {
            spinner = false
            if (waiting) {
                delay(500)
                spinner = true
            }
        }
        if (spinner) Spinner(Modifier.align(Alignment.Center))
        if (error == null && !visible && markerAction != null) {
            SkipButton(
                if (marker?.isIntro == true) "Skip intro" else "Next episode",
                Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 48.dp),
            )
        }
        if (error == null && visible && item != null) {
            InfoBox(info, trackBox, Modifier.align(Alignment.TopStart).padding(start = 40.dp, top = 28.dp))
            Controls(
                item, duration, audio, subtitle, isPlaying, position, scrubAt, previews, scrub,
                onPrevious, onNext, onTogglePlay = { player.togglePlay() },
                modifier = Modifier.align(Alignment.BottomCenter),
                onPick = { audioStep = null; subtitleStep = null; picker = it },
                onKey = { e ->
                    when (e.key) {
                        Key.DirectionLeft -> { scrub(e.nativeKeyEvent, -1); true }
                        Key.DirectionRight -> { scrub(e.nativeKeyEvent, 1); true }
                        // OK jumps to the scrub position, otherwise pauses or resumes.
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            if (scrubAt != null) commitScrub() else player.togglePlay()
                            true
                        }
                        else -> false
                    }
                },
                onLeaveBar = ::cancelScrub,
                chaptersOpen = chaptersOpen,
                onChaptersOpen = { chaptersOpen = it },
                chapterImage = chapterImage,
                onChapter = { c ->
                    cancelScrub()
                    seekTo(c.start)
                    runCatching { scrub.requestFocus() }
                    chaptersOpen = false
                },
            )
        }
    }


    if (question != null) AudioQuestionDialog(question, onAnswer)

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
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
    onTogglePlay: () -> Unit,
    modifier: Modifier,
    onPick: (Int) -> Unit,
    onKey: (KeyEvent) -> Boolean,
    onLeaveBar: () -> Unit,
    chaptersOpen: Boolean,
    onChaptersOpen: (Boolean) -> Unit,
    chapterImage: (String?) -> String?,
    onChapter: (Chapter) -> Unit,
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
            .background(Palette.Ground.copy(alpha = 0.94f), RoundedCornerShape(14.dp))
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
                PillButton("Subtitles: ${subtitle?.let(Describe::trackLabel) ?: "Off"}", { onPick(3) }, icon = Icons.Subtitles)
                PillButton("Audio: ${audio?.let(Describe::trackLabel) ?: "—"}", { onPick(2) }, icon = Icons.Audio)
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
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.OnInk,
                    )
                }
            }
        }
        val chapters = item.chapters
        Row(
            Modifier.onPreviewKeyEvent { e ->
                // Down from the play controls opens the chapters; there's nothing else below them.
                val open = chapters.isNotEmpty() && !chaptersOpen && e.key == Key.DirectionDown && e.type == KeyEventType.KeyDown
                if (open) onChaptersOpen(true)
                open
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(formatDuration(shown), fontSize = 10.sp, fontWeight = FontWeight.Medium, color = Palette.Secondary)
            // Previous/next episode around play/pause; movies and home videos get play/pause alone.
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
                if (item.isEpisode) IconPillButton(Icons.Previous, "Previous episode", onPrevious)
                IconPillButton(if (isPlaying) Icons.Pause else Icons.Play, if (isPlaying) "Pause" else "Play", onTogglePlay)
                if (item.isEpisode) IconPillButton(Icons.Next, "Next episode", onNext)
            }
            Text("−" + formatDuration((duration - shown).coerceAtLeast(0)), fontSize = 10.sp, fontWeight = FontWeight.Medium, color = Palette.Secondary)
        }
        if (chapters.isNotEmpty()) {
            if (chaptersOpen) ChapterRow(chapters, shown, chapterImage, onChapter, onClose = { onChaptersOpen(false) })
            else Text("▾  Chapters", Modifier.align(Alignment.CenterHorizontally), fontSize = 10.sp, color = Palette.Secondary)
        }
    }
}

/**
 * The file's chapters, opening on the one playing (its card shows how far into it), with Plex's
 * thumbnail, the title (or "Chapter 3" when the file has none) and where it starts. OK jumps there.
 * Moving up out of the row closes it.
 */
@Composable
private fun ChapterRow(
    chapters: List<Chapter>,
    position: Long,
    chapterImage: (String?) -> String?,
    onChapter: (Chapter) -> Unit,
    onClose: () -> Unit,
) {
    val current = chapters.indexOfLast { it.start <= position }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (current - 1).coerceAtLeast(0))
    val focusCurrent = remember { FocusRequester() }
    var entered by remember { mutableStateOf(false) }
    LazyRow(
        state = state,
        modifier = Modifier.onFocusChanged { if (it.hasFocus) entered = true else if (entered) onClose() },
        contentPadding = PaddingValues(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        itemsIndexed(chapters, key = { i, _ -> i }) { i, c ->
            WideCard(
                imageUrl = chapterImage(c.thumb),
                title = c.label,
                subtitle = formatDuration(c.start),
                progress = if (i == current && c.end > c.start) ((position - c.start).toFloat() / (c.end - c.start)).coerceIn(0f, 1f) else 0f,
                watched = false,
                onClick = { onChapter(c) },
                width = 168.dp,
                modifier = if (i == current) Modifier.focusRequester(focusCurrent) else Modifier,
            )
        }
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focusCurrent.requestFocus() }
    }
}

private val okKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

/** Looks like a focused button; OK presses it (handled with the player's keys, so it never takes focus). */
@Composable
private fun SkipButton(label: String, modifier: Modifier) {
    Row(
        modifier
            .background(Palette.Ink, RoundedCornerShape(8.dp))
            .border(2.dp, Color.White, RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        Text("OK", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp))
    }
}

/** A ring with a turning arc, for loading and buffering. */
@Composable
private fun Spinner(modifier: Modifier) {
    val turn = rememberInfiniteTransition(label = "spinner")
    val angle by turn.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "angle")
    Canvas(modifier.size(44.dp).background(Color(0x991C1C1E), CircleShape).padding(10.dp)) {
        val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        drawArc(Color.White.copy(alpha = 0.25f), 0f, 360f, useCenter = false, style = stroke)
        drawArc(Color.White, angle, 90f, useCenter = false, style = stroke)
    }
}

/**
 * Small technical readout in the top-left corner: what's playing and how (direct, or Plex converting),
 * live. With the controls, or alone when the remote's audio/subtitle button steps to [track], whose
 * details follow underneath.
 */
@Composable
private fun InfoBox(info: PlaybackInfo, track: TrackInfo?, modifier: Modifier) {
    Column(
        modifier
            .width(IntrinsicSize.Max)
            .background(Color(0xCC3A3A3E), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        InfoLine("Video", info.video)
        InfoLine("Audio", info.audio)
        InfoLine("Subs", info.subtitle)
        Tags(info.markers)
        if (track != null) {
            Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.25f)))
            Text(track.heading, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            track.lines.forEach { (label, value) -> InfoLine(label, value) }
            if (track.markers.isNotEmpty()) Tags(track.markers)
        }
    }
}

@Composable
private fun Tags(markers: List<String>) {
    Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        markers.forEach {
            Text(
                it,
                modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                fontSize = 8.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
            )
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

/** Audio that would only reach the receiver as stereo, in a file that also has a compatibility track. */
data class AudioQuestion(val source: Stream, val compat: Stream)

/** Back picks the compatibility track: it plays directly, nothing gets converted. */
@Composable
private fun AudioQuestionDialog(q: AudioQuestion, onAnswer: (Boolean) -> Unit) {
    val focus = remember { FocusRequester() }
    val source = Describe.audioFormat(q.source)
    val compat = Describe.audioFormat(q.compat)
    Dialog(onDismissRequest = { onAnswer(true) }) {
        Column(
            Modifier.width(440.dp).background(Palette.Tile, RoundedCornerShape(14.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Which audio?", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, modifier = Modifier.padding(start = 4.dp))
            Text(
                "This TV can only send $source to the receiver as stereo. The file also has a $compat track " +
                    "in ${q.compat.language ?: "the same language"} that plays directly. Or Plex can convert the $source to 5.1.",
                fontSize = 12.sp, color = Palette.Secondary, modifier = Modifier.padding(horizontal = 4.dp),
            )
            PillButton("$compat track", { onAnswer(true) }, Modifier.fillMaxWidth().focusRequester(focus))
            PillButton("Plex converts the $source", { onAnswer(false) }, Modifier.fillMaxWidth())
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
                        label = s?.let(Describe::trackLabel) ?: "Off",
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
