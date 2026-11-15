package de.ring.hatv.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

// ---------- JSON-Helfer ----------

fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

// ---------- Zustände ----------

data class EntityState(
    val entityId: String,
    val state: String,
    val attributes: JsonObject,
) {
    val domain: String get() = entityId.substringBefore('.')
    val friendlyName: String get() = attributes.str("friendly_name") ?: entityId.substringAfter('.')
    fun attr(key: String): Double? = attributes.num(key)
    fun attrStrings(key: String): List<String> =
        attributes.arr(key)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

    val isUnavailable: Boolean get() = state == "unavailable" || state == "unknown"

    fun isActive(): Boolean = state in ACTIVE_STATES ||
        (domain == "climate" && state != "off" && !isUnavailable)

    companion object {
        private val ACTIVE_STATES = setOf(
            "on", "open", "opening", "closing", "playing", "unlocked", "unlocking",
            "home", "cleaning", "armed_home", "armed_away", "armed_night", "triggered",
        )
    }
}

// ---------- Registries ----------

data class Area(val id: String, val name: String)
data class Device(val id: String, val name: String, val areaId: String?)
data class RegEntry(
    val entityId: String,
    val deviceId: String?,
    val areaId: String?,
    val hidden: Boolean,
    val category: String?,
)

class Registry(
    val areas: Map<String, Area>,
    val devices: Map<String, Device>,
    val entities: Map<String, RegEntry>,
    val errors: List<String> = emptyList(),
) {
    fun areaOf(entityId: String): String? {
        val r = entities[entityId] ?: return null
        return r.areaId ?: r.deviceId?.let { devices[it]?.areaId }
    }

    companion object {
        suspend fun load(client: HaClient): Registry {
            val errors = mutableListOf<String>()
            // Fehlt die Berechtigung (z. B. Nicht-Admin-Benutzer), läuft das Dashboard trotzdem
            // weiter, nur ohne Bereiche bzw. ohne Filter für versteckte Entitäten.
            suspend fun list(type: String): JsonArray = try {
                client.call(type).jsonArray
            } catch (e: Exception) {
                errors += "$type: ${e.message}"
                JsonArray(emptyList())
            }

            val areas = list("config/area_registry/list").mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val id = o.str("area_id") ?: return@mapNotNull null
                Area(id, o.str("name") ?: id)
            }.associateBy { it.id }

            val devices = list("config/device_registry/list").mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val id = o.str("id") ?: return@mapNotNull null
                Device(id, o.str("name_by_user") ?: o.str("name") ?: id, o.str("area_id"))
            }.associateBy { it.id }

            val entities = list("config/entity_registry/list").mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val id = o.str("entity_id") ?: return@mapNotNull null
                RegEntry(
                    entityId = id,
                    deviceId = o.str("device_id"),
                    areaId = o.str("area_id"),
                    hidden = o.str("hidden_by") != null || o.str("disabled_by") != null,
                    category = o.str("entity_category"),
                )
            }.associateBy { it.entityId }

            return Registry(areas, devices, entities, errors)
        }
    }
}

// ---------- UI-Modell ----------

sealed interface UiAction {
    data class Open(val pageId: String) : UiAction
    data class MoreInfo(val entityId: String) : UiAction
    data class Toggle(val entityId: String) : UiAction
    data class Service(
        val domain: String,
        val service: String,
        val entityId: String? = null,
        val data: JsonObject? = null,
        val target: JsonObject? = null,
    ) : UiAction
    data object Settings : UiAction
    data object None : UiAction
}

data class Tile(
    val key: String,
    val title: String,
    val icon: String,
    val stateLine: String? = null,
    val active: Boolean = false,
    val unavailable: Boolean = false,
    val entityId: String? = null,
    val tap: UiAction,
    val hold: UiAction? = null,
    val double: UiAction? = null,
)

data class Section(val title: String?, val tiles: List<Tile>)
data class Page(val id: String, val title: String, val sections: List<Section>)

/** Ein Eintrag im Navigationsstapel. Merkt sich die zuletzt fokussierte Karte. */
class NavEntry(val pageId: String) {
    var focusKey: String? = null
}

data class Config(val url: String, val token: String, val accent: String = "#03A9F4")

internal fun JsonElement?.asObj(): JsonObject? = runCatching { this?.jsonObject }.getOrNull()
