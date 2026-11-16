package io.nachklang.hatv.ui

import io.nachklang.hatv.R
import io.nachklang.hatv.Res

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.nachklang.hatv.data.NavEntry
import io.nachklang.hatv.data.Page
import io.nachklang.hatv.data.Tile
import io.nachklang.hatv.data.UiAction
import kotlinx.coroutines.delay

/**
 * Eine Ebene des Dashboards. Kacheln liegen in FlowRows, die Navigation mit dem
 * Steuerkreuz übernimmt das Compose-Fokussystem. Beim Betreten (auch per Zurück)
 * wird die zuletzt aktive Karte wieder fokussiert.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PageScreen(
    page: Page,
    entry: NavEntry,
    breadcrumb: String,
    accent: Color,
    onAction: (UiAction) -> Unit,
) {
    val requesters = remember(page.id) { mutableMapOf<String, FocusRequester>() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 28.dp),
    ) {
        if (breadcrumb.isNotEmpty()) Text(breadcrumb, color = Muted, fontSize = 14.sp)
        Text(page.title, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = Color.White)

        if (page.sections.all { it.tiles.isEmpty() }) {
            Spacer(Modifier.height(24.dp))
            Text(Res.s(R.string.s_empty), color = Muted)
        }

        page.sections.forEach { sec ->
            if (sec.tiles.isEmpty()) return@forEach
            Spacer(Modifier.height(20.dp))
            sec.title?.let {
                Text(it, color = Muted, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(10.dp))
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                sec.tiles.forEach { t ->
                    key(t.key) {
                        val fr = requesters.getOrPut(t.key) { FocusRequester() }
                        TileView(
                            tile = t,
                            accent = accent,
                            focusRequester = fr,
                            onFocused = { entry.focusKey = t.key },
                            onAction = onAction,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    // Fokus wiederherstellen bzw. erste Karte markieren
    LaunchedEffect(page.id) {
        delay(60)
        val first = page.sections.firstNotNullOfOrNull { it.tiles.firstOrNull() }?.key
        val k = entry.focusKey?.takeIf { it in requesters } ?: first
        k?.let { runCatching { requesters[it]?.requestFocus() } }
    }
}

@Composable
fun TileView(
    tile: Tile,
    accent: Color,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onAction: (UiAction) -> Unit,
) {
    val press = rememberPressHandler(
        onTap = { onAction(tile.tap) },
        onHold = tile.hold?.let { h -> { onAction(h) } },
        onDouble = tile.double?.let { d -> { onAction(d) } },
    )
    val on = tile.active

    Surface(
        // Touch/Maus-Fallback; Tasten werden vorher von onPreviewKeyEvent verarbeitet
        onClick = { onAction(tile.tap) },
        onLongClick = tile.hold?.let { h -> { onAction(h) } },
        modifier = Modifier
            .size(width = 230.dp, height = 116.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .onPreviewKeyEvent(press),
        shape = ClickableSurfaceDefaults.shape(TileShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (on) ActiveColor else TileColor,
            focusedContainerColor = if (on) ActiveFocused else TileFocused,
            contentColor = Color.White,
            focusedContentColor = Color.White,
        ),
        border = focusBorder(accent),
        scale = focusScale(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(tile.icon, fontSize = 24.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                tile.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (tile.unavailable) Muted else Color.White,
            )
            tile.stateLine?.let {
                Text(
                    it,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = when {
                        tile.unavailable -> Muted
                        on -> ActiveText
                        else -> Muted
                    },
                )
            }
        }
    }
}
