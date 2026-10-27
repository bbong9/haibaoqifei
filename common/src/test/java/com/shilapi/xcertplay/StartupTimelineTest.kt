package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.hardware.usb.UsbManager
import android.provider.Settings
import com.shilapi.xcertplay.host.R
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class StartupTimelineTest {
    @get:Rule val directory = TemporaryFolder()
    private lateinit var context: Context

    @Before fun setUp() {
        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
        ReflectionHelpers.setStaticField(StartupTimeline::class.java, "target", null)
        val app = RuntimeEnvironment.getApplication()
        context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory.root
        }
        val info = app.packageManager.getPackageInfo(app.packageName, 0).apply {
            versionName = "0.2.14-seal.5"
            longVersionCode = 37
        }
        shadowOf(app.packageManager).installPackage(info)
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 23)
    }

    @After fun tearDown() {
        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
        ReflectionHelpers.setStaticField(StartupTimeline::class.java, "target", null)
    }

    @Test fun formatsNullValuesWithoutReadingRuntimeState() {
        assertEquals(
            "up=12345 pid=42 AUTO_CONNECT_DECISION storedMode=none resolvedMode=WIRELESS",
            StartupTimeline.formatLine(12345, 42, "AUTO_CONNECT_DECISION", listOf("storedMode" to null, "resolvedMode" to "WIRELESS")),
        )
    }

    @Test fun replacesWhitespaceInValues() {
        assertEquals(
            "up=12345 pid=42 HOST_RESTART reason=switching_to_USB",
            StartupTimeline.formatLine(12345, 42, "HOST_RESTART", listOf("reason" to "switching to USB")),
        )
        assertEquals(
            "up=12345 pid=42 HOST_RESTART reason=one_two_three",
            StartupTimeline.formatLine(12345, 42, "HOST_RESTART", listOf("reason" to "one\ttwo\nthree")),
        )
    }

    @Test fun everyTableEventAndAllItsFieldsSurviveRedaction() {
        val expectedFields = mapOf(
            "PROCESS_START" to setOf("boot", "date", "version"),
            "BOOT_RECEIVED" to setOf("launchEnabled"),
            "BOOT_LAUNCH" to setOf("result", "error"),
            "UI_CREATE" to setOf("action", "launcherCategory", "pageExtra", "isLauncher", "hasSession", "restored"),
            "UI_FORWARD" to setOf("from", "usbAttached"),
            "UI_NEW_INTENT" to setOf("action", "launcherCategory", "pageExtra", "isLauncher", "hasSession"),
            "UI_RESUME" to setOf("initialLaunch"), "UI_PAUSE" to emptySet(),
            "AUTO_CONNECT_DECISION" to setOf("setupError", "hasSession", "autoConnect", "pageExtra", "storedMode", "resolvedMode", "lastWireless", "usbAttached", "chooseWireless", "action"),
            "CONNECT_REQUEST" to setOf("wireless", "hasSession"),
            "CONNECT_ABORT" to setOf("wireless", "reason"),
            "OPEN_PROJECTION" to setOf("preferUsb", "usbIntent"),
            "UI_USB_OBSERVER" to emptySet(), "UI_USB_OBSERVER_CLOSED" to emptySet(),
            "UI_USB_ATTACHED" to setOf("launched"),
            "HOST_CREATE" to setOf("action", "usbIntent", "wirelessEnabled"),
            "HOST_NEW_INTENT" to setOf("action", "usbIntent", "wirelessEnabled", "controller", "switchToUsb"),
            "HOST_RESUME" to emptySet(), "HOST_PAUSE" to emptySet(), "HOST_STOP" to emptySet(),
            "HOST_START_STACK" to setOf("generation", "wireless"),
            "HOST_STATUS" to setOf("generation", "current", "status", "dropped"),
            "HOST_RESTART" to setOf("generation", "reason"),
            "HOST_STREAM" to setOf("generation", "current", "type", "active"),
        )
        val cases = mutableListOf(
            case(0, "PROCESS_START", "boot" to 23, "date" to "2026-10-09", "version" to "0.2.14-seal.5/37"),
            case(1, "BOOT_RECEIVED", "launchEnabled" to true),
            case(2, "BOOT_LAUNCH", "result" to "ok", "error" to null),
            case(2, "BOOT_LAUNCH", "result" to "failed", "error" to "RuntimeException"),
            case(3, "UI_CREATE", "action" to null, "launcherCategory" to false, "pageExtra" to false, "isLauncher" to false, "hasSession" to false, "restored" to false),
            case(4, "UI_FORWARD", "from" to "create", "usbAttached" to true),
            case(5, "UI_NEW_INTENT", "action" to Intent.ACTION_MAIN, "launcherCategory" to true, "pageExtra" to false, "isLauncher" to true, "hasSession" to true),
            case(6, "UI_FORWARD", "from" to "newIntent", "usbAttached" to false),
            case(7, "UI_RESUME", "initialLaunch" to true), case(7, "UI_PAUSE"),
            autoConnectCase(chooseWireless = true, action = "post"),
            autoConnectCase(chooseWireless = false, action = "post", usbAttached = true),
            autoConnectCase(chooseWireless = "skipped", action = "skip"),
            case(9, "CONNECT_REQUEST", "wireless" to false, "hasSession" to false),
            case(11, "OPEN_PROJECTION", "preferUsb" to true, "usbIntent" to true),
            case(12, "UI_USB_OBSERVER"), case(12, "UI_USB_OBSERVER_CLOSED"),
            case(12, "UI_USB_ATTACHED", "launched" to true), case(12, "UI_USB_ATTACHED", "launched" to false),
            case(13, "HOST_CREATE", "action" to UsbManager.ACTION_USB_DEVICE_ATTACHED, "usbIntent" to true, "wirelessEnabled" to false),
            case(14, "HOST_NEW_INTENT", "action" to UsbManager.ACTION_USB_DEVICE_ATTACHED, "usbIntent" to true, "wirelessEnabled" to true, "controller" to true, "switchToUsb" to true),
            case(14, "HOST_NEW_INTENT", "action" to Intent.ACTION_MAIN, "usbIntent" to false, "wirelessEnabled" to false, "controller" to false, "switchToUsb" to false),
            case(15, "HOST_RESUME"), case(15, "HOST_PAUSE"), case(15, "HOST_STOP"),
            case(16, "HOST_START_STACK", "generation" to 2, "wireless" to false),
            case(18, "HOST_RESTART", "generation" to 3, "reason" to "switching to USB"),
            case(19, "HOST_STREAM", "generation" to 3, "current" to 3, "type" to 0, "active" to true),
            case(19, "HOST_STREAM", "generation" to 2, "current" to 3, "type" to 0, "active" to false),
        )
        listOf("pendingHotspotSetup", "setupError", "manualHotspotInvalid", "carHotspotOff", "noPairedPhone", "notificationPermission").forEach {
            cases += case(10, "CONNECT_ABORT", "wireless" to true, "reason" to it)
        }
        listOf("stale", "menu", "no").forEach {
            cases += case(17, "HOST_STATUS", "generation" to 2, "current" to 3, "status" to "SelectingConfiguration", "dropped" to it)
        }

        assertEquals((0..19).toSet(), cases.map { it.row }.toSet())
        assertEquals(expectedFields.keys, cases.map { it.event }.toSet())
        for (case in cases) {
            assertEquals("T${case.row} ${case.event} fields", expectedFields[case.event], case.fields.map { it.first }.toSet())
            val line = StartupTimeline.formatLine(12345, 42, case.event, case.fields)
            val safe = DiagnosticRedactor.redact(line)
            assertNotNull("T${case.row} ${case.event} was dropped: $line", safe)
            assertEquals("T${case.row} ${case.event} must retain up and the event", line, safe)
            println("SURVIVES T${case.row} $line")
        }
    }

    @Test fun writesOncePerProcessOnlyInTheStartupDirectory() {
        val hostLog = File(context.filesDir, "logs/diplay.log")
        assertFalse(hostLog.exists())
        writeSampleEvents()
        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
        val timeline = File(context.filesDir, "logs/startup/timeline.log")
        assertTrue(timeline.isFile)
        val lines = timeline.readLines()
        assertEquals(1, lines.count { it.contains(" PROCESS_START ") })
        assertTrue(lines.first().contains(" PROCESS_START "))
        assertTrue(lines.any { it.contains(" UI_CREATE ") })
        assertTrue(lines.any { it.contains(" AUTO_CONNECT_DECISION ") })
        assertFalse(hostLog.exists())
        assertFalse(File(timeline.parentFile, "previous.log").exists())
        println("TIMELINE_SAMPLE_BEGIN")
        lines.forEach(::println)
        println("TIMELINE_SAMPLE_END")
    }

    @Test fun preservesExistingHostAndTimelineLogs() {
        val hostLog = File(context.filesDir, "logs/diplay.log")
        hostLog.parentFile!!.mkdirs()
        hostLog.writeText("existing host session\n")
        val originalHost = hostLog.readBytes()
        val timeline = File(context.filesDir, "logs/startup/timeline.log")
        timeline.parentFile!!.mkdirs()
        timeline.writeText("earlier startup event\n")
        writeSampleEvents()
        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
        assertArrayEquals(originalHost, hostLog.readBytes())
        assertTrue(timeline.readText().startsWith("earlier startup event\n"))
        assertEquals(1, timeline.readLines().count { it.contains(" PROCESS_START ") })
        assertFalse(File(timeline.parentFile, "previous.log").exists())
    }

    @Test fun summarizesEveryCompleteSegmentAndRendersTheDiagnosticText() {
        val summary = StartupTimeline.summarize(completeEvents()).single()
        assertEquals(9, summary.bootCount)
        assertEquals(1, summary.generation)
        assertEquals(9800L, summary.boot)
        assertEquals(2100L, summary.launch)
        assertEquals(150L, summary.decide)
        assertEquals(900L, summary.mfi)
        assertEquals(40L, summary.cleanup)
        assertEquals(1800L, summary.wifi)
        assertEquals(12040L, summary.bluetooth)
        assertEquals(1500L, summary.iap2)
        assertEquals(6100L, summary.iphoneJoin)
        assertEquals(820L, summary.session)
        assertEquals(60L, summary.bonjour)
        assertEquals(35460L, summary.bootToFrame)
        assertEquals(23410L, summary.connectToFrame)
        assertEquals("bluetooth" to 12040L, summary.slowest)
        assertEquals(0, summary.retries)
        assertNull(summary.failure)
        val line = summary.formatLine(1)
        assertEquals(line, DiagnosticRedactor.redact(line))
        println("SUMMARY_SAMPLE $line")
        val text = StartupTimeline.renderLastSummary(context, summary)
        assertTrue(text.contains("bluetooth"))
        assertTrue(text.contains("35.5"))
        assertTrue(text.contains("23.4"))
        println("DIAGNOSTICS_RENDER $text")
    }

    @Test fun processWithoutTimingDataRendersNoRecord() {
        val summary = StartupTimeline.summarize(listOf(sample(100, "PROCESS_START", "boot" to 1))).single()
        assertEquals(context.getString(R.string.settings_startup_no_record),
            StartupTimeline.renderLastSummary(context, summary))
    }

    @Test fun partialTimingDataStillRendersTheAvailableSummary() {
        val summary = StartupTimeline.summarize(listOf(
            sample(100, "PROCESS_START", "boot" to 1),
            sample(1000, "BOOT_RECEIVED"),
        )).single()
        assertEquals("boot" to 1000L, summary.slowest)
        assertNotEquals(context.getString(R.string.settings_startup_no_record),
            StartupTimeline.renderLastSummary(context, summary))
    }

    @Test fun missingBluetoothEndpointDoesNotInventDurations() {
        val summary = StartupTimeline.summarize(completeEvents().filterNot { it.contains(" CP_W_BT_CONNECTED ") }).single()
        assertNull(summary.bluetooth)
        assertNull(summary.iap2)
        assertEquals("boot" to 9800L, summary.slowest)
    }

    @Test fun selectsLastGenerationWithAFrameAndRecordsEarlierFailure() {
        val lines = completeEvents().filterNot { it.contains(" FIRST_FRAME ") } + listOf(
            sample(40000, "CP_W_FAIL", "gen" to 1, "failure" to "FIRST_TCP_TIMEOUT"),
            sample(41000, "CP_W_BEGIN", "gen" to 2),
            sample(41040, "CP_W_CLEANUP_DONE", "gen" to 2, "elapsedMs" to 40),
            sample(42000, "CP_W_LINK_READY", "gen" to 2),
            sample(43000, "CP_W_BT_START", "gen" to 2, "attempt" to 1),
            sample(45000, "CP_W_BT_CONNECTED", "gen" to 2, "elapsedMs" to 2000),
            sample(47000, "CP_W_WIFI_INFO_SENT", "gen" to 2),
            sample(49000, "CP_W_FIRST_TCP", "gen" to 2),
            sample(50000, "FIRST_FRAME", "gen" to 2),
            sample(51000, "CP_W_BEGIN", "gen" to 3),
        )
        val summary = StartupTimeline.summarize(lines).single()
        assertEquals(2, summary.generation)
        assertEquals(2, summary.retries)
        assertEquals("FIRST_TCP_TIMEOUT", summary.failure)
        assertEquals(2000L, summary.bluetooth)
        assertEquals(1000L, summary.session)
        assertEquals(50000L, summary.bootToFrame)
        assertEquals(37950L, summary.connectToFrame)
        assertEquals(summary.formatLine(1), DiagnosticRedactor.redact(summary.formatLine(1)))
    }

    @Test fun twoGenerationsWithSecondFrameReportOneRetry() {
        val lines = listOf(
            sample(100, "PROCESS_START", "boot" to 1),
            sample(101, "CP_W_BEGIN", "gen" to 1),
            sample(102, "CP_W_FAIL", "gen" to 1, "failure" to "IOException"),
            sample(103, "CP_W_BEGIN", "gen" to 2),
            sample(104, "FIRST_FRAME", "gen" to 2),
        )
        val summary = StartupTimeline.summarize(lines).single()
        assertEquals(2, summary.generation)
        assertEquals(1, summary.retries)
        assertEquals("IOException", summary.failure)
    }

    @Test fun selectsLastGenerationWhenNeitherReachesAFrame() {
        val lines = listOf(
            sample(100, "PROCESS_START", "boot" to 1),
            sample(101, "CP_W_BEGIN", "gen" to 1),
            sample(102, "CP_W_BEGIN", "gen" to 2),
            sample(103, "CP_W_FAIL", "gen" to 2, "failure" to "IOException"),
        )
        val summary = StartupTimeline.summarize(lines).single()
        assertEquals(2, summary.generation)
        assertNull(summary.bootToFrame)
        assertEquals(1, summary.retries)
        assertEquals("IOException", summary.failure)
    }

    @Test fun aLateProcessHasNoBootToFrameButKeepsConnectToFrame() {
        val summary = StartupTimeline.summarize(completeEvents(offset = 595000)).single()
        assertNull(summary.bootToFrame)
        assertEquals(23410L, summary.connectToFrame)
    }

    @Test fun keepsOnlyTheMostRecentThreeProcesses() {
        val lines = (1..4).flatMap { count -> listOf(
            sample(count * 100L, "PROCESS_START", "boot" to count),
            sample(count * 100L + 1, "BOOT_RECEIVED"),
        ) }
        val summaries = StartupTimeline.summarize(lines)
        assertEquals(listOf(2, 3, 4), summaries.map { it.bootCount })
        summaries.forEachIndexed { index, summary ->
            val line = summary.formatLine(index + 1)
            assertEquals(line, DiagnosticRedactor.redact(line))
        }
    }

    @Test fun diagnosticReportPlacesTheComputedSummaryBeforeTheTimeline() {
        val timeline = File(context.filesDir, "logs/startup/timeline.log")
        timeline.parentFile!!.mkdirs()
        timeline.writeText(completeEvents().joinToString("\n", postfix = "\n"))
        val activity = org.robolectric.Robolectric.buildActivity(DiPlayActivity::class.java).get()
        try {
            val report = ReflectionHelpers.callInstanceMethod<String>(activity, "diagnosticReportText",
                ReflectionHelpers.ClassParameter.from(Context::class.java, context))
            val summary = StartupTimeline.summarize(completeEvents()).single().formatLine(1)
            assertTrue(report.contains(summary))
            assertTrue(report.indexOf("--- Startup summary (ms) ---") < report.indexOf("--- Startup timeline"))
            assertTrue(report.indexOf("--- Startup summary (ms) ---") < report.indexOf("Head unit:"))
        } finally {
            activity.finish()
        }
    }

    @Test fun allWirelessMilestoneSchemasSurviveRedaction() {
        val cases = listOf(
            "HOST_RETRY_SCHEDULED" to listOf("delayMs" to 2000, "waitsFor" to "bluetooth"),
            "HOST_RETRY_SCHEDULED" to listOf("delayMs" to 16000, "waitsFor" to "wifi"),
            "HOST_RETRY_SCHEDULED" to listOf("delayMs" to 30000, "waitsFor" to "none"),
            "HOST_RETRY_EARLY" to listOf("radio" to "bluetooth"),
            "HOST_RETRY_EARLY" to listOf("radio" to "wifi"),
            "CP_MFI_START" to emptyList(),
            "CP_MFI_READY" to listOf("elapsedMs" to 900),
            "CP_W_BEGIN" to listOf("gen" to 1),
            "CP_W_CLEANUP_DONE" to listOf("gen" to 1, "elapsedMs" to 40),
            "CP_W_LINK_READY" to listOf("gen" to 1, "mode" to "MANUAL", "backend" to "MANUAL_HOTSPOT", "band" to 5180, "channel" to 36, "elapsedMs" to 1800),
            "CP_W_BT_START" to listOf("gen" to 1, "attempt" to 1),
            "CP_W_BT_CONNECTED" to listOf("gen" to 1, "elapsedMs" to 12040),
            "CP_W_BT_FAILED" to listOf("gen" to 1, "elapsedMs" to 1000, "error" to "IOException"),
            "CP_W_IAP2_START" to listOf("gen" to 1),
            "CP_W_WIFI_INFO_SENT" to listOf("gen" to 1),
            "CP_W_BONJOUR_START" to listOf("gen" to 1),
            "CP_W_BONJOUR_CREATE" to listOf("gen" to 1, "family" to "IPv4", "elapsedMs" to 20),
            "CP_W_BONJOUR_CREATE" to listOf("gen" to 1, "family" to "IPv6", "elapsedMs" to 30),
            "CP_W_BONJOUR_READY" to listOf("gen" to 1, "elapsedMs" to 60),
            "CP_W_FIRST_TCP" to listOf("gen" to 1),
            "CP_W_SESSION_START" to listOf("gen" to 1),
            "CP_W_FAIL" to listOf("gen" to 1, "failure" to "HOTSPOT_NOT_READY"),
            "FIRST_FRAME" to listOf("gen" to 1),
        )
        for ((event, fields) in cases) {
            for ((key) in fields) {
                val lower = key.lowercase()
                assertFalse("unsafe field: $key", lower.endsWith("name") ||
                    listOf("ssid", "pass", "token", "host").any { it in lower })
            }
            val line = StartupTimeline.formatLine(12345, 42, event, fields)
            assertEquals("$event was redacted", line, DiagnosticRedactor.redact(line))
        }
    }

    private fun completeEvents(offset: Long = 0): List<String> = listOf(
        sample(5000 + offset, "PROCESS_START", "boot" to 9),
        sample(9800 + offset, "BOOT_RECEIVED"),
        sample(11900 + offset, "UI_CREATE"),
        sample(12050 + offset, "CONNECT_REQUEST"),
        sample(12100 + offset, "CP_MFI_START"),
        sample(13000 + offset, "CP_MFI_READY", "elapsedMs" to 900),
        sample(13000 + offset, "CP_W_BEGIN", "gen" to 1),
        sample(13040 + offset, "CP_W_CLEANUP_DONE", "gen" to 1, "elapsedMs" to 40),
        sample(14840 + offset, "CP_W_LINK_READY", "gen" to 1),
        sample(14845 + offset, "CP_W_BONJOUR_START", "gen" to 1),
        sample(14905 + offset, "CP_W_BONJOUR_READY", "gen" to 1, "elapsedMs" to 60),
        sample(15000 + offset, "CP_W_BT_START", "gen" to 1, "attempt" to 1),
        sample(27040 + offset, "CP_W_BT_CONNECTED", "gen" to 1, "elapsedMs" to 12040),
        sample(28540 + offset, "CP_W_WIFI_INFO_SENT", "gen" to 1),
        sample(34640 + offset, "CP_W_FIRST_TCP", "gen" to 1),
        sample(34800 + offset, "CP_W_SESSION_START", "gen" to 1),
        sample(35460 + offset, "FIRST_FRAME", "gen" to 1),
    )

    private fun sample(up: Long, event: String, vararg fields: Pair<String, Any?>): String =
        "09:00:00.000  " + StartupTimeline.formatLine(up, 42, event, fields.toList())

    private fun writeSampleEvents() {
        StartupTimeline.event(context, "UI_CREATE", "action" to null, "launcherCategory" to false,
            "pageExtra" to false, "isLauncher" to false, "hasSession" to false, "restored" to false)
        val decision = autoConnectCase(chooseWireless = true, action = "post")
        StartupTimeline.event(context, decision.event, *decision.fields.toTypedArray())
    }

    private fun autoConnectCase(chooseWireless: Any, action: String, usbAttached: Boolean = false) = case(
        8, "AUTO_CONNECT_DECISION", "setupError" to false, "hasSession" to false,
        "autoConnect" to true, "pageExtra" to false, "storedMode" to "unset", "resolvedMode" to "WIRELESS",
        "lastWireless" to true, "usbAttached" to usbAttached, "chooseWireless" to chooseWireless, "action" to action,
    )

    private data class EventCase(val row: Int, val event: String, val fields: List<Pair<String, Any?>>)
    private fun case(row: Int, event: String, vararg fields: Pair<String, Any?>) = EventCase(row, event, fields.toList())
}
