/*
 * ZeePlay — Zeekr instrument-cluster display.
 *
 * Per-OEM cluster descriptor in the style of GeelyClusterDisplay / DiLink4ClusterDisplay:
 * it names the CarPlay instrument-cluster video stream geometry and recognises the Zeekr
 * 001 cluster panel. It is compiled in both editions but only ever matches in the Zeekr
 * edition (BuildConfig.IS_ZEEKR), so standard builds are unaffected.
 */
package com.shilapi.xcertplay

import android.view.Display
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay

internal object ZeekrClusterDisplay {

    // Zeekr 001 instrument cluster: a wide, short secondary presentation panel.
    // TODO(device): confirm exact pixels/name from `dumpsys display` on the car;
    // matching is size/flag-based so an off-by-a-few panel still works.
    const val STREAM_WIDTH = 2560
    const val STREAM_HEIGHT = 536

    /** Active only in the Zeekr edition; never engages on standard builds. */
    fun supported(): Boolean = com.shilapi.xcertplay.host.BuildConfig.IS_ZEEKR

    /**
     * The Zeekr cluster is the non-default presentation display with a wide, short aspect.
     * Gated by [supported] so this never claims another OEM's cluster on a standard build.
     */
    fun matches(displayId: Int, width: Int, height: Int, flags: Int): Boolean =
        supported() &&
            displayId != Display.DEFAULT_DISPLAY &&
            (flags and Display.FLAG_PRESENTATION) != 0 &&
            width >= 1600 && height in 320..820 && width >= height * 2

    fun matches(display: Display, width: Int, height: Int): Boolean =
        matches(display.displayId, width, height, display.flags)

    /**
     * CarPlay cluster stream for the Zeekr panel. Uses the real panel pixels so the aspect
     * is kept; falls back to the nominal 001 geometry when the caller has no measured size.
     */
    fun streamConfig(
        width: Int = STREAM_WIDTH,
        height: Int = STREAM_HEIGHT,
        content: CarPlayClusterDisplay.Content = CarPlayClusterDisplay.Content.MAP,
        horizontalStep: Int = 0,
        verticalStep: Int = 0,
        markerXPercent: Int? = null,
        markerYPercent: Int? = null,
    ): AirPlayDisplayConfig = CarPlayClusterDisplay.config(
        widthPixels = width,
        heightPixels = height,
        scalePercent = 100,
        horizontalStep = horizontalStep,
        verticalStep = verticalStep,
        content = content,
        markerXPercent = markerXPercent,
        markerYPercent = markerYPercent,
    )
}
