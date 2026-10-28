package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClusterMirrorPerformancePointTest {
    @Test fun platformPointStringKeepsOnlyDimensionsAndRate() {
        assertEquals("1920x1088@60", formatPerformancePoint("PerformancePoint(1920x1088@60, max 120fps)"))
        assertEquals("3840x2160@30", formatPerformancePoint("3840 x 2160 @ 30"))
        assertNull(formatPerformancePoint("unavailable"))
    }
}
