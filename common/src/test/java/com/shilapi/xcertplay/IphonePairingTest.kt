package com.shilapi.xcertplay

import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Intent
import android.os.Parcelable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w1000dp-h700dp-land")
class IphonePairingTest {
    private lateinit var activity: DiPlayActivity
    private lateinit var adapter: BluetoothAdapter
    private lateinit var device: BluetoothDevice
    private lateinit var pairing: IphonePairing
    private val paired = mutableListOf<BluetoothDevice>()
    private var cancelled = 0
    private var settings = 0

    @Before fun setUp() {
        activity = Robolectric.buildActivity(DiPlayActivity::class.java).setup().get()
        adapter = mock(BluetoothAdapter::class.java)
        device = mock(BluetoothDevice::class.java)
        `when`(adapter.startDiscovery()).thenReturn(true)
        `when`(device.address).thenReturn("00:11:22:33:44:55")
        `when`(device.name).thenReturn("Test iPhone")
        `when`(device.bondState).thenReturn(BluetoothDevice.BOND_NONE)
        `when`(device.createBond()).thenReturn(true)
        pairing = IphonePairing(activity, adapter, { paired += it }, { cancelled++ }, { settings++ })
        pairing.show()
    }

    @After fun tearDown() {
        pairing.close()
        activity.finish()
        AppAppearanceRuntime.resetForTest()
    }

    private fun send(action: String) {
        val receiver: BroadcastReceiver = ReflectionHelpers.getField(pairing, "receiver")
        receiver.onReceive(activity, Intent(action).putExtra(BluetoothDevice.EXTRA_DEVICE, device as Parcelable))
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }

    @Test fun discoveredDeviceIsPairedOnlyAfterTheUserChoosesItAndTheSystemConfirms() {
        send(BluetoothDevice.ACTION_FOUND)
        val list = views(ShadowAlertDialog.getLatestAlertDialog().window!!.decorView).filterIsInstance<ListView>().single()
        assertEquals(1, list.adapter.count)
        verify(device, never()).createBond()
        list.performItemClick(list.adapter.getView(0, null, list), 0, 0)
        verify(device, times(1)).createBond()
        assertTrue(paired.isEmpty())
        pairing.onStop()
        `when`(device.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
        send(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        assertTrue(paired.isEmpty())
        pairing.onStart()
        assertEquals(listOf(device), paired)
        assertEquals(0, cancelled)
        verify(adapter, atLeastOnce()).cancelDiscovery()
    }

    @Test fun cancellingStopsDiscoveryAndDoesNotSelectADevice() {
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, cancelled)
        assertTrue(paired.isEmpty())
        verify(adapter, atLeastOnce()).cancelDiscovery()
    }

    @Test fun anUnnamedDiscoveryIsShownAsAnAddressNotAsAPairedDevice() {
        `when`(device.name).thenReturn(null)
        send(BluetoothDevice.ACTION_FOUND)
        val list = views(ShadowAlertDialog.getLatestAlertDialog().window!!.decorView).filterIsInstance<ListView>().single()
        assertEquals("00:11:22:33:44:55", list.adapter.getItem(0))
        verify(device, never()).createBond()
    }

    @Test fun systemBluetoothFallbackClosesTheScanner() {
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, settings)
        assertEquals(0, cancelled)
        verify(adapter, atLeastOnce()).cancelDiscovery()
    }
}
