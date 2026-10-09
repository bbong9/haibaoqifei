package com.shilapi.xcertplay.media

import android.view.Surface
import com.shilapi.xcertplay.airplay.VideoCodec
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal sealed interface VideoJob {
    data class Config(val codec: VideoCodec, val codecData: ByteArray) : VideoJob
    data class Frame(
        val nalus: ByteArray,
        val receivedNs: Long = System.nanoTime(),
        /** The iPhone's frame time and when the frame arrived (System.nanoTime), 0 when unknown. */
        val senderNanos: Long = 0L,
        val arrivalNanos: Long = 0L,
    ) : VideoJob
    data class SurfaceChanged(val surface: Surface?) : VideoJob
    /** Stop rendering to a surface that is going away; completes [request] once the codec has let go. */
    data class DetachSurface(val request: SurfaceDetachRequest) : VideoJob
    /** Ask the iPhone for a keyframe, so a surface that just came back gets a picture without waiting for motion. */
    data object RefreshPicture : VideoJob
    data object Resync : VideoJob
}

/** Do not resume dependent pictures after losing a reference frame. */
internal class VideoReferenceChain {
    var needsKeyFrame = true
        private set
    fun reset() { needsKeyFrame = true }
    fun accepts(bytes: ByteArray, codec: VideoCodec): Boolean =
        !needsKeyFrame || MediaCodecSupport.isRandomAccess(bytes, codec)
    fun onQueued() { needsKeyFrame = false }
}

/** Limit latency and memory without ever dropping a reference frame silently. */
internal class VideoDecodeQueue(
    // Wi-Fi delivers frames in bursts after a radio gap; the decoder's 250 ms age check bounds latency.
    private val maxFrames: Int = 60,
    private val maxBytes: Int = 8 * 1024 * 1024,
) {
    private val lock = ReentrantLock()
    private val available = lock.newCondition()
    private val jobs = ArrayDeque<VideoJob>()
    private var frameCount = 0
    private var frameBytes = 0L

    fun offer(job: VideoJob) = lock.withLock {
        if (job is VideoJob.Frame) {
            if (frameCount >= maxFrames || frameBytes + job.nalus.size > maxBytes) {
                discardFramesLocked()
                jobs.addLast(VideoJob.Resync)
                available.signal()
            }
            // A single oversized frame is also a lost reference chain.
            if (job.nalus.size > maxBytes) return
            frameCount++
            frameBytes += job.nalus.size
        }
        jobs.addLast(job)
        available.signal()
    }

    fun discardFrames() = lock.withLock { discardFramesLocked() }

    private fun discardFramesLocked() {
        jobs.removeIf { it is VideoJob.Frame || it is VideoJob.Resync }
        frameCount = 0
        frameBytes = 0L
    }

    fun poll(timeoutMillis: Long): VideoJob? {
        lock.lockInterruptibly()
        try {
            var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (jobs.isEmpty()) {
                if (remaining <= 0) return null
                remaining = available.awaitNanos(remaining)
            }
            return jobs.removeFirst().also {
                if (it is VideoJob.Frame) {
                    frameCount--
                    frameBytes -= it.nalus.size
                }
            }
        } finally {
            lock.unlock()
        }
    }

    /** Removes and returns every queued job, for a worker that is shutting down. */
    fun drain(): List<VideoJob> = lock.withLock {
        jobs.toList().also { jobs.clear(); frameCount = 0; frameBytes = 0L }
    }
}

/** Drain output while waiting for input: full output buffers can otherwise starve input forever. */
internal object VideoInputPump {
    fun acquire(
        running: () -> Boolean,
        drain: () -> Unit,
        dequeue: () -> Int,
        nanoTime: () -> Long = System::nanoTime,
        timeoutNs: Long = TimeUnit.MILLISECONDS.toNanos(500),
    ): Int {
        val start = nanoTime()
        while (running()) {
            drain()
            val index = dequeue()
            if (index >= 0) return index
            if (nanoTime() - start >= timeoutNs) break
        }
        return -1
    }
}
