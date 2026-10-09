package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayStatus
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en", manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WirelessStartupRecoveryTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var retry: Button
    private val failure = CarPlayStatus.Failed("timeout", startupFailure = WirelessStartupFailure.FIRST_TCP_TIMEOUT)

    @Before fun setup() {
        CarPlayBackgroundSession.clear()
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", activity)
        retry = Button(activity).apply { visibility = View.GONE }
        ReflectionHelpers.setField(activity, "startupRetryButton", retry)
    }

    @After fun cleanup() {
        ReflectionHelpers.getField<Handler>(activity, "mainHandler").removeCallbacksAndMessages(null)
        ReflectionHelpers.getField<ExecutorService>(activity, "teardownExecutor").shutdownNow()
        ReflectionHelpers.getField<ExecutorService>(activity, "airPlayCommandExecutor").shutdownNow()
        CarPlayBackgroundSession.clear()
    }

    private fun report(generation: Int = 0, status: CarPlayStatus = failure) {
        ReflectionHelpers.callInstanceMethod<(CarPlayStatus) -> Unit>(activity, "createStatusReporter",
            from(Int::class.javaPrimitiveType, generation))(status)
    }
    private fun budget() = ReflectionHelpers.getField<WirelessStartupRetryBudget>(activity, "startupRetryBudget")

    private fun attachController(): PausedExecutorService {
        ReflectionHelpers.getField<ExecutorService>(activity, "teardownExecutor").shutdownNow()
        val teardown = PausedExecutorService()
        ReflectionHelpers.setField(activity, "teardownExecutor", teardown)
        val sizeClass = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")
        val size = sizeClass.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(1920, 990)
        ReflectionHelpers.setField(activity, "activeDisplaySize", size)
        ReflectionHelpers.setField(activity, "controller", mock(CarPlayController::class.java))
        return teardown
    }

    @Test fun restartCancelsPendingReconnectWithoutAnotherRestart() {
        val teardown = attachController()
        report(status = CarPlayStatus.Failed("Connection closed"))
        assertTrue(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertEquals(1, ReflectionHelpers.getField<Int>(activity, "reconnectAttempts"))

        ReflectionHelpers.callInstanceMethod<Unit>(activity, "restartCarPlay", from(String::class.java, "switching to USB"))
        assertEquals(1, ReflectionHelpers.getField<Int>(activity, "restartGeneration"))
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))

        teardown.runAll()
        shadowOf(Looper.getMainLooper()).idleFor(31, TimeUnit.SECONDS)
        assertEquals(1, ReflectionHelpers.getField<Int>(activity, "restartGeneration"))
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertEquals(1, ReflectionHelpers.getField<Int>(activity, "reconnectAttempts"))
        assertEquals(0, budget().retries)
    }

    @Test fun replacementGenerationCanScheduleAndExecuteItsOwnReconnect() {
        val teardown = attachController()
        report(status = CarPlayStatus.Failed("Connection closed"))
        assertTrue(ReflectionHelpers.getField(activity, "reconnectScheduled"))

        ReflectionHelpers.callInstanceMethod<Unit>(activity, "restartCarPlay", from(String::class.java, "switching to USB"))
        assertEquals(1, ReflectionHelpers.getField<Int>(activity, "restartGeneration"))
        teardown.runAll()
        shadowOf(Looper.getMainLooper()).idle()

        report(1, CarPlayStatus.Failed("Replacement connection closed"))
        assertTrue(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertEquals(2, ReflectionHelpers.getField<Int>(activity, "reconnectAttempts"))
        shadowOf(Looper.getMainLooper()).idleFor(4, TimeUnit.SECONDS)
        assertEquals(2, ReflectionHelpers.getField<Int>(activity, "restartGeneration"))
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertEquals(2, ReflectionHelpers.getField<Int>(activity, "reconnectAttempts"))
        assertEquals(0, budget().retries)
    }

    @Test fun duplicateFailureAndCleanupErrorScheduleOnlyOneRetry() {
        report()
        report()
        report(status = CarPlayStatus.Failed("Bluetooth socket closed"))
        assertEquals(1, budget().retries)
        assertEquals(1, ReflectionHelpers.getField<Int>(activity, "reconnectAttempts"))
    }

    @Test fun exhaustedBudgetStopsAutomaticRecoveryAndExposesRetry() {
        repeat(6) { generation ->
            ReflectionHelpers.setField(activity, "restartGeneration", generation)
            ReflectionHelpers.setField(activity, "reconnectScheduled", false)
            report(generation)
        }
        assertEquals(5, budget().retries)
        assertTrue(ReflectionHelpers.getField(activity, "startupRetryStopped"))
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertEquals(View.VISIBLE, retry.visibility)
        report(5, CarPlayStatus.Failed("Bluetooth socket closed"))
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
    }

    @Test fun unrecoverableConfigurationDoesNotAutomaticallyRetry() {
        report(status = CarPlayStatus.Failed("Permission missing", startupFailure = WirelessStartupFailure.HOTSPOT_CONFIGURATION))
        assertEquals(0, budget().retries)
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertEquals(View.VISIBLE, retry.visibility)
    }

    @Test fun staleGenerationUserDisconnectMenuAndDifferentOwnerCannotSchedule() {
        report(1)
        assertEquals(0, budget().retries)
        ReflectionHelpers.setField(activity, "menuOpen", true)
        report()
        assertEquals(0, budget().retries)
        ReflectionHelpers.setField(activity, "menuOpen", false)
        ReflectionHelpers.getField<AtomicBoolean>(activity, "shuttingDown").set(true)
        report()
        assertEquals(0, budget().retries)
        ReflectionHelpers.getField<AtomicBoolean>(activity, "shuttingDown").set(false)
        ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", Any())
        report()
        assertEquals(0, budget().retries)
    }
}
