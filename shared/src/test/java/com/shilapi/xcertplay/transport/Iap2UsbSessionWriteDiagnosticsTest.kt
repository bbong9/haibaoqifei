package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, shadows = [WriteDiagnosticsUsbConnectionShadow::class])
class Iap2UsbSessionWriteDiagnosticsTest {
    @Test fun failedWriteReportsTimeoutAndNonnegativeElapsedMillis() {
        val connection = connection()
        val usb = Shadow.extract<WriteDiagnosticsUsbConnectionShadow>(connection)
        val data = byteArrayOf(1, 2, 3, 4)
        val timeoutMillis = 2_000
        usb.transferred = -1

        pipe(connection).use { pipe ->
            val error = assertThrows(IphoneUsbException.DeviceUnavailable::class.java) {
                pipe.write(data, timeoutMillis)
            }
            val message = requireNotNull(error.message)
            assertTrue(message, message.startsWith("USBMUX write transferred -1 of ${data.size} bytes"))
            assertTrue(message, message.contains(" timeoutMs=$timeoutMillis "))
            assertTrue(message, message.contains("elapsedMs="))
            val elapsedMillis = message.substringAfter("elapsedMs=").toLongOrNull()
            assertTrue(message, elapsedMillis != null && elapsedMillis >= 0)
            println(message)
        }

        assertEquals(1, usb.transferCalls)
        assertEquals(timeoutMillis, usb.lastTimeoutMillis)
        assertEquals(data.size, usb.lastLength)
        assertEquals(0x04, usb.lastEndpoint?.address)
        assertSame(data, usb.lastData)
    }

    @Test fun completeWriteSucceedsWithOneTransfer() {
        val connection = connection()
        val usb = Shadow.extract<WriteDiagnosticsUsbConnectionShadow>(connection)
        val data = byteArrayOf(5, 6, 7)
        val timeoutMillis = 1_234
        usb.transferred = data.size

        pipe(connection).use { it.write(data, timeoutMillis) }

        assertEquals(1, usb.transferCalls)
        assertEquals(timeoutMillis, usb.lastTimeoutMillis)
        assertEquals(data.size, usb.lastLength)
        assertEquals(0x04, usb.lastEndpoint?.address)
        assertSame(data, usb.lastData)
    }

    private fun pipe(connection: UsbDeviceConnection) =
        Iap2UsbSession(connection, endpoint(0x04), endpoint(0x85))

    private fun connection(): UsbDeviceConnection = ReflectionHelpers.callConstructor(
        UsbDeviceConnection::class.java, ClassParameter.from(UsbDevice::class.java, null),
    )

    private fun endpoint(address: Int): UsbEndpoint = ReflectionHelpers.callConstructor(
        UsbEndpoint::class.java, ClassParameter.from(Int::class.javaPrimitiveType, address),
        ClassParameter.from(Int::class.javaPrimitiveType, 2),
        ClassParameter.from(Int::class.javaPrimitiveType, 512),
        ClassParameter.from(Int::class.javaPrimitiveType, 0),
    )
}

@Implements(UsbDeviceConnection::class)
class WriteDiagnosticsUsbConnectionShadow {
    var transferred = -1
    var transferCalls = 0
    var lastTimeoutMillis = 0
    var lastLength = 0
    var lastEndpoint: UsbEndpoint? = null
    var lastData: ByteArray? = null

    @Implementation fun bulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray, length: Int, timeout: Int): Int {
        transferCalls++
        lastEndpoint = endpoint
        lastData = buffer
        lastLength = length
        lastTimeoutMillis = timeout
        return transferred
    }

    @Implementation fun close() {}
}
