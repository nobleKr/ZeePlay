/*
 * DiPlay — Zeekr adaptation. GPLv3 (see LICENSE).
 *
 * Zeekr 001 display geometry and matching. Sizes are hardware facts of the
 * DHU (center / cluster / windshield HUD); matching is orientation-agnostic
 * with a 5% tolerance. Reimplemented for the fork.
 */
package com.shilapi.xcertplay.hud.zeekr

import kotlin.math.abs

object ZeekrDisplays {
    const val CENTER_WIDTH = 2560
    const val CENTER_HEIGHT = 1600
    const val CLUSTER_WIDTH = 2560
    const val CLUSTER_HEIGHT = 536
    const val HUD_WIDTH = 1024
    const val HUD_HEIGHT = 576

    fun isCenter(width: Int, height: Int) = matches(width, height, CENTER_WIDTH, CENTER_HEIGHT)
    fun isCluster(width: Int, height: Int) = matches(width, height, CLUSTER_WIDTH, CLUSTER_HEIGHT)
    fun isHud(width: Int, height: Int) = matches(width, height, HUD_WIDTH, HUD_HEIGHT)

    /** True when (w,h) matches the target in either orientation, each side within 5%. */
    private fun matches(width: Int, height: Int, ew: Int, eh: Int): Boolean =
        (near(width, ew) && near(height, eh)) || (near(width, eh) && near(height, ew))

    private fun near(actual: Int, expected: Int): Boolean =
        expected > 0 && abs(actual - expected) * 20 <= expected
}
