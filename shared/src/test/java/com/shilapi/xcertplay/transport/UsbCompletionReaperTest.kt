package com.shilapi.xcertplay.transport

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch

class UsbCompletionReaperTest {
    private val completions = LinkedBlockingQueue<String>()
    private val reaps = AtomicInteger()
    private val reaper = UsbCompletionReaper("test-reaper") {
        reaps.incrementAndGet()
        completions.take().takeUnless { it == FAILED }
    }

    @Test fun timesOutWhileTheQueuedRequestIsPendingAndKeepsItForTheNextWait() {
        reaper.queued()
        assertThrows(TimeoutException::class.java) { reaper.await(30) }
        completions.put("read")
        assertEquals("read", reaper.await(1_000))
        reaper.close()
    }

    @Test fun completionsAreReturnedInOrder() {
        reaper.queued()
        reaper.queued()
        completions.put("first")
        completions.put("second")
        assertEquals("first", reaper.await(1_000))
        assertEquals("second", reaper.await(1_000))
        reaper.close()
    }

    @Test fun doesNotBlockInTheReapCallWithNothingInFlight() {
        reaper.queued()
        completions.put("read")
        assertEquals("read", reaper.await(1_000))
        TimeUnit.MILLISECONDS.sleep(50)
        assertEquals(1, reaps.get())
        reaper.close()
    }

    @Test fun aFailedConnectionEndsEveryLaterWait() {
        reaper.queued()
        completions.put(FAILED)
        assertNull(reaper.await(1_000))
        assertNull(reaper.await(1_000))
        reaper.close()
    }

    @Test fun closeWakesAWaiterEvenBeforeTheNativeCompletionArrives() {
        val waiting = CountDownLatch(1)
        val ended = CountDownLatch(1)
        reaper.queued()
        val waiter = Thread {
            waiting.countDown()
            if (reaper.await(60_000) == null) ended.countDown()
        }.apply { start() }
        try {
            assertTrue(waiting.await(1, TimeUnit.SECONDS))
            reaper.close()
            assertTrue(ended.await(1, TimeUnit.SECONDS))
        } finally {
            completions.put(FAILED)
            reaper.close()
            waiter.join(1_000)
        }
    }

    private companion object { const val FAILED = "failed" }
}
