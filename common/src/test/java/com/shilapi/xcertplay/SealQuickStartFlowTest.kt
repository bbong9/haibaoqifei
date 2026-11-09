package com.shilapi.xcertplay

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
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
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers

/** Exercises the UI decisions without a real Bluetooth radio or the private authentication assets. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w1000dp-h700dp-land")
@LooperMode(LooperMode.Mode.PAUSED)
class SealQuickStartFlowTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val adapter: BluetoothAdapter get() = BluetoothAdapter.getDefaultAdapter()
    private var controller: ActivityController<DiPlayActivity>? = null

    @Before fun setUp() {
        context.getSharedPreferences("diplay", 0).edit().clear().commit()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        CarPlayBackgroundSession.clear()
        assertTrue(SealQuickStart.prepare(context, sealEdition = true, freshInstall = true))
        DiPlayPreferences.saveAutoConnect(context, true)
        context.getSharedPreferences("diplay", 0).edit().putBoolean("notification_asked", true).commit()
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.WIFI_P2P)
        shadowOf(adapter).setEnabled(true)
        shadowOf(adapter).setBondedDevices(emptySet())
    }

    @After fun tearDown() {
        controller?.let { runCatching { it.pause().stop().destroy() } }
        CarPlayBackgroundSession.clear()
        AppAppearanceRuntime.resetForTest()
        context.getSharedPreferences("diplay", 0).edit().clear().commit()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    private fun devices(vararg pairs: Pair<String, String>): Set<BluetoothDevice> =
        pairs.mapTo(mutableSetOf()) { (name, address) ->
            adapter.getRemoteDevice(address).also { shadowOf(it).setName(name) }
        }

    private fun open(state: Bundle? = null): DiPlayActivity {
        val built = Robolectric.buildActivity(DiPlayActivity::class.java)
        controller = built
        val screen = built.get()
        screen.sealEditionCheck = { true }
        built.create(state)
        // Source-only test builds deliberately do not contain runtime authentication assets.
        ReflectionHelpers.setField(screen, "setupError", null as String?)
        built.start().resume()
        idle()
        return screen
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun dialog(): AlertDialog = ShadowAlertDialog.getLatestAlertDialog()
    private fun click(which: Int) { dialog().getButton(which).performClick(); idle() }
    private fun pauseForSettings() { controller!!.pause().stop() }
    private fun returnFromSettings() { controller!!.restart().start().resume(); idle() }
    private fun started(screen: DiPlayActivity): String? = shadowOf(screen).nextStartedActivity?.component?.className

    @Test fun onlyRecognizablePairedIphoneIsSavedAndStartsProjection() {
        shadowOf(adapter).setBondedDevices(devices("Alice's iPhone" to "00:11:22:33:44:55", "Speaker" to "00:11:22:33:44:66"))
        val screen = open()
        assertEquals("00:11:22:33:44:55", DiPlayPreferences.phoneAddress(context))
        assertEquals(CarPlayScreen.target(screen).name, started(screen))
        assertFalse(ReflectionHelpers.getField(screen, "pendingWireless"))
    }

    @Test fun multipleIphonesRequireAChoiceAndCancellationDoesNotConnect() {
        shadowOf(adapter).setBondedDevices(devices("Alice's iPhone" to "00:11:22:33:44:55", "Bob's iPhone" to "00:11:22:33:44:66"))
        val screen = open()
        assertNull(DiPlayPreferences.phoneAddress(context))
        assertEquals(2, dialog().listView.count)
        click(AlertDialog.BUTTON_NEGATIVE)
        assertFalse(ReflectionHelpers.getField(screen, "pendingWireless"))
        assertNull(started(screen))
    }

    @Test fun leavingBluetoothWithoutPairingDoesNotRestartTheRequest() {
        val screen = open()
        click(AlertDialog.BUTTON_POSITIVE)
        assertNotNull(shadowOf(screen).nextStartedActivity)
        pauseForSettings()
        returnFromSettings()
        assertNull(DiPlayPreferences.phoneAddress(context))
        assertFalse(ReflectionHelpers.getField(screen, "pendingWireless"))
        assertNull(started(screen))
    }

    @Test fun pairingAnIphoneWhileInSettingsContinuesOnce() {
        val screen = open()
        click(AlertDialog.BUTTON_POSITIVE)
        assertNotNull(shadowOf(screen).nextStartedActivity)
        pauseForSettings()
        shadowOf(adapter).setBondedDevices(devices("Alice's iPhone" to "00:11:22:33:44:55"))
        returnFromSettings()
        assertEquals("00:11:22:33:44:55", DiPlayPreferences.phoneAddress(context))
        assertEquals(CarPlayScreen.target(screen).name, started(screen))
        pauseForSettings()
        returnFromSettings()
        assertNull(started(screen))
    }

    @Test fun inAppScanPairsTheChosenPhoneAndContinuesThePendingConnection() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val screen = open()
        click(AlertDialog.BUTTON_NEUTRAL)
        val phone = adapter.getRemoteDevice("00:11:22:33:44:55").also {
            shadowOf(it).setName("Alice's iPhone")
            shadowOf(it).setBondState(BluetoothDevice.BOND_NONE)
            shadowOf(it).setCreatedBond(true)
        }
        screen.sendBroadcast(Intent(BluetoothDevice.ACTION_FOUND).putExtra(BluetoothDevice.EXTRA_DEVICE, phone))
        idle()
        fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
        val list = descendants(dialog().window!!.decorView).filterIsInstance<ListView>().single()
        assertEquals(1, list.adapter.count)
        assertNull(DiPlayPreferences.phoneAddress(context))
        list.performItemClick(list.adapter.getView(0, null, list), 0, 0)
        screen.sendBroadcast(Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED).putExtra(BluetoothDevice.EXTRA_DEVICE, phone))
        idle()
        assertEquals("00:11:22:33:44:55", DiPlayPreferences.phoneAddress(context))
        assertEquals(CarPlayScreen.target(screen).name, started(screen))
        assertFalse(ReflectionHelpers.getField(screen, "pendingWireless"))
    }

    @Test fun cancellingPairAnotherDoesNotSelectAnExistingPhone() {
        shadowOf(adapter).setBondedDevices(devices("Alice's iPhone" to "00:11:22:33:44:55", "Bob's iPhone" to "00:11:22:33:44:66"))
        val screen = open()
        click(AlertDialog.BUTTON_NEUTRAL)
        assertNotNull(shadowOf(screen).nextStartedActivity)
        pauseForSettings()
        returnFromSettings()
        assertNull(DiPlayPreferences.phoneAddress(context))
        assertFalse(ReflectionHelpers.getField(screen, "pendingWireless"))
        assertNull(started(screen))
    }

    @Test fun recreationInBluetoothSettingsKeepsThePairingSnapshot() {
        shadowOf(adapter).setBondedDevices(devices("Alice's iPhone" to "00:11:22:33:44:55", "Bob's iPhone" to "00:11:22:33:44:66"))
        val first = open()
        click(AlertDialog.BUTTON_NEUTRAL)
        assertNotNull(shadowOf(first).nextStartedActivity)
        val state = Bundle()
        controller!!.pause().saveInstanceState(state).stop().destroy()
        controller = null

        // The user returned without pairing anything. The newly-created activity must not
        // select one of the old devices or start another automatic attempt.
        val restored = open(state)
        assertNull(DiPlayPreferences.phoneAddress(context))
        assertFalse(ReflectionHelpers.getField(restored, "pendingWireless"))
        assertNull(started(restored))
    }

    @Test fun newlyPairedUnknownDeviceDoesNotCauseAnOldIphoneToBeSelected() {
        shadowOf(adapter).setBondedDevices(devices("Alice's iPhone" to "00:11:22:33:44:55", "Bob's iPhone" to "00:11:22:33:44:66"))
        val screen = open()
        click(AlertDialog.BUTTON_NEUTRAL)
        assertNotNull(shadowOf(screen).nextStartedActivity)
        pauseForSettings()
        shadowOf(adapter).setBondedDevices(devices("Alice's iPhone" to "00:11:22:33:44:55", "Bob" to "00:11:22:33:44:66", "New phone" to "00:11:22:33:44:77"))
        returnFromSettings()
        assertNull(DiPlayPreferences.phoneAddress(context))
        assertEquals(3, dialog().listView.count)
        assertNull(started(screen))
    }

    @Test fun enablingBluetoothCanResumeWithAnAlreadyPairedIphone() {
        shadowOf(adapter).setBondedDevices(devices("Alice's iPhone" to "00:11:22:33:44:55"))
        shadowOf(adapter).setEnabled(false)
        val screen = open()
        click(AlertDialog.BUTTON_POSITIVE)
        assertNotNull(shadowOf(screen).nextStartedActivity)
        pauseForSettings()
        shadowOf(adapter).setEnabled(true)
        returnFromSettings()
        assertEquals("00:11:22:33:44:55", DiPlayPreferences.phoneAddress(context))
        assertEquals(CarPlayScreen.target(screen).name, started(screen))
    }

    @Test @Config(sdk = [33])
    fun decliningScanPermissionOffersSystemBluetoothAndCancelsThePendingConnection() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.BLUETOOTH_SCAN)
        val screen = open()
        click(AlertDialog.BUTTON_NEUTRAL)
        val request = shadowOf(screen).lastRequestedPermission
        assertTrue(request.requestedPermissions.contains(Manifest.permission.BLUETOOTH_SCAN))
        screen.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
            IntArray(request.requestedPermissions.size) { android.content.pm.PackageManager.PERMISSION_DENIED })
        idle()
        assertEquals(screen.getString(com.shilapi.xcertplay.host.R.string.open_bluetooth),
            dialog().getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        click(AlertDialog.BUTTON_NEGATIVE)
        assertFalse(ReflectionHelpers.getField(screen, "pendingWireless"))
        assertNull(DiPlayPreferences.phoneAddress(context))
        assertNull(started(screen))
    }

    @Test @Config(sdk = [33])
    fun decliningBluetoothPermissionDoesNotLeaveAConnectionPending() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val screen = open()
        val request = shadowOf(screen).lastRequestedPermission
        assertTrue(request.requestedPermissions.contains(Manifest.permission.BLUETOOTH_CONNECT))
        screen.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
            IntArray(request.requestedPermissions.size) { android.content.pm.PackageManager.PERMISSION_DENIED })
        idle()
        assertFalse(ReflectionHelpers.getField(screen, "pendingWireless"))
        assertFalse(ReflectionHelpers.getField(screen, "bluetoothPermissionPending"))
        assertNull(DiPlayPreferences.phoneAddress(context))
        assertNull(started(screen))
    }

    @Test @Config(sdk = [33])
    fun grantingBluetoothPermissionContinuesToThePairedIphone() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(adapter).setBondedDevices(devices("Alice's iPhone" to "00:11:22:33:44:55"))
        val screen = open()
        val request = shadowOf(screen).lastRequestedPermission
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        screen.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
            IntArray(request.requestedPermissions.size) { android.content.pm.PackageManager.PERMISSION_GRANTED })
        idle()
        assertFalse(ReflectionHelpers.getField(screen, "pendingWireless"))
        assertEquals("00:11:22:33:44:55", DiPlayPreferences.phoneAddress(context))
        assertEquals(CarPlayScreen.target(screen).name, started(screen))
    }

    @Test @Config(sdk = [33])
    fun recreationWhileBluetoothPermissionIsPendingWaitsForItsResult() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val first = open()
        val request = shadowOf(first).lastRequestedPermission
        val state = Bundle()
        controller!!.pause().saveInstanceState(state).stop().destroy()
        controller = null

        val restored = open(state)
        assertTrue(ReflectionHelpers.getField(restored, "pendingWireless"))
        assertTrue(ReflectionHelpers.getField(restored, "bluetoothPermissionPending"))
        assertNull(started(restored))
        restored.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
            IntArray(request.requestedPermissions.size) { android.content.pm.PackageManager.PERMISSION_DENIED })
        idle()
        assertFalse(ReflectionHelpers.getField(restored, "pendingWireless"))
        assertFalse(ReflectionHelpers.getField(restored, "bluetoothPermissionPending"))
    }

    @Test @Config(sdk = [33])
    fun permissionResultBeforeRecreatedActivityResumesDoesNotConnectTwice() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(adapter).setBondedDevices(devices("Alice's iPhone" to "00:11:22:33:44:55"))
        val first = open()
        val request = shadowOf(first).lastRequestedPermission
        val state = Bundle()
        controller!!.pause().saveInstanceState(state).stop().destroy()

        val built = Robolectric.buildActivity(DiPlayActivity::class.java)
        controller = built
        val restored = built.get().apply { sealEditionCheck = { true } }
        built.create(state)
        ReflectionHelpers.setField(restored, "setupError", null as String?)
        built.start()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        restored.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
            IntArray(request.requestedPermissions.size) { android.content.pm.PackageManager.PERMISSION_GRANTED })
        idle()
        assertEquals("00:11:22:33:44:55", DiPlayPreferences.phoneAddress(context))
        assertEquals(CarPlayScreen.target(restored).name, started(restored))

        built.resume()
        idle()
        assertNull(started(restored))
    }
}
