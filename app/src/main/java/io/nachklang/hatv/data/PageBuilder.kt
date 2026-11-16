package io.nachklang.hatv.data

import io.nachklang.hatv.R
import io.nachklang.hatv.Res

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Baut die Seiten (Ebenen) der App.
 *
 * Ohne gespeicherte Dashboard-Konfiguration (Standard-Dashboard von HA, automatisch erzeugt)
 * wird die Struktur wie im HA-Frontend aus Bereichen, Geräten und Entitäten abgeleitet:
 *   home            -> Bereiche + Entitäten ohne Bereich (nach Domain)
 *   area:<id>       -> Entitäten des Bereichs, gruppiert nach Domain
 *   domain:<d>      -> Entitäten ohne Bereich dieser Domain
 *   device:<id>     -> alle Entitäten eines Geräts
 *
 * Mit gespeicherter Konfiguration (Dashboard in HA "übernommen") werden Views und Karten gelesen:
 *   home            -> Liste der Views (oder direkt die einzige View)
 *   view:<index>    -> Karten der View, inkl. tap_action / hold_action / double_tap_action
 */
class PageBuilder(
    private val reg: Registry,
    private val lovelace: JsonObject?,
) {
    private val views: List<JsonObject> =
        lovelace?.arr("views")?.mapNotNull { it as? JsonObject }.orEmpty()

    fun build(id: String, states: Map<String, EntityState>): Page =
        if (views.isNotEmpty()) buildLovelace(id, states) else buildGenerated(id, states)

    // ------------------------------------------------------------------
    // Automatisch erzeugtes Dashboard
    // ------------------------------------------------------------------

    private fun visible(states: Map<String, EntityState>): List<EntityState> =
        states.values.filter { s ->
            val r = reg.entities[s.entityId]
            s.domain !in HIDDEN_DOMAINS && (r == null || (!r.hidden && r.category == null))
        }

    private fun buildGenerated(id: String, states: Map<String, EntityState>): Page {
        val vis = visible(states)
        return when {
            id == "home" -> {
                val byArea = vis.groupBy { reg.areaOf(it.entityId) }
                val areaTiles = reg.areas.values
                    .sortedBy { it.name.lowercase() }
                    .mapNotNull { a ->
                        val list = byArea[a.id] ?: return@mapNotNull null
                        areaTile(a, list)
                    }
                val unassigned = byArea[null].orEmpty().groupBy { it.domain }
                val domainTiles = unassigned.keys.sortedBy { domainOrder(it) }.map { d ->
                    val list = unassigned.getValue(d)
                    val on = list.count { it.domain in TOGGLE_DOMAINS && it.isActive() }
                    Tile(
                        key = "domain:$d",
                        title = domainLabel(d),
                        icon = domainIcon(d),
                        stateLine = countLine(list.size, on),
                        active = on > 0,
                        tap = UiAction.Open("domain:$d"),
                    )
                }
                Page(
                    "home", "Home",
                    listOfNotNull(
                        Section(Res.s(R.string.p_areas), areaTiles).takeIf { areaTiles.isNotEmpty() },
                        Section(Res.s(R.string.p_no_area), domainTiles).takeIf { domainTiles.isNotEmpty() },
                        Section("System", listOf(settingsTile())),
                    ),
                )
            }

            id.startsWith("area:") -> {
                val areaId = id.removePrefix("area:")
                val area = reg.areas[areaId]
                val list = vis.filter { reg.areaOf(it.entityId) == areaId }
                Page(id, area?.name ?: Res.s(R.string.p_area), byDomainSections(list, area?.name))
            }

            id.startsWith("domain:") -> {
                val d = id.removePrefix("domain:")
                val list = vis.filter { it.domain == d && reg.areaOf(it.entityId) == null }
                Page(id, domainLabel(d), byDomainSections(list, null))
            }

            id.startsWith("device:") -> {
                val devId = id.removePrefix("device:")
                val dev = reg.devices[devId]
                val list = vis.filter { reg.entities[it.entityId]?.deviceId == devId }
                // Doppelklick auf dem Geräte-Screen nicht erneut auf das Gerät zeigen lassen
                val sections = byDomainSections(list, dev?.name).map { sec ->
                    sec.copy(tiles = sec.tiles.map { t ->
                        if (t.double == UiAction.Open(id)) t.copy(double = null) else t
                    })
                }
                Page(id, dev?.name ?: Res.s(R.string.p_device), sections)
            }

            else -> Page(id, Res.s(R.string.p_unknown), emptyList())
        }
    }

    private fun areaTile(a: Area, list: List<EntityState>): Tile {
        val on = list.count { it.domain in TOGGLE_DOMAINS && it.isActive() }
        val temp = list.firstOrNull {
            it.domain == "sensor" && it.attributes.str("device_class") == "temperature" && !it.isUnavailable
        }
        val line = listOfNotNull(
            countLine(list.size, on),
            temp?.let { t -> t.state.toDoubleOrNull()?.let { "${fmt(it)} °C" } },
        ).joinToString(" · ")
        return Tile(
            key = "area:${a.id}",
            title = a.name,
            icon = "🏠",
            stateLine = line,
            active = on > 0,
            tap = UiAction.Open("area:${a.id}"),
        )
    }

    private fun countLine(total: Int, on: Int): String =
        Res.s(R.string.p_count, total) + if (on > 0) " " + Res.s(R.string.p_count_on, on) else ""

    private fun byDomainSections(list: List<EntityState>, stripPrefix: String?): List<Section> =
        list.groupBy { it.domain }
            .toSortedMap(compareBy<String> { domainOrder(it) }.thenBy { it })
            .map { (d, items) ->
                Section(
                    domainLabel(d),
                    items.sortedBy { displayName(it, stripPrefix).lowercase() }
                        .map { entityTile(it, stripPrefix) },
                )
            }

    fun entityTile(s: EntityState, stripPrefix: String?): Tile = Tile(
        key = s.entityId,
        title = displayName(s, stripPrefix),
        icon = domainIcon(s.domain),
        stateLine = stateText(s),
        active = s.isActive(),
        unavailable = s.isUnavailable,
        entityId = s.entityId,
        tap = defaultTap(s.entityId),
        hold = defaultHold(s.entityId),
        double = defaultDouble(s.entityId, reg.entities[s.entityId]?.deviceId),
    )

    private fun settingsTile() = Tile(
        key = "settings",
        title = Res.s(R.string.p_settings),
        icon = "⚙️",
        stateLine = Res.s(R.string.p_conn_color),
        tap = UiAction.Settings,
    )

    // ------------------------------------------------------------------
    // Gespeichertes Dashboard (Lovelace-Konfiguration)
    // ------------------------------------------------------------------

    private fun buildLovelace(id: String, states: Map<String, EntityState>): Page = when {
        id == "home" && views.size == 1 -> viewPage(0, "home", states, withSettings = true)
        id == "home" -> Page(
            "home",
            lovelace?.str("title") ?: "Home",
            listOf(
                Section(null, views.mapIndexed { i, v ->
                    Tile(
                        key = "view:$i",
                        title = v.str("title") ?: v.str("path") ?: "Ansicht ${i + 1}",
                        icon = "📋",
                        tap = UiAction.Open("view:$i"),
                    )
                }),
                Section("System", listOf(settingsTile())),
            ),
        )
        id.startsWith("view:") -> viewPage(id.removePrefix("view:").toIntOrNull() ?: 0, id, states, false)
        else -> buildGenerated(id, states) // device:, area:, domain: weiterhin automatisch
    }

    private fun viewPage(i: Int, pageId: String, states: Map<String, EntityState>, withSettings: Boolean): Page {
        val v = views.getOrNull(i) ?: return Page(pageId, Res.s(R.string.p_view), emptyList())
        val sections = mutableListOf<Section>()
        var current = mutableListOf<Tile>()
        var currentTitle: String? = null
        var n = 0

        fun flush() {
            if (current.isNotEmpty()) sections += Section(currentTitle, current.toList())
            current = mutableListOf()
            currentTitle = null
        }

        fun addEntity(conf: JsonObject?, entityId: String, name: String?) {
            val s = states[entityId] ?: return
            val base = entityTile(s, null)
            val holdConf = conf?.obj("hold_action")
            val doubleConf = conf?.obj("double_tap_action")
            current += base.copy(
                key = "${entityId}#${n++}",
                title = name ?: base.title,
                tap = parseAction(conf?.obj("tap_action"), entityId) ?: base.tap,
                hold = if (holdConf != null) parseAction(holdConf, entityId) else base.hold,
                double = if (doubleConf != null) parseAction(doubleConf, entityId) else base.double,
            )
        }

        fun walk(c: JsonObject) {
            when (val type = c.str("type")) {
                "vertical-stack", "horizontal-stack", "grid" -> {
                    c.str("title")?.let { flush(); currentTitle = it }
                    c.arr("cards")?.forEach { (it as? JsonObject)?.let { walk(it) } }
                }
                "conditional" -> c.obj("card")?.let { walk(it) }
                "heading" -> { flush(); currentTitle = c.str("heading") }
                "entities", "glance" -> {
                    flush()
                    currentTitle = c.str("title")
                    c.arr("entities")?.forEach { e ->
                        when (e) {
                            is JsonPrimitive -> e.contentOrNull?.let { addEntity(null, it, null) }
                            is JsonObject -> e.str("entity")?.let { addEntity(e, it, e.str("name")) }
                            else -> {}
                        }
                    }
                    flush()
                }
                else -> {
                    val ent = c.str("entity")
                    val area = c.str("area")
                    when {
                        ent != null -> addEntity(c, ent, c.str("name"))
                        area != null && reg.areas[area] != null -> {
                            val a = reg.areas.getValue(area)
                            val list = visible(states).filter { reg.areaOf(it.entityId) == area }
                            current += areaTile(a, list).copy(key = "area:$area#${n++}")
                        }
                        c.obj("tap_action") != null -> {
                            val act = parseAction(c.obj("tap_action"), null) ?: UiAction.None
                            current += Tile(
                                key = "card:${type}#${n++}",
                                title = c.str("name") ?: c.str("title") ?: Res.s(R.string.p_action),
                                icon = "🔘",
                                tap = act,
                                hold = parseAction(c.obj("hold_action"), null),
                                double = parseAction(c.obj("double_tap_action"), null),
                            )
                        }
                    }
                }
            }
        }

        v.arr("cards")?.forEach { (it as? JsonObject)?.let { walk(it) } }
        v.arr("sections")?.forEach { s ->
            flush()
            (s as? JsonObject)?.arr("cards")?.forEach { (it as? JsonObject)?.let { walk(it) } }
        }
        flush()
        if (withSettings) sections += Section("System", listOf(settingsTile()))
        return Page(pageId, v.str("title") ?: "Home", sections)
    }

    /** Wandelt eine HA-Aktion (tap_action etc.) um. null = keine Aktion. */
    private fun parseAction(a: JsonObject?, entityId: String?): UiAction? {
        if (a == null) return null
        val target = a.str("entity") ?: entityId
        return when (a.str("action")) {
            "none" -> null
            "toggle" -> target?.let { UiAction.Toggle(it) }
            "more-info" -> target?.let { UiAction.MoreInfo(it) }
            "navigate" -> navigate(a.str("navigation_path"))
            "perform-action", "call-service" -> {
                val svc = a.str("perform_action") ?: a.str("service") ?: return null
                val data = a.obj("data") ?: a.obj("service_data")
                val tgt = a.obj("target")
                    ?: if (data?.containsKey("entity_id") != true && entityId != null)
                        buildJsonObject { put("entity_id", entityId) } else null
                UiAction.Service(svc.substringBefore('.'), svc.substringAfter('.'), null, data, tgt)
            }
            else -> target?.let { defaultTap(it) }
        }
    }

    private fun navigate(path: String?): UiAction? {
        val seg = path?.trimEnd('/')?.substringAfterLast('/') ?: return null
        val idx = views.indexOfFirst { it.str("path") == seg }
            .takeIf { it >= 0 } ?: seg.toIntOrNull()?.takeIf { it in views.indices }
        return idx?.let { UiAction.Open("view:$it") }
    }
}
