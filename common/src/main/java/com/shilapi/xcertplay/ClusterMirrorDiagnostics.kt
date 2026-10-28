package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import kotlin.math.roundToInt

internal object ClusterMirrorDiagnostics {
    fun report(context: Context): String {
        val mode = if (ClusterMirrorPolicy.enabled(context)) "on" else "off"
        val rect = ClusterActivityOutput.mirrorRect?.let {
            listOf(it.left, it.top, it.right, it.bottom).joinToString(",") { value -> value.roundToInt().toString() }
        } ?: "-"
        val crop = ClusterActivityOutput.mirrorCrop?.let {
            listOf(it.left, it.top, it.right, it.bottom).joinToString(",") { value -> value.roundToInt().toString() }
        } ?: "-"
        val dock = if (mode == "off") "-" else when (ClusterActivityOutput.mirrorDockEdge) {
            AirPlayInfoPlist.DOCK_EDGE_DRIVER_SIDE -> "DRIVER_SIDE"
            AirPlayInfoPlist.DOCK_EDGE_BOTTOM -> "BOTTOM"
            else -> "AUTO"
        }
        val navOnly = if (AirPlayPersistence.loadClusterMirrorNavOnly(context)) "on" else "off"
        val size = AirPlayPersistence.loadClusterMirrorSize(context)
        val custom = if (size == ClusterMirrorSize.CUSTOM) {
            val rect = AirPlayPersistence.loadClusterMirrorCustom(context)
            " mirrorCustom=${rect.x},${rect.y},${rect.w},${rect.h}"
        } else ""
        return "mirrorMain=$mode mirrorSize=${size.name}$custom " +
            "mirrorAttached=${ClusterActivityOutput.mirrorAttached} mirrorRect=$rect mirrorCrop=$crop dockEdge=$dock " +
            "clusterUiPaused=${BydNavigationOutputs.clusterMainMirrorShown()} navOnly=$navOnly " +
            "navigating=${ClusterActivityOutput.mirrorNavigating} dockCrop=${AirPlayPersistence.loadClusterMirrorDockCrop(context)}"
    }

    fun decoderPerformance(): String = "decoderPerf=" +
        (CarPlayBackgroundSession.snapshot()?.sink?.mainDecoderPerformancePoints()?.joinToString(",") ?: "-")
}
