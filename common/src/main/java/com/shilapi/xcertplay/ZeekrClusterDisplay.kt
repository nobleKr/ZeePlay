/*
 * ZeePlay — Zeekr instrument-cluster display.
 *
 * Per-OEM cluster descriptor in the style of GeelyClusterDisplay / DiLink4ClusterDisplay:
 * it recognises the Zeekr 001 instrument-cluster panel so ClusterMapPresentation can pick it
 * and show the CarPlay cluster stream there. Compiled in both editions but only ever matches
 * in the Zeekr edition (BuildConfig.IS_ZEEKR), so standard builds are unaffected. The stream
 * geometry comes from the measured panel in CarPlayHostActivity.clusterDisplayConfig(), so no
 * per-OEM stream descriptor is needed here.
 *
 * Confirmed on the 001 (dumpsys display): the cluster is logical Display 1, "HDMI Screen"
 * uniqueId local:3 (port 3), 2560 x 536, EXTERNAL + FLAG_PRESENTATION. The car's other
 * presentation panels — 1024 x 576 (local:4) and 1280 x 360 (local:5) — are narrower than the
 * 2000 px floor, so the match below uniquely selects the cluster.
 */
package com.shilapi.xcertplay

import android.view.Display

internal object ZeekrClusterDisplay {

    /** Active only in the Zeekr edition; never engages on standard builds. */
    fun supported(): Boolean = com.shilapi.xcertplay.host.BuildConfig.IS_ZEEKR

    /**
     * The Zeekr 001 cluster is the wide, short presentation panel (confirmed 2560 x 536).
     * Bounds are anchored on that measured geometry with a little tolerance for trim/firmware
     * variance; the width floor plus the ultra-wide aspect keep the 1024 x 576 and 1280 x 360
     * panels out. Gated by [supported] so this never claims another OEM's cluster.
     */
    fun matches(displayId: Int, width: Int, height: Int, flags: Int): Boolean =
        supported() &&
            displayId != Display.DEFAULT_DISPLAY &&
            (flags and Display.FLAG_PRESENTATION) != 0 &&
            width >= 2000 &&          // 001 cluster panel is 2560 wide
            height in 440..680 &&     // confirmed 536
            width >= height * 3       // ultra-wide strip (~4.8:1)

    fun matches(display: Display, width: Int, height: Int): Boolean =
        matches(display.displayId, width, height, display.flags)
}
