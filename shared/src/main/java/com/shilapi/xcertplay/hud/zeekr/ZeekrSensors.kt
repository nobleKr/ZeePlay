/*
 * DiPlay — Zeekr adaptation. GPLv3 (see LICENSE).
 *
 * ECARX AdaptAPI sensor bus (ICarSensor via reflection) as the primary source
 * of battery/range/gear/speed, falling back to ZeekrCarSignals (android.car).
 * Mirrors the engine reference ZeekrSensors.
 */
package com.shilapi.xcertplay.hud.zeekr

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.transport.VehicleGear
import com.shilapi.xcertplay.transport.VehicleStatusSnapshot
import kotlin.math.abs
import kotlin.math.roundToInt

interface ZeekrSensorBus {
    fun latest(sensorType: Int): Float?
    fun event(sensorType: Int): Int?
}

/** AdaptAPI ICarSensor: getSensorLatestValue / getSensorEvent / isSensorSupported. */
internal class AdaptApiSensorBus(private val sensor: Any) : ZeekrSensorBus {
    private fun supported(type: Int): Boolean = runCatching {
        sensor.javaClass.getMethod("isSensorSupported", Int::class.javaPrimitiveType).invoke(sensor, type) as? Boolean
    }.getOrNull() ?: false

    override fun latest(sensorType: Int): Float? {
        if (!supported(sensorType)) return null
        return runCatching {
            sensor.javaClass.getMethod("getSensorLatestValue", Int::class.javaPrimitiveType).invoke(sensor, sensorType) as? Float
        }.getOrNull()
    }

    override fun event(sensorType: Int): Int? {
        if (!supported(sensorType)) return null
        return runCatching {
            sensor.javaClass.getMethod("getSensorEvent", Int::class.javaPrimitiveType).invoke(sensor, sensorType) as? Int
        }.getOrNull()
    }
}

object ZeekrSensors {
    private const val TAG = "ZeekrSensors"
    private const val CAR_CLASS = "com.ecarx.xui.adaptapi.car.Car"

    const val BATTERY_PERCENT = 4210688   // 0x404000
    const val BATTERY_STATE = 2102528
    const val RANGE_KM = 1054976          // 0x101900
    const val SPEED_KMH = 1048832
    const val GEAR = 2097664
    const val GEAR_NEUTRAL = 2097680
    const val GEAR_DRIVE = 2097696
    const val GEAR_PARK = 2097712
    const val GEAR_REVERSE = 2097728
    private val CHARGING_STATES = setOf(2102530, 2102545, 2102546, 2102554, 2102552, 2102553)
    private val DRIVE_RANGE = 2097665..2097674

    @Volatile private var bus: ZeekrSensorBus? = null

    private fun bus(context: Context): ZeekrSensorBus? {
        bus?.let { return it }
        return synchronized(this) { bus ?: open(context.applicationContext)?.also { bus = it } }
    }

    private fun open(context: Context): ZeekrSensorBus? = runCatching {
        val car = Class.forName(CAR_CLASS).getMethod("create", Context::class.java).invoke(null, context) ?: return null
        val getSensorManager = car.javaClass.methods.firstOrNull { it.name == "getSensorManager" && it.parameterCount == 0 } ?: return null
        val sensor = getSensorManager.invoke(car) ?: return null
        AdaptApiSensorBus(sensor)
    }.getOrElse { Log.w(TAG, "AdaptAPI sensor manager unavailable", it); null }

    // --- bus-level reads (engine reference) ---
    fun gear(source: ZeekrSensorBus): VehicleGear? = when (val e = source.event(GEAR)) {
        null, -1 -> null
        GEAR_PARK -> VehicleGear.PARK
        GEAR_REVERSE -> VehicleGear.REVERSE
        GEAR_NEUTRAL -> VehicleGear.NEUTRAL
        GEAR_DRIVE -> VehicleGear.DRIVE
        else -> if (e in DRIVE_RANGE) VehicleGear.DRIVE else { Log.d(TAG, "unmapped gear 0x${e.toString(16)}"); null }
    }

    fun speedKmh(source: ZeekrSensorBus): Double? =
        source.latest(SPEED_KMH)?.toDouble()?.takeIf { it in 0.0..300.0 }

    fun battery(source: ZeekrSensorBus): ZeekrBatteryReading? {
        val percent = source.latest(BATTERY_PERCENT)?.toDouble()?.takeIf { it in 0.0..100.0 } ?: return null
        val range = source.latest(RANGE_KM)?.toDouble()?.takeIf { it in 0.0..2000.0 }?.roundToInt() ?: return null
        val charging = source.event(BATTERY_STATE)?.let { CHARGING_STATES.contains(it) } ?: false
        return ZeekrBatteryReading(percent, range, charging)
    }

    // --- context-level with android.car fallback ---
    fun battery(context: Context): ZeekrBatteryReading? =
        bus(context)?.let { battery(it) } ?: ZeekrCarSignals.battery(context)
    fun gear(context: Context): VehicleGear? =
        bus(context)?.let { gear(it) } ?: ZeekrCarSignals.gear(context)
    fun speedKmh(context: Context): Double? =
        bus(context)?.let { speedKmh(it) } ?: ZeekrCarSignals.speedKmh(context)

    fun androidSpeedKmh(metersPerSecond: Double): Double? =
        (abs(metersPerSecond) * 3.6).takeIf { it in 0.0..300.0 }

    fun snapshot(reading: ZeekrBatteryReading, lowPercent: Int): VehicleStatusSnapshot {
        val fraction = reading.percent / 100.0
        return VehicleStatusSnapshot(
            rangeKm = reading.rangeKm,
            rangeWarning = reading.percent <= lowPercent,
            batteryPercent = reading.percent,
            currentChargeWh = null,
            maxChargeWh = null,
            maxRangeKm = if (fraction > 0.0) (reading.rangeKm / fraction).roundToInt() else reading.rangeKm,
            charging = reading.charging,
        )
    }
}
