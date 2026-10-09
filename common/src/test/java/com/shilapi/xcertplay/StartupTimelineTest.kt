package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.hardware.usb.UsbManager
import android.provider.Settings
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
