/*
 * ZeePlay — Zeekr adaptation (zeekr edition only). GPLv3 (see LICENSE).
 *
 * JNI facade for the Zeekr 001 HUD (HAB) frame channel. The native side
 * (jni/zeekr_hab.cpp) dlopen()s the vendor /system/lib64/libHabCamera.so and
 * registers a HabCameraListener with it; libHabCamera bridges to the QNX VM
 * that owns the windshield HUD over Qualcomm HAB. Clean-room: no third-party
 * com.pc.hablib / libhabmanager code or binary is used.
 *
 * Pull model, matching the vendor: submit the latest frame; the vendor copies
 * it out on its own buffer cycle (notifyHabWriteBuffer). The native library is
 * only packaged in the Zeekr edition (see jni/Android.mk gating), so loadLibrary
 * is guarded and this stays inert if the lib is absent.
 */
package com.shilapi.xcertplay.hud.zeekr

import android.hardware.HardwareBuffer
import android.util.Log

internal object ZeekrHabNative {
    private const val TAG = "Zeekr-HAB"

    @Volatile var available: Boolean = false
        private set

    init {
        available = runCatching { System.loadLibrary("zeekr_hab") }
            .onFailure { Log.w(TAG, "libzeekr_hab unavailable: ${it.message}") }
            .isSuccess
    }

    /** Open the HUD HAB channel and register our listener. Handle (>=1) or <0 on error. */
    external fun open(width: Int, height: Int, channel: Int): Long

    /** Hand the latest rendered frame to the native side; the vendor pulls it. */
    external fun submitFrame(handle: Long, buffer: HardwareBuffer): Int

    external fun close(handle: Long): Int
}
