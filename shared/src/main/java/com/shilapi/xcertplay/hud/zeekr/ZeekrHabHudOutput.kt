/*
 * DiPlay — Zeekr adaptation. GPLv3 (see LICENSE).
 *
 * Renders the 780x244 HUD frame and feeds each HardwareBuffer to the Zeekr HAB
 * channel via ZeekrHabNative. The vendor (libHabCamera → QNX) pulls the latest
 * submitted frame on its own buffer cycle. The caller draws into inputSurface
 * (from the iAP2 RouteGuidance → ZeekrHudRouteState renderer, increment 3).
 */
package com.shilapi.xcertplay.hud.zeekr

import android.media.ImageReader
import android.hardware.HardwareBuffer
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

class ZeekrHabHudOutput(
    private val width: Int = ZeekrHudRegion.WIDTH,
    private val height: Int = ZeekrHudRegion.HEIGHT,
) : AutoCloseable {
    private val tag = "Zeekr-HUD"
    private val lock = Any()
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var reader: ImageReader? = null
    private var habHandle: Long = -1
    @Volatile private var started = false

    // HardwareBuffer usage for a GPU-written, vendor-composer-read frame.
    // TODO(device): confirm against libHabCamera expectations (the reference
    // channel used USAGE_GPU_SAMPLED_IMAGE | USAGE_COMPOSER_OVERLAY, 2 buffers).
    private val bufferUsage = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
        HardwareBuffer.USAGE_COMPOSER_OVERLAY

    fun start(): Boolean = synchronized(lock) {
        if (started) return true
        if (!ZeekrHabNative.available) { Log.w(tag, "native HAB shim not loaded"); return false }
        habHandle = ZeekrHabNative.open(width, height, /*channel*/ ZeekrHudRegion.HAB_CHANNEL)
        if (habHandle < 0) { Log.w(tag, "HAB open failed ($habHandle)"); return false }
        val ht = HandlerThread("zeekr-hud").apply { start() }
        val h = Handler(ht.looper)
        val ir = ImageReader.newInstance(width, height, HardwareBuffer.RGBA_8888, 2, bufferUsage)
        ir.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                img.hardwareBuffer?.use { buf ->
                    if (started && ZeekrHabNative.submitFrame(habHandle, buf) < 0) {
                        Log.w(tag, "HAB frame rejected")
                    }
                }
            } finally { img.close() }
        }, h)
        thread = ht; handler = h; reader = ir; started = true
        Log.i(tag, "HUD output up ${width}x$height")
        return true
    }

    /** Surface to draw the HUD frame into (Canvas/GL). Null until started. */
    val inputSurface get() = reader?.surface

    override fun close() = synchronized(lock) {
        started = false
        runCatching { reader?.close() }; reader = null
        if (habHandle >= 0) { runCatching { ZeekrHabNative.close(habHandle) }; habHandle = -1 }
        runCatching { thread?.quitSafely() }; thread = null; handler = null
    }
}
