package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayHudMapTest {
    @Test fun sizeSnapsToNearestPreset() {
        // 110 -> 100%  (640x576); 130 -> 125% (800x720); 60 -> 75% (480x432)
        assertEquals(640 to 576, CarPlayClusterDisplay.hudMap(110, 0, 0, 0.1).let { it.widthPixels to it.heightPixels })
        assertEquals(800 to 720, CarPlayClusterDisplay.hudMap(130, 0, 0, 0.1).let { it.widthPixels to it.heightPixels })
        assertEquals(480 to 432, CarPlayClusterDisplay.hudMap(60, 0, 0, 0.1).let { it.widthPixels to it.heightPixels })
    }

    @Test fun streamIsMapAt15Fps() {
        val c = CarPlayClusterDisplay.hudMap(100, 0, 0, 0.2)
        assertEquals(CarPlayClusterDisplay.MAP_URL, c.initialUrl)
        assertEquals(15, c.fps)
        assertEquals(0, c.primaryInputDevice)
        assertTrue(c.safeAreaDrawOutside == true)
    }

    @Test fun markerCentredGivesSymmetricSafeArea() {
        val c = CarPlayClusterDisplay.hudMap(100, 0, 0, 0.1) // 640x576, half=30 -> x=y=50
        val s = c.safeArea!!
        assertEquals(115, s.top);  assertEquals(115, s.bottom)   // 576*0.20
        assertEquals(128, s.left); assertEquals(128, s.right)    // 640*0.20
    }

    @Test fun markerShiftClampsInsideWindow() {
        // +8 horizontal -> x = 50 + 8*2.5 = 70 (max); left=640*0.40=256, right=0
        val s = CarPlayClusterDisplay.hudMap(100, 8, 0, 0.1).safeArea!!
        assertEquals(256, s.left)
        assertEquals(0, s.right)
    }

    @Test fun physicalHintFollowsMmPerPixel() {
        val c = CarPlayClusterDisplay.hudMap(100, 0, 0, 0.5) // 640x576
        assertEquals(320, c.widthPhysicalMm)
        assertEquals(288, c.heightPhysicalMm)
    }
}
