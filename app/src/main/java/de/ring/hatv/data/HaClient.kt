package de.ring.hatv.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

enum class ConnStatus { Connecting, Connected, Disconnected, AuthFailed }

class HaException(message: String) : Exception(message)

/**
 * Minimaler Client für die Home-Assistant-WebSocket-API.
 * - Authentifizierung mit Long-Lived Access Token
 * - request/response über die Nachrichten-ID
 * - Live-Zustände über `subscribe_entities` (komprimiertes Diff-Format)
 * - automatischer Reconnect mit Backoff
 */
class HaClient(
    private val baseUrl: String,
    private val token: String,
    private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var ws: WebSocket? = null
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonElement>>()
    @Volatile private var ready = CompletableDeferred<Unit>()
    @Volatile private var subscriptionId = -1
    @Volatile private var backoff = 1000L
    @Volatile private var stopped = false

    private val stateMap = HashMap<String, EntityState>()
    private val _states = MutableStateFlow<Map<String, EntityState>>(emptyMap())
    val states: StateFlow<Map<String, EntityState>> = _states

    private val _status = MutableStateFlow(ConnStatus.Connecting)
    val status: StateFlow<ConnStatus> = _status

    fun start() {
        scope.launch { loop() }
    }

    fun close() {
        stopped = true
        ws?.close(1000, "bye")
    }

    private fun wsUrl(): String {
        val b = baseUrl.trim().trimEnd('/')
        val ws = when {
            b.startsWith("https://") -> "wss://" + b.removePrefix("https://")
            b.startsWith("http://") -> "ws://" + b.removePrefix("http://")
            else -> "ws://$b"
        }
        return "$ws/api/websocket"
    }

    private suspend fun loop() {
        while (scope.isActive && !stopped) {
            val closed = CompletableDeferred<Unit>()
            ready = CompletableDeferred()
            _status.value = ConnStatus.Connecting
            val req = Request.Builder().url(wsUrl()).build()
            ws = http.newWebSocket(req, Listener(closed))
            closed.await()
            pending.values.forEach { it.completeExceptionally(IOException("Verbindung getrennt")) }
            pending.clear()
            if (_status.value == ConnStatus.AuthFailed || stopped) return
            _status.value = ConnStatus.Disconnected
            delay(backoff)
            backoff = min(backoff * 2, 30_000L)
        }
    }

    private inner class Listener(private val closed: CompletableDeferred<Unit>) : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            when (msg.str("type")) {
                "auth_required" -> webSocket.send(
                    buildJsonObject {
                        put("type", "auth")
                        put("access_token", token)
                    }.toString()
                )
                "auth_ok" -> {
                    backoff = 1000L
                    _status.value = ConnStatus.Connected
                    ready.complete(Unit)
                    scope.launch { runCatching { subscribeEntities() } }
                }
                "auth_invalid" -> {
                    _status.value = ConnStatus.AuthFailed
                    webSocket.close(1000, "auth invalid")
                }
                "result" -> {
                    val id = msg["id"]?.jsonPrimitive?.intOrNull ?: return
                    val d = pending.remove(id) ?: return
                    val ok = msg["success"]?.jsonPrimitive?.contentOrNull == "true"
                    if (ok) d.complete(msg["result"] ?: JsonNull)
                    else {
                        val err = msg["error"] as? JsonObject
                        d.completeExceptionally(
                            HaException(err?.str("message") ?: err?.str("code") ?: "Fehler")
                        )
                    }
                }
                "event" -> {
                    val id = msg["id"]?.jsonPrimitive?.intOrNull
                    if (id == subscriptionId) (msg["event"] as? JsonObject)?.let { applyDiff(it) }
                }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closed.complete(Unit)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            closed.complete(Unit)
        }
    }

    /** Sendet einen Befehl und wartet auf das Ergebnis. */
    suspend fun call(type: String, build: JsonObjectBuilder.() -> Unit = {}): JsonElement {
        ready.await()
        return send(nextId.getAndIncrement(), type, build)
    }

    private suspend fun send(id: Int, type: String, build: JsonObjectBuilder.() -> Unit): JsonElement {
        val d = CompletableDeferred<JsonElement>()
        pending[id] = d
        val msg = buildJsonObject {
            put("id", id)
            put("type", type)
            build()
        }
        if (ws?.send(msg.toString()) != true) {
            pending.remove(id)
            throw IOException("Nicht verbunden")
        }
        return withTimeout(20_000) { d.await() }
    }

    private suspend fun subscribeEntities() {
        synchronized(stateMap) { stateMap.clear() }
        val id = nextId.getAndIncrement()
        subscriptionId = id
        send(id, "subscribe_entities") {}
    }

    suspend fun callService(
        domain: String,
        service: String,
        target: JsonObject?,
        data: JsonObject?,
    ) {
        call("call_service") {
            put("domain", domain)
            put("service", service)
            if (data != null) put("service_data", data)
            if (target != null) put("target", target)
        }
    }

    /**
     * Format von subscribe_entities:
     * a = hinzugefügt {entity_id: {s, a, c, lc, lu}}
     * c = geändert   {entity_id: {"+": {s?, a?}, "-": {a: [keys]}}}
     * r = entfernt   [entity_id]
     */
    private fun applyDiff(ev: JsonObject) {
        synchronized(stateMap) {
            (ev["a"] as? JsonObject)?.forEach { (id, v) ->
                val o = v as? JsonObject ?: return@forEach
                stateMap[id] = EntityState(
                    entityId = id,
                    state = o.str("s") ?: "unknown",
                    attributes = (o["a"] as? JsonObject) ?: JsonObject(emptyMap()),
                )
            }
            (ev["c"] as? JsonObject)?.forEach { (id, v) ->
                val old = stateMap[id] ?: return@forEach
                val o = v as? JsonObject ?: return@forEach
                var s = old.state
                var attrs: Map<String, JsonElement> = old.attributes
                (o["+"] as? JsonObject)?.let { plus ->
                    plus.str("s")?.let { s = it }
                    (plus["a"] as? JsonObject)?.let { attrs = attrs + it }
                }
                (o["-"] as? JsonObject)?.get("a")?.jsonArray?.let { rm ->
                    val keys = rm.mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()
                    attrs = attrs.filterKeys { it !in keys }
                }
                stateMap[id] = old.copy(state = s, attributes = JsonObject(attrs))
            }
            (ev["r"])?.let { r ->
                runCatching { r.jsonArray }.getOrNull()?.forEach { e ->
                    e.jsonPrimitive.contentOrNull?.let { stateMap.remove(it) }
                }
            }
            _states.value = HashMap(stateMap)
        }
    }
}
