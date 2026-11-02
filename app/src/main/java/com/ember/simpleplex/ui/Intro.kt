package com.ember.simpleplex.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Set by MainActivity on any key, and once the opening has played: it plays once per start of the app. */
var introSkipped by mutableStateOf(false)

/**
 * The opening, about 1.2 s: the two play buttons of the x fly in and click together, "simple" slides
 * in beside them, then the camera dives through the gap of the x into Home. Drawn over the app, which
 * loads underneath and takes keys all along; never focusable.
 */
@Composable
fun Intro() {
    if (introSkipped) return
    val time = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        time.animateTo(1190f, tween(1190, easing = LinearEasing))
        introSkipped = true
    }
    val ink = Palette.Ink
    val ground = Palette.Ground
    Canvas(Modifier.fillMaxSize()) {
        val t = time.value
        fun span(from: Float, to: Float, easing: Easing = FastOutSlowInEasing) =
            easing.transform(((t - from) / (to - from)).coerceIn(0f, 1f))

        drawRect(ground, alpha = 1f - span(952f, 1190f, LinearEasing))

        val u = 90.dp.toPx() / 100 // pixels per wordmark unit (1 em = 100)
        val origin = Offset(center.x - Wordmark.WIDTH / 2 * u, center.y + 36.5f * u) // left end of the baseline
        val gap = origin + Offset((Wordmark.X_AT + 20 * Wordmark.X_SCALE) * u, (Wordmark.X_TOP + 18 * Wordmark.X_SCALE) * u)
        val zoom = 1f + 13f * span(850f, 1190f, CubicBezierEasing(0.55f, 0f, 0.8f, 0.2f))
        val alpha = 1f - span(1054f, 1190f, LinearEasing)

        scale(zoom, pivot = gap) {
            translate(origin.x, origin.y) {
                scale(u, pivot = Offset.Zero) {
                    val word = span(408f, 748f, LinearOutSlow)
                    translate(15f * (1f - word)) {
                        drawPath(Wordmark.sim, ink, alpha * word)
                        drawPath(Wordmark.ple, Palette.Plex, alpha * word)
                    }
                    // The halves fly in from a third of the screen away, overshoot a little and click.
                    val far = size.width / 3 / u
                    val apart = if (t < 408f) far * (1f - span(0f, 408f, Fly)) - 2.5f * span(0f, 408f, Fly)
                    else -2.5f * (1f - span(408f, 510f, Fly))
                    val click = 1f + 0.18f * if (t < 493f) span(408f, 493f, LinearOutSlow) else 1f - span(493f, 612f, LinearOutSlow)
                    val shown = alpha * span(0f, 136f, LinearEasing)
                    translate(Wordmark.X_AT, Wordmark.X_TOP) {
                        scale(Wordmark.X_SCALE * click, pivot = Offset(20f, 18f)) {
                            drawHalf(Wordmark.left, Offset(-apart, 0f), Palette.Plex, shown)
                            drawHalf(Wordmark.right, Offset(apart, 0f), PlexDeep, shown)
                        }
                    }
                }
            }
        }
    }
}

private val Fly = CubicBezierEasing(0.2f, 0.8f, 0.3f, 1f)
private val LinearOutSlow = CubicBezierEasing(0f, 0f, 0.58f, 1f)
private val PlexDeep = Color(0xFFB9800A)

private fun DrawScope.drawHalf(path: Path, by: Offset, color: Color, alpha: Float) = translate(by.x, by.y) {
    drawPath(path, color, alpha)
    drawPath(path, color, alpha, Stroke(width = 4f, join = StrokeJoin.Round))
}

/** The "simplex" wordmark: Inter SemiBold letters from tools/wordmark.py, 100 units to the em, baseline at 0. */
private object Wordmark {
    val sim = path("M27.39 1.12Q21.09 1.12 16.26 -0.68Q11.43 -2.49 8.33 -5.96Q5.22 -9.42 4.3 -14.36L16.16 -16.6Q17.29 -12.55 20.14 -10.57Q23 -8.59 27.64 -8.59Q32.32 -8.59 35.08 -10.47Q37.84 -12.35 37.84 -15.14Q37.84 -17.48 36.04 -19.02Q34.23 -20.56 30.47 -21.39L21.14 -23.39Q13.43 -25.05 9.62 -28.86Q5.81 -32.67 5.81 -38.67Q5.81 -43.75 8.59 -47.46Q11.38 -51.17 16.33 -53.22Q21.29 -55.27 27.83 -55.27Q34.03 -55.27 38.5 -53.52Q42.97 -51.76 45.73 -48.61Q48.49 -45.46 49.56 -41.21L38.23 -38.96Q37.3 -41.85 34.86 -43.82Q32.42 -45.8 27.98 -45.8Q23.93 -45.8 21.22 -44.02Q18.51 -42.24 18.51 -39.45Q18.51 -37.06 20.31 -35.5Q22.12 -33.94 26.27 -33.06L35.55 -31.1Q43.31 -29.44 47.07 -25.78Q50.83 -22.12 50.83 -16.36Q50.83 -11.18 47.85 -7.25Q44.87 -3.32 39.58 -1.1Q34.28 1.12 27.39 1.12ZM58.17 0V-54.59H70.87V0ZM64.52 -62.3Q61.49 -62.3 59.32 -64.36Q57.14 -66.41 57.14 -69.24Q57.14 -72.17 59.32 -74.19Q61.49 -76.22 64.52 -76.22Q67.59 -76.22 69.77 -74.19Q71.94 -72.17 71.94 -69.29Q71.94 -66.41 69.77 -64.36Q67.59 -62.3 64.52 -62.3ZM80.84 0V-54.59H92.76L93.24 -41.46H92.32Q93.49 -46.34 95.95 -49.41Q98.42 -52.49 101.72 -53.98Q105.01 -55.47 108.62 -55.47Q114.53 -55.47 118.17 -51.76Q121.81 -48.05 123.32 -40.82H121.81Q122.93 -45.7 125.64 -48.95Q128.35 -52.2 132.09 -53.83Q135.82 -55.47 140.07 -55.47Q145 -55.47 148.91 -53.32Q152.81 -51.17 155.11 -47.05Q157.4 -42.92 157.4 -36.82V0H144.71V-34.86Q144.71 -39.94 141.93 -42.29Q139.14 -44.63 135.24 -44.63Q132.21 -44.63 129.96 -43.31Q127.72 -41.99 126.52 -39.67Q125.32 -37.35 125.32 -34.28V0H112.92V-35.45Q112.92 -39.6 110.33 -42.11Q107.75 -44.63 103.69 -44.63Q100.91 -44.63 98.59 -43.36Q96.27 -42.09 94.9 -39.6Q93.54 -37.11 93.54 -33.45V0Z")
    val ple = path("M167.38 20.41V-54.59H179.78V-45.56H180.61Q181.59 -47.51 183.4 -49.76Q185.2 -52 188.3 -53.64Q191.4 -55.27 196.24 -55.27Q202.59 -55.27 207.66 -52.05Q212.74 -48.83 215.75 -42.55Q218.75 -36.28 218.75 -27.2Q218.75 -18.21 215.82 -11.91Q212.89 -5.62 207.79 -2.32Q202.68 0.98 196.19 0.98Q191.55 0.98 188.43 -0.61Q185.3 -2.2 183.45 -4.44Q181.59 -6.69 180.61 -8.64H180.08V20.41ZM192.77 -9.47Q197.02 -9.47 199.9 -11.79Q202.78 -14.11 204.25 -18.14Q205.71 -22.17 205.71 -27.25Q205.71 -32.32 204.27 -36.3Q202.83 -40.28 199.95 -42.55Q197.07 -44.82 192.77 -44.82Q188.57 -44.82 185.69 -42.63Q182.81 -40.43 181.32 -36.5Q179.83 -32.57 179.83 -27.25Q179.83 -21.92 181.32 -17.92Q182.81 -13.92 185.72 -11.69Q188.62 -9.47 192.77 -9.47ZM238.98 -72.75V0H226.28V-72.75ZM273.08 1.12Q264.82 1.12 258.89 -2.34Q252.96 -5.81 249.76 -12.11Q246.56 -18.41 246.56 -26.95Q246.56 -35.4 249.71 -41.75Q252.86 -48.1 258.65 -51.68Q264.43 -55.27 272.25 -55.27Q277.23 -55.27 281.74 -53.66Q286.26 -52.05 289.75 -48.68Q293.24 -45.31 295.22 -40.11Q297.2 -34.91 297.2 -27.73V-23.73H252.57V-32.37H290.9L284.94 -29.83Q284.94 -34.42 283.53 -37.92Q282.11 -41.41 279.3 -43.36Q276.49 -45.31 272.29 -45.31Q268.1 -45.31 265.17 -43.33Q262.24 -41.36 260.7 -38.06Q259.16 -34.77 259.16 -30.71V-24.76Q259.16 -19.58 260.92 -16.02Q262.68 -12.45 265.85 -10.64Q269.02 -8.84 273.22 -8.84Q276.01 -8.84 278.28 -9.64Q280.55 -10.45 282.21 -12.04Q283.87 -13.62 284.7 -15.92L296.37 -13.53Q295.05 -9.13 291.88 -5.83Q288.7 -2.54 283.94 -0.71Q279.18 1.12 273.08 1.12Z")
    /** The x: two play buttons on a 40×36 grid, tips meeting at (20, 18). */
    val left = path("M3,3 L18,18 L3,33 Z")
    val right = path("M37,3 L22,18 L37,33 Z")
    const val X_AT = 300.85f
    const val X_TOP = -55.9f
    const val X_SCALE = 1.55f
    const val WIDTH = X_AT + 40 * X_SCALE

    private fun path(d: String) = PathParser().parsePathString(d).toPath()
}

/** Plex's chevron, [s] wide and high either side of [at]. */
internal fun DrawScope.drawChevron(at: Offset, s: Float, color: Color, alpha: Float) {
    val path = Path().apply {
        moveTo(at.x - s, at.y - s); lineTo(at.x - 0.1f * s, at.y - s); lineTo(at.x + s, at.y)
        lineTo(at.x - 0.1f * s, at.y + s); lineTo(at.x - s, at.y + s); lineTo(at.x + 0.1f * s, at.y); close()
    }
    drawPath(path, color, alpha)
}
