package com.shilapi.xcertplay.hud.zeekr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZeekrMediaAndWheelTest {
    @Test fun artworkConsumersAreTheZeekrSurfaces() {
        val c = ZeekrMediaTargets.ARTWORK_CONSUMERS
        assertTrue(c.contains("com.zeekr.mediacenter"))
        assertTrue(c.contains("com.zeekr.mediawidget.csd.island"))
        assertTrue(c.contains("ecarx.launcher3"))
        assertEquals("no duplicates", c.size, c.toSet().size)
    }

    @Test fun grantArtworkVisitsEveryConsumer() {
        val seen = mutableListOf<String>()
        ZeekrMediaTargets.grantArtwork { seen.add(it) }
        assertEquals(ZeekrMediaTargets.ARTWORK_CONSUMERS, seen)
    }

    @Test fun grantArtworkSurvivesOneFailingPackage() {
        val seen = mutableListOf<String>()
        ZeekrMediaTargets.grantArtwork { pkg ->
            if (pkg == "com.zeekr.mediacenter") error("not installed")
            seen.add(pkg)
        }
        assertEquals(ZeekrMediaTargets.ARTWORK_CONSUMERS.size - 1, seen.size)
    }

    @Test fun wheelCommandWireRoundTrips() {
        assertEquals("zeekr-wheel-play", ZeekrWheelCommand.PLAY.wire)
        assertEquals(ZeekrWheelCommand.NEXT, ZeekrWheelCommand.fromWire("zeekr-wheel-next"))
        assertNull(ZeekrWheelCommand.fromWire("unknown"))
    }
}
