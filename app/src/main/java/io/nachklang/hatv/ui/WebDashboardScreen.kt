package io.nachklang.hatv.ui

import io.nachklang.hatv.R
import io.nachklang.hatv.Res

import android.annotation.SuppressLint
import android.net.Uri
import android.view.KeyEvent
import android.webkit.RenderProcessGoneDetail
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceResponse
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.nachklang.hatv.data.Config
import kotlinx.coroutines.delay
import org.json.JSONObject

/** Custom-JS (HACS-Karten, UIX, browser_mod …) im TV-WebView nicht laden – deutlich schnellere Startzeit. */
private const val BLOCK_CUSTOM_JS = true

/**
 * Zeigt das echte Home-Assistant-Frontend in einer WebView.
 * Die Bedienung per Fernbedienung übernimmt assets/tv-nav.js (Steuerkreuz, Rahmen, OK).
 *
 *  - Steuerkreuz / OK: werden in MainActivity.dispatchKeyEvent abgefangen und an das Skript weitergegeben
 *  - Zurück: eine Seite zurück (Dialoge schließen sich dabei); auf der obersten Ebene öffnet sich das Menü
 *  - Menü-Taste: öffnet das Menü direkt
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebDashboardScreen(
    cfg: Config,
    accent: Color,
    onSettings: () -> Unit,
    onExit: () -> Unit,
    registerKeys: (((KeyEvent) -> Boolean)?) -> Unit,
) {
    val ctx = LocalContext.current
    val holder = remember { arrayOfNulls<WebView>(1) }
    var menuOpen by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    val navScript = remember(cfg.accent) {
        val js = ctx.assets.open("tv-nav.js").bufferedReader().use { it.readText() }
        "window.__tvAccent=${JSONObject.quote(cfg.accent)};\n$js"
    }

    BackHandler {
        val wv = holder[0]
        when {
            menuOpen -> menuOpen = false
            wv == null -> menuOpen = true
            else -> wv.evaluateJavascript("window.__tv?window.__tv.back():'none'") { r ->
                android.util.Log.d("TVDBG", "back -> $r")
                // 'dialog' / 'page': Skript hat es erledigt; sonst oberste Ebene -> Menü
                if (r == null || r.contains("none") || r == "null") menuOpen = true
            }
        }
    }

    // Zurück aus dem Hintergrund: nur bei längerer Pause (30 Minuten) oder abgerissener Verbindung neu laden
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var stoppedAt = 0L
        val observer = LifecycleEventObserver { _, ev ->
            val wv = holder[0] ?: return@LifecycleEventObserver
            when (ev) {
                Lifecycle.Event.ON_STOP -> { stoppedAt = System.currentTimeMillis(); wv.onPause() }
                Lifecycle.Event.ON_START -> {
                    wv.onResume()
                    val was = stoppedAt
                    stoppedAt = 0L
                    if (was > 0) {
                        if (System.currentTimeMillis() - was > 30 * 60 * 1000L) {
                            wv.reload()
                        } else {
                            // Home Assistant verbindet sich selbst neu; falls das nach 4 s nicht geklappt hat, Seite neu laden
                            wv.postDelayed({
                                wv.evaluateJavascript(
                                    "(function(){var h=document.querySelector('home-assistant');" +
                                        "return !!(h&&h.hass&&h.hass.connection&&h.hass.connection.connected);})()"
                                ) { r -> if (r != "true") wv.reload() }
                            }, 4000)
                        }
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(menuOpen) {
        registerKeys { ev -> handleKey(holder[0], ev, menuOpen) { menuOpen = true } }
        onDispose { registerKeys(null) }
    }

    Box(Modifier.fillMaxSize().background(Background)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                WebView(c).apply {
                    holder[0] = this
                    WebView.setWebContentsDebuggingEnabled(true)   // chrome://inspect über ADB
                    setBackgroundColor(0xFF111418.toInt())
                    isFocusable = false
                    isFocusableInTouchMode = false
                    with(settings) {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        setSupportZoom(false)
                    }
                    if (cfg.zoom != 100) setInitialScale(cfg.zoom)

                    // Anmeldung ohne Eingabe: Long-Lived Token als gespeicherte Sitzung ablegen
                    val origin = originOf(cfg.url)
                    val seed = tokenScript(cfg.token, origin)
                    val docStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                    if (docStart) WebViewCompat.addDocumentStartJavaScript(this, seed, setOf(origin))
                    var seeded = docStart

                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                            android.util.Log.i("TVDBG", m.message() + " (" + m.sourceId() + ":" + m.lineNumber() + ")")
                            return true
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            if (!seeded) {
                                // Fallback ohne Document-Start-Skript: Token setzen und einmal neu laden
                                seeded = true
                                view.evaluateJavascript(seed) { view.reload() }
                                return
                            }
                            loading = false
                            error = null
                            view.evaluateJavascript(navScript, null)
                        }

                        // Ladezeit: Custom-Karten und -Erweiterungen (HACS, UIX, browser_mod, kiosk-mode …) werden vom
                        // HA-Frontend global geladen, das TV-Dashboard nutzt nur Standardkarten. Diese Skripte leer beantworten.
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val u = request.url
                            val path = u.path ?: return null
                            val isJs = path.endsWith(".js") || path.endsWith(".mjs")
                            val custom = path.startsWith("/local/") || path.startsWith("/uix/") ||
                                path.startsWith("/browser_mod") || path.startsWith("/plant/") ||
                                (path.startsWith("/hacsfiles/") && !path.contains("hass-hue-icons")) ||
                                u.host == "cdn.jsdelivr.net"
                            if (BLOCK_CUSTOM_JS && isJs && custom) {
                                return WebResourceResponse(
                                    "application/javascript", "utf-8", 200, "OK",
                                    mapOf("Access-Control-Allow-Origin" to "*"),
                                    java.io.ByteArrayInputStream(ByteArray(0))
                                )
                            }
                            return null
                        }

                        override fun onReceivedError(view: WebView, request: WebResourceRequest, err: WebResourceError) {
                            if (request.isForMainFrame) {
                                error = "${err.description} (${err.errorCode})"
                                loading = false
                            }
                        }

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                            request.url.host != Uri.parse(cfg.url).host

                        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                            error = Res.s(R.string.w_proc_gone)
                            return true
                        }
                    }
                    loadUrl(cfg.url.trimEnd('/') + "/" + cfg.start.trimStart('/'))
                }
            },
            onRelease = {
                it.destroy()
                holder[0] = null
            },
        )

        if (loading && error == null) {
            Box(Modifier.fillMaxSize().background(Background), contentAlignment = Alignment.Center) {
                Text(Res.s(R.string.w_loading), color = Muted, fontSize = 20.sp)
            }
        }

        error?.let {
            Box(Modifier.fillMaxSize().background(Background), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(Res.s(R.string.w_load_failed), color = Color.White, fontSize = 22.sp)
                    Text(cfg.url, color = Muted, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
                    Text(it, color = ErrorColor, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
                    Text(Res.s(R.string.w_back_menu), color = Muted, fontSize = 14.sp,
                        modifier = Modifier.padding(top = 20.dp))
                }
            }
        }

        if (menuOpen) {
            WebMenu(
                accent = accent,
                onResume = { menuOpen = false },
                onReload = { holder[0]?.reload(); menuOpen = false },
                onBackground = {
                    menuOpen = false
                    var c: android.content.Context = ctx
                    while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
                    (c as? android.app.Activity)?.moveTaskToBack(true)   // App bleibt geladen im Hintergrund
                },
                onSettings = onSettings,
                onExit = onExit,
            )
        }
    }
}

/** Leitet Steuerkreuz und OK an das Skript in der Seite weiter. */
private fun handleKey(wv: WebView?, ev: KeyEvent, menuOpen: Boolean, openMenu: () -> Unit): Boolean {
    if (menuOpen || wv == null) return false
    val dir = when (ev.keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> "up"
        KeyEvent.KEYCODE_DPAD_DOWN -> "down"
        KeyEvent.KEYCODE_DPAD_LEFT -> "left"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
        else -> null
    }
    if (dir != null) {
        if (ev.action == KeyEvent.ACTION_DOWN) wv.evaluateJavascript("window.__tv&&window.__tv.nav('$dir')", null)
        return true
    }
    return when (ev.keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
            if (ev.action == KeyEvent.ACTION_DOWN) {
                // gehaltene Taste (repeatCount > 0) nicht erneut senden: Home Assistant misst die Haltedauer selbst
                if (ev.repeatCount == 0) wv.evaluateJavascript("window.__tv&&window.__tv.down()", null)
            } else if (ev.action == KeyEvent.ACTION_UP) {
                wv.evaluateJavascript("window.__tv&&window.__tv.up()", null)
            }
            true
        }
        KeyEvent.KEYCODE_MENU -> {
            if (ev.action == KeyEvent.ACTION_UP) openMenu()
            true
        }
        else -> false
    }
}

private fun originOf(url: String): String {
    val u = Uri.parse(url)
    return "${u.scheme}://${u.authority}"
}

/** Legt die gespeicherte HA-Sitzung (Token) und ein ausgeblendetes Seitenmenü im localStorage an. */
private fun tokenScript(token: String, origin: String): String {
    val tok = JSONObject.quote(token)
    val o = JSONObject.quote(origin)
    return "(function(){try{" +
        "var t={access_token:$tok,token_type:'Bearer',expires_in:315360000,hassUrl:$o,clientId:$o+'/'," +
        "expires:Date.now()+315360000000,refresh_token:''};" +
        "localStorage.setItem('hassTokens',JSON.stringify(t));" +
        "localStorage.setItem('dockedSidebar','\"always_hidden\"');" +
        "}catch(e){}})();"
}

@Composable
private fun WebMenu(
    accent: Color,
    onResume: () -> Unit,
    onReload: () -> Unit,
    onBackground: () -> Unit,
    onSettings: () -> Unit,
    onExit: () -> Unit,
) {
    val first = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("HA TV Dashboard", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            MenuButton(Res.s(R.string.m_background), accent, first, onBackground)
            MenuButton(Res.s(R.string.m_resume), accent, null, onResume)
            MenuButton(Res.s(R.string.m_reload), accent, null, onReload)
            MenuButton(Res.s(R.string.m_settings), accent, null, onSettings)
            MenuButton(Res.s(R.string.m_exit), accent, null, onExit)
        }
    }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { first.requestFocus() }
    }
}

@Composable
private fun MenuButton(label: String, accent: Color, focusRequester: FocusRequester?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(360.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        shape = ClickableSurfaceDefaults.shape(TileShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = TileColor, focusedContainerColor = TileFocused,
            contentColor = Color.White, focusedContentColor = Color.White,
        ),
        border = focusBorder(accent),
        scale = focusScale(),
    ) {
        Box(Modifier.padding(vertical = 16.dp).width(360.dp), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 18.sp)
        }
    }
}
