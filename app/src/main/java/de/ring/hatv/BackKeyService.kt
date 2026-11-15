package de.ring.hatv

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Bedienungshilfendienst: Wenn die App nicht im Vordergrund ist, holt ein langer Druck auf Zurück sie nach vorn.
 * Kurze Drücke gehen unverändert an die gerade offene App. Es werden nur Zurück-Tasten betrachtet;
 * Bildschirminhalte werden nicht gelesen.
 */
class BackKeyService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var fired = false
    private val bringToFront = Runnable {
        fired = true
        Log.i(TAG, "langer Druck erkannt -> App nach vorn")
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
            Log.i(TAG, "startActivity aufgerufen")
        }.onFailure { Log.w(TAG, "startActivity fehlgeschlagen: $it") }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Dienst verbunden")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK) return false
        Log.i(TAG, "BACK action=${event.action} repeat=${event.repeatCount} visible=${MainActivity.visible}")
        // Im Vordergrund bearbeitet die App den langen Druck selbst
        if (MainActivity.visible) { handler.removeCallbacks(bringToFront); fired = false; return false }
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    fired = false
                    handler.removeCallbacks(bringToFront)
                    handler.postDelayed(bringToFront, LONG_PRESS_MS)
                    false                      // kurzer Druck soll normal an die aktuelle App gehen
                } else fired                   // gehaltene Taste nach dem Auslösen nicht mehr weitergeben
            }
            KeyEvent.ACTION_UP -> {
                handler.removeCallbacks(bringToFront)
                val consumed = fired           // das Loslassen nach dem Auslösen schluckt den „Zurück"-Befehl der anderen App
                fired = false
                consumed
            }
            else -> false
        }
    }

    private companion object { const val LONG_PRESS_MS = 700L; const val TAG = "BackKeyService" }
}
