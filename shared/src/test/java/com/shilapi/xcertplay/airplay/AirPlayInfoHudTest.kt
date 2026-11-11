package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AirPlayInfoHudTest {
    private fun cfg(hudCard: Boolean, hudMap: Boolean) = AirPlayConfig(
        deviceName = "t", deviceId = "d", btMac = "00:11:22:33:44:55", sourceVersion = "1",
        main = AirPlayDisplayConfig(widthPixels = 1920, heightPixels = 1080),
        cluster = AirPlayDisplayConfig(widthPixels = 2560, heightPixels = 536, widthPhysicalMm = 292),
        hudCard = hudCard, hudMap = hudMap,
    )

    @Suppress("UNCHECKED_CAST")
    private fun displays(c: AirPlayConfig): List<Map<String, Any?>> =
        AirPlayInfoPlist.build(c)["displays"] as List<Map<String, Any?>>

    @Test fun noHudScreensByDefault() {
        val uuids = displays(cfg(false, false)).map { it["uuid"] }
        assertFalse(uuids.contains(AirPlayInfoPlist.CARD_UUID))
        assertFalse(uuids.contains(AirPlayInfoPlist.MAP_UUID))
    }

    @Test fun cardDeclaredWhenEnabled() {
        val card = displays(cfg(true, false)).first { it["uuid"] == AirPlayInfoPlist.CARD_UUID }
        assertEquals(AirPlayInfoPlist.CARD_WIDTH, card["widthPixels"])
        assertEquals(AirPlayInfoPlist.CARD_HEIGHT, card["heightPixels"])
        assertEquals(AirPlayInfoPlist.CARD_URL, card["initialURL"])
    }

    @Test fun mapDeclaredWhenEnabled() {
        val map = displays(cfg(false, true)).first { it["uuid"] == AirPlayInfoPlist.MAP_UUID }
        assertEquals(640, map["widthPixels"])   // 100% of 640x576
        assertEquals(576, map["heightPixels"])
        assertEquals(CarPlayClusterDisplay.MAP_URL, map["initialURL"])
    }
}
