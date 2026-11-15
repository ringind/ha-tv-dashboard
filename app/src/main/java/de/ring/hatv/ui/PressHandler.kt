package de.ring.hatv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Ab dieser Dauer gilt OK als "lang gedrückt". */
const val HOLD_MS = 500L

/** Zeitfenster für den zweiten Druck eines Doppelklicks. */
const val DOUBLE_MS = 300L

fun KeyEvent.isSelectKey(): Boolean =
    key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter

/**
 * Wertet die OK-Taste der Fernbedienung aus:
 *  - einfacher Druck  -> onTap
 *  - langer Druck     -> onHold (feuert nach HOLD_MS, noch während die Taste gehalten wird)
 *  - Doppelklick      -> onDouble
 *
 * Gibt es kein onDouble, wird onTap sofort beim Loslassen ausgelöst. Nur wenn eine
 * Doppelklick-Aktion existiert, wartet der Handler DOUBLE_MS auf einen zweiten Druck.
 *
 * Ein KeyUp ohne vorheriges KeyDown auf diesem Element (z. B. nachdem ein langer Druck
 * eine neue Ebene geöffnet hat) wird ignoriert.
 */
@Composable
fun rememberPressHandler(
    onTap: () -> Unit,
    onHold: (() -> Unit)?,
    onDouble: (() -> Unit)?,
): (KeyEvent) -> Boolean {
    val scope = rememberCoroutineScope()
    val tap by rememberUpdatedState(onTap)
    val hold by rememberUpdatedState(onHold)
    val dbl by rememberUpdatedState(onDouble)

    return remember {
        var holdJob: Job? = null
        var tapJob: Job? = null
        var holdFired = false
        var downSeen = false

        val handler: (KeyEvent) -> Boolean = handler@{ ev ->
            if (!ev.isSelectKey()) return@handler false
            when (ev.type) {
                KeyEventType.KeyDown -> {
                    if (ev.nativeKeyEvent.repeatCount == 0) {
                        downSeen = true
                        holdFired = false
                        holdJob?.cancel()
                        val h = hold
                        if (h != null) {
                            holdJob = scope.launch {
                                delay(HOLD_MS)
                                holdFired = true
                                tapJob?.cancel()
                                tapJob = null
                                h()
                            }
                        }
                    }
                    true
                }

                KeyEventType.KeyUp -> {
                    if (!downSeen) return@handler true
                    downSeen = false
                    holdJob?.cancel()
                    holdJob = null
                    if (holdFired) return@handler true

                    val d = dbl
                    when {
                        d == null -> tap()
                        tapJob?.isActive == true -> {
                            tapJob?.cancel()
                            tapJob = null
                            d()
                        }
                        else -> tapJob = scope.launch {
                            delay(DOUBLE_MS)
                            tapJob = null
                            tap()
                        }
                    }
                    true
                }

                else -> false
            }
        }
        handler
    }
}

/**
 * Schutz für Screens, die per langem Druck geöffnet werden: Solange die OK-Taste vom
 * vorherigen Screen noch gehalten wird (Wiederholungs-KeyDowns und das abschließende KeyUp),
 * werden diese Ereignisse verschluckt. Erst ein neuer Druck wird durchgelassen.
 * Als onPreviewKeyEvent am äußeren Container verwenden.
 */
@Composable
fun rememberFreshPressGuard(): (KeyEvent) -> Boolean = remember {
    var armed = false
    val guard: (KeyEvent) -> Boolean = guard@{ ev ->
        if (!ev.isSelectKey()) return@guard false
        when (ev.type) {
            KeyEventType.KeyDown -> {
                if (ev.nativeKeyEvent.repeatCount == 0) { armed = true; false } else !armed
            }
            KeyEventType.KeyUp -> {
                if (!armed) true else { armed = false; false }
            }
            else -> false
        }
    }
    guard
}
