package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsWindowDiagnosticsTest {
    @Test fun effectiveSizeAndRedactionAreStable() {
        val cases = listOf(160 to "small", 200 to "medium", 240 to "large", 320 to "large")
        for ((effectiveDensity, size) in cases) {
            val line = SettingsWindowDiagnostics.line(1920, 1080, 160, 1.3f, effectiveDensity)
            assertEquals("Settings window: widthPx=1920 heightPx=1080 densityDpi=160 fontScale=1.30 interfaceSize=$size", line)
            assertEquals(line, DiagnosticRedactor.redact(line))
        }
    }
}
