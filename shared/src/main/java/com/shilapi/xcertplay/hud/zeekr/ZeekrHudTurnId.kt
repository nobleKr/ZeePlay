/*
 * DiPlay — Zeekr adaptation. GPLv3 (see LICENSE).
 * Apple/AMap maneuver icon -> Zeekr HUD turn id. Logic mirrors the engine
 * reference (ZeekrHudTurnId.from): roundabout exits, forks, 1..20 passthrough+100.
 */
package com.shilapi.xcertplay.hud.zeekr

object ZeekrHudTurnId {
    fun from(icon: Int, roundaboutExit: Int, fork: Int = 0): Int {
        if (fork < 0) return 147
        if (fork > 0) return 148
        if (roundaboutExit in 1..10) {
            when (icon) {
                11, 12 -> return roundaboutExit + 3600   // DNSConstants.KNOWN_ANSWER_TTL
                17, 18 -> return roundaboutExit + 130
            }
        }
        if (icon in 1..20) return icon + 100
        return when (icon) {
            21 -> 141; 22 -> 142; 23 -> 193; 24 -> 143; 25 -> 144
            26 -> 145; 27 -> 194; 28 -> 146; 70 -> 148; 71 -> 147
            else -> 0
        }
    }
}
