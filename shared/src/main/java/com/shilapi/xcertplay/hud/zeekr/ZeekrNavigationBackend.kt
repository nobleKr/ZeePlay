/*
 * DiPlay — Zeekr adaptation. GPLv3 (see LICENSE).
 *
 * Zeekr backend. HUD guidance is sent as AMap standard broadcasts
 * (ZeekrHudMessage) to the Zeekr nav/HUD/launcher receivers, fed from the
 * engine's iAP2 RouteGuidance parse. Vehicle data comes from ZeekrVehicleStatus;
 * wheel speed from ZeekrWheelSpeed. The 780x244 HAB pixel path and the ECARX
 * DimInteraction SDK path remain for the on-car step.
 */
package com.shilapi.xcertplay.hud.zeekr

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.hud.BydHudRouteState
import com.shilapi.xcertplay.hud.NavigationHudBackend
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.transport.VehicleSpeedSource
import com.shilapi.xcertplay.transport.VehicleStatusProvider

object ZeekrNavigationBackend : NavigationHudBackend {
    private const val TAG = "Zeekr-Nav"
    override val id = "zeekr"

    @Volatile private var app: Context? = null
    @Volatile private var hud: ZeekrHabHudOutput? = null
    private val route = BydHudRouteState()
    @Volatile private var sdk: ZeekrHudSdk? = null
    private var hudActive = false

    override fun start(context: Context) {
        app = context.applicationContext
        if (hud == null) {
            ZeekrDisplayRouter.clusterDisplay(context) // logs the pick; AR-HUD pixel path is on-car
            hud = ZeekrHabHudOutput().also { if (!it.start()) Log.w(TAG, "HAB pixel output not up (SDK/broadcast still active)") }
        }
        if (sdk == null) {
            sdk = ZeekrHudSdk(context.applicationContext) { tag, msg -> Log.i(TAG, "sdk/$tag: $msg") }.also {
                runCatching { it.refresh(); if (it.enabled()) it.projection(true) }
            }
        }
    }

    override fun carPlaySessionStarted() { hudActive = false }

    @Synchronized
    override fun onFrame(frame: Iap2Frame) {
        val ctx = app ?: return
        route.accept(frame.messageId, frame.payload)
        val g = route.current()
        if (g != null) {
            val apple = route.currentApple()
            val guidance = ZeekrHudGuidance(
                icon = g.gaode,
                roundaboutExit = roundaboutExitOf(g.gaode),
                distanceMeters = g.distanceMeters,
                road = g.road,
                routeRemainingMeters = apple?.remainingMeters?.toInt() ?: -1,
                routeRemainingSeconds = apple?.remainingSeconds?.toInt() ?: -1,
            )
            ZeekrHudBroadcaster.send(ctx, ZeekrHudMessage.guidance(guidance, includeState = !hudActive))
            sdk?.publish(guidance, ZeekrHudTurnId.from(guidance.icon, guidance.roundaboutExit, 0))
            hudActive = true
        } else if (hudActive) {
            ZeekrHudBroadcaster.send(ctx, ZeekrHudMessage.ended())
            hudActive = false
        }
    }

    override fun endNow(preserveTurnOverlay: Boolean) {
        if (hudActive && !preserveTurnOverlay) {
            app?.let { ZeekrHudBroadcaster.send(it, ZeekrHudMessage.ended()) }
            hudActive = false
        }
        hud?.close(); hud = null
        sdk?.end(); sdk = null
    }

    override fun setClusterStreamControl(control: (Boolean) -> Unit) { /* cluster map stream: on-car */ }
    override fun clearClusterStreamControl(control: (Boolean) -> Unit) { /* on-car */ }

    override fun batteryStatus(context: Context): VehicleStatusProvider =
        ZeekrVehicleStatus.batteryProvider(context)
    override fun wheelSpeed(context: Context): VehicleSpeedSource = ZeekrWheelSpeedSource(context)
    override fun parked(context: Context): Boolean? = ZeekrVehicleStatus.parked(context)

    /** Gaode roundabout codes encode the exit: 24+exit (RHD) or 34+exit (LHD). */
    private fun roundaboutExitOf(gaode: Int): Int = when (gaode) {
        in 25..33 -> gaode - 24
        in 35..43 -> gaode - 34
        else -> 0
    }
}
