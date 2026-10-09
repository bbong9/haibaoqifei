package com.shilapi.xcertplay

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

/** Collected only on report export, never in the video or UI frame loop. */
internal object VideoPerformanceDiagnostics {
    fun report(context: Context): String = buildString {
        if (SealPerformanceProfile.enabled(context)) {
            appendLine("Edition: Seal 2022 / seal.1; target 60 fps; not yet measured on this car")
            appendLine("User-supplied target: iPhone 16 Pro Max / iOS 27; firmware 21.1.2.2601210.1; hardware V31.E34.00.32.00")
        }
        appendLine("Saved output: direct=${AirPlayPersistence.loadDirectVideo(context)} smooth=${AirPlayPersistence.loadSmoothVideo(context)} (applies at reconnect)")
        appendLine("rx=received; shown=codec releases, not physical presentation; touch2frame=next received frame, not verified touch response")
        appendLine("queue=receive-to-worker wait; releaseGap=codec release interval, not display interval; allGap includes static screens and stalls")
        appendLine("Actual displays:")
        runCatching {
            val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            manager?.displays?.forEach { display ->
                val mode = display.mode
                appendLine("  id=${display.displayId} mode=${mode.modeId} ${mode.physicalWidth}x${mode.physicalHeight} ${mode.refreshRate}Hz")
                display.supportedModes.forEach {
                    appendLine("    supported=${it.modeId} ${it.physicalWidth}x${it.physicalHeight} ${it.refreshRate}Hz")
                }
            }
        }.onFailure { appendLine("  unavailable: ${it.javaClass.simpleName}") }
        appendLine("AVC decoder capabilities (advertised support is not a benchmark):")
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder && MediaFormat.MIMETYPE_VIDEO_AVC in it.supportedTypes }
                .forEach { info ->
                    val hardware = if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated.toString() else "unknown"
                    appendLine("  ${info.name} hardware=$hardware")
                    runCatching {
                        val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                        val video = caps.videoCapabilities
                        for ((width, height) in listOf(1920 to 1080, 1600 to 900, 1280 to 720)) {
                            appendLine("    ${width}x${height}@60=${video?.areSizeAndRateSupported(width, height, 60.0)}")
                        }
                        if (Build.VERSION.SDK_INT >= 30) appendLine("    lowLatency=${caps.isFeatureSupported("low-latency")}")
                    }.onFailure { appendLine("    unavailable: ${it.javaClass.simpleName}") }
                }
        }.onFailure { appendLine("  unavailable: ${it.javaClass.simpleName}") }
    }
}
