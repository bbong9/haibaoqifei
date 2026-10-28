package com.shilapi.xcertplay

import android.graphics.RectF
import com.shilapi.xcertplay.airplay.SafeAreaRect
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ClusterMirrorLayoutTest {
    private val safe = SafeAreaRect(513, 111, 1327, 537)

    @Test fun safeAreaFitsAndCentersTheCompleteSource() {
        val rect = ClusterMirrorLayout.plan(1920, 720, 1940, 1092, safe, ClusterMirrorSize.SAFE_AREA)
        assertEquals(756.8f, rect.width(), 0.2f)
        assertEquals(426f, rect.height(), 0.01f)
        assertEquals(920f, rect.centerX(), 0.01f)
        assertEquals(324f, rect.centerY(), 0.01f)
    }

    @Test fun fullHeightUsesSafeAreaHorizontalCenter() {
        val rect = ClusterMirrorLayout.plan(1920, 720, 1940, 1092, safe, ClusterMirrorSize.FULL_HEIGHT)
        assertEquals(1279.1f, rect.width(), 0.2f)
        assertEquals(720f, rect.height(), 0.01f)
        assertEquals(920f, rect.centerX(), 0.01f)
        assertEquals(0f, rect.top, 0.01f)
    }

    @Test fun zeroDimensionsReturnTheViewWithoutThrowing() {
        assertEquals(RectF(0f, 0f, 1920f, 720f),
            ClusterMirrorLayout.plan(1920, 720, 0, 1092, safe, ClusterMirrorSize.SAFE_AREA))
        assertEquals(RectF(0f, 0f, 0f, 720f),
            ClusterMirrorLayout.plan(0, 720, 1940, 1092, safe, ClusterMirrorSize.FULL_HEIGHT))
    }

    @Test fun croppedAspectFitsTheSafeAreaAndTheFullHeight() {
        val keep = ClusterMirrorCrop.keep(1940, 1092, null, false, 7)
        val inside = ClusterMirrorLayout.plan(1920, 720, keep.width().toInt(), keep.height().toInt(), safe,
            ClusterMirrorSize.SAFE_AREA)
        assertEquals(703.7f, inside.width(), 0.2f)
        assertEquals(426f, inside.height(), 0.01f)
        assertEquals(920f, inside.centerX(), 0.01f)
        val full = ClusterMirrorLayout.plan(1920, 720, keep.width().toInt(), keep.height().toInt(), safe,
            ClusterMirrorSize.FULL_HEIGHT)
        assertEquals(1189.45f, full.width(), 0.2f)
        assertEquals(720f, full.height(), 0.01f)
        assertEquals(920f, full.centerX(), 0.01f)
    }
}
