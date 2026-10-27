package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayControllerFirstFrameTest {
    @Test fun wiredMainFrameReachesTheHostButRetiredSessionsDoNot() {
        val frames = mutableListOf<AirPlaySession>()
        val controller = controller(frames)
        val active = session()
        val stale = session()
        val listener = controller.javaClass.getDeclaredField("sessionListener")
            .apply { isAccessible = true }.get(controller) as AirPlaySessionListener
        controller.javaClass.getDeclaredField("activeSession").apply { isAccessible = true }.set(controller, active)
        try {
            listener.onVideoFrameRendered(stale)
            assertTrue(frames.isEmpty())
            listener.onVideoFrameRendered(active)
            assertEquals(listOf(active), frames)
            active.close()
            listener.onVideoFrameRendered(active)
            assertEquals(listOf(active), frames)
        } finally {
            stale.close()
            active.close()
            controller.close()
            assertTrue(controller.awaitClosed(2_000))
        }
    }

    @Test fun throwingMilestoneCallbackDoesNotInterruptWirelessSessionOrFirstFrame() {
        val frames = mutableListOf<AirPlaySession>()
        val events = mutableListOf<String>()
        val controller = controller(frames, CarPlayTransport.WIRELESS) { event, _ ->
            events += event
            throw AssertionError("diagnostic callback failed")
        }
        val active = session()
        val identity = AirPlayListenerIdentity(7)
        val watchdog = FirstTcpWatchdog(identity, schedule = { _, _ -> {} }, onTimeout = {})
        val phase = controller.javaClass.declaredClasses.single { it.simpleName == "Phase" }
            .enumConstants.single { it.toString() == "WIRELESS" }
        controller.javaClass.getDeclaredField("phase").apply { isAccessible = true }.set(controller, phase)
        (controller.javaClass.getDeclaredField("wirelessGeneration").apply { isAccessible = true }
            .get(controller) as AtomicInteger).set(7)
        controller.javaClass.getDeclaredField("activeSession").apply { isAccessible = true }.set(controller, active)
        val listener = controller.javaClass.getDeclaredMethod(
            "wirelessSessionListener", Int::class.javaPrimitiveType, FirstTcpWatchdog::class.java,
        ).apply { isAccessible = true }.invoke(controller, 7, watchdog) as AirPlaySessionListener
        try {
            listener.onTcpAccepted(AirPlayTcpAccepted(identity, false, System.nanoTime()))
            listener.onSessionActive(active)
            listener.onVideoFrameRendered(active)
            listener.onVideoFrameRendered(active)
            assertSame(active, controller.activeAirPlaySession())
            assertEquals(listOf(active), frames)
            assertEquals(listOf("CP_W_FIRST_TCP", "CP_W_SESSION_START", "FIRST_FRAME"), events)
        } finally {
            active.close()
            controller.close()
            assertTrue(controller.awaitClosed(2_000))
        }
    }

    private fun controller(
        frames: MutableList<AirPlaySession>,
        transport: CarPlayTransport = CarPlayTransport.WIRED,
        onMilestone: (String, List<Pair<String, Any?>>) -> Unit = { _, _ -> },
    ) = CarPlayController(
        RuntimeEnvironment.getApplication(),
        CarPlayRuntimeConfig(
            mfiTarget = MfiTarget.LOCAL, transport = transport,
            identification = Iap2IdentificationConfig(name = "test", modelIdentifier = "test", manufacturer = "test",
                serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3),
        ),
        AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
        AirPlayIdentity.generate(), PairingStore(),
        object : AirPlaySessionListener {
            override fun onVideoFrameRendered(session: AirPlaySession) { frames += session }
        },
        object : AirPlayMediaHandler {}, {}, onStartupMilestone = onMilestone,
    )

    private fun session() = AirPlaySession(
        Socket(), AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
        AirPlayIdentity.generate(), PairingStore(), null,
        object : AirPlaySessionListener {}, object : AirPlayMediaHandler {},
    )
}
