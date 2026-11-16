package io.nachklang.hatv.ui

import io.nachklang.hatv.R
import io.nachklang.hatv.Res

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.nachklang.hatv.data.EntityState
import io.nachklang.hatv.data.UiAction
import io.nachklang.hatv.data.defaultTap
import io.nachklang.hatv.data.domainIcon
import io.nachklang.hatv.data.fmt
import io.nachklang.hatv.data.stateText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Ein mit links/rechts einstellbarer Wert (Helligkeit, Position, Temperatur, Lautstärke …). */
class ValueControl(
    val label: String,
    val min: Double,
    val max: Double,
    val step: Double,
    val unit: String,
    val read: (EntityState) -> Double?,
    val write: (String, Double) -> UiAction,
) {
    companion object {
        fun forEntity(s: EntityState): ValueControl? = when (s.domain) {
            "light" -> {
                val modes = s.attrStrings("supported_color_modes")
                if (modes.isNotEmpty() && modes.all { it == "onoff" }) null
                else ValueControl(Res.s(R.string.d_brightness), 0.0, 100.0, 10.0, "%",
                    read = { st -> st.attr("brightness")?.let { it / 255.0 * 100 } ?: if (st.state == "on") 100.0 else 0.0 },
                    write = { id, v -> UiAction.Service("light", "turn_on", id, buildJsonObject { put("brightness_pct", v.toInt()) }) })
            }
            "cover" -> if (s.attr("current_position") == null) null else
                ValueControl("Position", 0.0, 100.0, 10.0, "%",
                    read = { it.attr("current_position") },
                    write = { id, v -> UiAction.Service("cover", "set_cover_position", id, buildJsonObject { put("position", v.toInt()) }) })
            "climate" -> if (s.attr("temperature") == null) null else
                ValueControl(Res.s(R.string.d_target_temp), s.attr("min_temp") ?: 7.0, s.attr("max_temp") ?: 30.0,
                    s.attr("target_temp_step") ?: 0.5, "°C",
                    read = { it.attr("temperature") },
                    write = { id, v -> UiAction.Service("climate", "set_temperature", id, buildJsonObject { put("temperature", v) }) })
            "media_player" -> if (s.attr("volume_level") == null) null else
                ValueControl(Res.s(R.string.d_volume), 0.0, 100.0, 5.0, "%",
                    read = { st -> st.attr("volume_level")?.let { it * 100 } },
                    write = { id, v -> UiAction.Service("media_player", "volume_set", id, buildJsonObject { put("volume_level", v / 100.0) }) })
            "fan" -> if (s.attr("percentage") == null) null else
                ValueControl(Res.s(R.string.d_level), 0.0, 100.0, s.attr("percentage_step") ?: 10.0, "%",
                    read = { it.attr("percentage") },
                    write = { id, v -> UiAction.Service("fan", "set_percentage", id, buildJsonObject { put("percentage", v.toInt()) }) })
            "input_number", "number" ->
                ValueControl(Res.s(R.string.d_value), s.attr("min") ?: 0.0, s.attr("max") ?: 100.0, s.attr("step") ?: 1.0,
                    s.attributes["unit_of_measurement"]?.let { (it as? JsonPrimitive)?.contentOrNull } ?: "",
                    read = { it.state.toDoubleOrNull() },
                    write = { id, v -> UiAction.Service(id.substringBefore('.'), "set_value", id, buildJsonObject { put("value", v) }) })
            else -> null
        }
    }
}

/** Schaltflächen der Detailansicht je Domain. */
fun buttonsFor(s: EntityState): List<Pair<String, UiAction>> {
    val id = s.entityId
    fun svc(d: String, service: String) = UiAction.Service(d, service, id)
    return when (s.domain) {
        "light", "switch", "fan", "input_boolean", "siren", "humidifier" ->
            listOf(Res.s(R.string.b_on) to svc(s.domain, "turn_on"), Res.s(R.string.b_off) to svc(s.domain, "turn_off"))
        "automation" -> listOf(Res.s(R.string.b_on) to svc("automation", "turn_on"), Res.s(R.string.b_off) to svc("automation", "turn_off"),
            Res.s(R.string.b_trigger) to svc("automation", "trigger"))
        "cover" -> listOf(Res.s(R.string.b_open) to svc("cover", "open_cover"), Res.s(R.string.b_stop) to svc("cover", "stop_cover"),
            Res.s(R.string.b_close) to svc("cover", "close_cover"))
        "media_player" -> listOf("⏯ Play/Pause" to svc("media_player", "media_play_pause"),
            "⏮" to svc("media_player", "media_previous_track"), "⏭" to svc("media_player", "media_next_track"),
            Res.s(R.string.b_toggle) to svc("media_player", "toggle"))
        "lock" -> listOf(Res.s(R.string.b_lock) to svc("lock", "lock"), Res.s(R.string.b_unlock) to svc("lock", "unlock"))
        "climate" -> s.attrStrings("hvac_modes").map { m ->
            hvacLabel(m) to UiAction.Service("climate", "set_hvac_mode", id, buildJsonObject { put("hvac_mode", m) })
        }
        "scene", "script" -> listOf(Res.s(R.string.b_run) to svc(s.domain, "turn_on"))
        "button", "input_button" -> listOf(Res.s(R.string.b_press) to svc(s.domain, "press"))
        "vacuum" -> listOf("Start" to svc("vacuum", "start"), "Pause" to svc("vacuum", "pause"),
            Res.s(R.string.b_dock) to svc("vacuum", "return_to_base"))
        "input_select", "select" -> s.attrStrings("options").map { o ->
            o to UiAction.Service(s.domain, "select_option", id, buildJsonObject { put("option", o) })
        }
        else -> emptyList()
    }
}

private fun hvacLabel(m: String) = when (m) {
    "off" -> Res.s(R.string.b_off); "heat" -> Res.s(R.string.h_heat); "cool" -> Res.s(R.string.h_cool); "auto" -> "Auto"
    "heat_cool" -> Res.s(R.string.h_heat_cool); "dry" -> Res.s(R.string.h_dry); "fan_only" -> Res.s(R.string.h_fan); else -> m
}

private val HIDDEN_ATTRS = setOf(
    "friendly_name", "icon", "supported_features", "entity_picture", "supported_color_modes",
    "color_mode", "attribution", "hvac_modes", "options", "editable", "id",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    entityId: String,
    state: EntityState?,
    accent: Color,
    onAction: (UiAction) -> Unit,
) {
    if (state == null) {
        Box(Modifier.fillMaxSize().padding(48.dp)) { Text(Res.s(R.string.d_not_found, entityId), color = Muted) }
        return
    }
    val ctl = remember(entityId) { ValueControl.forEntity(state) }
    val buttons = buttonsFor(state)
    val first = remember { FocusRequester() }
    val guard = rememberFreshPressGuard()

    Column(
        Modifier.fillMaxSize().onPreviewKeyEvent(guard).verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 28.dp),
    ) {
        Text("${domainIcon(state.domain)}  ${state.friendlyName}", fontSize = 30.sp,
            fontWeight = FontWeight.SemiBold, color = Color.White)
        Text(stateText(state), fontSize = 18.sp, color = if (state.isActive()) ActiveText else Muted)
        Spacer(Modifier.height(28.dp))

        if (ctl != null) {
            ValueSlider(ctl, state, accent, first, onSet = onAction, onOk = { onAction(defaultTap(entityId)) })
            Spacer(Modifier.height(20.dp))
        }

        if (buttons.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                buttons.forEachIndexed { i, (label, action) ->
                    ActionButton(label, accent, if (ctl == null && i == 0) first else null) { onAction(action) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        val attrs = state.attributes.filterKeys { it !in HIDDEN_ATTRS }.entries.take(16)
        if (attrs.isNotEmpty()) {
            Text(Res.s(R.string.d_attributes), color = Muted, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            attrs.forEach { (k, v) ->
                val txt = (v as? JsonPrimitive)?.contentOrNull ?: v.toString()
                Text("$k: ${txt.take(120)}", color = Color(0xFFCFD5DC), fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(Res.s(R.string.d_back_hint), color = Muted, fontSize = 12.sp)
    }

    LaunchedEffect(entityId) {
        delay(60)
        runCatching { first.requestFocus() }
    }
}

@Composable
private fun ValueSlider(
    ctl: ValueControl,
    state: EntityState,
    accent: Color,
    focusRequester: FocusRequester,
    onSet: (UiAction) -> Unit,
    onOk: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val remote = ctl.read(state)
    var value by remember(state.entityId) { mutableStateOf(remote ?: ctl.min) }
    var editing by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }

    // Externe Änderungen übernehmen, solange nicht gerade eingestellt wird
    LaunchedEffect(remote) { if (!editing && remote != null) value = remote }

    fun adjust(dir: Int) {
        value = (value + dir * ctl.step).coerceIn(ctl.min, ctl.max)
        editing = true
        job?.cancel()
        job = scope.launch {
            delay(350) // erst senden, wenn die Taste kurz ruht
            onSet(ctl.write(state.entityId, value))
            delay(1500)
            editing = false
        }
    }

    Surface(
        onClick = onOk,
        modifier = Modifier
            .width(640.dp)
            .height(96.dp)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.key) {
                    Key.DirectionLeft -> { adjust(-1); true }
                    Key.DirectionRight -> { adjust(1); true }
                    else -> false
                }
            },
        shape = ClickableSurfaceDefaults.shape(TileShape),
        colors = ClickableSurfaceDefaults.colors(containerColor = TileColor, focusedContainerColor = TileFocused,
            contentColor = Color.White, focusedContentColor = Color.White),
        border = focusBorder(accent),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("${ctl.label}: ${fmt(value)} ${ctl.unit}   ◀ ▶ einstellen · OK schalten", fontSize = 16.sp)
            Spacer(Modifier.height(12.dp))
            val frac = ((value - ctl.min) / (ctl.max - ctl.min)).toFloat().coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(Color(0xFF39414B))) {
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(accent))
            }
        }
    }
}

@Composable
private fun ActionButton(label: String, accent: Color, focusRequester: FocusRequester?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .height(56.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        shape = ClickableSurfaceDefaults.shape(TileShape),
        colors = ClickableSurfaceDefaults.colors(containerColor = TileColor, focusedContainerColor = TileFocused,
            contentColor = Color.White, focusedContentColor = Color.White),
        border = focusBorder(accent),
        scale = focusScale(),
    ) {
        Box(Modifier.padding(horizontal = 22.dp, vertical = 16.dp)) { Text(label, fontSize = 16.sp) }
    }
}
