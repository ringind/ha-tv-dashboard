package io.nachklang.hatv.ui

import io.nachklang.hatv.R
import io.nachklang.hatv.Res

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import io.nachklang.hatv.data.Config
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import kotlinx.coroutines.delay
import java.net.Inet4Address
import java.net.NetworkInterface

const val SETUP_PORT = 8765

/**
 * Einrichtung ohne Tippen auf der Fernbedienung: Die App startet einen kleinen Webserver im
 * Heimnetz und zeigt einen QR-Code. Am Handy werden HA-Adresse, Token und Rahmenfarbe eingegeben.
 */
@Composable
fun SetupScreen(
    current: Config?,
    authFailed: Boolean,
    accent: Color,
    onSave: (Config) -> Unit,
    onCancel: (() -> Unit)?,
) {
    val ip = remember { localIp() }
    val url = ip?.let { "http://$it:$SETUP_PORT/" }
    val qr = remember(url) { url?.let { qrBitmap(it, 440) } }

    DisposableEffect(Unit) {
        val server = SetupServer(SETUP_PORT, current, onSave)
        runCatching { server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        onDispose { server.stop() }
    }

    val cancelFocus = remember { FocusRequester() }
    Row(
        Modifier.fillMaxSize().padding(56.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(Res.s(R.string.u_title), fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Spacer(Modifier.height(20.dp))
            if (authFailed) {
                Text(Res.s(R.string.u_token_rejected), color = ErrorColor, fontSize = 16.sp)
                Spacer(Modifier.height(12.dp))
            }
            Text(Res.s(R.string.u_step1), color = Color.White, fontSize = 18.sp)
            Text(Res.s(R.string.u_step2), color = Color.White, fontSize = 18.sp)
            Text("   " + Res.s(R.string.u_step2b), color = Muted, fontSize = 15.sp)
            Text(Res.s(R.string.u_step3), color = Color.White, fontSize = 18.sp)
            Spacer(Modifier.height(20.dp))
            Text(url ?: Res.s(R.string.u_no_network), color = accent, fontSize = 22.sp, fontWeight = FontWeight.Medium)
            current?.let {
                Spacer(Modifier.height(12.dp))
                Text(Res.s(R.string.u_current, it.url), color = Muted, fontSize = 14.sp)
            }
            if (onCancel != null) {
                Spacer(Modifier.height(28.dp))
                Surface(
                    onClick = onCancel,
                    modifier = Modifier.focusRequester(cancelFocus),
                    shape = ClickableSurfaceDefaults.shape(TileShape),
                    colors = ClickableSurfaceDefaults.colors(containerColor = TileColor, focusedContainerColor = TileFocused,
                        contentColor = Color.White, focusedContentColor = Color.White),
                    border = focusBorder(accent),
                    scale = focusScale(),
                ) { Box(Modifier.padding(horizontal = 24.dp, vertical = 14.dp)) { Text(Res.s(R.string.u_back)) } }
            }
        }
        if (qr != null) {
            Box(Modifier.size(460.dp).background(Color.White).padding(10.dp)) {
                Image(qr.asImageBitmap(), contentDescription = Res.s(R.string.u_qr_desc), modifier = Modifier.size(440.dp))
            }
        } else {
            Spacer(Modifier.width(1.dp))
        }
    }

    if (onCancel != null) {
        LaunchedEffect(Unit) { delay(60); runCatching { cancelFocus.requestFocus() } }
    }
}

fun localIp(): String? = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
        ?.hostAddress
}.getOrNull()

fun qrBitmap(text: String, size: Int): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) for (y in 0 until size) {
        bmp.setPixel(x, y, if (m[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    }
    return bmp
}

private class SetupServer(
    port: Int,
    private val current: Config?,
    private val onSave: (Config) -> Unit,
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.POST) {
            runCatching { session.parseBody(HashMap()) }
            @Suppress("DEPRECATION")
            val p = session.parms
            var url = p["url"]?.trim().orEmpty()
            // Schema und Port ergänzen, damit "192.168.178.20" reicht
            if (url.isNotEmpty() && !url.contains("://")) url = "http://$url"
            val hostPart = url.substringAfter("://").substringBefore('/')
            if (url.startsWith("http://") && hostPart.isNotEmpty() && !hostPart.contains(':')) {
                url = url.replaceFirst(hostPart, "$hostPart:8123")
            }
            val token = p["token"]?.trim().orEmpty().ifEmpty { current?.token.orEmpty() }
            val accent = p["accent"]?.trim().orEmpty().ifEmpty { "#03A9F4" }
            val mode = if (p["mode"] == "native") "native" else "web"
            val start = p["start"]?.trim().orEmpty().trimStart('/')
            val zoom = p["zoom"]?.trim()?.toIntOrNull()?.coerceIn(50, 300) ?: 100
            return if (url.startsWith("http") && token.isNotEmpty()) {
                onSave(Config(url.trimEnd('/'), token, accent, mode, start, zoom))
                val host = url.substringAfter("://").substringBefore('/').substringBefore(':')
                val hint = if (host.endsWith(".local"))
                    "<p>" + Res.s(R.string.f_hint_local, "<b>.local</b>") + "</p>"
                else ""
                html("<h2>✅ ${Res.s(R.string.f_saved)}</h2><p>${Res.s(R.string.f_saved_text, "<b>${esc(url)}</b>")}</p>$hint")
            } else {
                html("<h2>⚠️ ${Res.s(R.string.f_incomplete)}</h2><p>${Res.s(R.string.f_incomplete_text)}</p><p><a href='/'>${Res.s(R.string.f_back)}</a></p>")
            }
        }
        // .local-Namen kann Android TV oft nicht auflösen -> nicht vorbelegen
        val curUrl = current?.url?.takeUnless { it.substringAfter("://").substringBefore(':').endsWith(".local") }
        val u = esc(curUrl ?: "")
        val a = esc(current?.accent ?: "#03A9F4")
        val curMode = current?.mode ?: "web"
        val s = esc(current?.start ?: "")
        val z = current?.zoom ?: 100
        val tokenHint = if (current != null) Res.s(R.string.f_token_keep) else ""
        return html(
            """
            <h2>HA TV Dashboard</h2>
            <form method="post">
              <label>${Res.s(R.string.f_url)}</label>
              <input name="url" value="$u" placeholder="192.168.178.20" autocapitalize="off" autocorrect="off" inputmode="url">
              <small style="color:#9aa3ad">${Res.s(R.string.f_url_hint)}</small>
              <label>${Res.s(R.string.f_token)}</label>
              <textarea name="token" rows="5" placeholder="$tokenHint"></textarea>
              <label>${Res.s(R.string.f_mode)}</label>
              <select name="mode">
                <option value="web" ${if (curMode == "web") "selected" else ""}>${Res.s(R.string.f_mode_web)}</option>
                <option value="native" ${if (curMode == "native") "selected" else ""}>${Res.s(R.string.f_mode_native)}</option>
              </select>
              <label>${Res.s(R.string.f_start)}</label>
              <input name="start" value="$s" placeholder="${Res.s(R.string.f_start_ph)}" autocapitalize="off" autocorrect="off">
              <label>${Res.s(R.string.f_zoom)}</label>
              <input name="zoom" type="number" value="$z" min="50" max="300">
              <label>${Res.s(R.string.f_accent)}</label>
              <input name="accent" type="color" value="$a">
              <button type="submit">${Res.s(R.string.f_save)}</button>
            </form>
            """.trimIndent()
        )
    }

    private fun html(body: String): Response = NanoHTTPD.newFixedLengthResponse(
        Response.Status.OK, "text/html; charset=utf-8",
        """
        <!doctype html><html lang="${java.util.Locale.getDefault().language}"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>HA TV Dashboard</title>
        <style>
          body{font-family:system-ui,sans-serif;background:#111418;color:#eee;padding:20px;max-width:560px;margin:auto}
          label{display:block;margin:18px 0 6px;color:#9aa3ad}
          input,textarea,select{width:100%;box-sizing:border-box;padding:12px;border-radius:10px;border:1px solid #39414b;background:#1f242b;color:#fff;font-size:16px}
          input[type=color]{height:52px;padding:4px}
          button{margin-top:24px;width:100%;padding:14px;border:0;border-radius:10px;background:#03a9f4;color:#fff;font-size:18px}
          a{color:#03a9f4}
        </style></head><body>$body</body></html>
        """.trimIndent()
    )

    private fun esc(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
}
