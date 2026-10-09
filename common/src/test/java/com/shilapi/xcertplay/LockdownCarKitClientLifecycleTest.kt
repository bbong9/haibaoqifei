package com.shilapi.xcertplay

import com.shilapi.xcertplay.transport.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.*

/** Replay service replies through the real plist framing; TLS is a pass-through test boundary. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [28], manifest = org.robolectric.annotation.Config.NONE)
class LockdownCarKitClientLifecycleTest {
    private val host = mock(Iap2UsbMuxHost::class.java)
    private val lockdown = mock(Iap2UsbMuxTcpConnection::class.java)
    private val service = mock(Iap2UsbMuxTcpConnection::class.java)
    private val record = mock(LockdownPairRecord::class.java).also {
        `when`(it.hostId).thenReturn("test-host")
        `when`(it.systemBuid).thenReturn("test-system")
    }
    private val client = LockdownCarKitClient(host) { stream, _, _ -> stream }

    private fun replies(serviceReply: String?) {
        fun frame(body: String): ByteArray {
            val xml = "<?xml version=\"1.0\"?><plist version=\"1.0\"><dict>$body</dict></plist>".toByteArray()
            return java.nio.ByteBuffer.allocate(4 + xml.size).putInt(xml.size).put(xml).array()
        }
        val bytes = frame("<key>EnableSessionSSL</key><true/>") +
            (serviceReply?.let(::frame) ?: ByteArray(0))
        var offset = 0
        `when`(lockdown.recv(anyInt(), anyLong())).thenAnswer { call ->
            if (offset == bytes.size) null else {
                val end = minOf(bytes.size, offset + call.getArgument<Int>(0))
                bytes.copyOfRange(offset, end).also { offset = end }
            }
        }
        `when`(host.connect(Iap2UsbMuxHost.LOCKDOWN_PORT, 5_000L)).thenReturn(lockdown)
        `when`(host.connect(1234, 5_000L)).thenReturn(service)
    }

    @Test fun rejectedServiceClosesTheLockdownSessionAndAllowsAnotherAttempt() {
        repeat(2) {
            replies("<key>Error</key><string>InvalidService</string>")
            val error = assertThrows(IphoneUsbException.Protocol::class.java) { client.open(record, "test") }
            assertTrue(error.message!!.contains("InvalidService"))
        }
        verify(lockdown, times(2)).close()
        verify(service, never()).close()
        verify(host, never()).close()
    }

    @Test fun missingServiceResponseClosesTheLockdownSession() {
        replies(null)
        assertThrows(IphoneUsbException.TimedOut::class.java) { client.open(record, "test") }
        verify(lockdown).close()
    }

    @Test fun malformedServicePortClosesTheLockdownSession() {
        replies("<key>Port</key><integer>0</integer>")
        assertThrows(IphoneUsbException.Protocol::class.java) { client.open(record, "test") }
        verify(lockdown).close()
    }

    @Test fun serviceConnectFailureClosesTheLockdownSession() {
        replies("<key>Port</key><integer>1234</integer>")
        `when`(host.connect(1234, 5_000L)).thenAnswer { throw IphoneUsbException.TimedOut("connect timed out") }
        assertThrows(IphoneUsbException.TimedOut::class.java) { client.open(record, "test") }
        verify(lockdown).close()
    }

    @Test fun serviceTlsFailureClosesTheLockdownSession() {
        replies("<key>Port</key><integer>1234</integer><key>EnableServiceSSL</key><true/>")
        val withTlsFailure = LockdownCarKitClient(host) { stream, _, _ ->
            if (stream === service) {
                // TlsDuplexChannel.open owns and closes its stream on handshake failure.
                stream.close()
                throw IphoneUsbException.TimedOut("TLS handshake timed out")
            }
            stream
        }
        assertThrows(IphoneUsbException.TimedOut::class.java) { withTlsFailure.open(record, "test") }
        verify(service).close()
        verify(lockdown).close()
    }

    @Test fun successfulServiceKeepsItsSessionAliveUntilClosed() {
        replies("<key>Port</key><integer>1234</integer>")
        val opened = client.open(record, "test")
        verify(lockdown, never()).close()
        verify(service, never()).close()
        val message = byteArrayOf(1, 2, 3)
        opened.send(message)
        verify(service).send(message)
        opened.close()
        opened.close()
        verify(service).close()
        verify(lockdown).close()
        verify(host, never()).close()
    }
}
