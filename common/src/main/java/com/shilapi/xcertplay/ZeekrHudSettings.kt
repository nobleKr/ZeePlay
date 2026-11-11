/*
 * DiPlay — Zeekr adaptation. GPLv3 (see LICENSE).
 *
 * Persisted toggles for the Zeekr windshield HUD (CarPlay card/map). Defaults:
 * the live map on, the glass turn card off, 100% size, marker centred — matching
 * the engine's out-of-the-box behaviour once a HUD display is present.
 */
package com.shilapi.xcertplay

import android.content.Context

object ZeekrHudSettings {
    private const val PREFS = "zeekr_hud"
    private const val KEY_MAP = "hud_map_enabled"
    private const val KEY_CARD = "hud_card_enabled"
    private const val KEY_SIZE = "hud_map_size_percent"
    private const val KEY_MARKER_H = "hud_map_marker_h"
    private const val KEY_MARKER_V = "hud_map_marker_v"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mapEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_MAP, true)
    fun cardEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_CARD, false)
    fun sizePercent(c: Context): Int = prefs(c).getInt(KEY_SIZE, 100)
    fun markerHorizontal(c: Context): Int = prefs(c).getInt(KEY_MARKER_H, 0)
    fun markerVertical(c: Context): Int = prefs(c).getInt(KEY_MARKER_V, 0)

    fun setMapEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_MAP, on).apply()
    fun setCardEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_CARD, on).apply()
    fun setSizePercent(c: Context, v: Int) = prefs(c).edit().putInt(KEY_SIZE, v).apply()
    fun setMarkerHorizontal(c: Context, v: Int) = prefs(c).edit().putInt(KEY_MARKER_H, v).apply()
    fun setMarkerVertical(c: Context, v: Int) = prefs(c).edit().putInt(KEY_MARKER_V, v).apply()
}
