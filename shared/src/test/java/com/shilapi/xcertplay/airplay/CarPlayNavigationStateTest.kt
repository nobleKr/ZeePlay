package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayNavigationStateTest {
    private fun clear() {
        CarPlayNavigationState.setWanted(HudScreen.CARD, false)
        CarPlayNavigationState.setWanted(HudScreen.MAP, false)
        CarPlayNavigationState.reset()
    }

    @Test fun turnByTurnEntitySetsGuidance() {
        clear()
        CarPlayNavigationState.onModesChanged(
            mapOf("appStates" to listOf(mapOf("appStateID" to 3, "entity" to 1)))
        )
        assertTrue(CarPlayNavigationState.phoneOwnsGuidance)
        CarPlayNavigationState.onModesChanged(
            mapOf("appStates" to listOf(mapOf("appStateID" to 3, "entity" to 0)))
        )
        assertFalse(CarPlayNavigationState.phoneOwnsGuidance)
    }

    @Test fun unrelatedModesLeaveGuidanceUnchanged() {
        clear()
        CarPlayNavigationState.onModesChanged(
            mapOf("appStates" to listOf(mapOf("appStateID" to 1, "speechMode" to -1)))
        )
        assertFalse(CarPlayNavigationState.phoneOwnsGuidance)
    }

    @Test fun setWantedDrivesRegisteredSessionsOnce() {
        clear()
        val calls = mutableListOf<Pair<HudScreen, Boolean>>()
        var have = false
        val host = object : HudScreenHost {
            override fun applyHudScreenWanted(screen: HudScreen, wanted: Boolean) { calls.add(screen to wanted) }
            override fun hasHudScreen(screen: HudScreen): Boolean = have
        }
        CarPlayNavigationState.register(host)
        try {
            CarPlayNavigationState.setWanted(HudScreen.MAP, true)
            CarPlayNavigationState.setWanted(HudScreen.MAP, true) // idempotent, no second call
            assertEquals(listOf(HudScreen.MAP to true), calls)
            assertTrue(CarPlayNavigationState.wanted(HudScreen.MAP))
            assertFalse(CarPlayNavigationState.hasSession(HudScreen.MAP))
            have = true
            assertTrue(CarPlayNavigationState.hasSession(HudScreen.MAP))
        } finally {
            CarPlayNavigationState.unregister(host)
            clear()
        }
    }
}
