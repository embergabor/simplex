package com.embergabor.simplex.core.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.IntState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.embergabor.simplex.core.plex.Item
import kotlinx.coroutines.delay

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
private fun Modifier.followCard(lift: State<Float>, cardHeight: IntState): Modifier =
    // Both read only while drawing: the card's height arriving after layout doesn't build the card again.
    graphicsLayer { translationY = cardHeight.intValue * (FOCUSED_SCALE - 1f) * lift.value / 2f }

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
            // No fade as it loads: a grid opening would animate every poster at once.
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
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

/** A show's unwatched episodes, in the corner where a watched one has its check. */
@Composable
private fun CountBadge(count: Int, modifier: Modifier) {
    Box(
        modifier.height(17.dp).widthIn(min = 17.dp).background(Color.White.copy(alpha = 0.92f), CircleShape).padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(count.toString(), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Palette.Light.ink)
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
    unwatched: Int = 0,
    prefetch: (() -> Unit)? = null,
) {
    val backdropState = LocalBackdrop.current
    val currentPrefetch by rememberUpdatedState(prefetch)
    LaunchedEffect(focused) {
        if (!focused) return@LaunchedEffect
        if (backdrop != null) backdropState?.value = backdrop
        // Once focus rests, as for the backdrop: scrolling past cards doesn't fetch each of them.
        currentPrefetch?.let { delay(300); it() }
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
                // At full strength at once: a shadow growing with the spring is drawn anew every frame.
                shadowElevation = if (focused) 18.dp.toPx() else 0f
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
        else if (unwatched > 0) CountBadge(unwatched, Modifier.align(Alignment.TopEnd).padding(7.dp))
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
    /** A show's unwatched episodes, counted in the corner. */
    unwatched: Int = 0,
    /** Fetches what playing it needs, once focus rests on it (see [com.embergabor.simplex.core.plex.Prefetch]). */
    prefetch: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val lift = animateLift(focused)
    val cardHeight = remember { mutableIntStateOf(0) }
    Column(Modifier.zIndex(if (focused) 1f else 0f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ArtCard(imageUrl, title, 2f / 3f, progress, watched, badge, onClick, onLongClick, interaction, focused, lift, { cardHeight.intValue = it }, modifier, cornerIcon, backdrop, unwatched, prefetch)
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
    prefetch: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val lift = animateLift(focused)
    val cardHeight = remember { mutableIntStateOf(0) }
    Column(
        modifier = (if (width != null) Modifier.width(width) else Modifier).zIndex(if (focused) 1f else 0f),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        ArtCard(imageUrl, title, 16f / 9f, progress, watched, badge, onClick, onLongClick, interaction, focused, lift, { cardHeight.intValue = it }, modifier, backdrop = backdrop, prefetch = prefetch)
        Column(Modifier.followCard(lift, cardHeight).padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            val small: @Composable (String) -> Unit = { Text(it, fontSize = 9.5.sp, color = Palette.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            val big: @Composable (String) -> Unit = { Text(it, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (subtitleFirst) { subtitle?.let { small(it) }; big(title) } else { big(title); subtitle?.let { small(it) } }
        }
    }
}


/** Set by a focused card on a screen that shows one: the artwork to show behind it. */
val LocalBackdrop = staticCompositionLocalOf<MutableState<String?>?> { null }


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

/** The remote's OK keys. */
val okKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

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
