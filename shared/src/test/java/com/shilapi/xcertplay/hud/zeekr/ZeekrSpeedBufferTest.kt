package com.shilapi.xcertplay.hud.zeekr

import com.shilapi.xcertplay.transport.VehicleGear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZeekrSpeedBufferTest {
    @Test fun drainNullWithoutGear() {
        val b = ZeekrSpeedBuffer()
        b.addSpeed(100, 10.0)
        assertNull("no gear yet", b.drain())
    }

    @Test fun drainNullWithoutSamples() {
        val b = ZeekrSpeedBuffer()
        b.setGear(VehicleGear.DRIVE)
        assertNull(b.drain())
    }

    @Test fun drainReturnsSamplesAndClears() {
        val b = ZeekrSpeedBuffer()
        b.setGear(VehicleGear.DRIVE)
        b.addSpeed(100, 10.0); b.addSpeed(350, 11.0)
        val r = b.drain()!!
        assertEquals(VehicleGear.DRIVE, r.gear)
        assertEquals(2, r.samples.size)
        assertEquals(10.0, r.samples[0].metersPerSecond, 1e-9)
        assertNull("cleared after drain", b.drain())
    }

    @Test fun negativeSpeedIgnored() {
        val b = ZeekrSpeedBuffer()
        b.setGear(VehicleGear.DRIVE)
        b.addSpeed(100, -1.0)
        assertNull(b.drain())
    }

    @Test fun capKeepsNewest() {
        val b = ZeekrSpeedBuffer(maxSamples = 3)
        b.setGear(VehicleGear.DRIVE)
        for (i in 1..5) b.addSpeed(i.toLong(), i.toDouble())
        val r = b.drain()!!
        assertEquals(3, r.samples.size)
        assertTrue("oldest dropped", r.samples.first().metersPerSecond >= 3.0)
        assertEquals(5.0, r.samples.last().metersPerSecond, 1e-9)
    }
}
