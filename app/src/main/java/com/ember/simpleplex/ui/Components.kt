@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.ember.simpleplex.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.FocusRequester
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
import com.ember.simpleplex.plex.Item
import com.ember.simpleplex.plex.PlexClient
import kotlinx.coroutines.launch

// --- Navigation and refresh plumbing ---

interface Nav {
    fun push(screen: Screen)
    fun play(ratingKeys: List<String>, fromStart: Boolean = false, shuffle: Boolean = false)
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

// --- Cards ---

private val cardShape = RoundedCornerShape(8.dp)

private const val FOCUSED_SCALE = 1.08f

/**
 * A focused poster or thumbnail grows together with its title, so nothing covers the text underneath,
 * and gets a ring drawn inside its edge (see [InsetRing]).
 */
@Composable
private fun Modifier.focusLift(focused: Boolean): Modifier {
    val scale by animateFloatAsState(if (focused) FOCUSED_SCALE else 1f, label = "focus")
    return graphicsLayer { scaleX = scale; scaleY = scale }.zIndex(if (focused) 1f else 0f)
}

/** Dark outer line for the grey background, white inner line for dark artwork. */
@Composable
private fun BoxScope.InsetRing() {
    Box(
        Modifier.matchParentSize()
            .border(2.dp, Palette.Ink, cardShape)
            .padding(2.dp)
            .border(3.dp, Color.White, RoundedCornerShape(6.dp))
    )
}

@Composable
private fun Artwork(url: String?, fallback: String) {
    Box(Modifier.fillMaxSize().background(Palette.Placeholder), contentAlignment = Alignment.Center) {
        Text(
            fallback, modifier = Modifier.padding(10.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            color = Palette.Secondary, textAlign = TextAlign.Center, maxLines = 3,
        )
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
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
        Icon(Icons.Check, contentDescription = "Watched", tint = Palette.Ink, modifier = Modifier.size(10.dp))
    }
}

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
    modifier: Modifier,
) {
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth().aspectRatio(aspect),
        shape = CardDefaults.shape(cardShape),
        // The whole card and its title grow instead (focusLift).
        scale = CardDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 0.97f),
        border = CardDefaults.border(focusedBorder = Border.None),
        colors = CardDefaults.colors(containerColor = Palette.Placeholder),
        interactionSource = interaction,
    ) {
        Box(Modifier.fillMaxSize()) {
            Artwork(url, fallback)
            if (progress > 0f) Progress(progress, Modifier.align(Alignment.BottomCenter).padding(horizontal = 10.dp, vertical = 9.dp).fillMaxWidth())
            if (watched) WatchedBadge(Modifier.align(Alignment.TopEnd).padding(7.dp))
            if (badge != null) {
                Text(
                    badge,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(7.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                    fontSize = 8.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                )
            }
            if (focused) InsetRing()
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
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(Modifier.focusLift(focused), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ArtCard(imageUrl, title, 2f / 3f, progress, watched, null, onClick, onLongClick, interaction, focused, modifier)
        Text(title, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Palette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp))
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
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(
        modifier = (if (width != null) Modifier.width(width) else Modifier).focusLift(focused),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        ArtCard(imageUrl, title, 16f / 9f, progress, watched, badge, onClick, onLongClick, interaction, focused, modifier)
        Column(Modifier.padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            val small: @Composable (String) -> Unit = { Text(it, fontSize = 9.5.sp, color = Palette.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            val big: @Composable (String) -> Unit = { Text(it, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (subtitleFirst) { subtitle?.let { small(it) }; big(title) } else { big(title); subtitle?.let { small(it) } }
        }
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
            focusedContentColor = Color.White,
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
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = ButtonDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ButtonDefaults.scale(focusedScale = 1.04f),
        colors = ButtonDefaults.colors(
            containerColor = Palette.Control,
            contentColor = Palette.Ink,
            focusedContainerColor = Palette.Ink,
            focusedContentColor = Color.White,
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
            focusedContentColor = Color.White,
            disabledContainerColor = Palette.Control.copy(alpha = 0.5f),
            disabledContentColor = Palette.Ink.copy(alpha = 0.3f),
        ),
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(16.dp))
    }
}

// --- Hold-OK menu ---

@Composable
fun ItemMenu(plex: PlexClient, item: Item, nav: Nav, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val bump = LocalRefreshBump.current
    val first = remember { FocusRequester() }
    val mark: (Boolean) -> Unit = { watched ->
        scope.launch {
            runCatching { plex.setWatched(item.ratingKey, watched) }
            bump()
            onDismiss()
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(320.dp).background(Palette.Tile, RoundedCornerShape(14.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(item.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, modifier = Modifier.padding(start = 4.dp, bottom = 7.dp))
            if (item.type != "show") {
                PillButton("Play from beginning", { onDismiss(); nav.play(listOf(item.ratingKey), fromStart = true) }, Modifier.fillMaxWidth().focusRequester(first))
            }
            PillButton("Mark as watched", { mark(true) }, Modifier.fillMaxWidth().then(if (item.type == "show") Modifier.focusRequester(first) else Modifier))
            PillButton("Mark as unwatched", { mark(false) }, Modifier.fillMaxWidth())
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { first.requestFocus() }
        }
    }
}

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
