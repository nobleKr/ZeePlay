/*
 * DiPlay — Zeekr adaptation. GPLv3 (see LICENSE).
 *
 * Platform seam for HUD / cluster / vehicle-data output. Today CarPlayController
 * talks to BydNavigationOutputs directly; this interface lets a Zeekr backend
 * sit beside the BYD one, selected at runtime by HudBackends.
 *
 * ADDITIVE: this increment only introduces the seam and the backends.
 * Wiring CarPlayController to hold a NavigationHudBackend (instead of calling
 * BydNavigationOutputs statically) is the next step and must be done with a
 * build environment — see docs/ZEEKR_ADAPTATION.md increment 2.
 */
package com.shilapi.xcertplay.hud

import android.content.Context
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.transport.VehicleSpeedSource
import com.shilapi.xcertplay.transport.VehicleStatusProvider

/** The cross-platform surface CarPlayController needs from a HUD/cluster backend. */
interface NavigationHudBackend {
    val id: String

    fun start(context: Context)
    fun carPlaySessionStarted()
    fun onFrame(frame: Iap2Frame)
    fun endNow(preserveTurnOverlay: Boolean = false)

    fun setClusterStreamControl(control: (Boolean) -> Unit)
    fun clearClusterStreamControl(control: (Boolean) -> Unit)

    /** EV charge/range for iAP2 VehicleStatus; provider returns null while unavailable. */
    fun batteryStatus(context: Context): VehicleStatusProvider
    /** Wheel-speed source for tunnel dead reckoning. */
    fun wheelSpeed(context: Context): VehicleSpeedSource
    /** true=parked (P), false=not parked, null=unknown. */
    fun parked(context: Context): Boolean?
}
