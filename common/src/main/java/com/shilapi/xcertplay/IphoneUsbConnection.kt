package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.IntentCompat
import com.shilapi.xcertplay.transport.IphoneUsbMatcher
import java.io.Closeable

/** Foreground USB discovery also works when the car does not dispatch the manifest activity intent. */
internal object IphoneUsbConnection {
    fun attached(context: Context): UsbDevice? = runCatching {
        context.getSystemService(UsbManager::class.java)?.deviceList?.values
            ?.firstOrNull { it.vendorId == IphoneUsbMatcher.APPLE_VENDOR_ID }
    }.getOrNull()

    fun fromIntent(intent: Intent): UsbDevice? {
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return null
        return IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            ?.takeIf { it.vendorId == IphoneUsbMatcher.APPLE_VENDOR_ID }
    }

    fun observe(context: Context, onAttached: (UsbDevice) -> Unit): Closeable {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                fromIntent(intent)?.let(onAttached)
            }
        }
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, filter)
        return Closeable { context.unregisterReceiver(receiver) }
    }

    fun hostIntent(context: Context, device: UsbDevice): Intent =
        Intent(context, CarPlayHostActivity::class.java)
            .setAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            .putExtra(UsbManager.EXTRA_DEVICE, device)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
}
