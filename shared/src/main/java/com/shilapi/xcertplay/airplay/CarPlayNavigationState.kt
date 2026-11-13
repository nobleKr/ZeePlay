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

    /**
     * iAP2 route guidance (0x5201/0x5202) is producing turns. A second signal that does not depend on
     * the modes app-state layout, which differs across iOS versions.
     */
    @Volatile
    var routeGuidanceActive = false
        private set

    /** The phone is guiding by either signal; the HUD screens follow this. */
    val guiding: Boolean get() = phoneOwnsGuidance || routeGuidanceActive

    fun setRouteGuidanceActive(active: Boolean) { routeGuidanceActive = active }

    @Volatile private var lastModesSummary: String? = null

    /** Parse the phone's modes message; set [phoneOwnsGuidance] from the turn-by-turn app state. */
    fun onModesChanged(params: Map<String, Any?>) {
        val states = params["appStates"] as? List<*> ?: return
        for (entry in states) {
            val state = entry as? Map<*, *> ?: continue
            if (intOf(state["appStateID"]) == APP_STATE_TURN_BY_TURN) {
                phoneOwnsGuidance = intOf(state["entity"]) == 1
                return
            }
        }
    }

    /**
     * One-line, report-safe description of the appStates layout (keys, numbers, short tokens; no
     * "name="/"token" text the diagnostic redactor drops), or null when unchanged since last time.
     */
    fun modesDiagnostic(params: Map<String, Any?>): String? {
        val safe = "appStates=" + layout(params["appStates"]) + " resources=" + layout(params["resources"])
        val line = "owns=$phoneOwnsGuidance route=$routeGuidanceActive " +
            safe.replace(Regex("(?i)name"), "nm").replace(Regex("(?i)token"), "tkn")
        if (line == lastModesSummary) return null
        lastModesSummary = line
        return line
    }

    private fun layout(raw: Any?): String = (raw as? List<*>)?.joinToString(" ") { entry ->
        val map = entry as? Map<*, *> ?: return@joinToString "?" + (entry?.javaClass?.simpleName ?: "null")
        map.entries.joinToString(",", "{", "}") { (k, v) -> "$k~" + describe(v) }
    } ?: ("absent:" + (raw?.javaClass?.simpleName ?: "null"))

    private fun describe(v: Any?): String = when (v) {
        null -> "null"
        is Number, is Boolean -> v.toString()
        is String -> if (v.length <= 16 && v.matches(Regex("[A-Za-z0-9_.-]*"))) "'$v'" else "s${v.length}"
        is Map<*, *> -> "map(" + v.keys.joinToString("|") + ")"
        is List<*> -> "list${v.size}"
        else -> v.javaClass.simpleName
    }

    private fun intOf(v: Any?): Int? = when (v) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
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

    fun reset() { phoneOwnsGuidance = false; routeGuidanceActive = false; lastModesSummary = null }
}
