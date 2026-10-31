package com.ember.simpleplex.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.lightColorScheme

/**
 * A constant mid grey rather than white: a bright full screen makes the TV keep adjusting its
 * brightness. Nothing on screen is pure white except the focus ring and text on dark.
 */
object Palette {
    val Ground = Color(0xFFBEBEC3)
    val Ink = Color(0xFF1C1C1E)
    val Secondary = Color(0xFF46464C)
    val Tile = Color(0xFFDCDCE0)
    val Control = Color(0xFFCDCDD2)
    val Divider = Color(0xFFA4A4AA)
    val Placeholder = Color(0xFFADADB3)
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Palette.Ink,
            onPrimary = Color.White,
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
    val Edit = stroke("M4 20h4L19 9l-4-4L4 16z M14 6l4 4")
    val Settings = stroke("M4 7h9 M17 7h3 M15 4.5v5 M4 17h3 M11 17h9 M9 14.5v5", width = 1.8f)
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
