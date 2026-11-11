/*
 * DiPlay — Zeekr adaptation. GPLv3 (see LICENSE).
 *
 * Steering-wheel media actions on the Zeekr 001 and the command strings they
 * send to the engine. The physical key -> action map is learned per vehicle
 * (keycode/scancode captured by the accessibility service, stored in prefs);
 * only these command names are fixed. Accessibility-service capture is
 * app-module manifest work (on-car step).
 */
package com.shilapi.xcertplay.hud.zeekr

enum class ZeekrWheelCommand(val wire: String) {
    PLAY("zeekr-wheel-play"),
    PAUSE("zeekr-wheel-pause"),
    NEXT("zeekr-wheel-next"),
    PREVIOUS("zeekr-wheel-previous"),
    SOURCE_LOST("zeekr-source-lost"),
    ;

    companion object {
        fun fromWire(wire: String): ZeekrWheelCommand? = entries.firstOrNull { it.wire == wire }
    }
}
