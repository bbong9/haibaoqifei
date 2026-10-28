package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.glance.CarPlayGlance
import com.shilapi.xcertplay.glance.RouteGuidanceDiagnostics
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class RouteGuidanceDiagnosticsReportTest {
    @Test fun guidanceChangesReachProcessLogWithoutAnActivityListener() {
        val previous = CarPlayGlance.routeGuidanceListener
        CarPlayGlance.routeGuidanceListener = null
        ShadowLog.clear()
        try {
            val frame = Iap2Messages.buildRaw(0x5201) {
                string(0x13, "百度地图")
                string(0x03, "private-road-marker")
                u8(0xfef1, 1)
            }
            CarPlayGlance.onFrame(frame)
            CarPlayGlance.onFrame(frame)
            val logs = ShadowLog.getLogsForTag("DiPlay-RouteGuidance")
            assertEquals(1, logs.size)
            val line = logs.single().msg
            assertTrue(line.contains("Route guidance: app=BAIDU params=3,19,65265"))
            assertEquals(line, DiagnosticRedactor.redact(line))
            assertFalse(line.contains("百度地图"))
            assertFalse(line.contains("private-road-marker"))
        } finally {
            CarPlayGlance.routeGuidanceListener = previous
        }
    }

    @Test fun sessionLogAndReportKeepOnlySafeGuidanceMetadata() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.cacheDir, "route-guidance-diagnostics-test.log")
        file.delete()
        val session = SessionLogFile(file)
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val sessionField = host.javaClass.getDeclaredField("sessionLog").apply { isAccessible = true }
        sessionField.set(host, session)
        @Suppress("UNCHECKED_CAST")
        val listener = host.javaClass.getDeclaredField("routeGuidanceListener").apply { isAccessible = true }
            .get(host) as (RouteGuidanceDiagnostics.Snapshot) -> Unit
        CarPlayGlance.routeGuidanceListener = listener
        try {
            val frame = Iap2Messages.buildRaw(0x5201) {
                string(0x13, "秘密导航")
                string(0x03, "private-road-marker")
                string(0x04, "private-destination-marker")
                u16(0x10, 1); u16(0x11, 3); u8(0x12, 1); u8(0x14, 0)
                u8(0xfefe, 7)
            }
            CarPlayGlance.onFrame(frame)
            CarPlayGlance.onFrame(frame)
            assertTrue(AsyncDiagnosticLog.awaitIdle(5_000))
            val logs = file.readLines()
            assertEquals(1, logs.size)
            assertTrue(logs.single().contains("Route guidance: app=OTHER params=3,4,16,17,18,19,20,65278"))
            assertEquals(logs.single(), DiagnosticRedactor.redact(logs.single()))

            val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
            val report = activity.javaClass.getDeclaredMethod("diagnosticReportText", Context::class.java)
                .apply { isAccessible = true }.invoke(activity, context) as String
            val line = report.lineSequence().single { it.startsWith("guidanceApp=") }
            assertEquals("guidanceApp=OTHER guidanceParams=3,4,16,17,18,19,20,65278 " +
                "laneShowing=1 laneTotal=3 laneIndex=1 sourceSupports=0", line)
            assertEquals(line, DiagnosticRedactor.redact(line))
            for (privateValue in listOf("秘密导航", "private-road-marker", "private-destination-marker")) {
                assertFalse(logs.joinToString("\n").contains(privateValue))
                assertFalse(report.contains(privateValue))
            }
        } finally {
            CarPlayGlance.routeGuidanceListener = null
            session.close()
            sessionField.set(host, null)
            file.delete()
        }
    }
}
