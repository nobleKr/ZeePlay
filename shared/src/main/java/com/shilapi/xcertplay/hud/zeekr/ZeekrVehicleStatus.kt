/*
 * DiPlay — Zeekr adaptation. GPLv3 (see LICENSE).
 *
 * Adapts the Zeekr sensor sources (AdaptAPI sensor bus, else android.car) into
 * the engine's VehicleStatusProvider and a parked() check.
 */
package com.shilapi.xcertplay.hud.zeekr

import android.content.Context
import com.shilapi.xcertplay.transport.VehicleGear
import com.shilapi.xcertplay.transport.VehicleStatusProvider

object ZeekrVehicleStatus {
    private const val LOW_BATTERY_PERCENT = 10

    fun batteryProvider(context: Context): VehicleStatusProvider {
        val app = context.applicationContext
        return VehicleStatusProvider {
            ZeekrSensors.battery(app)?.let { ZeekrSensors.snapshot(it, LOW_BATTERY_PERCENT) }
        }
    }

    /** true = parked (P), false = a drive gear, null = unknown. */
    fun parked(context: Context): Boolean? =
        ZeekrSensors.gear(context.applicationContext)?.let { it == VehicleGear.PARK }
}
