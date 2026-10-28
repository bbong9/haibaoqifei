package com.shilapi.xcertplay

import android.content.Context
import android.graphics.RectF
import com.shilapi.xcertplay.airplay.SafeAreaRect
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import kotlin.math.min
import kotlin.math.roundToInt

enum class ClusterMirrorSize { SAFE_AREA, FULL_HEIGHT }

internal object ClusterMirrorPolicy {
    const val MAX_SCALE_PERCENT = 75

    fun enabled(context: Context): Boolean = AirPlayPersistence.loadClusterMirrorMain(context) &&
        AdbClusterRouter.enabled(context) && !AirPlayPersistence.loadLegacyClusterEnabled(context)

    fun resolutionPercent(saved: Int, enabled: Boolean): Int =
        if (enabled) min(saved, MAX_SCALE_PERCENT) else saved
}

internal object ClusterMirrorCrop {
    fun keep(width: Int, height: Int, dockEdge: Int?, rightHandDrive: Boolean, percent: Int): RectF {
        val w = width.coerceAtLeast(0)
        val h = height.coerceAtLeast(0)
        val fraction = percent.coerceIn(0, 20) / 100f
        if (dockEdge == AirPlayInfoPlist.DOCK_EDGE_BOTTOM) {
            return RectF(0f, 0f, w.toFloat(), (h - (h * fraction).roundToInt()).toFloat())
        }
        val cropped = (w * fraction).roundToInt().toFloat()
        return if (rightHandDrive) RectF(0f, 0f, w - cropped, h.toFloat())
        else RectF(cropped, 0f, w.toFloat(), h.toFloat())
    }
}

internal object ClusterMirrorLayout {
    fun plan(viewW: Int, viewH: Int, srcW: Int, srcH: Int, safe: SafeAreaRect,
        size: ClusterMirrorSize): RectF {
        if (viewW <= 0 || viewH <= 0 || srcW <= 0 || srcH <= 0) {
            return RectF(0f, 0f, viewW.coerceAtLeast(0).toFloat(), viewH.coerceAtLeast(0).toFloat())
        }
        val scale = when (size) {
            ClusterMirrorSize.SAFE_AREA -> min(safe.width.toFloat() / srcW, safe.height.toFloat() / srcH)
            ClusterMirrorSize.FULL_HEIGHT -> viewH.toFloat() / srcH
        }
        val width = srcW * scale
        val height = srcH * scale
        val centerX = (safe.left + safe.right) / 2f
        val centerY = if (size == ClusterMirrorSize.SAFE_AREA) (safe.top + safe.bottom) / 2f else viewH / 2f
        return RectF(centerX - width / 2f, centerY - height / 2f,
            centerX + width / 2f, centerY + height / 2f)
    }
}
