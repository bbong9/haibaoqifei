package com.shilapi.xcertplay

import android.graphics.RectF
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun coverCropsTopAndBottomForWiderTarget() {
        val keep = RectF(136f, 0f, 1940f, 1092f)
        assertEquals(RectF(136f, 95f, 1940f, 997f), ClusterMirrorCrop.cover(keep, 2f))
        assertEquals(RectF(136f, 0f, 1940f, 1092f), keep)
    }

    @Test fun coverCropsBothSidesForSquareTarget() {
        assertEquals(RectF(492f, 0f, 1584f, 1092f),
            ClusterMirrorCrop.cover(RectF(136f, 0f, 1940f, 1092f), 1f))
    }

    @Test fun coverLeavesMatchingAspectUnchanged() {
        val keep = RectF(136f, 0f, 1940f, 1092f)
        assertEquals(keep, ClusterMirrorCrop.cover(keep, keep.width() / keep.height()))
    }

    @Test fun defaultFrameExactlyMatchesCoverForDifferentAspects() {
        val keep = RectF(136f, 0f, 1940f, 1092f)
        for (aspect in listOf(.5f, 1f, keep.width() / keep.height(), 2f, 3f)) {
            assertEquals(ClusterMirrorCrop.cover(keep, aspect), ClusterMirrorCrop.frame(keep, aspect, 100, 50, 50))
        }
    }

    @Test fun frameFocusAlignsToTopAndBottom() {
        val keep = RectF(136f, 0f, 1940f, 1092f)
        assertEquals(keep.top, ClusterMirrorCrop.frame(keep, 3f, 100, 50, 0).top, .0001f)
        assertEquals(keep.bottom, ClusterMirrorCrop.frame(keep, 3f, 100, 50, 100).bottom, .0001f)
    }

    @Test fun doubleZoomHalvesCoverAndCentersTheFrame() {
        val keep = RectF(136f, 0f, 1940f, 1092f)
        val cover = ClusterMirrorCrop.cover(keep, 3f)
        val frame = ClusterMirrorCrop.frame(keep, 3f, 200, 50, 50)
        assertEquals(cover.width() / 2f, frame.width(), .0001f)
        assertEquals(cover.height() / 2f, frame.height(), .0001f)
        assertEquals(keep.centerX(), frame.centerX(), .0001f)
        assertEquals(keep.centerY(), frame.centerY(), .0001f)
    }

    @Test fun frameClampsParametersAndStaysWithinKeep() {
        val keep = RectF(136f, 10f, 1940f, 1102f)
        assertEquals(ClusterMirrorCrop.frame(keep, 3f, 100, 0, 100),
            ClusterMirrorCrop.frame(keep, 3f, -9, -50, 150))
        assertEquals(ClusterMirrorCrop.frame(keep, 3f, 250, 100, 0),
            ClusterMirrorCrop.frame(keep, 3f, 999, 500, -1))
        for (aspect in listOf(.5f, 1f, 3f)) for (zoom in listOf(-1, 100, 173, 250, 500)) {
            for (x in listOf(-1, 0, 50, 100, 101)) for (y in listOf(-1, 0, 50, 100, 101)) {
                val frame = ClusterMirrorCrop.frame(keep, aspect, zoom, x, y)
                assertTrue("$frame outside $keep", frame.left >= keep.left && frame.top >= keep.top &&
                    frame.right <= keep.right && frame.bottom <= keep.bottom)
            }
        }
    }
}
