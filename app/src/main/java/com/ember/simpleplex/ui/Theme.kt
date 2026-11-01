package com.ember.simpleplex.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.lightColorScheme

/** Light, Dark, or Auto: dark between sunset and sunrise. Picked in the top menu, see [FollowTheme]. */
enum class ThemeMode { Light, Dark, Auto }

class Colors(
    val ground: Color, val ink: Color, val secondary: Color, val tile: Color,
    val control: Color, val divider: Color, val placeholder: Color, val onInk: Color,
    /** Under the focused card: a shadow on the light ground, a soft light glow on the dark one. */
    val shadow: Color,
) {
    /** Part way to [to], for the fade when the theme switches. */
    fun lerp(to: Colors, t: Float) = Colors(
        lerp(ground, to.ground, t), lerp(ink, to.ink, t), lerp(secondary, to.secondary, t), lerp(tile, to.tile, t),
        lerp(control, to.control, t), lerp(divider, to.divider, t), lerp(placeholder, to.placeholder, t),
        lerp(onInk, to.onInk, t), lerp(shadow, to.shadow, t),
    )
}

/**
 * A constant mid grey rather than white, and a dark grey rather than black: a bright or pure black
 * full screen makes the TV keep adjusting its brightness.
 * Each colour reads [current], so switching it recomposes every screen.
 */
object Palette {
    val Light = Colors(
        ground = Color(0xFFBEBEC3), ink = Color(0xFF1C1C1E), secondary = Color(0xFF46464C), tile = Color(0xFFDCDCE0),
        control = Color(0xFFCDCDD2), divider = Color(0xFFA4A4AA), placeholder = Color(0xFFADADB3), onInk = Color.White,
        shadow = Color.Black,
    )
    val Dark = Colors(
        ground = Color(0xFF1C1C1E), ink = Color(0xFFE6E6EA), secondary = Color(0xFF9A9AA0), tile = Color(0xFF2C2C2E),
        control = Color(0xFF3A3A3C), divider = Color(0xFF48484C), placeholder = Color(0xFF38383C), onInk = Color(0xFF1C1C1E),
        shadow = Color.White,
    )
    var current by mutableStateOf(Light)
    var mode by mutableStateOf(ThemeMode.Light)

    val Ground get() = current.ground
    val Ink get() = current.ink
    val Secondary get() = current.secondary
    val Tile get() = current.tile
    val Control get() = current.control
    val Divider get() = current.divider
    val Placeholder get() = current.placeholder
    /** Text and icons on [Ink], as on focused buttons. */
    val OnInk get() = current.onInk
    val Shadow get() = current.shadow
    /** The Kids button, the same in both themes, with dark text. */
    val Kids = Color(0xFFFFB340)
    /** Plex's orange, for the servers circling the TV while PlexVerse loads. */
    val Plex = Color(0xFFE5A00D)
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Palette.Ink,
            onPrimary = Palette.OnInk,
            background = Palette.Ground,
            onBackground = Palette.Ink,
            surface = Palette.Tile,
            onSurface = Palette.Ink,
        ),
        content = content,
    )
}

/** Stroke icons on a 24×24 grid, same drawings as the design canvas. */
object Icons {
    val Film = stroke("M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z M7 4v16 M17 4v16 M3 9h4 M3 15h4 M17 9h4 M17 15h4")
    val Tv = stroke("M4 5h16a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z M8 21h8 M12 18v3")
    val Folder = stroke("M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z")
    val Shuffle = stroke("M16 3h5v5 M4 20 21 3 M21 16v5h-5 M15 15l6 6 M4 4l5 5")
    val Check = stroke("m5 12.5 4.5 4.5L19 7.5", width = 2.4f)
    val Subtitles = stroke("M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z M7 12h3 M12 12h5 M7 15.5h6 M15 15.5h2")
    val Audio = stroke("M4 9v6h4l5 4V5L8 9z M16.5 8.5a5 5 0 0 1 0 7")
    val Search = stroke("M10.5 4a6.5 6.5 0 1 0 0 13 6.5 6.5 0 0 0 0-13z M15.5 15.5 20 20", width = 2f)
    val Move = stroke("M12 3v18 M8 7l4-4 4 4 M8 17l4 4 4-4")
    val Edit = stroke("M4 20h4L19 9l-4-4L4 16z M14 6l4 4")
    val Settings = stroke("M4 7h9 M17 7h3 M15 4.5v5 M4 17h3 M11 17h9 M9 14.5v5", width = 1.8f)
    val Sun = stroke("M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8z M12 2v2 M12 20v2 M4.9 4.9l1.4 1.4 M17.7 17.7l1.4 1.4 M2 12h2 M20 12h2 M4.9 19.1l1.4-1.4 M17.7 6.3l1.4-1.4")
    val Moon = stroke("M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z")
    val Sunset = stroke("M3 18h18 M7 18a5 5 0 0 1 10 0 M12 7v3 M5.6 11.6l1.4 1.4 M18.4 11.6 17 13 M9 21h6")
    val Smile = stroke("M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z M8.5 14a4 4 0 0 0 7 0 M9 9.5h.01 M15 9.5h.01", width = 2f)
    val Previous = fill("M5 5h2.6v14H5z M19.5 5v14L8.5 12z")
    val Next = fill("M16.4 5H19v14h-2.6z M4.5 5v14l11-7z")
    val Play = fill("M7 4.5v15l12.5-7.5z")
    val Pause = fill("M6 4h4v16H6z M14 4h4v16h-4z")

    private fun stroke(d: String, width: Float = 1.6f) = ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).addPath(
        pathData = addPathNodes(d),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ).build()

    private fun fill(d: String) = ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).addPath(pathData = addPathNodes(d), fill = SolidColor(Color.Black)).build()
}
