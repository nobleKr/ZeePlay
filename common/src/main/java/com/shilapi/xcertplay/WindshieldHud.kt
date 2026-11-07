package com.shilapi.xcertplay

import com.shilapi.xcertplay.hud.ClusterTurnGuidance

/**
 * Optional per-OEM windshield head-up output, selected at build time.
 *
 * Standard builds have none; the Zeekr edition draws the turn card to the 001
 * windshield HUD over the vendor HAB channel. Callbacks arrive on the main
 * thread, so implementations must hand work to their own thread and never block.
 */
internal interface WindshieldHud {
    /** Push the latest guidance to the HUD; null clears it. */
    fun update(guidance: ClusterTurnGuidance?)

    /** Tear down the HUD output and its resources. */
    fun release()
}
