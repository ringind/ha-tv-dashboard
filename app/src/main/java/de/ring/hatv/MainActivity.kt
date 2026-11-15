package de.ring.hatv

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import de.ring.hatv.data.ConnStatus
import de.ring.hatv.data.PageBuilder
import de.ring.hatv.ui.Background
import de.ring.hatv.ui.DetailScreen
import de.ring.hatv.ui.ErrorColor
import de.ring.hatv.ui.TileColor
import de.ring.hatv.ui.TileFocused
import de.ring.hatv.ui.TileShape
import de.ring.hatv.ui.focusBorder
import de.ring.hatv.ui.focusScale
import de.ring.hatv.ui.Muted
import de.ring.hatv.ui.PageScreen
import de.ring.hatv.ui.SetupScreen
import de.ring.hatv.ui.WebDashboardScreen
import de.ring.hatv.ui.parseColor

class MainActivity : ComponentActivity() {
    /** Tasten-Abfang für die WebView-Ansicht (Steuerkreuz und OK gehen an das Skript in der Seite). */
    companion object {
        /** true, solange die App sichtbar ist (vom Bedienungshilfendienst abgefragt). */
        @Volatile var visible = false
    }

    override fun onStart() { super.onStart(); visible = true }
    override fun onStop() { visible = false; super.onStop() }

    private var webKeys: ((android.view.KeyEvent) -> Boolean)? = null

    /** Langer Druck auf Zurück: App in den Hintergrund schieben (geladen im Speicher). Kurzer Druck bleibt „eine Ebene zurück". */
    override fun onKeyLongPress(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            moveTaskToBack(true)
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
        webKeys?.invoke(event) == true || super.dispatchKeyEvent(event)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Prozess im Speicher halten, damit die App auf Tastendruck sofort bereitsteht
        runCatching {
            androidx.core.content.ContextCompat.startForegroundService(this, android.content.Intent(this, KeepAliveService::class.java))
        }
        setContent {
            val vm: AppViewModel = viewModel()
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Background)) {
                    App(vm, onExit = {
                        // Beenden: auch den Bereithalte-Dienst stoppen
                        stopService(android.content.Intent(this@MainActivity, KeepAliveService::class.java))
                        finishAndRemoveTask()
                    }, registerKeys = { webKeys = it })
                }
            }
        }
    }
}

@Composable
fun App(vm: AppViewModel, onExit: () -> Unit, registerKeys: (((android.view.KeyEvent) -> Boolean)?) -> Unit) {
    val ctx = LocalContext.current
    val cfg by vm.config.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val accent = parseColor(cfg?.accent ?: "#03A9F4")

    LaunchedEffect(Unit) {
        vm.messages.collect { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }
    }

    val authFailed = cfg?.mode == "native" && status == ConnStatus.AuthFailed
    val needsSetup = cfg == null || authFailed || vm.showSetup
    if (needsSetup) {
        val canCancel = cfg != null && !authFailed
        BackHandler(enabled = canCancel) { vm.showSetup = false }
        SetupScreen(
            current = cfg,
            authFailed = authFailed,
            accent = accent,
            onSave = vm::saveConfig,
            onCancel = if (canCancel) ({ vm.showSetup = false }) else null,
        )
        return
    }

    val c = cfg ?: return
    if (c.mode == "web") {
        key(c) {
            WebDashboardScreen(
                cfg = c,
                accent = accent,
                onSettings = { vm.showSetup = true },
                onExit = onExit,
                registerKeys = registerKeys,
            )
        }
        return
    }
    Dashboard(vm, accent, status, onExit)
}

@Composable
private fun Dashboard(vm: AppViewModel, accent: androidx.compose.ui.graphics.Color, status: ConnStatus, onExit: () -> Unit) {
    val ctx = LocalContext.current
    val states by vm.states.collectAsStateWithLifecycle()
    val registry by vm.registry.collectAsStateWithLifecycle()
    val lovelace by vm.lovelace.collectAsStateWithLifecycle()
    val err by vm.lastError.collectAsStateWithLifecycle()
    val cfg by vm.config.collectAsStateWithLifecycle()
    var lastBack by remember { mutableLongStateOf(0L) }

    // Zurück: eine Ebene hoch; auf der obersten Ebene zweimal drücken zum Beenden
    BackHandler(enabled = true) {
        if (!vm.back()) {
            val now = System.currentTimeMillis()
            if (now - lastBack < 2000) onExit()
            else {
                lastBack = now
                Toast.makeText(ctx, "Zum Beenden erneut Zurück drücken", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val reg = registry
    if (reg == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    when (status) {
                        ConnStatus.Connected -> "Lade Dashboard …"
                        ConnStatus.Disconnected -> "Keine Verbindung zu Home Assistant – neuer Versuch läuft …"
                        else -> "Verbinde mit Home Assistant …"
                    },
                    color = Muted, fontSize = 20.sp,
                )
                cfg?.url?.let { Text(it, color = Muted, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp)) }
                err?.let { Text(it, color = ErrorColor, fontSize = 16.sp, modifier = Modifier.padding(top = 10.dp)) }

                // Ohne Verbindung muss die Adresse trotzdem änderbar sein (z. B. IP statt .local-Name)
                val settingsFocus = remember { FocusRequester() }
                Surface(
                    onClick = { vm.showSetup = true },
                    modifier = Modifier.padding(top = 32.dp).focusRequester(settingsFocus),
                    shape = ClickableSurfaceDefaults.shape(TileShape),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = TileColor, focusedContainerColor = TileFocused,
                        contentColor = Color.White, focusedContentColor = Color.White,
                    ),
                    border = focusBorder(accent),
                    scale = focusScale(),
                ) {
                    Text("Adresse / Token ändern", fontSize = 18.sp,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp))
                }
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(150)
                    runCatching { settingsFocus.requestFocus() }
                }
            }
        }
        return
    }

    val builder = remember(reg, lovelace) { PageBuilder(reg, lovelace) }
    val entry = vm.stack.last()

    Box(Modifier.fillMaxSize()) {
        // key(entry): jede Ebene bekommt ihren eigenen Compose-Zustand
        key(entry) {
            if (entry.pageId.startsWith("more:")) {
                val id = entry.pageId.removePrefix("more:")
                DetailScreen(id, states[id], accent, onAction = vm::perform)
            } else {
                val page = builder.build(entry.pageId, states)
                val crumb = vm.stack.dropLast(1)
                    .map { e -> if (e.pageId.startsWith("more:")) "Details" else builder.build(e.pageId, states).title }
                    .joinToString(" › ")
                PageScreen(page, entry, crumb, accent, onAction = vm::perform)
            }
        }

        if (status != ConnStatus.Connected) {
            Column(Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                Text("● Verbindung getrennt – verbinde neu …", color = ErrorColor, fontSize = 14.sp)
            }
        }
    }
}
