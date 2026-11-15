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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import de.ring.hatv.data.ConnStatus
import de.ring.hatv.data.PageBuilder
import de.ring.hatv.ui.Background
import de.ring.hatv.ui.DetailScreen
import de.ring.hatv.ui.ErrorColor
import de.ring.hatv.ui.Muted
import de.ring.hatv.ui.PageScreen
import de.ring.hatv.ui.SetupScreen
import de.ring.hatv.ui.parseColor

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: AppViewModel = viewModel()
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Background)) {
                    App(vm, onExit = { finish() })
                }
            }
        }
    }
}

@Composable
fun App(vm: AppViewModel, onExit: () -> Unit) {
    val ctx = LocalContext.current
    val cfg by vm.config.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val accent = parseColor(cfg?.accent ?: "#03A9F4")

    LaunchedEffect(Unit) {
        vm.messages.collect { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }
    }

    val needsSetup = cfg == null || status == ConnStatus.AuthFailed || vm.showSetup
    if (needsSetup) {
        val canCancel = cfg != null && status != ConnStatus.AuthFailed
        BackHandler(enabled = canCancel) { vm.showSetup = false }
        SetupScreen(
            current = cfg,
            authFailed = status == ConnStatus.AuthFailed,
            accent = accent,
            onSave = vm::saveConfig,
            onCancel = if (canCancel) ({ vm.showSetup = false }) else null,
        )
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
