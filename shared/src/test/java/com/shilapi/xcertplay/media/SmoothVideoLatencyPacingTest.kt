package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmoothVideoLatencyPacingTest {
    private val ms = 1_000_000L

    @Test fun balancedPresetMatchesTheOriginalOnEveryFrame() {
        val original = PacingDelay(90 * ms)
        val balanced = PacingDelay.forLatency(90 * ms, SmoothVideoLatency.BALANCED)
        repeat(1_000) { frame ->
            val needed = if (frame % 20 == 0) 150 * ms else (30 + frame % 70) * ms
            original.onFrame(needed)
            balanced.onFrame(needed)
            assertEquals("frame=$frame", original.nanos, balanced.nanos)
        }
    }

    @Test fun lowLatencySettlesWithinFiveSecondsAtThirtyAndSixtyFps() {
        for (fps in listOf(30, 60)) {
            val delay = PacingDelay.forLatency(200 * ms, SmoothVideoLatency.LOW)
            var previous = delay.nanos
            repeat(fps * 5) {
                delay.onFrame(40 * ms)
                assertTrue(previous - delay.nanos in 0..2 * ms)
                previous = delay.nanos
            }
            assertEquals("fps=$fps", 50 * ms, delay.nanos)
        }
    }

    @Test fun lowLatencyKeepsTheThirtyMillisecondMinimumAndFasterDescent() {
        val delay = PacingDelay.forLatency(55 * ms, SmoothVideoLatency.LOW)
        repeat(14) { delay.onFrame(0) }
        assertEquals(55 * ms, delay.nanos)
        delay.onFrame(0)
        assertEquals(53 * ms, delay.nanos)
        repeat(100) { delay.onFrame(0) }
        assertEquals(30 * ms, delay.nanos)
    }
}
