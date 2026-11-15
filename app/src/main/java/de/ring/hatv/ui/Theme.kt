package de.ring.hatv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults

val Background = Color(0xFF111418)
val TileColor = Color(0xFF1F242B)
val TileFocused = Color(0xFF2A313A)
val ActiveColor = Color(0xFF3A3220)
val ActiveFocused = Color(0xFF4A3F26)
val ActiveText = Color(0xFFFFC107)
val Muted = Color(0xFF9AA3AD)
val ErrorColor = Color(0xFFEF5350)

val TileShape = RoundedCornerShape(14.dp)

fun parseColor(hex: String, fallback: Color = Color(0xFF03A9F4)): Color = runCatching {
    Color(android.graphics.Color.parseColor(hex.trim()))
}.getOrDefault(fallback)

/** Rahmen, der nur an der aktiven (fokussierten) Karte erscheint. */
@Composable
fun focusBorder(accent: Color) = ClickableSurfaceDefaults.border(
    focusedBorder = Border(
        border = BorderStroke(3.dp, accent),
        shape = TileShape,
    ),
)

@Composable
fun focusScale() = ClickableSurfaceDefaults.scale(focusedScale = 1.05f)
