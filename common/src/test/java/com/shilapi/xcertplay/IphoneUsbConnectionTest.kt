package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Looper
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class IphoneUsbConnectionTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val usb = mock(UsbManager::class.java)
    private val phone = device(0x05ac)

    @Before fun reset() {
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    private fun context(seal: Boolean): Context {
        val resources = spy(app.resources)
        doReturn(seal).`when`(resources).getBoolean(R.bool.config_seal_edition)
        return object : ContextWrapper(app) {
            override fun getResources() = resources
            override fun getSystemService(name: String): Any? =
                if (name == Context.USB_SERVICE) usb else super.getSystemService(name)
        }
    }

    @Test fun attachedPhoneOverridesWirelessOnlyForSealAndDoesNotOverwritePreferences() {
        `when`(usb.deviceList).thenReturn(hashMapOf("iphone" to phone))
        val seal = context(true)
        DiPlayPreferences.saveDefaultConnectionMode(seal, DefaultConnectionMode.WIRELESS)
        AirPlayPersistence.saveWirelessEnabled(seal, true)
        assertFalse(DiPlayPreferences.autoConnectWireless(seal))
        assertTrue(DiPlayPreferences.autoConnectWireless(context(false)))
        assertEquals(DefaultConnectionMode.WIRELESS, DiPlayPreferences.defaultConnectionMode(seal))
        assertTrue(AirPlayPersistence.loadWirelessEnabled(seal))
        `when`(usb.deviceList).thenReturn(hashMapOf())
        assertTrue(DiPlayPreferences.autoConnectWireless(seal))
    }

    @Test fun anotherUsbAccessoryDoesNotOverrideWireless() {
        val accessory = device(0x1a86)
        `when`(usb.deviceList).thenReturn(hashMapOf("ch341" to accessory))
        assertNull(IphoneUsbConnection.attached(context(true)))
        assertTrue(DiPlayPreferences.autoConnectWireless(context(true)))
    }

    @Test fun foregroundObserverHandlesOnlyPhoneAttachmentsAndStopsWhenClosed() {
        val seen = mutableListOf<UsbDevice>()
        val observer = IphoneUsbConnection.observe(app, seen::add)
        fun send(intent: Intent) {
            app.sendBroadcast(intent)
            shadowOf(Looper.getMainLooper()).idle()
        }
        send(Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED))
        send(Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, device(0x1a86)))
        send(Intent(UsbManager.ACTION_USB_DEVICE_DETACHED).putExtra(UsbManager.EXTRA_DEVICE, phone))
        assertTrue(seen.isEmpty())
        val attached = Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, phone)
        send(attached)
        assertEquals(listOf(phone), seen)
        val forwarded = IphoneUsbConnection.hostIntent(app, seen.single())
        assertEquals(CarPlayHostActivity::class.java.name, forwarded.component?.className)
        assertSame(phone, IphoneUsbConnection.fromIntent(forwarded))
        observer.close()
        send(attached)
        assertEquals(1, seen.size)
    }

    private fun device(vendor: Int): UsbDevice = mock(UsbDevice::class.java).also {
        `when`(it.vendorId).thenReturn(vendor)
    }
}
