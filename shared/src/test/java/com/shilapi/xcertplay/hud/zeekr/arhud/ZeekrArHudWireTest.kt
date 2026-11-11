package com.shilapi.xcertplay.hud.zeekr.arhud

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZeekrArHudWireTest {
    private fun route() = ZeekrArHudRoute(
        requestId = "r1",
        pathId = 1,
        timestamp = 1000,
        links = listOf(
            ZeekrArHudRoute.Link(
                points = listOf(
                    ZeekrArHudRoute.Point(10.0, 50.0, 100.0),
                    ZeekrArHudRoute.Point(10.1, 50.1, 101.0),
                ),
                lengthMeters = 120, roadClass = 3, roadName = "A1", direction = 1, laneCount = 2,
            ),
        ),
        segments = listOf(ZeekrArHudRoute.Segment(mainAction = 2, assistantAction = 0, linkCount = 1)),
    )

    @Test fun validRouteRoundTrips() {
        assertTrue(route().isValid())
        val bytes = ZeekrArHudWire.route(route())
        assertTrue("non-empty encoding", bytes.isNotEmpty())
    }

    @Test fun invalidWhenSegmentLinkCountMismatch() {
        val r = route().copy(segments = listOf(ZeekrArHudRoute.Segment(2, 0, 2))) // 2 != 1 link
        assertFalse(r.isValid())
    }

    @Test fun invalidOnOutOfRangeCoordinate() {
        val bad = route().copy(
            links = listOf(route().links[0].copy(
                points = listOf(
                    ZeekrArHudRoute.Point(200.0, 50.0, 0.0),  // lon > 180
                    ZeekrArHudRoute.Point(10.0, 50.0, 0.0),
                ),
            )),
        )
        assertFalse(bad.isValid())
    }

    @Test fun heartbeatEncodesNavigatingFlags() {
        // field1=count(0)->tag 0x08 val 0; field2=7->0x10 0x07; field3=3->0x18 0x03
        assertArrayEquals(byteArrayOf(0x08, 0x00, 0x10, 0x07, 0x18, 0x03), ZeekrArHudWire.heartbeat(0, true))
        assertArrayEquals(byteArrayOf(0x08, 0x00, 0x10, 0x00, 0x18, 0x00), ZeekrArHudWire.heartbeat(0, false))
    }

    @Test fun eventCodes() {
        assertEquals(4352, ZeekrArHudWire.ROUTE_EVENT)
        assertEquals(4353, ZeekrArHudWire.HEART_EVENT)
    }
}
