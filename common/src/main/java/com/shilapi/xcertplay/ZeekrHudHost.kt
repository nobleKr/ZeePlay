/*
 * DiPlay — Zeekr adaptation. GPLv3 (see LICENSE).
 *
 * Host-side orchestrator for the Zeekr windshield HUD, mirroring the engine's
 * ZeekrHudBridge: shows the CarPlay HUD card/map stream on the HUD display,
 * keeps CarPlayNavigationState in step with the phone's guidance (opening the
 * wanted screen while the phone is actively guiding), and drives the AR-HUD
 * route feed over fdbus. It owns its own pump, so the host only needs start()
 * when a session's sink is ready and stop() when it ends.
 */
package com.shilapi.xcertplay

import android.app.Activity
import android.util.Log
import android.view.Surface
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.CarPlayNavigationState
import com.shilapi.xcertplay.airplay.HudScreen
import com.shilapi.xcertplay.hud.zeekr.ZeekrDisplayRouter
import com.shilapi.xcertplay.hud.zeekr.arhud.ZeekrArHudOutput
import com.shilapi.xcertplay.hud.zeekr.arhud.ZeekrArHudRoute
import com.shilapi.xcertplay.media.AndroidMediaSink
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class ZeekrHudHost(private val activity: Activity, private val sink: AndroidMediaSink) {
    private val arHud = ZeekrArHudOutput { tag, msg -> Log.i(TAG, "$tag: $msg") }
    private var presentation: ZeekrHudPresentation? = null
    @Volatile private var surface: Surface? = null
    private var ticker: ScheduledExecutorService? = null

    /** Show the HUD presentation and start the pump; no-op without a HUD display. */
    fun start() {
        val display = ZeekrDisplayRouter.hudDisplay(activity) ?: run {
            Log.i(TAG, "no HUD display; HUD host idle")
            return
        }
        activity.runOnUiThread {
            if (presentation == null) {
                val p = ZeekrHudPresentation(activity, display) { s -> activity.runOnUiThread { onSurface(s) } }
                p.setOnDismissListener { if (presentation === p) { presentation = null; onSurface(null) } }
                runCatching { p.show(); presentation = p }
                    .onFailure { Log.w(TAG, "HUD presentation failed: ${it.message}") }
            }
        }
        if (ticker == null) {
            ticker = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "zeekr-hud").apply { isDaemon = true }
            }.also { it.scheduleWithFixedDelay({ runCatching { tick() } }, 500, 700, TimeUnit.MILLISECONDS) }
        }
    }

    /** Supply the current CarPlay route geometry for the AR-HUD (null clears it). */
    fun setArRoute(route: ZeekrArHudRoute?) = arHud.setRoute(route)

    fun stop() {
        ticker?.shutdownNow()
        ticker = null
        runCatching { arHud.clear() }
        activity.runOnUiThread {
            onSurface(null)
            runCatching { presentation?.dismiss() }
            presentation = null
        }
        CarPlayNavigationState.reset()
    }

    private fun tick() {
        arHud.tick(CarPlayNavigationState.phoneOwnsGuidance)
        apply()
    }

    private fun apply() {
        val guiding = CarPlayNavigationState.phoneOwnsGuidance
        CarPlayNavigationState.setWanted(HudScreen.MAP, guiding && ZeekrHudSettings.mapEnabled(activity))
        CarPlayNavigationState.setWanted(HudScreen.CARD, guiding && ZeekrHudSettings.cardEnabled(activity))
    }

    private fun onSurface(s: Surface?) {
        val prev = surface
        surface = s
        for (type in screenTypes()) {
            if (s != null) sink.setSurface(type, s) else if (prev != null) sink.clearSurface(type, prev)
        }
    }

    private fun screenTypes(): List<Int> = buildList {
        if (ZeekrHudSettings.mapEnabled(activity)) add(AirPlayInfoPlist.STREAM_TYPE_MAP_SCREEN)
        if (ZeekrHudSettings.cardEnabled(activity)) add(AirPlayInfoPlist.STREAM_TYPE_CARD_SCREEN)
    }

    companion object { private const val TAG = "Zeekr-HUD" }
}
