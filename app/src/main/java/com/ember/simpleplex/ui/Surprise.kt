@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.ember.simpleplex.ui

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.ember.simpleplex.plex.Item
import com.ember.simpleplex.plex.PlexServer

/** Dark brown on the Kids orange, as on the Kids button. */
private val OnKids = Color(0xFF2A1A00)

/** Kids' Surprise me! card, the size of a Continue watching card: OK spins the reel and Simplex picks something. */
@Composable
fun SurpriseButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(Modifier.width(200.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Button(
            onClick = onClick,
            modifier = modifier.fillMaxWidth().aspectRatio(16f / 9f).semantics { contentDescription = "Surprise me" },
            contentPadding = PaddingValues(0.dp),
            shape = ButtonDefaults.shape(RoundedCornerShape(8.dp)),
            scale = ButtonDefaults.scale(focusedScale = 1.1f),
            glow = ButtonDefaults.glow(focusedGlow = Glow(Color(0xFFFF8C00).copy(alpha = 0.35f), 18.dp)),
            colors = ButtonDefaults.colors(
                containerColor = Palette.Kids, contentColor = OnKids,
                focusedContainerColor = Palette.Kids, focusedContentColor = OnKids,
            ),
        ) {
            Canvas(Modifier.fillMaxSize()) { drawDie() }
        }
        // As big as the two lines under the other cards.
        Text("Surprise me!", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Ink, maxLines = 1, modifier = Modifier.padding(horizontal = 2.dp))
    }
}

/**
 * A die in 3D, seen from above a corner: one on top, two on the left, three on the right. Each face is
 * drawn as a unit square mapped onto its side of the cube, so the pips foreshorten with it.
 */
private fun DrawScope.drawDie() {
    val s = size.minDimension * 0.36f
    val c = center
    val dx = s * 0.866f
    val top = Offset(c.x, c.y - s)
    val left = Offset(c.x - dx, c.y - s / 2)
    val right = Offset(c.x + dx, c.y - s / 2)
    val leftLow = Offset(c.x - dx, c.y + s / 2)
    val bottom = Offset(c.x, c.y + s)
    // The outline shows between the faces as seams.
    drawPath(Path().apply {
        moveTo(top.x, top.y); lineTo(right.x, right.y); lineTo(right.x, c.y + s / 2)
        lineTo(bottom.x, bottom.y); lineTo(leftLow.x, leftLow.y); lineTo(left.x, left.y); close()
    }, OnKids.copy(alpha = 0.85f))
    fun face(origin: Offset, u: Offset, v: Offset, color: Color, pips: List<Offset>) = withTransform({
        transform(Matrix(floatArrayOf(u.x, u.y, 0f, 0f, v.x, v.y, 0f, 0f, 0f, 0f, 1f, 0f, origin.x, origin.y, 0f, 1f)))
    }) {
        drawRoundRect(color, Offset(0.05f, 0.05f), Size(0.9f, 0.9f), CornerRadius(0.16f))
        pips.forEach { drawCircle(OnKids, 0.1f, it) }
    }
    face(left, top - left, c - left, Color.White, listOf(Offset(0.5f, 0.5f)))
    face(left, c - left, leftLow - left, Color(0xFFF1E4D4), listOf(Offset(0.28f, 0.28f), Offset(0.72f, 0.72f)))
    face(c, right - c, bottom - c, Color(0xFFD8C2A8), listOf(Offset(0.25f, 0.25f), Offset(0.5f, 0.5f), Offset(0.75f, 0.75f)))
}

private const val REEL = 16
private const val LANDS_AT = 12
private val CARD_W = 150.dp
private val CARD_H = 225.dp
private val GAP = 22.dp

/**
 * The surprise: a reel of posters spins and slows down on a random pick, then Play counts down for
 * four and a half seconds and starts it, unless focus moved to Spin again. A series plays a random episode.
 */
@Composable
fun Surprise(entries: List<VerseEntry>, connected: Map<String, PlexServer>, nav: Nav, onDismiss: () -> Unit) {
    // Only what a reached server can play.
    val playable = remember(entries, connected) { entries.filter { e -> e.sources.any { it.machineId in connected } } }
    if (playable.isEmpty()) return onDismiss()
    var spin by remember { mutableIntStateOf(0) }
    val reel = remember(spin) {
        val pick = playable.random()
        val others = (playable - pick).ifEmpty { listOf(pick) }
        List(REEL) { i -> if (i == LANDS_AT) pick else others.random() }
    }
    val pick = reel[LANDS_AT]
    val source = pick.sources.first { it.machineId in connected }
    val server = connected.getValue(source.machineId)
    val episode by produceState<Item?>(null, spin) {
        value = if (pick.item.type == "show") runCatching { server.episodes(source.item.ratingKey).randomOrNull() }.getOrNull() else null
    }
    val travel = remember(spin) { Animatable(0f) }
    var landed by remember(spin) { mutableStateOf(false) }
    LaunchedEffect(spin) {
        travel.animateTo(1f, tween(1900, delayMillis = 400, easing = CubicBezierEasing(0.12f, 0.7f, 0.18f, 1f)))
        landed = true
    }
    val canPlay = pick.item.type != "show" || episode != null
    fun play() {
        val entry = if (pick.item.type == "show") episode?.let { Copy(server, it).ref } else pick.sources.joinToString(",") { it.ref }
        if (entry != null) { onDismiss(); nav.play(listOf(entry)) }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Ground)) {
            Text(
                if (landed) "Surprise!" else "Spinning…", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Ink,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 33.dp),
            )

            // The reel: card i sits i steps right of the middle, and the whole strip slides left onto the pick.
            val step = CARD_W + GAP
            val width = maxWidth
            val dim by animateFloatAsState(if (landed) 0.25f else 1f, tween(400), label = "dim")
            val lift by animateFloatAsState(if (landed) 1.25f else 1f, tween(350), label = "lift")
            Box(Modifier.fillMaxWidth().padding(top = 128.dp).height(CARD_H)) {
                reel.forEachIndexed { i, entry ->
                    val x = width / 2 - CARD_W / 2 + step * i - step * LANDS_AT * travel.value
                    if (x < -CARD_W || x > width) return@forEachIndexed
                    val isPick = i == LANDS_AT
                    Box(
                        Modifier
                            .offset(x = x)
                            .size(CARD_W, CARD_H)
                            .graphicsLayer {
                                alpha = if (isPick) 1f else dim
                                if (isPick) { scaleX = lift; scaleY = lift }
                            }
                            .then(if (isPick && landed) Modifier.border(3.dp, Palette.Kids, RoundedCornerShape(16.dp)) else Modifier)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Palette.Placeholder),
                    ) {
                        Text(
                            entry.item.title, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Ink,
                            maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                        )
                        val s = entry.sources.firstOrNull { it.machineId in connected }
                        AsyncImage(
                            model = s?.let { connected[it.machineId]?.image(it.item.thumb, 248, 372) },
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = landed,
                enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 8 },
                modifier = Modifier.fillMaxWidth().padding(top = 405.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row {
                        Text(pick.item.title, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Ink)
                        episode?.let {
                            Text(" · Season ${it.parentIndex ?: 0}, Episode ${it.index ?: 0}", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Palette.Secondary)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        val first = remember { FocusRequester() }
                        var counting by remember(spin) { mutableStateOf(true) }
                        val ring = remember(spin) { Animatable(0f) }
                        LaunchedEffect(landed, canPlay) {
                            if (!landed || !canPlay) return@LaunchedEffect
                            withFrameNanos { }
                            runCatching { first.requestFocus() }
                        }
                        LaunchedEffect(landed, canPlay, counting) {
                            if (!landed || !canPlay || !counting) return@LaunchedEffect
                            ring.animateTo(1f, tween(4600, easing = LinearEasing))
                            play()
                        }
                        KidsButton(
                            onClick = ::play,
                            modifier = Modifier.focusRequester(first).onFocusChanged { if (!it.isFocused && ring.value > 0f) counting = false },
                            fill = Palette.Kids, content = OnKids,
                        ) {
                            // The ring runs down while Play counts to starting by itself.
                            Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                                Canvas(Modifier.fillMaxSize()) {
                                    val w = 2.5.dp.toPx()
                                    val r = size.minDimension / 2 - w / 2
                                    drawCircle(OnKids.copy(alpha = 0.25f), r, style = Stroke(w))
                                    if (counting) drawArc(OnKids, -90f, 360f * (1f - ring.value), false, Offset(w / 2, w / 2), Size(2 * r, 2 * r), style = Stroke(w))
                                }
                                Icon(Icons.Play, contentDescription = null, modifier = Modifier.size(12.dp))
                            }
                            Spacer(Modifier.width(10.dp))
                            Text("Play", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                        }
                        KidsButton(onClick = { spin++ }, fill = Palette.Control, content = Palette.Ink) {
                            Icon(Icons.Dice, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("Spin again", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KidsButton(onClick: () -> Unit, fill: Color, content: Color, modifier: Modifier = Modifier, label: @Composable () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        contentPadding = PaddingValues(horizontal = 22.dp),
        shape = ButtonDefaults.shape(CircleShape),
        scale = ButtonDefaults.scale(focusedScale = 1.06f),
        colors = ButtonDefaults.colors(
            containerColor = fill, contentColor = content,
            focusedContainerColor = if (fill == Palette.Kids) fill else Palette.Ink,
            focusedContentColor = if (fill == Palette.Kids) content else Palette.OnInk,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) { label() }
    }
}
