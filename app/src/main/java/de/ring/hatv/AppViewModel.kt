package de.ring.hatv

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.ring.hatv.data.Config
import de.ring.hatv.data.ConnStatus
import de.ring.hatv.data.EntityState
import de.ring.hatv.data.HaClient
import de.ring.hatv.data.NavEntry
import de.ring.hatv.data.Registry
import de.ring.hatv.data.UiAction
import de.ring.hatv.data.toggleService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("ha_tv", Context.MODE_PRIVATE)

    val config = MutableStateFlow(loadConfig())
    var showSetup by mutableStateOf(false)

    val status = MutableStateFlow(ConnStatus.Disconnected)
    val states = MutableStateFlow<Map<String, EntityState>>(emptyMap())
    val registry = MutableStateFlow<Registry?>(null)
    val lovelace = MutableStateFlow<JsonObject?>(null)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Navigationsstapel: jede Ebene ist ein Eintrag, Zurück entfernt den obersten. */
    val stack = mutableStateListOf(NavEntry("home"))

    private var client: HaClient? = null
    private var clientJob: Job? = null

    init {
        config.value?.let { connect(it) }
    }

    // ---------------- Konfiguration ----------------

    private fun loadConfig(): Config? {
        val url = prefs.getString("url", null) ?: return null
        val token = prefs.getString("token", null) ?: return null
        return Config(url, token, prefs.getString("accent", null) ?: "#03A9F4")
    }

    /** Wird vom Einrichtungs-Webserver (eigener Thread) aufgerufen. */
    fun saveConfig(c: Config) {
        prefs.edit()
            .putString("url", c.url)
            .putString("token", c.token)
            .putString("accent", c.accent)
            .apply()
        viewModelScope.launch {
            val old = config.value
            config.value = c
            showSetup = false
            if (old == null || old.url != c.url || old.token != c.token || status.value != ConnStatus.Connected) {
                connect(c)
            }
        }
    }

    // ---------------- Verbindung ----------------

    private fun connect(c: Config) {
        clientJob?.cancel()
        client?.close()
        registry.value = null
        lovelace.value = null
        stack.clear()
        stack.add(NavEntry("home"))

        val job = SupervisorJob(viewModelScope.coroutineContext[Job])
        val scope = CoroutineScope(viewModelScope.coroutineContext + job)
        clientJob = job

        val cl = HaClient(c.url, c.token, scope)
        client = cl
        scope.launch { cl.states.collect { states.value = it } }
        scope.launch {
            cl.status.collect { st ->
                status.value = st
                if (st == ConnStatus.Connected) loadMeta(cl, scope)
            }
        }
        cl.start()
    }

    private fun loadMeta(cl: HaClient, scope: CoroutineScope) = scope.launch {
        try {
            val reg = async { Registry.load(cl) }
            // Standard-Dashboard ohne gespeicherte Konfiguration liefert "config_not_found"
            val ll = async {
                runCatching { cl.call("lovelace/config") as? JsonObject }
                    .getOrNull()
                    ?.takeIf { it["views"] != null }
            }
            lovelace.value = ll.await()
            registry.value = reg.await()
        } catch (e: Exception) {
            messages.tryEmit("Laden fehlgeschlagen: ${e.message}")
        }
    }

    // ---------------- Navigation ----------------

    fun push(pageId: String) {
        stack.add(NavEntry(pageId))
    }

    /** @return false, wenn bereits auf der obersten Ebene */
    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    // ---------------- Aktionen ----------------

    fun perform(a: UiAction) {
        when (a) {
            is UiAction.Open -> push(a.pageId)
            is UiAction.MoreInfo -> push("more:${a.entityId}")
            is UiAction.Settings -> showSetup = true
            is UiAction.None -> Unit
            is UiAction.Toggle -> perform(toggleService(states.value[a.entityId], a.entityId))
            is UiAction.Service -> {
                val cl = client ?: return
                viewModelScope.launch {
                    try {
                        val target = a.target ?: a.entityId?.let { id -> buildJsonObject { put("entity_id", id) } }
                        cl.callService(a.domain, a.service, target, a.data)
                    } catch (e: Exception) {
                        messages.tryEmit("${a.domain}.${a.service}: ${e.message}")
                    }
                }
            }
        }
    }

    override fun onCleared() {
        client?.close()
        super.onCleared()
    }
}
