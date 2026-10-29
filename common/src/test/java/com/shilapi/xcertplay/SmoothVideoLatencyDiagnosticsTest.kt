package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.SmoothVideoLatency
import org.junit.Assert.assertEquals
import org.junit.Test

class SmoothVideoLatencyDiagnosticsTest {
    @Test fun decoderLatencySurvivesDiagnosticRedaction() {
        for (latency in SmoothVideoLatency.entries) {
            val line = "Video: decoder=c2.mtk.avc.decoder mime=video/avc size=1920x1080 priority=0 latency=${latency.diagnosticValue}"
            assertEquals(line, DiagnosticRedactor.redact(line))
        }
    }
}
