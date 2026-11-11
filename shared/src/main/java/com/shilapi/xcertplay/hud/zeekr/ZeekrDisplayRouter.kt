/*
 * DiPlay — Zeekr adaptation. GPLv3 (see LICENSE).
 *
 * Picks the instrument-cluster / HUD presentation display on a Zeekr 001 DHU.
 * Pure open Android API (DisplayManager). Written for the fork; no third-party
 * code. Device-specific constants are marked TODO and must be confirmed from
 * `dumpsys display` / `dumpsys SurfaceFlinger --display-id` on the car.
 */
package com.shilapi.xcertplay.hud.zeekr

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display

object ZeekrDisplayRouter {
    private const val TAG = "Zeekr-Display"

    /** Candidate presentation displays, main display (id 0) excluded. */
    fun presentationDisplays(context: Context): List<Display> {
        val dm = context.getSystemService(DisplayManager::class.java) ?: return emptyList()
        val byCategory = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).toList()
        val all = dm.displays.toList()
        return (byCategory + all).distinctBy { it.displayId }.filter { it.displayId != Display.DEFAULT_DISPLAY }
    }

    /** The Zeekr cluster display (2560x536, ±5%), or null. */
    fun clusterDisplay(context: Context): Display? {
        val candidates = presentationDisplays(context)
        candidates.firstOrNull { val p = sizeOf(it); ZeekrDisplays.isCluster(p.x, p.y) }
            ?.let { return logPick("cluster-size", it) }
        candidates.firstOrNull { nameHints(it, "cluster", "instrument") }?.let { return logPick("cluster-name", it) }
        return null
    }

    /** The Zeekr windshield HUD display (1024x576, ±5%), or null. */
    fun hudDisplay(context: Context): Display? {
        val candidates = presentationDisplays(context)
        candidates.firstOrNull { val p = sizeOf(it); ZeekrDisplays.isHud(p.x, p.y) }
            ?.let { return logPick("hud-size", it) }
        candidates.firstOrNull { nameHints(it, "hud", "head-up", "headup") }?.let { return logPick("hud-name", it) }
        return null
    }

    private fun sizeOf(display: Display): Point = Point().also { display.getRealSize(it) }

    private fun nameHints(display: Display, vararg hints: String): Boolean {
        val n = display.name?.lowercase() ?: return false
        return hints.any { n.contains(it) }
    }

    private fun logPick(how: String, d: Display): Display {
        Log.i(TAG, "picked display by $how: id=${d.displayId} name=${d.name} size=${sizeOf(d)}")
        return d
    }
}
