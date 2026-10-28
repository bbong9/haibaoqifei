package com.shilapi.xcertplay.glance

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import org.junit.Assert.*
import org.junit.Test

class RouteGuidanceDiagnosticsTest {
    @Test fun constructedFrameRecordsSortedDistinctIdsAndOnlyNumericLaneValues() {
        val diagnostics = RouteGuidanceDiagnostics()
        val change = diagnostics.accept(Iap2Messages.buildRaw(0x5201) {
            string(0x13, "高德地图")
            u8(0x14, 1)
            u8(0x12, 1)
            u16(0x11, 4)
            u16(0x10, 2)
            string(0x03, "private street")
            u8(0x01, 1)
            u8(0x01, 1)
        })!!

        assertEquals(RouteGuidanceDiagnostics.GuidanceApp.AMAP, change.app)
        assertEquals(listOf(1, 3, 16, 17, 18, 19, 20), change.parameterIds)
        assertEquals(2, change.laneIndex)
        assertEquals(4, change.laneTotal)
        assertEquals(1, change.laneShowing)
        assertEquals(1, change.sourceSupports)
        val log = change.logLine()
        val report = change.reportLine()
        assertEquals("Route guidance: app=AMAP params=1,3,16,17,18,19,20 laneShowing=1 " +
            "laneTotal=4 laneIndex=2 sourceSupports=1", log)
        assertEquals("guidanceApp=AMAP guidanceParams=1,3,16,17,18,19,20 laneShowing=1 " +
            "laneTotal=4 laneIndex=2 sourceSupports=1", report)
        assertFalse(log.contains("高德"))
        assertFalse(report.contains("private street"))
    }

    @Test fun sourceMappingCoversChineseEnglishUnknownAndMissing() {
        val examples = listOf(
            "高德地图" to "AMAP", "AMap" to "AMAP", "AutoNavi Maps" to "AMAP",
            "Maps" to "APPLE_MAPS", "地图" to "APPLE_MAPS", "地圖" to "APPLE_MAPS",
            "百度地图" to "BAIDU", "Baidu Map" to "BAIDU",
            "Waze" to "GOOGLE", "Google Maps" to "GOOGLE", "Private navigation" to "OTHER",
        )
        for ((source, expected) in examples) {
            val diagnostics = RouteGuidanceDiagnostics()
            val update = diagnostics.accept(Iap2Messages.buildRaw(0x5201) { string(0x13, source) })!!
            assertEquals(source, expected, update.app.toString())
            assertFalse(source, update.logLine().contains(source))
            assertFalse(source, update.reportLine().contains(source))
        }
        val missing = RouteGuidanceDiagnostics().accept(Iap2Messages.buildRaw(0x5201) { u8(1, 1) })!!
        assertNull(missing.app)
        assertTrue(missing.reportLine().contains("guidanceApp=-"))
        assertTrue(missing.reportLine().contains("laneShowing=- laneTotal=-"))
    }

    @Test fun emitsOnlyInitiallyOrWhenAppOrParameterSetChanges() {
        val diagnostics = RouteGuidanceDiagnostics()
        fun update(showing: Int, reverse: Boolean = false) = Iap2Messages.buildRaw(0x5201) {
            if (reverse) { u8(0x12, showing); string(0x13, "Maps") }
            else { string(0x13, "Maps"); u8(0x12, showing) }
        }
        assertNotNull(diagnostics.accept(update(1)))
        assertNull(diagnostics.accept(update(1)))
        assertNull(diagnostics.accept(update(0, reverse = true)))
        assertEquals(0, diagnostics.snapshot()!!.laneShowing)
        assertNotNull(diagnostics.accept(Iap2Messages.buildRaw(0x5201) {
            string(0x13, "Baidu"); u8(0x12, 0)
        }))
        assertNotNull(diagnostics.accept(Iap2Messages.buildRaw(0x5201) { string(0x13, "Baidu") }))
        assertNull(diagnostics.accept(Iap2Messages.buildRaw(0x5202) { u8(1, 1) }))
    }

    @Test fun malformedFrameDoesNotChangeTheLastDiagnostics() {
        val diagnostics = RouteGuidanceDiagnostics()
        val good = diagnostics.accept(Iap2Messages.buildRaw(0x5201) { u8(1, 1) })
        assertNull(diagnostics.accept(Iap2Frame(0x5201, byteArrayOf(0, 7, 0, 1, 1))))
        assertEquals(good, diagnostics.snapshot())
        val wrongScalar = diagnostics.accept(Iap2Messages.buildRaw(0x5201) {
            u8(0x11, 4); u16(0x12, 1)
        })!!
        assertEquals(listOf(17, 18), wrongScalar.parameterIds)
        assertNull(wrongScalar.laneTotal)
        assertNull(wrongScalar.laneShowing)
    }

    @Test fun glancePublishesDiagnosticsWithoutChangingExistingNavigation() {
        val changes = mutableListOf<RouteGuidanceDiagnostics.Snapshot>()
        CarPlayGlance.routeGuidanceListener = { changes += it }
        try {
            CarPlayGlance.setConnected(true)
            CarPlayGlance.onFrame(Iap2Messages.buildRaw(0x5202) { u16(1, 7); u8(3, 2) })
            val route = Iap2Messages.buildRaw(0x5201) {
                u8(1, 1); u16List(0x0d, listOf(7)); string(0x13, "地图")
                u16(0x10, 0); u16(0x11, 3); u8(0x12, 1); u8(0x14, 1)
            }
            CarPlayGlance.onFrame(route)
            CarPlayGlance.onFrame(route)
            assertEquals(1, changes.size)
            assertEquals(RouteGuidanceDiagnostics.GuidanceApp.APPLE_MAPS, changes.single().app)
            assertEquals(changes.single(), CarPlayGlance.routeGuidanceDiagnostics())
            assertEquals(2, CarPlayGlance.snapshot().maneuverType)
        } finally {
            CarPlayGlance.routeGuidanceListener = null
            CarPlayGlance.setConnected(false)
        }
    }
}
