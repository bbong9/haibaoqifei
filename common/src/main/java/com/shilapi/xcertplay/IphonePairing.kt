package com.shilapi.xcertplay

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.host.R

/** User-selected classic Bluetooth discovery. The OS and iPhone still confirm pairing. */
@SuppressLint("MissingPermission") // The owning activity requests the version-specific permissions first.
internal class IphonePairing(
    private val activity: DiPlayActivity,
    private val adapter: BluetoothAdapter,
    private val paired: (BluetoothDevice) -> Unit,
    private val cancelled: () -> Unit,
    private val systemSettings: () -> Unit,
) {
    private val devices = linkedMapOf<String, BluetoothDevice>()
    private val labels = ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1)
    private var dialog: AlertDialog? = null
    private var receiverRegistered = false
    private var finished = false
    private var foreground = true
    private var discoveryOwned = false
    private var pairingAddress: String? = null
    private var completedWhileStopped: BluetoothDevice? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (finished) return
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = intent.device() ?: return
                    val address = runCatching { device.address }.getOrNull() ?: return
                    if (runCatching { device.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)) return
                    devices[address] = device
                    updateList()
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    discoveryOwned = false
                    if (pairingAddress == null) dialog?.setMessage(activity.getString(R.string.pair_scan_finished))
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = intent.device() ?: return
                    if (runCatching { device.address }.getOrNull() != pairingAddress) return
                    // Do not trust a broadcast extra: verify the adapter's current bond state.
                    when (runCatching { device.bondState }.getOrDefault(BluetoothDevice.BOND_NONE)) {
                        BluetoothDevice.BOND_BONDED -> complete(device)
                        BluetoothDevice.BOND_NONE -> {
                            pairingAddress = null
                            dialog?.setMessage(activity.getString(R.string.pair_scan_failed))
                            if (foreground) scan()
                        }
                    }
                }
            }
        }
    }

    fun show() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        try {
            ContextCompat.registerReceiver(activity, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            receiverRegistered = true
        } catch (_: RuntimeException) {
            finished = true
            cancelled()
            return
        }
        val list = ListView(activity).apply {
            adapter = labels
            setOnItemClickListener { _, _, position, _ ->
                devices.values.elementAtOrNull(position)?.let(::pair)
            }
        }
        dialog = activity.appDialogBuilder()
            .setTitle(R.string.pair_your_iphone)
            .setMessage(R.string.pair_scan_instructions)
            .setView(list)
            .setPositiveButton(R.string.pair_scan_again, null)
            .setNeutralButton(R.string.open_bluetooth) { _, _ ->
                finishWithoutCallback()
                systemSettings()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> cancel() }
            .setOnCancelListener { cancel() }
            .setOnDismissListener { if (!finished) cancel() }
            .show().also { shown ->
                shown.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { if (pairingAddress == null) scan() }
            }
        scan()
    }

    fun onStart() {
        foreground = true
        completedWhileStopped?.let { completedWhileStopped = null; complete(it); return }
        if (!finished && dialog != null && pairingAddress == null) scan()
    }

    fun onStop() {
        foreground = false
        stopDiscovery()
    }

    fun close() = finishWithoutCallback()

    private fun scan() {
        if (finished || !foreground) return
        devices.clear()
        updateList()
        dialog?.setMessage(activity.getString(R.string.pair_scan_instructions))
        val started = runCatching {
            if (adapter.isDiscovering) true
            else adapter.startDiscovery().also { discoveryOwned = it }
        }.getOrDefault(false)
        if (!started) dialog?.setMessage(activity.getString(R.string.pair_scan_failed))
    }

    private fun updateList() {
        labels.clear()
        devices.forEach { (address, device) ->
            val name = runCatching { device.name }.getOrNull()?.takeIf { it.isNotBlank() }
            labels.add(if (name == null) address else "$name · ${address.takeLast(5)}")
        }
        labels.notifyDataSetChanged()
    }

    private fun pair(device: BluetoothDevice) {
        if (pairingAddress != null) return
        stopDiscovery()
        if (runCatching { device.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)) {
            complete(device)
            return
        }
        pairingAddress = runCatching { device.address }.getOrNull() ?: return
        dialog?.setMessage(activity.getString(R.string.pair_scan_confirm))
        if (!runCatching { device.createBond() }.getOrDefault(false)) {
            pairingAddress = null
            Toast.makeText(activity, R.string.pair_scan_failed, Toast.LENGTH_LONG).show()
            scan()
        }
    }

    private fun complete(device: BluetoothDevice) {
        if (finished) return
        if (!foreground) { completedWhileStopped = device; return }
        finishWithoutCallback()
        paired(device)
    }

    private fun cancel() {
        if (finished) return
        finishWithoutCallback()
        cancelled()
    }

    private fun finishWithoutCallback() {
        if (finished) return
        finished = true
        stopDiscovery()
        if (receiverRegistered) runCatching { activity.unregisterReceiver(receiver) }
        receiverRegistered = false
        dialog?.dismiss()
        dialog = null
    }

    private fun stopDiscovery() {
        if (discoveryOwned) runCatching { adapter.cancelDiscovery() }
        discoveryOwned = false
    }

    private fun Intent.device(): BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
    }
}
