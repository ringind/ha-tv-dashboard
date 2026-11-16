package io.nachklang.hatv.data

import io.nachklang.hatv.R
import io.nachklang.hatv.Res

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/** Domains, die im automatisch erzeugten Dashboard nicht als Kachel erscheinen. */
val HIDDEN_DOMAINS = setOf(
    "zone", "persistent_notification", "update", "tts", "stt", "conversation", "wake_word",
    "assist_satellite", "event", "ai_task", "todo", "calendar", "image", "camera",
    "device_tracker", "tag", "notify", "text", "date", "time", "datetime",
)

/** Domains, die per Toggle schaltbar sind (für "x an"-Zähler). */
val TOGGLE_DOMAINS = setOf(
    "light", "switch", "fan", "input_boolean", "cover", "media_player", "siren",
    "humidifier", "valve", "lock", "climate",
)

private val ORDER = listOf(
    "light", "switch", "fan", "cover", "climate", "media_player", "lock", "vacuum",
    "scene", "script", "automation", "button", "input_button", "input_boolean",
    "input_number", "number", "input_select", "select", "humidifier", "water_heater",
    "valve", "siren", "alarm_control_panel", "person", "weather", "sun",
    "binary_sensor", "sensor",
)

fun domainOrder(d: String): Int = ORDER.indexOf(d).let { if (it < 0) ORDER.size else it }

fun domainLabel(d: String): String = when (d) {
    "light" -> Res.s(R.string.dom_light)
    "switch" -> Res.s(R.string.dom_switch)
    "fan" -> Res.s(R.string.dom_fan)
    "cover" -> Res.s(R.string.dom_cover)
    "climate" -> Res.s(R.string.dom_climate)
    "media_player" -> Res.s(R.string.dom_media)
    "lock" -> Res.s(R.string.dom_lock)
    "vacuum" -> Res.s(R.string.dom_vacuum)
    "scene" -> Res.s(R.string.dom_scene)
    "script" -> Res.s(R.string.dom_script)
    "automation" -> Res.s(R.string.dom_automation)
    "button", "input_button" -> Res.s(R.string.dom_button)
    "input_boolean" -> "Helfer (Schalter)"
    "input_number", "number" -> Res.s(R.string.dom_number)
    "input_select", "select" -> Res.s(R.string.dom_select)
    "humidifier" -> Res.s(R.string.dom_humidifier)
    "water_heater" -> Res.s(R.string.dom_water_heater)
    "valve" -> Res.s(R.string.dom_valve)
    "siren" -> Res.s(R.string.dom_siren)
    "alarm_control_panel" -> Res.s(R.string.dom_alarm)
    "person" -> Res.s(R.string.dom_person)
    "weather" -> Res.s(R.string.dom_weather)
    "sun" -> Res.s(R.string.dom_sun)
    "binary_sensor" -> Res.s(R.string.dom_binary_sensor)
    "sensor" -> Res.s(R.string.dom_sensor)
    else -> d.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

fun domainIcon(d: String): String = when (d) {
    "light" -> "💡"
    "switch", "input_boolean" -> "🔌"
    "fan" -> "🌀"
    "cover" -> "🪟"
    "climate", "water_heater" -> "🌡️"
    "media_player" -> "📺"
    "lock" -> "🔒"
    "vacuum" -> "🧹"
    "scene" -> "🎬"
    "script" -> "📜"
    "automation" -> "🤖"
    "button", "input_button" -> "🔘"
    "input_number", "number" -> "🔢"
    "input_select", "select" -> "📋"
    "person" -> "👤"
    "weather" -> "⛅"
    "sun" -> "☀️"
    "binary_sensor" -> "🔵"
    "sensor" -> "📈"
    "alarm_control_panel" -> "🛡️"
    "valve" -> "🚰"
    "humidifier" -> "💧"
    "siren" -> "🚨"
    else -> "▫️"
}

fun stateText(s: EntityState): String {
    val base = when (s.state) {
        "on" -> Res.s(R.string.st_on)
        "off" -> Res.s(R.string.st_off)
        "open" -> Res.s(R.string.st_open)
        "opening" -> Res.s(R.string.st_opening)
        "closed" -> Res.s(R.string.st_closed)
        "closing" -> Res.s(R.string.st_closing)
        "locked" -> Res.s(R.string.st_locked)
        "unlocked" -> Res.s(R.string.st_unlocked)
        "playing" -> Res.s(R.string.st_playing)
        "paused" -> Res.s(R.string.st_paused)
        "idle" -> Res.s(R.string.st_idle)
        "standby" -> "Standby"
        "home" -> Res.s(R.string.st_home)
        "not_home" -> Res.s(R.string.st_away)
        "unavailable" -> Res.s(R.string.st_unavailable)
        "unknown" -> Res.s(R.string.st_unknown)
        "heat" -> Res.s(R.string.st_heating)
        "cool" -> Res.s(R.string.st_cooling)
        "auto" -> Res.s(R.string.st_auto)
        "docked" -> Res.s(R.string.st_docked)
        "cleaning" -> Res.s(R.string.st_cleaning)
        else -> s.state
    }
    return when {
        s.domain == "light" && s.state == "on" ->
            s.attr("brightness")?.let { Res.s(R.string.st_on_pct, (it / 255.0 * 100).roundToInt()) } ?: base
        s.domain == "cover" && s.state == "open" ->
            s.attr("current_position")?.let { Res.s(R.string.st_open_pct, it.roundToInt()) } ?: base
        s.domain == "climate" ->
            listOfNotNull(base, s.attr("current_temperature")?.let { "${fmt(it)} °C" }).joinToString(" · ")
        s.domain == "media_player" && s.state == "playing" ->
            s.attributes.str("media_title")?.let { Res.s(R.string.st_playing_title, it) } ?: base
        else -> s.attributes.str("unit_of_measurement")?.let { "$base $it" } ?: base
    }
}

fun fmt(v: Double): String =
    if (v == v.roundToInt().toDouble()) v.roundToInt().toString() else String.format("%.1f", v)

/** Name ohne vorangestellten Bereichsnamen ("Wohnzimmer Deckenlicht" -> "Deckenlicht"). */
fun displayName(s: EntityState, stripPrefix: String?): String {
    val n = s.friendlyName
    if (stripPrefix.isNullOrBlank()) return n
    if (n.length > stripPrefix.length && n.startsWith(stripPrefix, ignoreCase = true)) {
        val rest = n.substring(stripPrefix.length).trimStart(' ', '-', ':', '_')
        if (rest.isNotBlank()) return rest.replaceFirstChar { it.uppercase() }
    }
    return n
}

// ---------- Standard-Aktionen je Domain ----------

/** OK-Taste: Standardfunktion */
fun defaultTap(entityId: String): UiAction = when (entityId.substringBefore('.')) {
    "light", "switch", "fan", "input_boolean", "automation", "cover", "media_player",
    "siren", "humidifier", "valve", "lock" -> UiAction.Toggle(entityId)
    "scene", "script" -> UiAction.Service(entityId.substringBefore('.'), "turn_on", entityId)
    "button", "input_button" -> UiAction.Service(entityId.substringBefore('.'), "press", entityId)
    "vacuum" -> UiAction.Service("vacuum", "start", entityId)
    else -> UiAction.MoreInfo(entityId)
}

/** Langer Druck: Detailansicht (wie in HA) */
fun defaultHold(entityId: String): UiAction = UiAction.MoreInfo(entityId)

/** Doppelklick: zweite Funktion */
fun defaultDouble(entityId: String, deviceId: String?): UiAction? = when (entityId.substringBefore('.')) {
    "light" -> UiAction.Service("light", "turn_on", entityId, buildJsonObject { put("brightness_pct", 100) })
    "cover" -> UiAction.Service("cover", "stop_cover", entityId)
    "media_player" -> UiAction.Service("media_player", "media_play_pause", entityId)
    "vacuum" -> UiAction.Service("vacuum", "return_to_base", entityId)
    "climate" -> UiAction.Toggle(entityId)
    else -> deviceId?.let { UiAction.Open("device:$it") }
}

/** Übersetzt ein Toggle in den passenden Service-Aufruf. */
fun toggleService(s: EntityState?, entityId: String): UiAction.Service {
    val d = entityId.substringBefore('.')
    return when (d) {
        "lock" -> UiAction.Service(d, if (s?.state == "locked") "unlock" else "lock", entityId)
        "climate" -> UiAction.Service(d, if (s?.state == "off") "turn_on" else "turn_off", entityId)
        "scene", "script" -> UiAction.Service(d, "turn_on", entityId)
        "button", "input_button" -> UiAction.Service(d, "press", entityId)
        else -> UiAction.Service(d, "toggle", entityId)
    }
}
