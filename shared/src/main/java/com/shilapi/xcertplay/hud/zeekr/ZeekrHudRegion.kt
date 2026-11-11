/*
 * DiPlay — Zeekr adaptation. GPLv3 (see LICENSE).
 *
 * The HAB frame region pushed to the windshield HUD on the Zeekr 001:
 * a 780x244 buffer. This is the buffer the vendor HUD channel accepts, not the
 * HUD display resolution (that is ZeekrDisplays.HUD_*).
 */
package com.shilapi.xcertplay.hud.zeekr

object ZeekrHudRegion {
    const val WIDTH = 780
    const val HEIGHT = 244
    // x, y, w, h of the active area inside the buffer (full frame on the 001).
    val PROFILE = intArrayOf(0, 0, WIDTH, HEIGHT)

    // Channel id passed to libHabCamera hab_camera_open(). TODO(device): confirm
    // the HUD channel on this firmware (dumpsys / logged on first on-car run).
    const val HAB_CHANNEL = 0
}
