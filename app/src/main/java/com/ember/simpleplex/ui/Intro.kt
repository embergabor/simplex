package com.ember.simpleplex.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp

/**
 * The opening, about 0.8 s: the TV settles in, a Plex chevron flies into its screen, then it all
 * fades away. Drawn over the app, which loads underneath and takes keys all along; never focusable.
 */
@Composable
fun Intro() {
    var done by rememberSaveable { mutableStateOf(false) }
    if (done) return
    val time = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        time.animateTo(1f, tween(800, easing = LinearEasing))
        done = true
    }
    val tv = rememberVectorPainter(Icons.Tv)
    val ink = Palette.Ink
    val ground = Palette.Ground
    Canvas(Modifier.fillMaxSize()) {
        val p = time.value
        fun phase(from: Float, to: Float) = FastOutSlowInEasing.transform(((p - from) / (to - from)).coerceIn(0f, 1f))
        val appear = phase(0f, 0.35f)
        val fly = phase(0.1f, 0.55f)
        val out = phase(0.7f, 1f)
        val alpha = 1f - out

        drawRect(ground, alpha = alpha)
        val side = 120.dp.toPx() * (0.85f + 0.15f * appear + 0.1f * out)
        translate(center.x - side / 2, center.y - side / 2) {
            with(tv) { draw(Size(side, side), alpha = appear * alpha, colorFilter = ColorFilter.tint(ink)) }
        }
        // The TV's screen is centred at (12, 11.5) of its 24-unit drawing.
        val screen = center + Offset(0f, -side / 48)
        val at = lerp(Offset(-side, screen.y), screen, fly)
        drawChevron(at, side * 0.14f, Palette.Plex, alpha * if (p > 0.1f) 1f else 0f)
    }
}

/** Plex's chevron, [s] wide and high either side of [at]. */
internal fun DrawScope.drawChevron(at: Offset, s: Float, color: Color, alpha: Float) {
    val path = Path().apply {
        moveTo(at.x - s, at.y - s); lineTo(at.x - 0.1f * s, at.y - s); lineTo(at.x + s, at.y)
        lineTo(at.x - 0.1f * s, at.y + s); lineTo(at.x - s, at.y + s); lineTo(at.x + 0.1f * s, at.y); close()
    }
    drawPath(path, color, alpha)
}
