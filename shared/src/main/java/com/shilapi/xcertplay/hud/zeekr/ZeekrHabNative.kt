/*
 * DiPlay — Zeekr adaptation. GPLv3 (see LICENSE).
 *
 * JNI facade for the Zeekr HUD (HAB) frame channel. The native side
 * (jni/zeekr_hab.cpp) dlopen()s the vendor /system/lib64/libHabCamera.so and
 * registers a HabCameraListener with it. libHabCamera bridges to the QNX VM
 * (which owns the HUD) over Qualcomm HAB. This is our own clean-room shim — not
 * the third-party `com.pc.hablib` / `libhabmanager`.
 *
 * Model is pull-based, matching the vendor: we submit the latest frame and the
 * vendor copies it out on its own buffer cycle (notifyHabWriteBuffer). Mirrors
 * the style of the existing NativeLinuxI2cBridge / LegacyHotspotNative.
 */
package com.shilapi.xcertplay.hud.zeekr

import android.hardware.HardwareBuffer
import android.util.Log

object ZeekrHabNative {
    private const val TAG = "Zeekr-HAB"
    @Volatile var available: Boolean = false; private set

    init {
        available = runCatching { System.loadLibrary("zeekr_hab") }
            .onFailure { Log.w(TAG, "libzeekr_hab unavailable: ${it.message}") }
            .isSuccess
    }

    /** Open the HUD HAB channel and register our listener. Handle (>=0) or <0 on error. */
    external fun open(width: Int, height: Int, channel: Int): Long

    /** Hand the latest rendered frame to the native side; the vendor pulls it. */
    external fun submitFrame(handle: Long, buffer: HardwareBuffer): Int

    external fun close(handle: Long): Int
}
