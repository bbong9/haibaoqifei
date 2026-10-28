package com.shilapi.xcertplay

import android.content.Context
import android.graphics.RectF
import com.shilapi.xcertplay.airplay.SafeAreaRect
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import kotlin.math.min
import kotlin.math.roundToInt

enum class ClusterMirrorSize { SAFE_AREA, FULL_HEIGHT, CUSTOM }

data class ClusterMirrorCustomRect(val x: Int = 50, val y: Int = 50, val w: Int = 67, val h: Int = 100) {
    internal fun clamped() = ClusterMirrorCustomRect(x.coerceIn(0, 100), y.coerceIn(0, 100),
        w.coerceIn(20, 100), h.coerceIn(20, 100))
}

data class ClusterMirrorView(val zoom: Int = 100, val focusX: Int = 50, val focusY: Int = 50) {
    internal fun clamped() = ClusterMirrorView(zoom.coerceIn(100, 250),
        focusX.coerceIn(0, 100), focusY.coerceIn(0, 100))
}

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

    fun cover(keep: RectF, targetAspect: Float): RectF {
        if (keep.width() <= 0f || keep.height() <= 0f || !targetAspect.isFinite() || targetAspect <= 0f)
            return RectF(keep)
        val sourceAspect = keep.width() / keep.height()
        return when {
            sourceAspect > targetAspect -> {
                val halfWidth = keep.height() * targetAspect / 2f
                RectF(keep.centerX() - halfWidth, keep.top, keep.centerX() + halfWidth, keep.bottom)
            }
            sourceAspect < targetAspect -> {
                val halfHeight = keep.width() / targetAspect / 2f
                RectF(keep.left, keep.centerY() - halfHeight, keep.right, keep.centerY() + halfHeight)
            }
            else -> RectF(keep)
        }
    }

    fun frame(keep: RectF, targetAspect: Float, zoomPercent: Int, focusX: Int, focusY: Int): RectF {
        val covered = cover(keep, targetAspect)
        val view = ClusterMirrorView(zoomPercent, focusX, focusY).clamped()
        if (view == ClusterMirrorView() || keep.width() <= 0f || keep.height() <= 0f) return covered
        val zoom = view.zoom / 100f
        val width = covered.width() / zoom
        val height = covered.height() / zoom
        val left = keep.left + (keep.width() - width) * view.focusX / 100f
        val top = keep.top + (keep.height() - height) * view.focusY / 100f
        return RectF(left, top, (left + width).coerceAtMost(keep.right),
            (top + height).coerceAtMost(keep.bottom))
    }
}

internal object ClusterMirrorLayout {
    fun custom(viewW: Int, viewH: Int, x: Int, y: Int, w: Int, h: Int): RectF {
        val viewWidth = viewW.coerceAtLeast(0).toFloat()
        val viewHeight = viewH.coerceAtLeast(0).toFloat()
        val settings = ClusterMirrorCustomRect(x, y, w, h).clamped()
        val width = viewWidth * settings.w / 100f
        val height = viewHeight * settings.h / 100f
        val left = (viewWidth * settings.x / 100f - width / 2f).coerceIn(0f, viewWidth - width)
        val top = (viewHeight * settings.y / 100f - height / 2f).coerceIn(0f, viewHeight - height)
        return RectF(left, top, left + width, top + height)
    }

    fun plan(viewW: Int, viewH: Int, srcW: Int, srcH: Int, safe: SafeAreaRect,
        size: ClusterMirrorSize, customRect: ClusterMirrorCustomRect = ClusterMirrorCustomRect()): RectF {
        if (size == ClusterMirrorSize.CUSTOM)
            return custom(viewW, viewH, customRect.x, customRect.y, customRect.w, customRect.h)
        if (viewW <= 0 || viewH <= 0 || srcW <= 0 || srcH <= 0) {
            return RectF(0f, 0f, viewW.coerceAtLeast(0).toFloat(), viewH.coerceAtLeast(0).toFloat())
        }
        val scale = if (size == ClusterMirrorSize.SAFE_AREA)
            min(safe.width.toFloat() / srcW, safe.height.toFloat() / srcH) else viewH.toFloat() / srcH
        val width = srcW * scale
        val height = srcH * scale
        val centerX = (safe.left + safe.right) / 2f
        val centerY = if (size == ClusterMirrorSize.SAFE_AREA) (safe.top + safe.bottom) / 2f else viewH / 2f
        return RectF(centerX - width / 2f, centerY - height / 2f,
            centerX + width / 2f, centerY + height / 2f)
    }
}
