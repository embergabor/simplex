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

object Palette {
    val Ground = Color(0xFFF2F2F4)
    val Ink = Color(0xFF1C1C1E)
    val Secondary = Color(0xFF5F5F66)
    val Tile = Color(0xFFFFFFFF)
    val Control = Color(0xFFE1E1E6)
    val Divider = Color(0xFFD3D3D8)
    val Placeholder = Color(0xFFD8D8DE)
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
