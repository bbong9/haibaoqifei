package com.shilapi.xcertplay

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.util.concurrent.PausedExecutorService
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en", manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WirelessSlowRetryTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var teardown: PausedExecutorService
    private lateinit var log: File
    private val failure = CarPlayStatus.Failed("timeout", startupFailure = WirelessStartupFailure.FIRST_TCP_TIMEOUT)
    private val selected = "12:34:56:78:9A:BC"

    @Before fun setUp() {
        CarPlayBackgroundSession.clear()
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        activity.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit().remove("phone_address").commit()
        ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", activity)
        ReflectionHelpers.setField(activity, "wirelessEnabled", true)
        ReflectionHelpers.setField(activity, "startupRetryButton", Button(activity).apply { visibility = View.GONE })
        ReflectionHelpers.getField<ExecutorService>(activity, "teardownExecutor").shutdownNow()
        teardown = PausedExecutorService()
        ReflectionHelpers.setField(activity, "teardownExecutor", teardown)
        val sizeClass = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")
        val size = sizeClass.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(1920, 990)
        ReflectionHelpers.setField(activity, "activeDisplaySize", size)
        ReflectionHelpers.setField(activity, "controller", mock(CarPlayController::class.java))
        log = File.createTempFile("slow-retry", ".log", activity.filesDir)
        ReflectionHelpers.setField(activity, "sessionLog", SessionLogFile(log))
    }

    @After fun tearDown() {
        ReflectionHelpers.getField<Handler>(activity, "mainHandler").removeCallbacksAndMessages(null)
        teardown.shutdownNow()
        ReflectionHelpers.getField<ExecutorService>(activity, "airPlayCommandExecutor").shutdownNow()
        CarPlayBackgroundSession.clear()
    }

    private fun report(status: CarPlayStatus = failure) {
        val generation = ReflectionHelpers.getField<Int>(activity, "restartGeneration")
        ReflectionHelpers.callInstanceMethod<(CarPlayStatus) -> Unit>(activity, "createStatusReporter",
            from(Int::class.javaPrimitiveType, generation))(status)
    }

    private fun budget() = ReflectionHelpers.getField<WirelessStartupRetryBudget>(activity, "startupRetryBudget")
    private fun generation() = ReflectionHelpers.getField<Int>(activity, "restartGeneration")
    private fun stopped() = ReflectionHelpers.getField<Boolean>(activity, "startupRetryStopped")
    private fun scheduled() = ReflectionHelpers.getField<Runnable?>(activity, "slowRetry")
    private fun idle(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(seconds, TimeUnit.SECONDS)

    private fun exhaustBudget() {
        repeat(5) { assertNotNull(budget().nextDelayMillis()) }
        report()
        assertTrue(stopped())
        assertNotNull(scheduled())
    }

    private fun acl(address: String) {
        val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address)
        ReflectionHelpers.getField<BroadcastReceiver>(activity, "bluetoothConnectedReceiver").onReceive(activity,
            Intent(BluetoothDevice.ACTION_ACL_CONNECTED).putExtra(BluetoothDevice.EXTRA_DEVICE, device))
    }

    @Test fun exhaustedBudgetRetriesAfterThirtySecondsAndDoesNotRestartTheFastBudget() {
        val stage = TextView(activity)
        ReflectionHelpers.setField(activity, "stageStatusView", stage)
        val delays = listOf(2L, 4L, 8L, 16L, 30L)
        for (delay in delays) {
            report()
            assertTrue(ReflectionHelpers.getField(activity, "reconnectScheduled"))
            idle(delay)
            teardown.runAll()
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals(5, budget().retries)
        report()
        assertTrue(stopped())
        assertTrue(log.readText().contains("wireless startup recovery stopped"))
        assertTrue(log.readText().contains("wireless startup slow retry in 30000ms"))
        assertTrue(stage.text.toString().contains("every 30 seconds"))
        val previous = generation()
        idle(29)
        assertEquals(previous, generation())
        idle(1)
        assertEquals(previous + 1, generation())
        assertEquals(5, budget().retries)
        teardown.runAll()
        shadowOf(Looper.getMainLooper()).idle()
        report()
        assertTrue(stopped())
        assertNotNull(scheduled())
        idle(2)
        assertEquals(previous + 1, generation())
        idle(28)
        assertEquals(previous + 2, generation())
    }

    @Test fun onlySelectedBluetoothDeviceTriggersThePendingRetryAndAddressIsNotLogged() {
        exhaustBudget()
        DiPlayPreferences.savePhone(activity, selected.lowercase(), "iPhone")
        acl("AA:BB:CC:DD:EE:FF")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, generation())
        acl(selected)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, generation())
        assertTrue(log.readText().contains("wireless startup slow retry now trigger=bluetooth"))
        assertFalse(log.readText().contains(selected, ignoreCase = true))
    }

    @Test fun anyBluetoothDeviceTriggersWhenNoPhoneIsSelected() {
        exhaustBudget()
        acl(selected)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, generation())
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun waitingMessageUsesChineseAndSurvivesLaterFailuresOfTheStoppedAttempt() {
        val stage = TextView(activity)
        ReflectionHelpers.setField(activity, "stageStatusView", stage)
        exhaustBudget()
        val waiting = stage.text.toString()
        assertTrue(waiting.endsWith("正在等待 iPhone，每 30 秒自动再试一次，也可以立即重试。"))
        report()
        report(CarPlayStatus.Failed("Bluetooth socket closed"))
        assertEquals(waiting, stage.text.toString())
        assertNotNull(scheduled())
    }

    @Test
    @Config(sdk = [31, 33])
    fun selectedBluetoothDeviceRequiresConnectPermissionOnAndroidTwelveAndLater() {
        exhaustBudget()
        DiPlayPreferences.savePhone(activity, selected, "iPhone")
        shadowOf(activity.application).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        acl(selected)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, generation())
        assertNotNull(scheduled())
        shadowOf(activity.application).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        acl(selected)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, generation())
    }

    @Test fun nonRetryableFailureDoesNotScheduleSlowRetry() {
        val stage = TextView(activity)
        ReflectionHelpers.setField(activity, "stageStatusView", stage)
        report(CarPlayStatus.Failed("hotspot", startupFailure = WirelessStartupFailure.HOTSPOT_CONFIGURATION))
        assertTrue(stopped())
        assertNull(scheduled())
        assertTrue(stage.text.toString().contains(activity.getString(R.string.wireless_startup_retries_exhausted)))
        idle(30)
        assertEquals(0, generation())
    }

    @Test fun wiredFailureDoesNotScheduleSlowRetry() {
        ReflectionHelpers.setField(activity, "wirelessEnabled", false)
        repeat(5) { budget().nextDelayMillis() }
        val stage = TextView(activity)
        ReflectionHelpers.setField(activity, "stageStatusView", stage)
        report()
        assertTrue(stopped())
        assertNull(scheduled())
        assertTrue(stage.text.toString().contains(activity.getString(R.string.wireless_startup_retries_exhausted)))
        idle(30)
        assertEquals(0, generation())
    }

    @Test fun resumingThePageRunsOnlyAPendingSlowRetry() {
        AirPlayPersistence.saveWirelessEnabled(activity, true)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onResume")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, generation())
        exhaustBudget()
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onResume")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, generation())
        assertTrue(log.readText().contains("wireless startup slow retry now trigger=resume"))
        idle(30)
        assertEquals(1, generation())
    }

    @Test fun manualRetryCancelsAnAlreadyPostedSlowRetry() {
        ReflectionHelpers.callInstanceMethod<View>(activity, "buildContentView")
        exhaustBudget()
        acl(selected)
        val retry = ReflectionHelpers.getField<View>(activity, "startupRetryButton")
        assertTrue(retry.performClick())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, generation())
        assertEquals(0, budget().retries)
        assertNull(scheduled())
        idle(30)
        assertEquals(1, generation())
    }

    @Test fun savingSettingsAndReconnectingCancelsTheSlowRetry() {
        exhaustBudget()
        ReflectionHelpers.setField(activity, "menuOpen", true)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "finishSettingsMenu",
            from(String::class.java, "Settings saved"), from(Boolean::class.javaPrimitiveType, true))
        assertEquals(1, generation())
        assertEquals(0, budget().retries)
        assertNull(scheduled())
        idle(30)
        assertEquals(1, generation())
    }

    @Test fun shutdownCancelsTheSlowRetry() {
        exhaustBudget()
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "shutdown",
            from(Boolean::class.javaPrimitiveType, false), from(String::class.java, "test"),
            from(Function0::class.java, {}))
        assertNull(scheduled())
        assertTrue(ReflectionHelpers.getField<AtomicBoolean>(activity, "shuttingDown").get())
        idle(30)
        assertEquals(1, generation())
    }

    @Test fun firstVideoFrameCancelsTheSlowRetryWithoutResettingTheBudget() {
        exhaustBudget()
        val session = mock(AirPlaySession::class.java)
        ReflectionHelpers.setField(activity, "activeAirPlaySession", session)
        val listener = ReflectionHelpers.callInstanceMethod<AirPlaySessionListener>(activity, "createSessionListener",
            from(Int::class.javaPrimitiveType, 0))
        listener.onVideoFrameRendered(session)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(scheduled())
        assertEquals(5, budget().retries)
        idle(30)
        assertEquals(0, generation())
        assertEquals(5, budget().retries)
    }

    @Test fun destroyingTheActivityCancelsTheSlowRetry() {
        exhaustBudget()
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onDestroy")
        assertNull(scheduled())
        idle(30)
        assertEquals(0, generation())
    }

    @Test fun aSlowRetryDueInTheMenuResumesAfterClosingAndCanRetryAgain() {
        exhaustBudget()
        ReflectionHelpers.setField(activity, "menuOpen", true)
        idle(30)
        assertEquals(0, generation())
        assertTrue(ReflectionHelpers.getField(activity, "recoveryPendingAfterMenu"))
        assertNull(scheduled())
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "finishSettingsMenu",
            from(String::class.java, "Settings changes discarded"), from(Boolean::class.javaPrimitiveType, false))
        assertEquals(1, generation())
        assertEquals(5, budget().retries)
        teardown.runAll()
        shadowOf(Looper.getMainLooper()).idle()
        report()
        assertTrue(stopped())
        assertNotNull(scheduled())
        idle(2)
        assertEquals(1, generation())
        idle(28)
        assertEquals(2, generation())
    }

    @Test fun aRepeatedFailureWhileTheSlowRetryIsDeferredByTheMenuDoesNotLoseRecovery() {
        exhaustBudget()
        ReflectionHelpers.setField(activity, "menuOpen", true)
        idle(30)
        report()
        assertEquals(0, generation())
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "finishSettingsMenu",
            from(String::class.java, "Settings changes discarded"), from(Boolean::class.javaPrimitiveType, false))
        assertEquals(1, generation())
        assertEquals(5, budget().retries)
        teardown.runAll()
        shadowOf(Looper.getMainLooper()).idle()
        report()
        assertNotNull(scheduled())
    }

    @Test fun slowRetryGuardsRejectAChangedOwnerGenerationOrHandshake() {
        exhaustBudget()
        ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", Any())
        idle(30)
        assertEquals(0, generation())
        ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", activity)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "scheduleSlowRetry")
        ReflectionHelpers.setField(activity, "restartGeneration", 1)
        idle(30)
        assertEquals(1, generation())
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "scheduleSlowRetry")
        ReflectionHelpers.setField(activity, "handshakeResetInProgress", true)
        idle(30)
        assertEquals(1, generation())
        assertNull(scheduled())
    }

    @Test fun slowRetryTimelineEventsSurviveRedactionWithoutBluetoothAddresses() {
        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
        val previous = ReflectionHelpers.getStaticField<SessionLogFile?>(StartupTimeline::class.java, "target")
        val timeline = File.createTempFile("slow-timeline", ".log", activity.filesDir)
        ReflectionHelpers.setStaticField(StartupTimeline::class.java, "target", SessionLogFile(timeline))
        try {
            exhaustBudget()
            DiPlayPreferences.savePhone(activity, selected, "iPhone")
            acl(selected)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
            val lines = timeline.readLines().filter { it.contains("HOST_SLOW_RETRY") }
            assertEquals(2, lines.size)
            assertTrue(lines.any { it.contains("HOST_SLOW_RETRY_SCHEDULED delayMs=30000") })
            assertTrue(lines.any { it.contains("HOST_SLOW_RETRY trigger=bluetooth") })
            lines.forEach { assertEquals(it, DiagnosticRedactor.redact(it)) }
            assertFalse(timeline.readText().contains(selected, ignoreCase = true))
            assertFalse(log.readText().contains(selected, ignoreCase = true))
        } finally {
            ReflectionHelpers.setStaticField(StartupTimeline::class.java, "target", previous)
        }
    }
}
