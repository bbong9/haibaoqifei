package com.shilapi.xcertplay

import com.shilapi.xcertplay.glance.CarPlayGlance
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.message.LaneGuidanceLane
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class LaneStripDiagnosticsTest {
    @Test fun changeAndReportContainOnlyTheReceivedLaneFields() {
        val context = RuntimeEnvironment.getApplication()
        val lanes = listOf(LaneGuidanceLane(0, 0, -90, null), LaneGuidanceLane(1, 2, 0, 90))
        assertEquals("Lane strip: lanes=2 recommended=1 angles=-90,0 highlights=-,90", LaneStripDiagnostics.change(lanes))
        assertEquals("Lane strip: cleared", LaneStripDiagnostics.change(emptyList()))
        AirPlayPersistence.saveClusterLaneStrip(context, false)
        CarPlayGlance.setConnected(true)
        val previous = CarPlayGlance.laneDiagnostics().updates
        CarPlayGlance.onFrame(Iap2Messages.buildRaw(0x5204) {
            group(2) { u16(0, 0); u8(1, 0); i16(2, -90) }
            group(2) { u16(0, 1); u8(1, 2); i16(2, 0); i16(3, 90) }
        })
        val report = LaneStripDiagnostics.report(context)
        assertTrue(report.contains("laneStrip=off laneUpdates=${previous + 1}"))
        assertTrue(report.contains("lastLanes=0:0:-90:-,1:2:0:90"))
        assertEquals(report, DiagnosticRedactor.redact(report))
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        val text = activity.javaClass.getDeclaredMethod("diagnosticReportText", Context::class.java)
            .apply { isAccessible = true }.invoke(activity, context) as String
        val section = text.substringAfter("--- ADB cluster activity routing ---").substringBefore("--- Standalone HUD compatibility ---")
        assertTrue(section, section.lines().contains(report))
        CarPlayGlance.setConnected(false)
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }
}
