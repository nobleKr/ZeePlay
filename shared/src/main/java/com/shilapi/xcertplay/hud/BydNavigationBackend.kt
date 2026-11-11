/*
 * DiPlay — Zeekr adaptation. GPLv3 (see LICENSE).
 *
 * BYD backend: thin delegate to the existing BydNavigationOutputs. No BYD logic
 * changes — this only exposes it through NavigationHudBackend so the controller
 * can be made platform-agnostic without touching BYD behaviour.
 */
package com.shilapi.xcertplay.hud

import android.content.Context
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.transport.VehicleSpeedSource
import com.shilapi.xcertplay.transport.VehicleStatusProvider

object BydNavigationBackend : NavigationHudBackend {
    override val id = "byd"

    override fun start(context: Context) = BydNavigationOutputs.start(context)
    override fun carPlaySessionStarted() = BydNavigationOutputs.carPlaySessionStarted()
    override fun onFrame(frame: Iap2Frame) = BydNavigationOutputs.onFrame(frame)
    override fun endNow(preserveTurnOverlay: Boolean) =
        BydNavigationOutputs.endNow(preserveTurnOverlay)

    override fun setClusterStreamControl(control: (Boolean) -> Unit) =
        BydNavigationOutputs.setClusterStreamControl(control)
    override fun clearClusterStreamControl(control: (Boolean) -> Unit) =
        BydNavigationOutputs.clearClusterStreamControl(control)

    override fun batteryStatus(context: Context): VehicleStatusProvider =
        BydNavigationOutputs.batteryStatus(context)
    override fun wheelSpeed(context: Context): VehicleSpeedSource =
        BydNavigationOutputs.wheelSpeed(context)
    override fun parked(context: Context): Boolean? = BydNavigationOutputs.parked(context)
}
