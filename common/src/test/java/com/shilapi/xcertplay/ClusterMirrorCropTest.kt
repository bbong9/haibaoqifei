package com.shilapi.xcertplay

import android.graphics.RectF
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ClusterMirrorCropTest {
    @Test fun driverSideCropsTheLeftForLeftHandDrive() {
        assertEquals(RectF(136f, 0f, 1940f, 1092f),
            ClusterMirrorCrop.keep(1940, 1092, AirPlayInfoPlist.DOCK_EDGE_DRIVER_SIDE, false, 7))
    }

    @Test fun driverSideCropsTheRightForRightHandDrive() {
        assertEquals(RectF(0f, 0f, 1804f, 1092f),
            ClusterMirrorCrop.keep(1940, 1092, AirPlayInfoPlist.DOCK_EDGE_DRIVER_SIDE, true, 7))
    }

    @Test fun bottomDockUsesTheSourceHeight() {
        assertEquals(RectF(0f, 0f, 1940f, 1016f),
            ClusterMirrorCrop.keep(1940, 1092, AirPlayInfoPlist.DOCK_EDGE_BOTTOM, false, 7))
    }

    @Test fun zeroCropKeepsTheWholeSource() {
        assertEquals(RectF(0f, 0f, 1940f, 1092f), ClusterMirrorCrop.keep(1940, 1092, null, false, 0))
    }

    @Test fun percentageIsClampedToTwenty() {
        assertEquals(RectF(388f, 0f, 1940f, 1092f), ClusterMirrorCrop.keep(1940, 1092, null, false, 90))
        assertEquals(RectF(0f, 0f, 1940f, 1092f), ClusterMirrorCrop.keep(1940, 1092, null, false, -1))
    }

    @Test fun automaticDockUsesTheDrivingSide() {
        assertEquals(RectF(136f, 0f, 1940f, 1092f), ClusterMirrorCrop.keep(1940, 1092, null, false, 7))
        assertEquals(RectF(0f, 0f, 1804f, 1092f), ClusterMirrorCrop.keep(1940, 1092, null, true, 7))
    }
}
