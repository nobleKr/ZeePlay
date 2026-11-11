package com.shilapi.xcertplay.hud.zeekr

import org.junit.Assert.assertEquals
import org.junit.Test

class ZeekrHudTest {
    @Test fun turnIdPassthroughPlus100() {
        assertEquals(102, ZeekrHudTurnId.from(2, 0))   // 1..20 -> +100
        assertEquals(141, ZeekrHudTurnId.from(21, 0))
        assertEquals(146, ZeekrHudTurnId.from(28, 0))
    }
    @Test fun turnIdForks() {
        assertEquals(147, ZeekrHudTurnId.from(5, 0, fork = -1))
        assertEquals(148, ZeekrHudTurnId.from(5, 0, fork = 1))
    }
    @Test fun turnIdRoundaboutExit() {
        assertEquals(133, ZeekrHudTurnId.from(17, 3))  // 130 + exit
    }

    @Test fun guidanceCarriesAmapExtras() {
        val g = ZeekrHudGuidance(icon = 12, roundaboutExit = 2, distanceMeters = 350,
            road = "Main St", routeRemainingMeters = 5000, routeRemainingSeconds = 600)
        val msgs = ZeekrHudMessage.guidance(g, includeState = true)
        assertEquals(2, msgs.size)
        assertEquals(ZeekrHudMessage.KEY_STATE, msgs[0].keyType)
        assertEquals(ZeekrHudMessage.STATE_GUIDING, msgs[0].extras["EXTRA_STATE"])
        val turn = msgs[1]
        assertEquals(ZeekrHudMessage.KEY_GUIDANCE, turn.keyType)
        assertEquals(12, turn.extras["NEW_ICON"])
        assertEquals(2, turn.extras["ROUNG_ABOUT_NUM"])
        assertEquals(350, turn.extras["SEG_REMAIN_DIS"])
        assertEquals("Main St", turn.extras["NEXT_ROAD_NAME"])
        assertEquals(600, turn.extras["ROUTE_REMAIN_TIME"])
    }

    @Test fun guidanceWithoutStateIsSingle() {
        assertEquals(1, ZeekrHudMessage.guidance(
            ZeekrHudGuidance(1, 0, 10, "x"), includeState = false).size)
    }

    @Test fun endedResetsFields() {
        val m = ZeekrHudMessage.ended().single()
        assertEquals(ZeekrHudMessage.STATE_ENDED, m.extras["EXTRA_STATE"])
        assertEquals(-1, m.extras["NEW_ICON"])
        assertEquals("", m.extras["NEXT_ROAD_NAME"])
    }

    @Test fun receiversAreTheNavHudSurfaces() {
        val r = ZeekrHudMessage.RECEIVER_PACKAGES
        assertEquals(true, r.contains("com.zeekr.hud"))
        assertEquals(true, r.contains("com.zeekr.automap"))
        assertEquals(true, r.contains("com.ecarx.navi"))
    }
}
