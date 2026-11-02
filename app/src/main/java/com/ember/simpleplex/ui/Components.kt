@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)

package com.ember.simpleplex.ui

import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.DialogWindowProvider
import coil3.request.SuccessResult
import coil3.request.crossfade
import coil3.toBitmap
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.ember.simpleplex.plex.Episodes
import com.ember.simpleplex.plex.Item
import com.ember.simpleplex.plex.PlexClient
import com.ember.simpleplex.plex.WakeOnLan
import com.ember.simpleplex.plex.PlexServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// --- Navigation and refresh plumbing ---

interface Nav {
    fun push(screen: Screen)
    /**
     * Plays [ratingKeys] from [startAt]. Each is a ratingKey on the home server, or a [queueEntry] of
     * copies on several servers, tried in order. An episode played in order gets its whole show as the
     * queue, unless [keepQueue] (e.g. a show's favourites, which play on to the next favourite). Videos
     * start from the beginning; with [resume], the first one continues where it was left.
     */
    fun play(ratingKeys: List<String>, resume: Boolean = false, shuffle: Boolean = false, startAt: Int = 0, keepQueue: Boolean = false)

    /** Back to the sign-in screen, after [PlexClient.signOut]. */
    fun signOut()
}

/** Changes whenever lists should reload (returning from the player, marking watched). */
val LocalRefresh = compositionLocalOf { 0 }
val LocalRefreshBump = compositionLocalOf<() -> Unit> { {} }

@Composable
fun <T> load(vararg keys: Any?, block: suspend () -> T): State<Result<T>?> =
    produceState<Result<T>?>(null, *keys) { value = runCatching { block() } }

/**
 * Remembers which item had focus on a screen so it gets focus back after Back or the player.
 * Falls back to the item marked as default the first time the screen opens.
 */
class FocusMemory(private val saved: MutableState<String?>) {
    val target: String? = saved.value
    var done = false
    fun focused(key: String) { saved.value = key }
}

@Composable
fun rememberFocusMemory(): FocusMemory {
    val saved = rememberSaveable { mutableStateOf<String?>(null) }
    return remember { FocusMemory(saved) }
}

@Composable
fun Modifier.remembersFocus(memory: FocusMemory, key: String, default: Boolean = false): Modifier {
    val requester = remember { FocusRequester() }
    val wanted = if (memory.target != null) memory.target == key else default
    if (wanted) {
        LaunchedEffect(Unit) {
            if (!memory.done) {
                withFrameNanos { }
                runCatching { requester.requestFocus() }
                memory.done = true
            }
        }
    }
    return this
        .focusRequester(requester)
        .onFocusChanged { if (it.isFocused) memory.focused(key) }
}

// --- Scrolling ---

/** The remote as MainActivity last saw it. */
object RemoteKeys {
    /** A key is held down and repeating. */
    var repeating = false
    /** The last direction pressed: -1, 0 or 1 across ([dx]) and down ([dy]). */
    var dx = 0
    var dy = 0
}

/**
 * How lists scroll to the focused item: it stays at a fixed spot (the pivot) and the content moves
 * under it, the way tvOS does it. A firm spring for single presses; while a direction key repeats,
 * the scroll jumps, so the list keeps up with the focus instead of trailing behind it.
 */
class PivotScroll(private val pivot: (containerSize: Float) -> Float) : BringIntoViewSpec {
    // Deprecated, but Compose 1.12 still reads it each time a scroll animation starts.
    @Deprecated("Animation spec customization is no longer supported.")
    override val scrollAnimationSpec: AnimationSpec<Float>
        get() = if (RemoteKeys.repeating) snap() else spring(stiffness = Spring.StiffnessMediumLow)

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        offset - pivot(containerSize).coerceAtMost(containerSize - size).coerceAtLeast(0f)

    companion object {
        /** Grids and columns: the focused item about a third of the way down. */
        val Default = PivotScroll { it / 3f }
    }
}

/** Around a LazyRow: the focused card stays at the row's start, right after its [start] padding. */
@Composable
fun PinnedRow(start: Dp = 48.dp, content: @Composable () -> Unit) {
    val px = with(LocalDensity.current) { start.toPx() }
    val spec = remember(px) { PivotScroll { px } }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/**
 * Loads the images of the rows after the next one into Coil's memory cache as the grid scrolls, so
 * fast scrolling shows posters instead of empty tiles. The grid already composes one row ahead
 * itself, so that row is skipped rather than requested twice. [url] gets the grid's item index.
 */
@Composable
fun PreloadImages(state: LazyGridState, vararg keys: Any?, rows: Int = 3, url: (index: Int) -> String?) {
    val context = LocalContext.current
    val currentUrl by rememberUpdatedState(url)
    LaunchedEffect(state, *keys) {
        val loader = SingletonImageLoader.get(context)
        var reached = -1
        snapshotFlow { state.layoutInfo.run { (visibleItemsInfo.lastOrNull()?.index ?: -1) to maxSpan } }
            .collect { (last, span) ->
                if (last < 0) return@collect
                val until = last + rows * span
                for (i in maxOf(reached + 1, last + span + 1)..until) {
                    currentUrl(i)?.let { loader.enqueue(ImageRequest.Builder(context).data(it).build()) }
                }
                reached = maxOf(reached, until)
            }
    }
}

// --- Text ---

@Composable
fun ScreenTitle(title: String, trailing: String? = null) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
        if (trailing != null) Text(trailing, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Secondary)
    }
}

@Composable
fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier = modifier, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
}

@Composable
fun Message(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 15.sp, color = Palette.Secondary, textAlign = TextAlign.Center)
    }
}

/**
 * A row that moving up or down into always lands on its first item (scrolled back to the start), not
 * on whatever happens to sit above or below. Give the first item the [first] requester. Focus put
 * back directly (after Back or the player) isn't affected.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun Modifier.entersOnFirst(state: LazyListState, first: FocusRequester): Modifier {
    val scope = rememberCoroutineScope()
    return focusProperties {
        enter = {
            if (state.firstVisibleItemIndex == 0) first
            else {
                // The first item isn't laid out while scrolled away: scroll back, then focus it.
                scope.launch {
                    state.scrollToItem(0)
                    withFrameNanos { }
                    runCatching { first.requestFocus() }
                }
                FocusRequester.Cancel
            }
        }
    }
}

/**
 * A load that failed, usually because the server is asleep or still starting (e.g. after Wake-on-LAN).
 * Tries again by itself every few seconds, or right away with the button. A retry reloads every screen's
 * data (the same refresh as coming back from the player), and the message stays until something loads.
 */
@Composable
fun ErrorMessage(text: String, modifier: Modifier = Modifier.fillMaxSize()) {
    val retry = LocalRefreshBump.current
    val button = remember { FocusRequester() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mac = remember { WakeOnLan.Prefs(context).mac }
    var woken by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { button.requestFocus() }
        while (true) {
            delay(5000)
            retry()
        }
    }
    Column(
        modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        Text(text, fontSize = 15.sp, color = Palette.Secondary, textAlign = TextAlign.Center)
        Text(woken ?: "Trying again every few seconds.", fontSize = 12.sp, color = Palette.Secondary)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // With a MAC address saved in Settings, waking is the likely fix, so it gets the focus.
            if (mac != null) {
                PillButton("Wake server", {
                    scope.launch {
                        woken = runCatching { WakeOnLan.send(mac, PlexClient.get(context).home?.uri) }
                            .fold({ "Wake sent. Trying again every few seconds while it starts." }, { "Couldn't send the wake: ${it.message}" })
                    }
                }, Modifier.focusRequester(button))
            }
            PillButton("Try again now", retry, if (mac == null) Modifier.focusRequester(button) else Modifier)
        }
    }
}

// --- Cards ---

private val cardShape = RoundedCornerShape(8.dp)

private const val FOCUSED_SCALE = 1.10f

/** The focus lift (0 to 1): a spring that settles with a small overshoot, like tvOS. */
@Composable
private fun animateLift(focused: Boolean): State<Float> =
    animateFloatAsState(if (focused) 1f else 0f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium), label = "lift")

/**
 * The title under a lifted card slides down by as much as the card's bottom edge moves, so it stays
 * readable. Both follow the same [lift].
 */
private fun Modifier.followCard(lift: State<Float>, cardHeight: Int): Modifier =
    graphicsLayer { translationY = cardHeight * (FOCUSED_SCALE - 1f) * lift.value / 2f }

/**
 * OK clicks; holding OK (its first repeat) is a long click, as on tv-material's cards. Only a press
 * that started here counts, so the release of a key pressed on the screen before doesn't click.
 */
@Composable
private fun Modifier.okClick(onClick: () -> Unit, onLongClick: (() -> Unit)?): Modifier {
    // 0: no press here, 1: pressed, 2: the press became a long click.
    val press = remember { intArrayOf(0) }
    return onKeyEvent { e ->
        if (e.key !in okKeys) return@onKeyEvent false
        val native = e.nativeKeyEvent
        when (native.action) {
            android.view.KeyEvent.ACTION_DOWN -> when (native.repeatCount) {
                0 -> press[0] = 1
                1 -> if (press[0] == 1 && onLongClick != null) { press[0] = 2; onLongClick() }
            }
            android.view.KeyEvent.ACTION_UP -> { if (press[0] == 1) onClick(); press[0] = 0 }
        }
        true
    }
}

@Composable
private fun Artwork(url: String?, fallback: String) {
    // The title only stands in when there's no image at all: while one loads, the plain tile looks
    // calmer than text that flickers and then gets replaced.
    Box(Modifier.fillMaxSize().background(Palette.Placeholder), contentAlignment = Alignment.Center) {
        if (url != null) {
            val context = LocalContext.current
            // A short fade for posters that load; ones already in memory show at once.
            val request = remember(url) { ImageRequest.Builder(context).data(url).crossfade(150).build() }
            AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                fallback, modifier = Modifier.padding(10.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                color = Palette.Secondary, textAlign = TextAlign.Center, maxLines = 3,
            )
        }
    }
}

@Composable
private fun Progress(progress: Float, modifier: Modifier) {
    Box(modifier.height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.45f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(Color.White))
    }
}

@Composable
private fun WatchedBadge(modifier: Modifier) {
    Box(modifier.size(17.dp).background(Color.White.copy(alpha = 0.92f), CircleShape), contentAlignment = Alignment.Center) {
        Icon(Icons.Check, contentDescription = "Watched", tint = Palette.Light.ink, modifier = Modifier.size(10.dp))
    }
}

/**
 * A poster or thumbnail. Focused, it lifts: it grows and casts a soft shadow. All of it is
 * drawn inside the focus target, so the box the lists scroll into view stays put. [backdrop] is shown
 * behind the screen while it has focus (see [WithBackdrop]).
 */
@Composable
private fun ArtCard(
    url: String?,
    fallback: String,
    aspect: Float,
    progress: Float,
    watched: Boolean,
    badge: String?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    interaction: MutableInteractionSource,
    focused: Boolean,
    lift: State<Float>,
    onHeight: (Int) -> Unit,
    modifier: Modifier,
    cornerIcon: ImageVector? = null,
    backdrop: String? = null,
) {
    val backdropState = LocalBackdrop.current
    LaunchedEffect(focused) {
        if (focused && backdrop != null) backdropState?.value = backdrop
    }
    Box(
        modifier.fillMaxWidth().aspectRatio(aspect).onSizeChanged { onHeight(it.height) }
            .okClick(onClick, onLongClick)
            .focusable(interactionSource = interaction)
            .semantics(mergeDescendants = true) {
                onClick { onClick(); true }
                onLongClick?.let { onLongClick { it(); true } }
            }
            .graphicsLayer {
                val s = 1f + (FOCUSED_SCALE - 1f) * lift.value
                scaleX = s
                scaleY = s
                shadowElevation = 18.dp.toPx() * lift.value.coerceIn(0f, 1f)
                // A light glow in dark mode, where a shadow vanishes (Android 9+; older TVs keep the shadow).
                ambientShadowColor = Palette.Shadow
                spotShadowColor = Palette.Shadow
                shape = cardShape
                clip = true
            },
    ) {
        Artwork(url, fallback)
        if (progress > 0f) Progress(progress, Modifier.align(Alignment.BottomCenter).padding(horizontal = 10.dp, vertical = 9.dp).fillMaxWidth())
        if (watched) WatchedBadge(Modifier.align(Alignment.TopEnd).padding(7.dp))
        if (cornerIcon != null) {
            Icon(
                cornerIcon, contentDescription = null, tint = Color.White,
                modifier = Modifier.align(Alignment.TopStart).padding(7.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                    .padding(3.dp).size(14.dp),
            )
        }
        if (badge != null) {
            Text(
                badge,
                modifier = Modifier.align(Alignment.BottomEnd).padding(7.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
                fontSize = 8.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
            )
        }
    }
}

/** 2:3 poster with the title underneath (movies, shows). */
@Composable
fun PosterCard(
    title: String,
    imageUrl: String?,
    progress: Float,
    watched: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    badge: String? = null,
    cornerIcon: ImageVector? = null,
    backdrop: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val lift = animateLift(focused)
    var cardHeight by remember { mutableIntStateOf(0) }
    Column(Modifier.zIndex(if (focused) 1f else 0f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ArtCard(imageUrl, title, 2f / 3f, progress, watched, badge, onClick, onLongClick, interaction, focused, lift, { cardHeight = it }, modifier, cornerIcon, backdrop)
        // Two lines, so long titles can be read; always two, so the grid's rows line up.
        Text(title, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Palette.Ink, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.followCard(lift, cardHeight).padding(horizontal = 2.dp))
    }
}

/** 16:9 thumbnail with up to two lines underneath (continue watching, episodes, home videos). */
@Composable
fun WideCard(
    imageUrl: String?,
    title: String,
    subtitle: String?,
    progress: Float,
    watched: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    width: androidx.compose.ui.unit.Dp? = null,
    subtitleFirst: Boolean = false,
    badge: String? = null,
    backdrop: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val lift = animateLift(focused)
    var cardHeight by remember { mutableIntStateOf(0) }
    Column(
        modifier = (if (width != null) Modifier.width(width) else Modifier).zIndex(if (focused) 1f else 0f),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        ArtCard(imageUrl, title, 16f / 9f, progress, watched, badge, onClick, onLongClick, interaction, focused, lift, { cardHeight = it }, modifier, backdrop = backdrop)
        Column(Modifier.followCard(lift, cardHeight).padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            val small: @Composable (String) -> Unit = { Text(it, fontSize = 9.5.sp, color = Palette.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            val big: @Composable (String) -> Unit = { Text(it, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (subtitleFirst) { subtitle?.let { small(it) }; big(title) } else { big(title); subtitle?.let { small(it) } }
        }
    }
}

// --- Backdrop ---

/** Set by a focused card on a screen inside [WithBackdrop]: the artwork to show behind it. */
val LocalBackdrop = staticCompositionLocalOf<MutableState<String?>?> { null }

/**
 * The focused title's artwork behind [content], blurred (Android 12 and up; just faded before) and
 * fading into the ground towards the bottom. It changes once focus has rested for a moment, so
 * scrolling past items doesn't load an image for each of them. Ask for a small image: blurred and
 * scaled up, nobody sees the difference.
 */
private var lastBackdrop: ImageBitmap? = null

@Composable
fun WithBackdrop(content: @Composable () -> Unit) {
    // Saved with the screen, so coming back to it brings its backdrop back too.
    val focused = rememberSaveable { mutableStateOf<String?>(null) }
    // Starts on the last screen's picture, so a new screen never opens on the bare ground.
    var image by remember { mutableStateOf(lastBackdrop) }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        val loader = SingletonImageLoader.get(context)
        snapshotFlow { focused.value }.filterNotNull().collectLatest { url ->
            // The first picture comes at once; later ones wait for focus to rest.
            if (image != null) delay(300)
            val result = loader.execute(ImageRequest.Builder(context).data(url).build())
            (result as? SuccessResult)?.let { image = it.image.toBitmap().asImageBitmap().also { b -> lastBackdrop = b } }
        }
    }
    Box(Modifier.fillMaxSize()) {
        Crossfade(image, animationSpec = tween(400), label = "backdrop") { bitmap ->
            if (bitmap != null) {
                Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(24.dp, BlurredEdgeTreatment.Rectangle))
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Palette.Ground.copy(alpha = 0.55f), 0.8f to Palette.Ground)))
        CompositionLocalProvider(LocalBackdrop provides focused, content = content)
    }
}

/** Light tile with an icon, for libraries and folders. Turns dark when focused, like the buttons. */
@Composable
fun IconTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    horizontal: Boolean = false,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
        scale = CardDefaults.scale(focusedScale = 1.06f),
        border = CardDefaults.border(focusedBorder = Border.None),
        colors = CardDefaults.colors(
            containerColor = Palette.Tile,
            contentColor = Palette.Ink,
            focusedContainerColor = Palette.Ink,
            focusedContentColor = Palette.OnInk,
        ),
    ) {
        if (horizontal) {
            Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
                Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(10.dp))
                Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Gray pill button that turns dark when focused. */
@Composable
fun PillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    accent: Color? = null,
) {
    Button(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        shape = ButtonDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ButtonDefaults.scale(focusedScale = 1.04f),
        colors = ButtonDefaults.colors(
            containerColor = accent ?: Palette.Control,
            contentColor = if (accent != null) Palette.Light.ink else Palette.Ink,
            focusedContainerColor = Palette.Ink,
            focusedContentColor = Palette.OnInk,
        ),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Check, contentDescription = "Selected", modifier = Modifier.size(12.dp))
        }
    }
}

/** Square icon-only version of [PillButton]; disabled (dimmed, not focusable) when [onClick] is null. */
@Composable
fun IconPillButton(icon: ImageVector, description: String, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    Button(
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        shape = ButtonDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ButtonDefaults.scale(focusedScale = 1.04f),
        colors = ButtonDefaults.colors(
            containerColor = Palette.Control,
            contentColor = Palette.Ink,
            focusedContainerColor = Palette.Ink,
            focusedContentColor = Palette.OnInk,
            disabledContainerColor = Palette.Control.copy(alpha = 0.5f),
            disabledContentColor = Palette.Ink.copy(alpha = 0.3f),
        ),
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(16.dp))
    }
}

internal val okKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun timeLeft(item: Item): String {
    val left = ((item.duration - item.viewOffset) / 60000).coerceAtLeast(1)
    return if (left >= 60) "${left / 60}h ${left % 60}m left" else "${left}m left"
}
