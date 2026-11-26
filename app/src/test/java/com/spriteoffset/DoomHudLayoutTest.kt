package com.spriteoffset

import org.junit.Assert.assertEquals
import org.junit.Test

class DoomHudLayoutTest {
    @Test
    fun pisge0OffsetsPlaceWeaponAtScreenCenterAndStatusBarTop() {
        // PISGE0 from the user's SLADE screenshots: 78x103, offsets -106/-65.
        val rect = DoomHudLayout.imageRect(
            frameLeft = 20f,
            frameTop = 30f,
            scale = 1f,
            imageWidth = 78,
            imageHeight = 103,
            offsetX = -106,
            offsetY = -65
        )

        assertEquals(126f, rect.left, 0.001f)
        assertEquals(95f, rect.top, 0.001f)
        assertEquals(204f, rect.right, 0.001f)
        assertEquals(198f, rect.bottom, 0.001f)
        assertEquals(180f, DoomHudLayout.centerX(20f, 1f), 0.001f)
        assertEquals(198f, DoomHudLayout.statusBarY(30f, 1f), 0.001f)
    }
}
