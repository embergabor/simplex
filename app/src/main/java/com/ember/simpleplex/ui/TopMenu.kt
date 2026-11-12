package com.ember.simpleplex.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import com.embergabor.simplex.core.ui.Palette
import com.embergabor.simplex.core.ui.Icons

/**
 * The menu at the top of Home: a capsule in the middle, tvOS style, with Search, Home and Settings.
 */
@Composable
fun TopMenu(current: Screen, nav: Nav, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            Modifier.background(Palette.Control.copy(alpha = 0.75f), CircleShape).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MenuItem(null, Icons.Search, "Search", selected = false) { nav.push(Screen.Search) }
            MenuItem("Home", null, null, current == Screen.Home) { nav.push(Screen.Home) }
            MenuItem(null, Icons.Settings, "Settings", selected = false) { nav.push(Screen.Settings) }
        }
    }
}

/** One capsule in [TopMenu]: underlined when it's the current screen, filled only when focused. */
@Composable
private fun MenuItem(label: String?, icon: ImageVector?, description: String?, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.height(32.dp),
        contentPadding = PaddingValues(horizontal = if (label == null) 12.dp else 15.dp),
        shape = ButtonDefaults.shape(CircleShape),
        scale = ButtonDefaults.scale(focusedScale = 1.1f),
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Palette.Ink,
            focusedContainerColor = Palette.Ink,
            focusedContentColor = Palette.OnInk,
        ),
    ) {
        val line = LocalContentColor.current
        Row(
            Modifier.drawBehind {
                if (selected) drawRoundRect(line, Offset(0f, size.height + 2.dp.toPx()), Size(size.width, 2.dp.toPx()), CornerRadius(1.dp.toPx()))
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) Icon(icon, contentDescription = description, modifier = Modifier.size(if (label == null) 15.dp else 13.dp))
            if (icon != null && label != null) Spacer(Modifier.width(6.dp))
            if (label != null) Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
