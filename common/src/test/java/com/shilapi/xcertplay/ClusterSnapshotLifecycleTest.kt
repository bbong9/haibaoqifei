package com.shilapi.xcertplay

import android.os.Looper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class ClusterSnapshotLifecycleTest {
    private val owner = Any()
    private val token = "01234567-89ab-cdef-0123-456789abcdef"
    private val calls = mutableListOf<String>()
    private lateinit var controller: ActivityController<AdbClusterActivity>
    private lateinit var window: AdbClusterActivity

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        AirPlayPersistence.saveLegacyClusterEnabled(app, false)
        AirPlayPersistence.saveAdbClusterEnabled(app, true)
        ClusterActivityOutput.stopForSettings()
        ClusterActivityOutput.bind(owner, 1) { }
        ReflectionHelpers.setStaticField(ClusterActivityOutput::class.java, "launchToken", token)
        ReflectionHelpers.setStaticField(ClusterActivityOutput::class.java, "expectedDisplay", 1)
        controller = Robolectric.buildActivity(AdbClusterActivity::class.java)
        window = controller.get()
        window.intent.putExtra("cluster_launch_token", token)
        window.snapshotExecutor = { _, trigger, _ -> calls += trigger }
    }

    @After fun tearDown() {
        ClusterActivityOutput.stopForSettings()
        RuntimeEnvironment.getApplication().getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    private fun confirm() = ReflectionHelpers.callInstanceMethod<Unit>(window, "confirmDisplay",
        ReflectionHelpers.ClassParameter.from(String::class.java, token),
        ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 1))

    @Test fun confirmedWindowCapturesOnceAtFiveAndThirtySeconds() {
        confirm()
        assertTrue(ClusterActivityOutput.hasConfirmedRoute())
        shadowOf(Looper.getMainLooper()).idleFor(4, TimeUnit.SECONDS)
        assertTrue(calls.isEmpty())
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        assertEquals(listOf("auto5s"), calls)
        shadowOf(Looper.getMainLooper()).idleFor(25, TimeUnit.SECONDS)
        assertEquals(listOf("auto5s", "auto30s"), calls)
        shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.SECONDS)
        assertEquals(listOf("auto5s", "auto30s"), calls)
        controller.destroy()
    }

    @Test fun destroyingWindowBeforeFiveSecondsCancelsBothCaptures() {
        confirm()
        shadowOf(Looper.getMainLooper()).idleFor(4, TimeUnit.SECONDS)
        controller.destroy()
        shadowOf(Looper.getMainLooper()).idleFor(40, TimeUnit.SECONDS)
        assertTrue(calls.isEmpty())
    }

    @Test fun destroyingWindowAfterFirstCaptureCancelsSecond() {
        confirm()
        shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.SECONDS)
        assertEquals(listOf("auto5s"), calls)
        controller.destroy()
        shadowOf(Looper.getMainLooper()).idleFor(30, TimeUnit.SECONDS)
        assertEquals(listOf("auto5s"), calls)
    }

    @Test fun rejectedDisplayDoesNotScheduleCaptures() {
        ReflectionHelpers.setStaticField(ClusterActivityOutput::class.java, "expectedDisplay", 2)
        confirm()
        shadowOf(Looper.getMainLooper()).idleFor(35, TimeUnit.SECONDS)
        assertTrue(calls.isEmpty())
        controller.destroy()
    }

    @Test fun presentedFramesCountOnlyTheCurrentLaunch() {
        val before = ClusterActivityOutput.framesPresented
        ClusterActivityOutput.countPresented(token)
        ClusterActivityOutput.countPresented("stale")
        ClusterActivityOutput.countPresented(token)
        assertEquals(before + 2, ClusterActivityOutput.framesPresented)
    }
}
