package com.shilapi.xcertplay.hud.zeekr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZeekrDisplaysTest {
    @Test fun clusterMatchesExactAndRotated() {
        assertTrue(ZeekrDisplays.isCluster(2560, 536))
        assertTrue(ZeekrDisplays.isCluster(536, 2560)) // orientation-agnostic
    }

    @Test fun hudMatchesExact() {
        assertTrue(ZeekrDisplays.isHud(1024, 576))
        assertTrue(ZeekrDisplays.isHud(576, 1024))
    }

    @Test fun centerMatches() {
        assertTrue(ZeekrDisplays.isCenter(2560, 1600))
    }

    @Test fun toleranceIsFivePercent() {
        // within 5% of cluster height 536 (>=510) passes, below fails
        assertTrue(ZeekrDisplays.isCluster(2560, 512))
        assertFalse(ZeekrDisplays.isCluster(2560, 480))
    }

    @Test fun unrelatedSizesDoNotMatch() {
        assertFalse(ZeekrDisplays.isCluster(1920, 1080))
        assertFalse(ZeekrDisplays.isHud(2560, 536))
        assertFalse(ZeekrDisplays.isCenter(1024, 576))
    }
}
