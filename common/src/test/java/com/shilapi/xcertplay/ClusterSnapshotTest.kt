package com.shilapi.xcertplay

import com.shilapi.xcertplay.adb.LocalAdb
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ClusterSnapshotTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val pkg = "com.shihab.diplay"
    private val state = ClusterSnapshot.WindowState(1, true, true, true, 42, "OFF")
    private val displayDump = """
        mBaseDisplayInfo=DisplayInfo{"内置屏幕, displayId 0", real 1920 x 1080, flags 1, owner android (uid 1000)}
        mBaseDisplayInfo=DisplayInfo{"fission_bg_xdjaVirtualSurface, displayId 1", real 1920 x 720, flags 0x10, owner com.xdja.containerservice (uid 1000)}
        mBaseDisplayInfo=DisplayInfo{"duplicate, displayId 1", real 10 x 10}
    """.trimIndent()
    private val activityDump = """
        Display #1 (activities from top to bottom):
            * Hist #7: ActivityRecord{a u0 com.byd.automap/.extra.MeterActivity t1}
            * Hist #6: ActivityRecord{b u0 com.shihab.diplay/com.shilapi.xcertplay.AdbClusterActivity t2}
            * Hist #5: ActivityRecord{c u0 com.example.private/.PersonalActivity t3}
            * Hist #4: ActivityRecord{d u0 com.xdja.containerservice/.View t4}
            * Hist #3: ActivityRecord{e u0 com.android.launcher/.Home t5}
            * Hist #2: ActivityRecord{f u0 android/.Fallback t6}
            * Hist #1: ActivityRecord{g u0 com.byd.other/.Seventh t7}
        ResumedActivity: ActivityRecord{x u0 com.example.leak/.Activity t8}
    """.trimIndent()
    private val layerDump = """
        + BufferLayer (com.byd.automap/.extra.MeterActivity#1)
            layerStack=1, z=20, isHidden=0, alpha=1.000
        + ContainerLayer (fission_bg_xdjaVirtualSurface#2)
            layerStack=1, z=40, flags=0x00000001, alpha=0.75
        + BufferLayer (com.shihab.diplay/AdbClusterActivity#3)
            unsupported=1
        + BufferLayer (com.example.private/Window#4)
            layerStack=9, z=999, alpha=0.5
    """.trimIndent()

    @After fun cleanUp() {
        File(context.filesDir, "cluster-snapshot.txt").delete()
    }

    @Test fun logicalDisplaysKeepFieldsAndDeduplicateDisplayIds() {
        assertEquals(listOf(
            "display id=0 label=内置屏幕 size=1920x1080 flags=1 owner=android",
            "display id=1 label=fission_bg_xdjaVirtualSurface size=1920x720 flags=0x10 owner=com.xdja.containerservice",
        ), ClusterSnapshot.displays(displayDump))
        assertEquals(listOf("display id=5 label=unknown size=- flags=- owner=-"),
            ClusterSnapshot.displays("mBaseDisplayInfo=DisplayInfo{\"unknown, displayId 5\"}"))
        assertEquals(listOf("display id=6 label=unknown size=- flags=- owner=-"),
            ClusterSnapshot.displays("mBaseDisplayInfo=DisplayInfo{\"unknown, displayId 6\", owner null (uid 0)}"))
    }

    @Test fun activityStackPreservesTopOrderMasksOtherAppsAndStopsAtSix() {
        assertEquals(listOf("stack display=1 top=com.byd.automap/.extra.MeterActivity," +
            "com.shihab.diplay/com.shilapi.xcertplay.AdbClusterActivity,other," +
            "com.xdja.containerservice/.View,com.android.launcher/.Home,android/.Fallback"),
            ClusterSnapshot.activities(activityDump, pkg))
    }

    @Test fun surfaceFlingerLayersAreFilteredParsedBoundedAndTolerateMissingFields() {
        assertEquals(listOf(
            "layer label=com.byd.automap/.extra.MeterActivity#1 stack=1 z=20 visible=true alpha=1.000",
            "layer label=fission_bg_xdjaVirtualSurface#2 stack=1 z=40 visible=false alpha=0.75",
            "layer label=com.shihab.diplay/AdbClusterActivity#3 stack=- z=- visible=- alpha=-",
        ), ClusterSnapshot.layers(layerDump, pkg))
        assertTrue(ClusterSnapshot.layers("nonsense\n+ BufferLayer (unclosed", pkg).isEmpty())
        val many = (0..45).joinToString("\n") { "+ BufferLayer (fission_$it#0)\n flags=unparseable" }
        assertEquals(40, ClusterSnapshot.layers(many, pkg).size)
        assertTrue(ClusterSnapshot.layers(many, pkg).all { it.endsWith("visible=- alpha=-") })
        val longLabel = "fission_" + "x".repeat(200)
        assertEquals(120, ClusterSnapshot.layers("+ BufferLayer ($longLabel)", pkg).single()
            .substringAfter("label=").substringBefore(" stack=").length)
        val mixed = "+ BufferLayer (fission#1)\n+ ColorLayer (unrelated#2)\n layerStack=3, z=7, alpha=0.5"
        assertEquals(listOf("layer label=fission#1 stack=- z=- visible=- alpha=-"),
            ClusterSnapshot.layers(mixed, pkg))
        assertEquals(listOf("layer label=xdja#1 stack=- z=- visible=- alpha=-"),
            ClusterSnapshot.layers("+ BufferLayer (xdja#1)\n layerStack=no, z=bad, alpha=bad", pkg))
    }

    @Test fun sensitiveLayerWrapperKeepsTheSystemComponentWithoutLeakingPrivateFields() {
        val dump = "+ BufferLayer (Surface(name=AppWindowToken{ token=private " +
            "ActivityRecord{ u0 com.byd.automap/.extra.MeterActivity t33}}})/@0x12#1)\n" +
            " layerStack=1, z=20, isHidden=0, alpha=1.0"
        val line = ClusterSnapshot.layers(dump, pkg).single()
        assertEquals("layer label=com.byd.automap/.extra.MeterActivity stack=1 z=20 visible=true alpha=1.0", line)
        assertEquals(line, DiagnosticRedactor.redact(line))
        assertFalse(line.contains("private"))
        ClusterSnapshot.save(context, listOf("snapshot trigger=button capturedAtUptimeMs=7", line))
        assertTrue(ClusterSnapshot.report(context).contains(line))
    }

    @Test fun readySnapshotUsesOnlyApprovedCommandsAndEveryLineSurvivesRedaction() {
        val commands = mutableListOf<String>()
        val lines = ClusterSnapshot.collect("auto5s", 12345, LocalAdb.Access.READY, pkg, state) { command ->
            commands += command
            when (command) {
                "dumpsys display" -> displayDump
                "dumpsys activity activities" -> activityDump
                "dumpsys SurfaceFlinger" -> layerDump
                "pm dump com.byd.automap" -> "User 0: ceDataInode=123 installed=true enabled=2 stopped=false"
                else -> error("Unexpected command")
            }
        }
        assertEquals(listOf("dumpsys display", "dumpsys activity activities", "dumpsys SurfaceFlinger",
            "pm dump com.byd.automap"), commands)
        assertEquals("snapshot trigger=auto5s capturedAtUptimeMs=12345", lines.first())
        assertEquals("diplayWindow display=1 confirmed=true streamActive=true surfaceValid=true " +
            "framesPresented=42 stockMapHold=OFF automapEnabled=2", lines.last())
        lines.forEach { line -> assertEquals(line, DiagnosticRedactor.redact(line)) }
        val keys = lines.flatMap { Regex("\\b([A-Za-z][A-Za-z0-9]*)=").findAll(it).map { match -> match.groupValues[1] } }
        assertTrue(keys.all { key ->
            val lower = key.lowercase()
            !lower.endsWith("name") && listOf("ssid", "pass", "token", "host").none(lower::contains)
        })
        ClusterSnapshot.save(context, lines)
        assertEquals(lines.joinToString("\n"), ClusterSnapshot.report(context))
    }

    @Test fun nonReadyAdbRecordsOnlyAccessAndNeverRunsCommands() {
        for (access in LocalAdb.Access.entries.filter { it != LocalAdb.Access.READY }) {
            val lines = ClusterSnapshot.collect("button", 321, access, pkg, state) { error("ADB command was run") }
            assertEquals(listOf("snapshot trigger=button capturedAtUptimeMs=321", "adbAccess=$access"), lines)
            ClusterSnapshot.save(context, lines)
            assertTrue(ClusterSnapshot.report(context).startsWith(lines.joinToString("\n")))
        }
    }

    @Test fun historyRetainsOnlyThreeNewestAndNeverExceedsSixteenKiB() {
        for (index in 1..4) ClusterSnapshot.save(context,
            listOf("snapshot trigger=button capturedAtUptimeMs=$index", "display id=$index label=仪表"))
        var report = ClusterSnapshot.report(context)
        assertEquals(listOf("4", "3", "2"), Regex("capturedAtUptimeMs=(\\d+)")
            .findAll(report).map { it.groupValues[1] }.toList())
        val window = "diplayWindow display=1 confirmed=true streamActive=true surfaceValid=true " +
            "framesPresented=42 stockMapHold=OFF automapEnabled=2"
        val big = listOf("snapshot trigger=button capturedAtUptimeMs=5", "adbAccess=READY") +
            (1..200).map { "display id=$it label=${"仪".repeat(100)}" } + window
        ClusterSnapshot.save(context, big)
        report = ClusterSnapshot.report(context)
        assertTrue(report.startsWith(big.first()))
        assertTrue(report.contains(window))
        assertTrue(report.toByteArray(Charsets.UTF_8).size <= 16 * 1024)
        assertTrue(Regex("(?m)^snapshot trigger=").findAll(report).count() <= 3)
        report.lineSequence().forEach { assertEquals(it, DiagnosticRedactor.redact(it)) }
    }

    @Test fun diagnosticReportContainsSnapshotSectionAndEmptyState() {
        File(context.filesDir, "cluster-snapshot.txt").delete()
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        fun report() = ReflectionHelpers.callInstanceMethod<String>(activity, "diagnosticReportText",
            ReflectionHelpers.ClassParameter.from(android.content.Context::class.java, context))
        assertTrue(report().contains("--- Cluster snapshot ---\nNo cluster snapshot yet."))
        ClusterSnapshot.save(context, listOf("snapshot trigger=button capturedAtUptimeMs=7", "adbAccess=NOT_APPROVED"))
        assertTrue(report().contains("--- Cluster snapshot ---\nsnapshot trigger=button capturedAtUptimeMs=7\nadbAccess=NOT_APPROVED"))
    }
}
