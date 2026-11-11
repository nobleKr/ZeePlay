/*
 * DiPlay — Zeekr adaptation. GPLv3 (see LICENSE).
 *
 * AMap/AutoNavi standard HUD broadcast protocol, as the engine reference uses it:
 * action AUTONAVI_STANDARD_BROADCAST_SEND, KEY_TYPE 10001 guidance / 10019 state,
 * sent to the Zeekr nav/HUD/launcher receivers. Extra keys match the vendor
 * protocol exactly (incl. the "ROUNG_ABOUT_NUM" vendor typo).
 */
package com.shilapi.xcertplay.hud.zeekr

/** One turn's data for the HUD broadcast (icon in AMap NEW_ICON vocabulary). */
data class ZeekrHudGuidance(
    val icon: Int,
    val roundaboutExit: Int,
    val distanceMeters: Int,
    val road: String,
    val routeRemainingMeters: Int = -1,
    val routeRemainingSeconds: Int = -1,
)

data class ZeekrHudMessage(val keyType: Int, val extras: Map<String, Any>) {
    companion object {
        const val ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND"
        const val KEY_GUIDANCE = 10001
        const val KEY_STATE = 10019
        const val STATE_GUIDING = 8
        const val STATE_ENDED = 9

        val RECEIVER_PACKAGES: List<String> = listOf(
            "com.zeekr.automap", "com.zeekr.navi", "com.zeekr.hud",
            "com.zeekr.carlauncher", "com.geely.navi", "com.ecarx.navi",
        )

        fun guidance(frame: ZeekrHudGuidance, includeState: Boolean): List<ZeekrHudMessage> {
            val turn = ZeekrHudMessage(
                KEY_GUIDANCE,
                mapOf(
                    "TYPE" to 0,
                    "ICON" to frame.icon,
                    "NEW_ICON" to frame.icon,
                    "ROUNG_ABOUT_NUM" to frame.roundaboutExit,
                    "SEG_REMAIN_DIS" to frame.distanceMeters,
                    "NEXT_ROAD_NAME" to frame.road,
                    "ROUTE_REMAIN_DIS" to frame.routeRemainingMeters,
                    "ROUTE_REMAIN_TIME" to frame.routeRemainingSeconds,
                ),
            )
            return if (!includeState) listOf(turn)
            else listOf(ZeekrHudMessage(KEY_STATE, mapOf("EXTRA_STATE" to STATE_GUIDING)), turn)
        }

        fun ended(): List<ZeekrHudMessage> = listOf(
            ZeekrHudMessage(
                KEY_STATE,
                mapOf(
                    "EXTRA_STATE" to STATE_ENDED,
                    "NEW_ICON" to -1,
                    "SEG_REMAIN_DIS" to -1,
                    "NEXT_ROAD_NAME" to "",
                    "ROUTE_REMAIN_DIS" to -1,
                    "ROUTE_REMAIN_TIME" to -1,
                ),
            ),
        )
    }
}
