package com.shilapi.xcertplay.media

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCrypto
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaCodec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class VideoDecoderPriorityTest {
    private val formats = ConcurrentHashMap<Surface, MediaFormat>()
    private val reports = CopyOnWriteArrayList<String>()
    private val surfaces = mutableListOf<Surface>()
    private val textures = mutableListOf<SurfaceTexture>()
    @Volatile private var rejectTuned = false
    private lateinit var sink: AndroidMediaSink
    private val sps = byteArrayOf(0x67, 0x42, 0xC0.toByte(), 0x1E)
    private val pps = byteArrayOf(0x68, 0xCE.toByte())
    private val avcC = byteArrayOf(1, 0x42, 0xC0.toByte(), 0x1E, 0xFF.toByte(), 0xE1.toByte(), 0, sps.size.toByte()) +
        sps + byteArrayOf(1, 0, pps.size.toByte()) + pps

    @Before fun setUp() {
        val codec = object : ShadowMediaCodec.CodecConfig.Codec {
            override fun process(input: ByteBuffer, output: ByteBuffer) = Unit
            override fun onConfigured(format: MediaFormat?, surface: Surface?, crypto: MediaCrypto?, flags: Int) {
                if (rejectTuned && format?.containsKey(MediaFormat.KEY_PRIORITY) == true)
                    throw IllegalArgumentException("tuned format rejected")
                if (format != null && surface != null) formats[surface] = format
            }
        }
        ShadowMediaCodec.addDecoder(MediaFormat.MIMETYPE_VIDEO_AVC,
            ShadowMediaCodec.CodecConfig(64 * 1024, 64 * 1024, codec))
        sink = AndroidMediaSink(surface = null)
    }

    @After fun tearDown() {
        sink.close()
        assertTrue(sink.awaitVideoReleased(5_000))
        surfaces.forEach { it.release() }
        textures.forEach { it.release() }
        ShadowMediaCodec.clearCodecs()
    }

    @Test fun onlyTheMainScreenGetsRealtimePriority() {
        assertEquals(0, videoDecoderPriority(110, null))
        assertEquals(1, videoDecoderPriority(110, " stream=110 mirror=cluster"))
        assertEquals(1, videoDecoderPriority(111, null))
    }

    @Test fun tunedFormatsAndDecoderLogsCarryTheirActualPriority() {
        val main = newSurface()
        val mirror = newSurface()
        val cluster = newSurface()
        sink.setVideoDiagnosticHandler(110) { reports += it }
        sink.setVideoDiagnosticHandler(111) { reports += it }
        sink.setSurface(110, main)
        sink.setMirrorSurface(110, "cluster", mirror)
        sink.setSurface(111, cluster)
        sink.onVideoConfig(110, avcC)
        sink.onVideoConfig(111, avcC)
        waitUntil { formats.size == 3 && reports.count { it.startsWith("decoder=") } >= 3 }
        assertEquals(0, formats.getValue(main).getInteger(MediaFormat.KEY_PRIORITY))
        assertEquals(1, formats.getValue(mirror).getInteger(MediaFormat.KEY_PRIORITY))
        assertEquals(1, formats.getValue(cluster).getInteger(MediaFormat.KEY_PRIORITY))
        assertTrue(reports.toString(), reports.any { it.startsWith("decoder=") && it.contains(" priority=0") })
        assertTrue(reports.toString(), reports.any { it.startsWith("decoder=") && it.contains(" priority=1") })
        val decoder = sink.javaClass.getDeclaredField("videoDecoders").apply { isAccessible = true }
            .get(sink) as Map<*, *>
        val mainDecoder = checkNotNull(decoder[110])
        val buildFormat = mainDecoder.javaClass.getDeclaredMethod("buildFormat", String::class.java,
            List::class.java, DecoderAttempt::class.java, MtkDecoderMode::class.java).apply { isAccessible = true }
        val minimal = buildFormat.invoke(mainDecoder, MediaFormat.MIMETYPE_VIDEO_AVC,
            emptyList<ByteArray>(), DecoderAttempt(null, tuned = false), MtkDecoderMode.NONE) as MediaFormat
        assertFalse(minimal.containsKey(MediaFormat.KEY_PRIORITY))
    }

    @Test fun aRejectedTunedFormatUsesTheExistingMinimalFallbackAndLogsNoPriority() {
        rejectTuned = true
        val main = newSurface()
        sink.setVideoDiagnosticHandler(110) { reports += it }
        sink.setSurface(110, main)
        sink.onVideoConfig(110, avcC)
        waitUntil { formats.containsKey(main) && reports.any { it.startsWith("decoder=") } }
        assertFalse(formats.getValue(main).containsKey(MediaFormat.KEY_PRIORITY))
        assertTrue(reports.toString(), reports.any { it.startsWith("decoder=") && it.contains(" priority=-") })
    }

    private fun newSurface(): Surface {
        val texture = SurfaceTexture(0).also { textures += it }
        return Surface(texture).also { surfaces += it }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("formats=$formats reports=$reports", condition())
    }
}
