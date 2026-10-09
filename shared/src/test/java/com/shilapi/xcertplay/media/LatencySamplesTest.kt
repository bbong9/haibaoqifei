package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class LatencySamplesTest {
    @Test fun boundedQuantilesResetBetweenWindowsButMaximumIncludesAllSamples() {
        val samples = LatencySamples(4)
        samples.record(100_000_000)
        for (ms in 1..4) samples.record(ms * 1_000_000L)
        assertEquals(" queue p50=2ms p95=4ms max=100ms n=5", samples.takeSummary("queue"))
        assertEquals("", samples.takeSummary("queue"))
        samples.record(0)
        assertEquals(" queue p50=0ms p95=0ms max=0ms n=1", samples.takeSummary("queue"))
    }
}
