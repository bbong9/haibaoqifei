package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.os.Looper
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.*
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLog
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Replays duplicate Android events and re-enumeration callback ordering without real USB I/O. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayControllerUsbRecoveryTest {
    private val transitions = mutableListOf<(IphoneUsbHost.TransitionResult) -> Unit>()
    private val opens = mutableListOf<(IphoneUsbHost.Iap2SessionResult) -> Unit>()
    private val statuses = mutableListOf<CarPlayStatus>()
    private val host = mock(IphoneUsbHost::class.java) { invocation ->
        when (invocation.method.name) {
            "requestCarPlayReenumerationAsync" -> {
                transitions += invocation.getArgument<(IphoneUsbHost.TransitionResult) -> Unit>(2)
                null
            }
            "openIap2UsbSessionAsync" -> {
                opens += invocation.getArgument<(IphoneUsbHost.Iap2SessionResult) -> Unit>(2)
                null
            }
            else -> Answers.RETURNS_DEFAULTS.answer(invocation)
        }
    }
    private val original = phone("/dev/bus/usb/001/011", carPlay = false)
    private val ready = phone("/dev/bus/usb/001/012", carPlay = true)
    private lateinit var controller: CarPlayController

    @Before fun setUp() {
        controller = CarPlayController(
            object : ContextWrapper(RuntimeEnvironment.getApplication()) {
                override fun getApplicationContext(): Context = this
                override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean = false
            },
            CarPlayRuntimeConfig(
                mfiTarget = MfiTarget.LOCAL, transport = CarPlayTransport.WIRED,
                identification = Iap2IdentificationConfig(
                    name = "test", modelIdentifier = "test", manufacturer = "test",
                    serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1",
                    carPlayUsbInterfaceNumber = 3,
                ),
            ),
            AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
            AirPlayIdentity(ByteArray(32), ByteArray(32), "test"), PairingStore(),
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, statuses::add,
        )
        ReflectionHelpers.setField(controller, "iphoneHost\$delegate", lazyOf(host))
        val phase = requireNotNull(
            Class.forName("com.shilapi.xcertplay.orchestration.CarPlayController\$Phase").enumConstants,
        ).single { (it as Enum<*>).name == "IPHONE" }
        ReflectionHelpers.setField(controller, "phase", phase)
        `when`(host.discover()).thenReturn(emptyList())
        `when`(host.requestPermission(original)).thenReturn(IphoneUsbHost.PermissionRequest.AlreadyGranted(original))
        `when`(host.requestPermission(ready)).thenReturn(IphoneUsbHost.PermissionRequest.AlreadyGranted(ready))
    }

    @After fun tearDown() {
        controller.close()
        assertTrue(controller.awaitClosed(2_000))
        BydNavigationOutputs.endNow()
    }

    @Test fun duplicateAttachAndPermissionEventsRequestTheTransitionOnlyOnce() {
        attach(original)
        attach(original)
        idle()
        permission(IphoneUsbHost.PermissionResult.Granted(original))
        attach(original)
        idle()
        assertEquals(1, transitions.size)
        verify(host, times(1)).requestPermission(original)
        assertTrue(failures().isEmpty())
    }

    @Test fun lateDenialDuringReenumerationDoesNotFailTheConnection() {
        attach(original)
        idle()
        permission(IphoneUsbHost.PermissionResult.Granted(original))
        idle()
        assertEquals(1, transitions.size)
        assertEquals("REENUMERATION", ReflectionHelpers.getField<Enum<*>>(controller, "phase").name)
        val pollGeneration = ReflectionHelpers.getField<Int>(controller, "permissionPollGeneration")

        permission(IphoneUsbHost.PermissionResult.Denied(original))
        idle()
        assertTrue(failures().isEmpty())
        assertEquals(pollGeneration, ReflectionHelpers.getField<Int>(controller, "permissionPollGeneration"))
        assertEquals(1, transitions.size)
    }

    @Test fun firstDenialStillFailsOnce() {
        `when`(host.requestPermission(original)).thenReturn(IphoneUsbHost.PermissionRequest.Requested(original))
        attach(original)
        idle()
        permission(IphoneUsbHost.PermissionResult.Denied(original))
        idle()
        assertEquals(1, failures().size)
        assertTrue(failures().single().message.contains("USB permission was denied"))

        permission(IphoneUsbHost.PermissionResult.Denied(original))
        idle()
        assertEquals(1, failures().size)
    }

    @Test fun duplicateQueuedAttachmentsAndLateDenialDoNotDisturbOpeningDataPaths() {
        attach(ready)
        attach(ready)
        idle()
        permission(IphoneUsbHost.PermissionResult.Denied(ready))
        idle()
        assertEquals(1, opens.size)
        verify(host, times(1)).requestPermission(ready)
        assertTrue(failures().isEmpty())
    }

    @Test fun oldTransitionFailureCannotFailTheReplacementConnection() {
        attach(original)
        idle()
        attach(ready)
        idle()
        transitions.single()(failedTransition())
        advance(2)
        assertEquals(1, opens.size)
        assertTrue(failures().isEmpty())
    }

    @Test fun reattachmentAfterTransferFailureInvalidatesItsPendingRetry() {
        attach(original)
        idle()
        transitions.single()(failedTransition())
        idle()
        assertTrue(failures().isEmpty())
        attach(ready)
        idle()
        advance(2)
        assertEquals(1, opens.size)
        assertTrue(failures().isEmpty())
    }

    @Test fun deviceListRecoversReenumerationEvenWhenAttachBroadcastIsMissing() {
        attach(original)
        idle()
        transitions.single()(IphoneUsbHost.TransitionResult.ReenumerationRequested)
        idle()
        `when`(host.discover()).thenReturn(listOf(ready))
        advance(1)
        assertEquals(1, opens.size)
        assertTrue(failures().isEmpty())
    }

    @Test fun realTransitionFailureIsReportedOnceAndStopsFurtherOpens() {
        attach(original)
        idle()
        transitions.single()(failedTransition())
        advance(2)
        attach(ready)
        transitions.single()(failedTransition())
        advance(2)
        assertEquals(1, failures().size)
        assertTrue(failures().single().message.contains("transferred -1"))
        assertTrue(opens.isEmpty())
    }

    @Test fun successfulRequestWithoutReappearanceEventuallyReportsTimeout() {
        attach(original)
        idle()
        transitions.single()(IphoneUsbHost.TransitionResult.ReenumerationRequested)
        advance(6)
        assertEquals(1, failures().size)
        assertTrue(failures().single().message.contains("did not reappear"))
    }

    @Test fun reenumerationTimeoutLogsPreviousAndDiscoveredDevices() {
        ShadowLog.clear()
        `when`(host.discover()).thenReturn(listOf(original))
        attach(original)
        idle()
        transitions.single()(IphoneUsbHost.TransitionResult.ReenumerationRequested)
        advance(6)
        assertEquals(1, failures().size)
        assertTrue(failures().single().message.contains("did not reappear"))
        assertTrue(opens.isEmpty())

        val diagnostic = ShadowLog.getLogsForTag(IphoneCarPlayConfiguration.TAG)
            .single { it.msg.contains("USB reenumeration not observed") }.msg
        assertTrue(
            diagnostic,
            diagnostic.contains(
                "USB reenumeration not observed previous=${original.deviceName}/none " +
                    "discovered=[${original.deviceName}/none] error=none",
            ),
        )
        println(diagnostic)
    }

    @Test fun lateOpenedPipeIsClosedWhenItsControllerHasBeenRetired() {
        attach(ready)
        idle()
        controller.close()
        assertTrue(controller.awaitClosed(2_000))
        val pipe = mock(Iap2UsbSession::class.java)
        opens.single()(IphoneUsbHost.Iap2SessionResult.Connected(pipe))
        verify(pipe).close()
        assertTrue(failures().isEmpty())
    }

    @Test fun retiringControllerClosesPipeEvenBeforeUsbMuxHandshakeFinishes() {
        val pipe = mock(Iap2UsbSession::class.java)
        ReflectionHelpers.setField(controller, "wiredUsbSession", pipe)
        controller.close()
        assertTrue(controller.awaitClosed(2_000))
        verify(pipe).close()
    }

    private fun attach(device: UsbDevice) {
        ReflectionHelpers.callInstanceMethod<Unit>(
            controller, "onIphoneAttached", ClassParameter.from(UsbDevice::class.java, device),
        )
    }

    private fun permission(result: IphoneUsbHost.PermissionResult) {
        ReflectionHelpers.callInstanceMethod<Unit>(
            controller, "onIphonePermission", ClassParameter.from(IphoneUsbHost.PermissionResult::class.java, result),
        )
    }

    private fun failedTransition() = IphoneUsbHost.TransitionResult.Failed(
        IphoneUsbException.Protocol("CarPlay configuration request transferred -1 of 1 bytes"),
    )

    private fun failures() = statuses.filterIsInstance<CarPlayStatus.Failed>()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun advance(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))

    private fun phone(name: String, carPlay: Boolean): UsbDevice = mock(UsbDevice::class.java).also { device ->
        `when`(device.deviceName).thenReturn(name)
        `when`(device.vendorId).thenReturn(0x05ac)
        if (carPlay) {
            val configuration = mock(UsbConfiguration::class.java)
            val mux = usbInterface(0xff, 0xfe, 2)
            val ncm = usbInterface(2, 0x0d, 0)
            `when`(device.configurationCount).thenReturn(1)
            `when`(device.getConfiguration(0)).thenReturn(configuration)
            `when`(configuration.id).thenReturn(6)
            `when`(configuration.interfaceCount).thenReturn(2)
            `when`(configuration.getInterface(0)).thenReturn(mux)
            `when`(configuration.getInterface(1)).thenReturn(ncm)
        }
    }

    private fun usbInterface(type: Int, subclass: Int, protocol: Int): UsbInterface =
        mock(UsbInterface::class.java).also {
            `when`(it.interfaceClass).thenReturn(type)
            `when`(it.interfaceSubclass).thenReturn(subclass)
            `when`(it.interfaceProtocol).thenReturn(protocol)
        }
}
