/*
 * DiPlay — Zeekr adaptation. GPLv3 (see LICENSE).
 *
 * Selects the HUD/cluster backend for the current head unit. BYD stays the
 * default; Zeekr is chosen only when the platform is detected, so existing
 * BYD units are unaffected until the controller is wired to this selector.
 */
package com.shilapi.xcertplay.hud

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.hud.zeekr.ZeekrNavigationBackend

object HudBackends {
    private const val TAG = "HudBackends"

    @Volatile private var cached: NavigationHudBackend? = null

    fun select(context: Context): NavigationHudBackend =
        cached ?: synchronized(this) {
            cached ?: detect(context.applicationContext).also {
                cached = it
                Log.i(TAG, "HUD backend = ${it.id}")
            }
        }

    private fun detect(context: Context): NavigationHudBackend =
        if (isZeekr(context)) ZeekrNavigationBackend else BydNavigationBackend

    /**
     * Zeekr probe via public signals only. TODO(device): confirm the surest
     * marker on the 001 (presence of com.zeekr.* system packages and/or
     * ro.* build props) and tighten this; keep BYD as the safe default.
     */
    internal fun isZeekr(context: Context): Boolean {
        val pm = context.packageManager
        val markers = listOf("com.zeekr.carlauncher", "com.zeekr.hud", "com.ecarx.xui.adaptapi")
        val hasPkg = markers.any { pkg ->
            runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false)
        }
        val brand = runCatching { android.os.Build.BRAND.lowercase() }.getOrDefault("")
        return hasPkg || brand.contains("zeekr") || brand.contains("ecarx")
    }
}
