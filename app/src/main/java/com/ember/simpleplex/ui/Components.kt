@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)

package com.ember.simpleplex.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import coil3.request.SuccessResult
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.foundation.Canvas
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.IntState
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.input.key.key
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
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.embergabor.simplex.core.plex.Item
import com.embergabor.simplex.core.plex.PlexClient
import com.embergabor.simplex.core.plex.WakeOnLan
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.embergabor.simplex.core.ui.Palette
import com.embergabor.simplex.core.ui.PillButton
import com.embergabor.simplex.core.ui.LocalBackdrop

// --- Navigation and refresh plumbing ---

interface Nav {
    fun push(screen: Screen)
    /**
     * Plays [ratingKeys] from [startAt]. An episode played in order gets its whole show as the
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

/**
 * Gives focus back to the Continue watching card a video was started from once the player closes. The
 * row changes while it plays: the title moves to the front, or leaves once finished. The card it was on
 * then scrolls off or goes, and focus with it. Here the row follows the title, or the card that took its
 * place.
 */
class PlayedFrom(private val played: MutableState<String?>) {
    private val requesters = mutableMapOf<String, FocusRequester>()
    private var keys: List<String>? = null

    /** For the card with this key. */
    fun requester(key: String): FocusRequester = requesters.getOrPut(key) { FocusRequester() }

    /** The card with this key starts a video. */
    fun played(key: String) { played.value = key }

    /** Called with the row's keys each time they change. */
    suspend fun follow(state: LazyListState, now: List<String>) {
        val before = keys
        keys = now
        val key = played.value ?: return
        if (before == null || before == now || now.isEmpty()) return
        played.value = null
        val i = now.indexOf(key).takeIf { it >= 0 } ?: before.indexOf(key).coerceIn(0, now.lastIndex)
        state.scrollToItem(i)
        withFrameNanos { }
        runCatching { requesters[now[i]]?.requestFocus() }
    }
}

@Composable
fun rememberPlayedFrom(): PlayedFrom {
    val played = rememberSaveable { mutableStateOf<String?>(null) }
    return remember { PlayedFrom(played) }
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

// --- Backdrop ---

/** The screen's own backdrop picture, which [WithBackdrop] sets; MainActivity gives each screen one. */
val LocalScreenBackdrop = staticCompositionLocalOf<MutableState<ImageBitmap?>> { mutableStateOf(null) }

/** The picture [Backdrop] shows: MainActivity points it at the backdrop of the screen on top. */
object Backdrops {
    var shown by mutableStateOf<State<ImageBitmap?>?>(null)
}

private var lastBackdrop: ImageBitmap? = null

/**
 * The focused title's artwork behind [content], blurred and fading into the ground towards the bottom
 * (see [Backdrop]). It changes once focus has rested for a moment, so scrolling past items doesn't load an
 * image for each of them. Ask for a small image: scaled up, nobody sees the difference.
 */
@Composable
fun WithBackdrop(content: @Composable () -> Unit) {
    // Saved with the screen, so coming back to it brings its backdrop back too.
    val focused = rememberSaveable { mutableStateOf<String?>(null) }
    val image = LocalScreenBackdrop.current
    // Starts on the last screen's picture, so a new screen never opens on the bare ground.
    remember(image) { if (image.value == null) image.value = lastBackdrop }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        val loader = SingletonImageLoader.get(context)
        snapshotFlow { focused.value }.filterNotNull().collectLatest { url ->
            // The first picture comes at once; later ones wait for focus to rest.
            if (image.value != null) delay(300)
            val result = loader.execute(ImageRequest.Builder(context).data(url).build())
            val picture = (result as? SuccessResult)?.image ?: return@collectLatest
            image.value = withContext(Dispatchers.Default) { Blur.backdrop(picture.toBitmap()) }.asImageBitmap().also { lastBackdrop = it }
        }
    }
    CompositionLocalProvider(LocalBackdrop provides focused, content = content)
}

/**
 * The ground, with [Backdrops.shown] on it fading into the ground towards the bottom; a new picture
 * fades in over the last. Drawn once behind every screen, so changing screens fades only what's in
 * front, and straight onto the screen: on a TV's GPU every full-screen pass costs, and an offscreen
 * layer (Crossfade's fade) costs two.
 */
@Composable
fun Backdrop() {
    // The opening plays on the bare ground; the picture fades in after it.
    val image = if (introSkipped) Backdrops.shown?.value else null
    var top by remember { mutableStateOf(image) }
    var under by remember { mutableStateOf<ImageBitmap?>(null) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(image) {
        if (image === top) return@LaunchedEffect
        under = top
        top = image
        fade.snapTo(0f)
        fade.animateTo(1f, tween(200))
        under = null
    }
    Canvas(Modifier.fillMaxSize()) {
        val t = fade.value
        val over = top
        val below = under
        // The ground only where no picture covers it.
        if (over == null || below == null && t < 1f) drawRect(Palette.Ground)
        if (below != null) drawCover(below, if (over == null) 1f - t else 1f)
        if (over != null) drawCover(over, t)
        // Over the bare ground the gradient adds nothing.
        if (over != null || below != null) drawRect(Brush.verticalGradient(0f to Palette.Ground.copy(alpha = 0.55f), 0.8f to Palette.Ground))
    }
}

/** [image] filling the whole area, cropped like [ContentScale.Crop]. */
private fun DrawScope.drawCover(image: ImageBitmap, alpha: Float) {
    val scale = max(size.width / image.width, size.height / image.height)
    val w = (size.width / scale).roundToInt().coerceAtMost(image.width)
    val h = (size.height / scale).roundToInt().coerceAtMost(image.height)
    drawImage(
        image,
        srcOffset = IntOffset((image.width - w) / 2, (image.height - h) / 2),
        srcSize = IntSize(w, h),
        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
        alpha = alpha,
    )
}
