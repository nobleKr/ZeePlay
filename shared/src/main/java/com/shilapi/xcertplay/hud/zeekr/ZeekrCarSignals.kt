/*
 * DiPlay — Zeekr adaptation. GPLv3 (see LICENSE).
 *
 * Reads EV charge/range, gear and speed from the Zeekr DHU via the Android Car
 * API, reached by reflection so :shared needs no android.car compile dependency
 * (same technique the platform uses). All reads are read-only and null-safe.
 *
 * Property ids are standard android.car VehiclePropertyIds values:
 *   EV_BATTERY_LEVEL 291504905, EV_BATTERY_CAPACITY 291504390,
 *   RANGE_REMAINING  291504904, EV_CHARGE_CURRENT... 291504908 (charge rate),
 *   PERF_VEHICLE_SPEED 291504647, GEAR_SELECTION 289408000.
 */
package com.shilapi.xcertplay.hud.zeekr

import android.content.Context
import com.shilapi.xcertplay.transport.VehicleGear
import kotlin.math.abs
import kotlin.math.roundToInt

data class ZeekrBatteryReading(val percent: Double, val rangeKm: Int, val charging: Boolean)

object ZeekrCarSignals {
    private const val EV_BATTERY_LEVEL = 291504905
    private const val EV_BATTERY_CAPACITY = 291504390
    private const val RANGE_REMAINING = 291504904
    private const val CHARGE_RATE_WATTS = 291504908
    private const val VEHICLE_SPEED = 291504647
    private const val GEAR_SELECTION = 289408000
    private const val GEAR_PARK = 4
    private const val GEAR_REVERSE = 2
    private const val GEAR_NEUTRAL = 1
    private const val GEAR_DRIVE = 8

    @Volatile private var manager: Any? = null

    fun battery(context: Context): ZeekrBatteryReading? {
        val level = floatProp(context, EV_BATTERY_LEVEL) ?: return null
        val capacity = floatProp(context, EV_BATTERY_CAPACITY)?.takeIf { it > 0f } ?: return null
        val percent = (level / capacity * 100.0).takeIf { it in 0.0..100.0 } ?: return null
        val rangeKm = (floatProp(context, RANGE_REMAINING)?.toDouble()?.div(1000.0))
            ?.takeIf { it in 0.0..2000.0 }?.roundToInt() ?: return null
        val charging = (floatProp(context, CHARGE_RATE_WATTS) ?: 0f) > 0f
        return ZeekrBatteryReading(percent, rangeKm, charging)
    }

    fun gear(context: Context): VehicleGear? = when (intProp(context, GEAR_SELECTION)) {
        GEAR_PARK -> VehicleGear.PARK
        GEAR_REVERSE -> VehicleGear.REVERSE
        GEAR_NEUTRAL -> VehicleGear.NEUTRAL
        GEAR_DRIVE -> VehicleGear.DRIVE
        else -> null
    }

    /** Vehicle speed in km/h (PERF_VEHICLE_SPEED is m/s), or null. */
    fun speedKmh(context: Context): Double? =
        floatProp(context, VEHICLE_SPEED)?.let { abs(it.toDouble()) * 3.6 }

    // --- reflection plumbing ---
    private fun manager(context: Context): Any? {
        manager?.let { return it }
        return runCatching {
            val car = Class.forName("android.car.Car")
            val create = car.getMethod("createCar", Context::class.java)
            val instance = create.invoke(null, context.applicationContext) ?: return null
            val getMgr = car.getMethod("getCarManager", String::class.java)
            getMgr.invoke(instance, "property")
        }.getOrNull()?.also { manager = it }
    }

    private fun floatProp(context: Context, id: Int): Float? = prop(context, "getFloatProperty", id) as? Float
    private fun intProp(context: Context, id: Int): Int? = prop(context, "getIntProperty", id) as? Int

    private fun prop(context: Context, method: String, id: Int): Any? {
        val mgr = manager(context) ?: return null
        return runCatching {
            mgr.javaClass.getMethod(method, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(mgr, id, 0)
        }.getOrNull()
    }
}
