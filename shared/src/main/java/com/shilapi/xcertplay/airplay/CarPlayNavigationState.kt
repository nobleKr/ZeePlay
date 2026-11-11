/*
 * DiPlay — Zeekr adaptation. GPLv3 (see LICENSE).
 *
 * Tracks whether the iPhone is actively guiding (turn-by-turn) and which HUD
 * screens the user wants, and drives the HUD CarPlay sessions to open/close the
 * CARD / MAP stream. Mirrors the engine reference CarPlayNavigationState: the
 * phone advertises app states in the modes message; appStateID 3 (turn-by-turn)
 * with entity == 1 means the phone owns guidance.
 *
 * A HudScreenHost is the per-session hook (implemented by AirPlaySession in the
 * on-car wiring step) that actually shows/hides the HUD screen URL.
 */
package com.shilapi.xcertplay.airplay

import java.util.concurrent.CopyOnWriteArraySet

interface HudScreenHost {
    fun applyHudScreenWanted(screen: HudScreen, wanted: Boolean)
    fun hasHudScreen(screen: HudScreen): Boolean
}

object CarPlayNavigationState {
    private const val APP_STATE_TURN_BY_TURN = 3

    @Volatile
    var phoneOwnsGuidance = false
        private set

    @Volatile private var cardWanted = false
    @Volatile private var mapWanted = false

    private val hudSessions = CopyOnWriteArraySet<HudScreenHost>()

    /** Parse the phone's modes message; set [phoneOwnsGuidance] from the turn-by-turn app state. */
    fun onModesChanged(params: Map<String, Any?>) {
        val states = params["appStates"] as? List<*> ?: return
        for (entry in states) {
            val state = entry as? Map<*, *> ?: continue
            if ((state["appStateID"] as? Number)?.toInt() == APP_STATE_TURN_BY_TURN) {
                phoneOwnsGuidance = (state["entity"] as? Number)?.toInt() == 1
                return
            }
        }
    }

    fun wanted(screen: HudScreen): Boolean = if (screen == HudScreen.CARD) cardWanted else mapWanted

    fun setWanted(screen: HudScreen, wanted: Boolean) {
        if (wanted(screen) == wanted) return
        if (screen == HudScreen.CARD) cardWanted = wanted else mapWanted = wanted
        for (session in hudSessions) session.applyHudScreenWanted(screen, wanted)
    }

    fun hasSession(screen: HudScreen): Boolean = hudSessions.any { it.hasHudScreen(screen) }

    fun register(session: HudScreenHost) { hudSessions.add(session) }
    fun unregister(session: HudScreenHost) { hudSessions.remove(session) }

    fun reset() { phoneOwnsGuidance = false }
}
