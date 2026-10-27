package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.glance.CarPlayGlance
import com.shilapi.xcertplay.iap2.message.LaneGuidanceLane

internal object LaneStripDiagnostics {
    fun change(lanes: List<LaneGuidanceLane>): String {
        if (lanes.isEmpty()) return "Lane strip: cleared"
        val recommended = lanes.filter { it.recommended }.joinToString(",") { it.index.toString() }.ifEmpty { "-" }
        val angles = lanes.joinToString(",") { it.angle.toString() }
        val highlights = lanes.joinToString(",") { it.highlightAngle?.toString() ?: "-" }
        return "Lane strip: lanes=${lanes.size} recommended=$recommended angles=$angles highlights=$highlights"
    }

    fun report(context: Context): String {
        val stats = CarPlayGlance.laneDiagnostics()
        val last = stats.last.joinToString(",") {
            "${it.index}:${it.status}:${it.angle}:${it.highlightAngle ?: "-"}"
        }.ifEmpty { "-" }
        val enabled = if (AirPlayPersistence.loadClusterLaneStrip(context)) "on" else "off"
        return "laneStrip=$enabled laneUpdates=${stats.updates} laneMax=${stats.max} lastLanes=$last"
    }
}
