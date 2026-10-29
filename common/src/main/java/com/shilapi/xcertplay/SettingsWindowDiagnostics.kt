package com.shilapi.xcertplay

import java.util.Locale

internal object SettingsWindowDiagnostics {
    fun line(width: Int, height: Int, systemDensityDpi: Int, fontScale: Float, effectiveDensityDpi: Int): String {
        val scale = effectiveDensityDpi.toFloat() / systemDensityDpi.coerceAtLeast(1)
        val size = when {
            scale <= 1.001f -> "small"
            scale <= 1.251f -> "medium"
            else -> "large"
        }
        return String.format(Locale.ROOT,
            "Settings window: widthPx=%d heightPx=%d densityDpi=%d fontScale=%.2f interfaceSize=%s",
            width, height, systemDensityDpi, fontScale, size)
    }
}
