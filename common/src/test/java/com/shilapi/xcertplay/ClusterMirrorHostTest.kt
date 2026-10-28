package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import com.shilapi.xcertplay.media.AndroidMediaSink
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class ClusterMirrorHostTest {
    private lateinit var host: CarPlayHostActivity
    private lateinit var window: AdbClusterActivity
    private lateinit var surface: Surface
    private lateinit var sink: AndroidMediaSink
    private val events = mutableListOf<String>()
    private var alt: Surface? = null
    private var mirror: Surface? = null
    private var detachGate: CountDownLatch? = null
    private var detachTimesOut = false
    private val lateReleaseWaitStarted = CountDownLatch(1)

    @Before fun setUp() {
        host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        AirPlayPersistence.saveClusterMapEnabled(host, true)
        AirPlayPersistence.saveAdbClusterEnabled(host, true)
        AirPlayPersistence.saveLegacyClusterEnabled(host, false)
        AirPlayPersistence.saveClusterMirrorMain(host, false)
        AirPlayPersistence.saveClusterMirrorNavOnly(host, false)
        val session = mock(AirPlaySession::class.java)
        `when`(session.hasRenderedMainFrame).thenReturn(true)
        set("activeAirPlaySession", session)
        set("adbClusterConfigured", true)
        set("sessionDisplay", CarPlaySessionDisplay(1940, 1092, Surface.ROTATION_0, false, false, 1940, 1092))
        sink = fakeSink()
        set("sink", sink)
        surface = mock(Surface::class.java)
        `when`(surface.isValid).thenReturn(true)
        ClusterActivityOutput.stopForSettings()
        ClusterActivityOutput.bind(host, 4) { next -> invokeSurface(next) }
        val token = "01234567-89ab-cdef-0123-456789abcdef"
        ClusterActivityOutput::class.java.getDeclaredField("launchToken")
            .apply { isAccessible = true }.set(ClusterActivityOutput, token)
        ClusterActivityOutput::class.java.getDeclaredField("expectedDisplay")
            .apply { isAccessible = true }.setInt(ClusterActivityOutput, 1)
        window = Robolectric.buildActivity(AdbClusterActivity::class.java).get()
        assertTrue(ClusterActivityOutput.confirm(window, token, 1))
        ClusterActivityOutput.attach(window, surface)
    }

    @After fun tearDown() {
        detachGate?.countDown()
        ClusterActivityOutput.stop(host)
        shadowOf(Looper.getMainLooper()).idle()
        BydNavigationOutputs.setClusterMainMirrorShown(false)
        (get("mainHandler") as Handler).removeCallbacksAndMessages(null)
        (get("teardownExecutor") as ExecutorService).shutdownNow()
        (get("airPlayCommandExecutor") as ExecutorService).shutdownNow()
        host.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    @Test fun switchesInOrderWithoutSharingSurfaceAndReattachesAfterSinkReplacement() {
        assertEquals(listOf("alt+"), events)
        AirPlayPersistence.saveClusterMirrorMain(host, true)
        invoke()
        waitUntil { get("clusterMirrorAttached") === surface }
        assertEquals(listOf("alt+", "alt-", "mirror+"), events)
        assertTrue(BydNavigationOutputs.clusterMainMirrorShown())
        invoke()
        assertEquals(1, events.count { it == "mirror+" })

        AirPlayPersistence.saveClusterMirrorMain(host, false)
        invoke()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("alt+", "alt-", "mirror+", "mirror-", "alt+"), events)
        assertFalse(BydNavigationOutputs.clusterMainMirrorShown())

        AirPlayPersistence.saveClusterMirrorMain(host, true)
        invoke()
        waitUntil { get("clusterMirrorAttached") === surface }
        val replacement = fakeSink()
        set("sink", replacement)
        invoke()
        waitUntil { get("clusterMirrorSink") === replacement && get("clusterMirrorAttached") === surface }
        assertEquals(3, events.count { it == "mirror+" })
        assertEquals(2, events.count { it == "mirror-" })
        ClusterActivityOutput.stop(host)
        assertNull(get("clusterMirrorAttached"))
        assertFalse(BydNavigationOutputs.clusterMainMirrorShown())
        assertEquals(3, events.count { it == "mirror-" })
    }

    @Test fun destroyedSurfaceDetachesTheMirror() {
        AirPlayPersistence.saveClusterMirrorMain(host, true)
        invoke()
        waitUntil { get("clusterMirrorAttached") === surface }
        ClusterActivityOutput.detach(window, surface)
        assertNull(get("clusterMirrorAttached"))
        assertNull(mirror)
        assertTrue(BydNavigationOutputs.clusterMainMirrorShown())
    }

    @Test fun navigationStartsImmediatelyAndEndsOnlyAfterFifteenSeconds() {
        AirPlayPersistence.saveClusterMirrorNavOnly(host, true)
        AirPlayPersistence.saveClusterMirrorMain(host, true)
        invoke()
        waitUntil { get("clusterMirrorPreparedSurface") === surface }
        assertNull(mirror)
        assertNull(alt)
        assertTrue(BydNavigationOutputs.clusterMainMirrorShown())
        val altStarts = events.count { it == "alt+" }

        guidance(ClusterTurnGuidance(1, 0, 100, ""))
        waitUntil { mirror === surface }
        guidance(null)
        shadowOf(Looper.getMainLooper()).idleFor(14, TimeUnit.SECONDS)
        assertSame(surface, mirror)
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        waitUntil { mirror == null }
        assertTrue(BydNavigationOutputs.clusterMainMirrorShown())
        assertNull(alt)
        assertEquals(altStarts, events.count { it == "alt+" })
    }

    @Test fun navigationReturningDuringTheHoldDoesNotDetachTheMirror() {
        AirPlayPersistence.saveClusterMirrorNavOnly(host, true)
        AirPlayPersistence.saveClusterMirrorMain(host, true)
        guidance(ClusterTurnGuidance(1, 0, 100, ""))
        waitUntil { mirror === surface }
        guidance(null)
        shadowOf(Looper.getMainLooper()).idleFor(14, TimeUnit.SECONDS)
        guidance(ClusterTurnGuidance(2, 0, 50, ""))
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertSame(surface, mirror)
        assertFalse(events.contains("mirror-"))
    }

    @Test fun disablingNavigationOnlyAttachesWithoutGuidance() {
        AirPlayPersistence.saveClusterMirrorNavOnly(host, true)
        AirPlayPersistence.saveClusterMirrorMain(host, true)
        invoke()
        waitUntil { get("clusterMirrorPreparedSurface") === surface }
        assertNull(mirror)
        AirPlayPersistence.saveClusterMirrorNavOnly(host, false)
        invoke()
        waitUntil { mirror === surface }
        assertTrue(BydNavigationOutputs.clusterMainMirrorShown())
    }

    @Test fun cancelledTransitionDoesNotAttachBeforeTheAltDecoderReleases() {
        detachGate = CountDownLatch(1)
        AirPlayPersistence.saveClusterMirrorMain(host, true)
        invoke()
        AirPlayPersistence.saveClusterMirrorMain(host, false)
        invoke()
        assertNull(mirror)
        assertNull(alt)
        detachGate!!.countDown()
        waitUntil { alt === surface }
        assertFalse(events.contains("mirror+"))
    }

    @Test fun timedOutDetachStillPreventsSurfaceReuseUntilTheCodecReleases() {
        detachTimesOut = true
        detachGate = CountDownLatch(1)
        AirPlayPersistence.saveClusterMirrorMain(host, true)
        invoke()
        assertTrue("the late release wait started", lateReleaseWaitStarted.await(5, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()
        AirPlayPersistence.saveClusterMirrorMain(host, false)
        invoke()
        assertNull("ALT must not reuse the surface while the codec still holds it", alt)
        assertNull(mirror)
        detachGate!!.countDown()
        waitUntil { alt === surface }
        assertFalse(events.contains("mirror+"))
    }

    private fun fakeSink(): AndroidMediaSink {
        val result = mock(AndroidMediaSink::class.java)
        val detach = mock(AndroidMediaSink.SurfaceDetach::class.java)
        `when`(detach.await()).thenAnswer {
            if (detachTimesOut) false else detachGate?.await(3, TimeUnit.SECONDS) ?: true
        }
        doAnswer {
            lateReleaseWaitStarted.countDown()
            detachGate?.await(5, TimeUnit.SECONDS)
            null
        }.`when`(detach).awaitUntilReleased()
        `when`(result.beginSurfaceDetachForStream(eq(111), anyValue())).thenReturn(detach)
        doAnswer { call ->
            if (call.getArgument<Int>(0) == 111) {
                assertNull("ALT cannot share a surface with mirror", mirror)
                alt = call.getArgument(1)
                events += "alt+"
            }
            null
        }.`when`(result).setSurface(anyInt(), anyValue())
        doAnswer { call ->
            if (call.getArgument<Int>(0) == 111) {
                if (alt === call.getArgument<Surface>(1)) alt = null
                events += "alt-"
            }
            null
        }.`when`(result).clearSurface(anyInt(), anyValue())
        doAnswer { call ->
            val next = call.getArgument<Surface?>(2)
            if (next != null) assertNull("mirror cannot share a surface with ALT", alt)
            mirror = next
            events += if (next == null) "mirror-" else "mirror+"
            null
        }.`when`(result).setMirrorSurface(eq(110), eqValue("cluster"), any())
        doAnswer { call -> call.getArgument<() -> Unit>(2).invoke(); null }
            .`when`(result).whenMirrorReleased(eq(110), eqValue("cluster"), anyValue())
        return result
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyValue(): T {
        any<T>()
        return null as T
    }

    private fun <T> eqValue(value: T): T {
        eq(value)
        return value
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("mirror transition timed out: $events", condition())
    }

    private fun invoke() = host.javaClass.getDeclaredMethod("applyClusterVideo")
        .apply { isAccessible = true }.invoke(host)
    private fun invokeSurface(value: Surface?) = host.javaClass.getDeclaredMethod("onClusterSurface", Surface::class.java)
        .apply { isAccessible = true }.invoke(host, value)
    private fun get(name: String): Any? = host.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(host)
    @Suppress("UNCHECKED_CAST")
    private fun guidance(value: ClusterTurnGuidance?) {
        (get("clusterTurnOverlayListener") as (ClusterTurnGuidance?) -> Unit).invoke(value)
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun set(name: String, value: Any?) = host.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(host, value)
}
