package com.shilapi.xcertplay

import android.content.Context

/**
 * The Zeekr 001 feeds CarPlay guidance to the windshield HUD and cluster through the
 * ECARX adaptapi. Gated by the same interaction settings as the other Zeekr outputs.
 */
internal fun createWindshieldHud(context: Context): WindshieldHud? =
    if (!ZeekrInteractionSettings.hudEnabled(context)) null
    else ZeekrEcarxHudOutput(context.applicationContext)
