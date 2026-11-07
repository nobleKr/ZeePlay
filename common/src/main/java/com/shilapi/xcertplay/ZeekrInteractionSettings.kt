package com.shilapi.xcertplay

import android.content.Context

/** Defaults preserve existing Zeekr media behavior; changes apply to the next session. */
internal object ZeekrInteractionSettings {
    private fun preferences(context: Context) = context.getSharedPreferences("zeekr_interaction", Context.MODE_PRIVATE)
    fun mediaEnabled(context: Context) = preferences(context).getBoolean("media_enabled", true)
    fun autoSelect(context: Context) = preferences(context).getBoolean("auto_select", true)
    fun controlsEnabled(context: Context) = preferences(context).getBoolean("controls_enabled", true)
    fun hudEnabled(context: Context) = preferences(context).getBoolean("hud_enabled", true)
    fun setMediaEnabled(context: Context, enabled: Boolean) { preferences(context).edit().putBoolean("media_enabled", enabled).apply() }
    fun setAutoSelect(context: Context, enabled: Boolean) { preferences(context).edit().putBoolean("auto_select", enabled).apply() }
    fun setControlsEnabled(context: Context, enabled: Boolean) { preferences(context).edit().putBoolean("controls_enabled", enabled).apply() }
    fun setHudEnabled(context: Context, enabled: Boolean) { preferences(context).edit().putBoolean("hud_enabled", enabled).apply() }
}
