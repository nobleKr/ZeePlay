/*
 * ZeePlay — Zeekr instrument-cluster display.
 *
 * Per-OEM cluster descriptor in the style of GeelyClusterDisplay / DiLink4ClusterDisplay:
 * it recognises the Zeekr 001 instrument-cluster panel so ClusterMapPresentation can pick it
 * and show the CarPlay cluster stream there. Compiled in both editions but only ever matches
 * in the Zeekr edition (BuildConfig.IS_ZEEKR), so standard builds are unaffected. The stream
 * geometry comes from the measured panel in CarPlayHostActivity.clusterDisplayConfig(), so no
 * per-OEM stream descriptor is needed here.
 */
package com.shilapi.xcertplay

import android.view.Display

internal object ZeekrClusterDisplay {

    /** Active only in the Zeekr edition; never engages on standard builds. */
    fun supported(): Boolean = com.shilapi.xcertplay.host.BuildConfig.IS_ZEEKR

    /**
     * The Zeekr 001 cluster is the non-default presentation display with a wide, short aspect.
     * Gated by [supported] so this never claims another OEM's cluster on a standard build.
     * TODO(device): confirm the exact pixels from `dumpsys display` on the car; matching is
     * size/flag-based so an off-by-a-few panel still works.
     */
    fun matches(displayId: Int, width: Int, height: Int, flags: Int): Boolean =
        supported() &&
            displayId != Display.DEFAULT_DISPLAY &&
            (flags and Display.FLAG_PRESENTATION) != 0 &&
            width >= 1600 && height in 320..820 && width >= height * 2

    fun matches(display: Display, width: Int, height: Int): Boolean =
        matches(display.displayId, width, height, display.flags)
}
