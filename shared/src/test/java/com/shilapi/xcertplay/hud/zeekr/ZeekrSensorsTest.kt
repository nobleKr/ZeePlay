package com.shilapi.xcertplay.hud.zeekr

import com.shilapi.xcertplay.transport.VehicleGear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZeekrSensorsTest {
    private class FakeBus(val events: Map<Int, Int> = emptyMap(), val values: Map<Int, Float> = emptyMap()) : ZeekrSensorBus {
        override fun latest(sensorType: Int): Float? = values[sensorType]
        override fun event(sensorType: Int): Int? = events[sensorType]
    }

    @Test fun gearMapping() {
        assertEquals(VehicleGear.PARK, ZeekrSensors.gear(FakeBus(events = mapOf(ZeekrSensors.GEAR to ZeekrSensors.GEAR_PARK))))
        assertEquals(VehicleGear.DRIVE, ZeekrSensors.gear(FakeBus(events = mapOf(ZeekrSensors.GEAR to ZeekrSensors.GEAR_DRIVE))))
        assertEquals(VehicleGear.DRIVE, ZeekrSensors.gear(FakeBus(events = mapOf(ZeekrSensors.GEAR to 2097670)))) // DRIVE range
        assertNull(ZeekrSensors.gear(FakeBus(events = mapOf(ZeekrSensors.GEAR to -1))))
    }

    @Test fun speedClamped() {
        assertEquals(42.0, ZeekrSensors.speedKmh(FakeBus(values = mapOf(ZeekrSensors.SPEED_KMH to 42f)))!!, 1e-6)
        assertNull(ZeekrSensors.speedKmh(FakeBus(values = mapOf(ZeekrSensors.SPEED_KMH to 999f))))
    }

    @Test fun batteryAndCharging() {
        val b = ZeekrSensors.battery(FakeBus(
            events = mapOf(ZeekrSensors.BATTERY_STATE to 2102545),
            values = mapOf(ZeekrSensors.BATTERY_PERCENT to 80f, ZeekrSensors.RANGE_KM to 350f)))!!
        assertEquals(80.0, b.percent, 1e-6); assertEquals(350, b.rangeKm); assertTrue(b.charging)
        assertNull(ZeekrSensors.battery(FakeBus(values = mapOf(ZeekrSensors.BATTERY_PERCENT to 80f)))) // no range
    }

    @Test fun snapshotMaxRange() {
        val s = ZeekrSensors.snapshot(ZeekrBatteryReading(percent = 50.0, rangeKm = 200, charging = false), lowPercent = 10)
        assertEquals(200, s.rangeKm); assertEquals(400, s.maxRangeKm); assertEquals(false, s.rangeWarning)
    }
}
