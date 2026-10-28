package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import kotlin.math.roundToInt

internal object ClusterMirrorDiagnostics {
    fun report(context: Context): String {
        val mode = if (ClusterMirrorPolicy.enabled(context)) "on" else "off"
        val rect = ClusterActivityOutput.mirrorRect?.let {
            listOf(it.left, it.top, it.right, it.bottom).joinToString(",") { value -> value.roundToInt().toString() }
        } ?: "-"
        return "mirrorMain=$mode mirrorSize=${AirPlayPersistence.loadClusterMirrorSize(context).name} " +
            "mirrorAttached=${ClusterActivityOutput.mirrorAttached} mirrorRect=$rect " +
            "clusterUiPaused=${BydNavigationOutputs.clusterMainMirrorShown()}"
    }

    fun decoderPerformance(): String = "decoderPerf=" +
        (CarPlayBackgroundSession.snapshot()?.sink?.mainDecoderPerformancePoints()?.joinToString(",") ?: "-")
}
