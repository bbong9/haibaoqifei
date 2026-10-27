package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.view.View
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import com.shilapi.xcertplay.iap2.message.LaneGuidanceLane
import kotlin.math.min

/**
 * Instruction card drawn by DiPlay on top of the dashboard map.
 *
 * Visual language follows Apple's turn banners: a dark glass capsule with a hairline stroke,
 * the maneuver glyph in a soft chip on the left, distance and road stacked on the right.
 * Maneuver glyphs are Material Symbols (Apache 2.0), tinted the system blue; the roundabout
 * exit number sits in a small badge on the glyph.
 */
internal class ClusterTurnCardView(context: Context) : View(context) {
    private var guidance: ClusterTurnGuidance? = null
    private var lanes: List<LaneGuidanceLane> = emptyList()
    private var night = false
    private var xPercent = ClusterTurnCardOverlay.DEFAULT_X_PERCENT
    private var yPercent = ClusterTurnCardOverlay.DEFAULT_Y_PERCENT
    private var sizePercent = ClusterTurnCardOverlay.DEFAULT_SIZE_PERCENT

    private val accent = Color.rgb(10, 132, 255)
    /** Card opacity as percent; 100 keeps the historical fully-opaque look available. */
    private var opacityPercent = 85
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f
    }

    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val distancePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val roadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(179, 199, 199, 204); typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(224, 235, 235, 240); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val lanePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val lanePath = Path()
    private val rect = RectF()
    private var glyph: Drawable? = null
    private var glyphTag: Int = -1

    init { applyPalette() }

    fun setLayout(xPercent: Int, yPercent: Int, sizePercent: Int) {
        this.xPercent = xPercent
        this.yPercent = yPercent
        this.sizePercent = sizePercent
        invalidate()
    }

    /** Percent 20..100 of the card's background alpha; text stays readable at any value. */
    fun setOpacity(percent: Int) {
        val next = percent.coerceIn(20, 100)
        if (next == opacityPercent) return
        opacityPercent = next
        applyPalette()
        invalidate()
    }

    /** Day: dark glass on a light map; night: lighter translucent panel for the dark map. */
    fun setNightMode(night: Boolean) {
        if (night == this.night) return
        this.night = night
        applyPalette()
        invalidate()
    }

    private fun applyPalette() {
        val alpha = (opacityPercent * 255 / 100).coerceIn(51, 255)
        if (night) {
            glassPaint.color = Color.argb(alpha, 12, 14, 18)
            chipPaint.color = Color.argb((alpha * 0.18f).toInt().coerceAtLeast(12), 255, 255, 255)
            strokePaint.color = Color.argb((alpha * 0.16f).toInt().coerceAtLeast(10), 255, 255, 255)
            distancePaint.color = Color.argb(255, 240, 242, 246)
            roadPaint.color = Color.argb(179, 176, 182, 192)
            infoPaint.color = Color.argb(224, 226, 228, 236)
            badgePaint.color = accent
        } else {
            glassPaint.color = Color.argb(alpha, 28, 28, 30)
            chipPaint.color = Color.argb((alpha * 0.18f).toInt().coerceAtLeast(12), 255, 255, 255)
            strokePaint.color = Color.argb((alpha * 0.15f).toInt().coerceAtLeast(10), 255, 255, 255)
            distancePaint.color = Color.WHITE
            roadPaint.color = Color.argb(179, 199, 199, 204)
            infoPaint.color = Color.argb(224, 235, 235, 240)
            badgePaint.color = accent
        }
    }

    fun setGuidance(next: ClusterTurnGuidance?) {
        if (guidance == next) {
            visibility = if (next == null) GONE else VISIBLE
            return
        }
        guidance = next
        visibility = if (next == null) GONE else VISIBLE
        bringToFront()
        invalidate()
    }

    fun setLanes(next: List<LaneGuidanceLane>) {
        val displayed = next.take(8)
        if (lanes == displayed) return
        lanes = displayed
        invalidate()
    }

    internal fun displayedLaneCount(): Int = if (guidance == null) 0 else lanes.size

    internal fun laneRowRect(): RectF? {
        if (guidance == null || lanes.isEmpty() || width <= 0 || height <= 0) return null
        val card = ClusterTurnCardOverlay.card(width, height, xPercent, yPercent, sizePercent)
        val rowHeight = card.height * 0.42f
        val desired = rowHeight * (lanes.size * 0.8f + (lanes.size - 1) * 0.08f)
        // The card is positioned in panel coordinates, so clamp against the panel, not the card.
        val margin = min(4, (width - 1) / 2)
        val maxStrip = (width - (dp(8f) * 2).toInt()).coerceAtLeast(1)
            .coerceAtMost(width - margin * 2)
        val stripWidth = desired.toInt().coerceAtLeast(1).coerceAtMost(maxStrip)
        val stripLeft = (card.left + card.width / 2f - stripWidth / 2f).toInt()
            .coerceIn(margin, width - stripWidth - margin)
        val top = card.top + card.height + card.height * 0.05f
        return RectF(stripLeft.toFloat(), top, (stripLeft + stripWidth).toFloat(), top + rowHeight)
    }

    internal fun infoStripTop(): Float {
        val card = ClusterTurnCardOverlay.card(width, height, xPercent, yPercent, sizePercent)
        val infoHeight = (card.height * 0.40f).coerceAtLeast(34f)
        val gap = card.height * 0.05f
        return laneRowRect()?.let { it.bottom + gap }
            ?: (card.top + card.height + gap).coerceAtMost(height - infoHeight)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1),
            MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val next = guidance ?: return
        val card = ClusterTurnCardOverlay.card(width, height, xPercent, yPercent, sizePercent)
        val h = card.height.toFloat()
        val w = card.width.toFloat()

        val radius = h * 0.30f
        rect.set(card.left.toFloat(), card.top.toFloat(), card.left + w, card.top + h)
        canvas.drawRoundRect(rect, radius, radius, glassPaint)
        canvas.drawRoundRect(rect, radius, radius, strokePaint)

        val chip = h * 0.76f
        val chipLeft = card.left + h * 0.12f
        val chipTop = card.top + (h - chip) / 2f
        rect.set(chipLeft, chipTop, chipLeft + chip, chipTop + chip)
        canvas.drawRoundRect(rect, chip * 0.26f, chip * 0.26f, chipPaint)

        val exit = next.roundaboutExit.takeIf { it in 1..9 }
        drawGlyph(canvas, next, chipLeft, chipTop, chip, exit)

        val textLeft = chipLeft + chip + h * 0.14f
        val textWidth = card.left + w - h * 0.10f - textLeft
        if (textWidth <= 0f) return
        distancePaint.textSize = h * 0.30f
        roadPaint.textSize = h * 0.17f
        canvas.drawText(
            ellipsize(distanceLabel(next.distanceMeters), textWidth, distancePaint),
            textLeft, card.top + h * 0.44f, distancePaint,
        )
        val road = roadLabel(next)
        if (road.isNotEmpty()) {
            canvas.drawText(
                ellipsize(road, textWidth, roadPaint),
                textLeft, card.top + h * 0.72f, roadPaint,
            )
        }
        laneRowRect()?.let { drawLaneRow(canvas, it) }
        drawInfoStrip(canvas, next, card.left, w.toInt(), h.toInt())
    }

    /** The arrival/duration/distance pill that hangs under the card, like the stock nav bar. */
    private fun drawInfoStrip(canvas: Canvas, next: ClusterTurnGuidance, left: Int, width: Int, cardHeight: Int) {
        val parts = infoParts(next)
        if (parts.isEmpty()) return
        val h = (cardHeight * 0.40f).coerceAtLeast(34f)
        val top = infoStripTop()
        infoPaint.textSize = h * 0.42f
        val separator = "   ·   "
        val text = parts.joinToString(separator)
        val textWidth = paintMeasure(text)
        // Wider than the card is fine: the strip centers on the card and may reach the panel edges.
        // left is an absolute panel coordinate, so every clamp below uses the panel width, never
        // the card width — clamping against the card width is what threw the strip to panel-left.
        val maxStrip = this.width - (dp(8f) * 2).toInt()
        val stripWidth = maxOf(width, (textWidth + h * 0.9f).toInt()).coerceAtMost(maxStrip)
        val stripLeft = (left + width / 2f - stripWidth / 2f).toInt()
            .coerceIn(4, (this.width - stripWidth - 4).coerceAtLeast(4))
        rect.set(stripLeft.toFloat(), top.toFloat(), (stripLeft + stripWidth).toFloat(), top + h)
        canvas.drawRoundRect(rect, h / 2f, h / 2f, glassPaint)
        canvas.drawRoundRect(rect, h / 2f, h / 2f, strokePaint)
        val draw = if (textWidth <= stripWidth - h * 0.6f) text else ellipsize(text, stripWidth - h * 0.6f, infoPaint)
        val drawWidth = paintMeasure(draw)
        val centerX = stripLeft + stripWidth / 2f
        canvas.drawText(draw, centerX - drawWidth / 2f, top + h * 0.69f, infoPaint)
    }

    private fun drawLaneRow(canvas: Canvas, row: RectF) {
        canvas.drawRoundRect(row, row.height() / 2f, row.height() / 2f, glassPaint)
        canvas.drawRoundRect(row, row.height() / 2f, row.height() / 2f, strokePaint)
        val unit = row.width() / (lanes.size * 0.8f + (lanes.size - 1) * 0.08f)
        val laneWidth = unit * 0.8f
        val gap = unit * 0.08f
        lanes.forEachIndexed { index, lane ->
            val centerX = row.left + index * (laneWidth + gap) + laneWidth / 2f
            val primary = LaneArrowShape.of(lane.angle)
            val highlight = lane.highlightAngle?.let(LaneArrowShape::of)
            val split = lane.recommended && highlight != null && highlight != primary
            drawLaneArrow(canvas, row, centerX, laneWidth, primary, lane.recommended && !split)
            if (split) drawLaneArrow(canvas, row, centerX, laneWidth, highlight!!, true)
        }
    }

    private fun drawLaneArrow(canvas: Canvas, row: RectF, cx: Float, laneWidth: Float,
        shape: LaneArrowShape, emphasized: Boolean) {
        val h = row.height()
        val top = row.top
        val left = shape == LaneArrowShape.SLIGHT_LEFT || shape == LaneArrowShape.LEFT || shape == LaneArrowShape.U_TURN_LEFT
        val direction = if (left) -1f else 1f
        val tipX: Float
        val tipY: Float
        val unitX: Float
        val unitY: Float
        lanePath.reset()
        when (shape) {
            LaneArrowShape.STRAIGHT -> {
                tipX = cx; tipY = top + h * 0.22f; unitX = 0f; unitY = -1f
                lanePath.moveTo(cx, top + h * 0.82f)
                lanePath.lineTo(tipX, tipY)
            }
            LaneArrowShape.SLIGHT_LEFT, LaneArrowShape.SLIGHT_RIGHT -> {
                tipX = cx + direction * laneWidth * 0.22f; tipY = top + h * 0.24f
                unitX = direction * 0.45f; unitY = -0.893f
                lanePath.moveTo(cx, top + h * 0.82f)
                lanePath.lineTo(cx, top + h * 0.55f)
                lanePath.lineTo(tipX, tipY)
            }
            LaneArrowShape.LEFT, LaneArrowShape.RIGHT -> {
                tipX = cx + direction * laneWidth * 0.31f; tipY = top + h * 0.32f
                unitX = direction; unitY = 0f
                lanePath.moveTo(cx, top + h * 0.82f)
                lanePath.lineTo(cx, top + h * 0.48f)
                lanePath.quadTo(cx, tipY, tipX, tipY)
            }
            LaneArrowShape.U_TURN_LEFT, LaneArrowShape.U_TURN_RIGHT -> {
                tipX = cx + direction * laneWidth * 0.25f; tipY = top + h * 0.64f
                unitX = 0f; unitY = 1f
                lanePath.moveTo(cx - direction * laneWidth * 0.18f, top + h * 0.82f)
                lanePath.lineTo(cx - direction * laneWidth * 0.18f, top + h * 0.39f)
                lanePath.cubicTo(cx - direction * laneWidth * 0.18f, top + h * 0.14f,
                    tipX, top + h * 0.14f, tipX, top + h * 0.39f)
                lanePath.lineTo(tipX, tipY)
            }
        }
        val head = min(laneWidth * 0.20f, h * 0.14f)
        val baseX = tipX - unitX * head
        val baseY = tipY - unitY * head
        lanePath.moveTo(baseX - unitY * head * 0.65f, baseY + unitX * head * 0.65f)
        lanePath.lineTo(tipX, tipY)
        lanePath.lineTo(baseX + unitY * head * 0.65f, baseY - unitX * head * 0.65f)
        lanePaint.color = distancePaint.color
        lanePaint.alpha = if (emphasized) 255 else (255 * 0.35f).toInt()
        lanePaint.strokeWidth = laneWidth * 0.12f
        canvas.drawPath(lanePath, lanePaint)
    }

    private fun paintMeasure(text: String): Float = infoPaint.measureText(text)

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun infoParts(next: ClusterTurnGuidance): List<String> {
        val parts = mutableListOf<String>()
        next.arrivalEpochSeconds?.takeIf { it > 0 }?.let { epoch ->
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epoch * 1000L))
            parts += context.getString(R.string.turn_card_info_arrival, time)
        }
        next.remainingSeconds?.takeIf { it > 0 }?.let { total ->
            val hours = total / 3600
            val minutes = (total % 3600) / 60
            parts += if (hours > 0) {
                context.getString(R.string.turn_card_info_duration_hm, hours.toInt(), minutes.toInt())
            } else {
                context.getString(R.string.turn_card_info_duration_m, minutes.toInt())
            }
        }
        next.remainingMeters?.takeIf { it > 0 }?.let { meters ->
            parts += context.getString(R.string.turn_card_info_km, meters / 1000.0)
        }
        return parts
    }

    /** Draws the tinted Material Symbols glyph; the roundabout exit number gets a corner badge. */
    private fun drawGlyph(canvas: Canvas, next: ClusterTurnGuidance, left: Float, top: Float, side: Float, exit: Int?) {
        if (next.icon == 0) return
        val resId = glyphRes(next.icon)
        if (resId != glyphTag) {
            glyph = ContextCompat.getDrawable(context, resId)?.mutate()?.apply { setTint(accent) }
            glyphTag = resId
        }
        val inset = side * 0.10f
        glyph?.setBounds(
            (left + inset).toInt(), (top + inset).toInt(),
            (left + side - inset).toInt(), (top + side - inset).toInt(),
        )
        glyph?.draw(canvas)
        if (exit != null) {
            val d = side * 0.42f
            val cx = left + side - d / 2f
            val cy = top + side - d / 2f
            canvas.drawCircle(cx, cy, d / 2f, badgePaint)
            badgeTextPaint.textSize = d * 0.62f
            val textY = cy - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f
            canvas.drawText(exit.toString(), cx, textY, badgeTextPaint)
        }
    }

    private fun glyphRes(icon: Int): Int = when (icon) {
        2 -> R.drawable.ic_turn_card_turn_left
        3 -> R.drawable.ic_turn_card_turn_right
        4 -> R.drawable.ic_turn_card_turn_slight_left
        5 -> R.drawable.ic_turn_card_turn_slight_right
        6 -> R.drawable.ic_turn_card_turn_sharp_left
        7 -> R.drawable.ic_turn_card_turn_sharp_right
        8 -> R.drawable.ic_turn_card_u_turn_left
        19 -> R.drawable.ic_turn_card_u_turn_right
        11, 12, 17, 18 -> R.drawable.ic_turn_card_roundabout_right
        15 -> R.drawable.ic_turn_card_sports_score
        else -> R.drawable.ic_turn_card_straight
    }

    private fun distanceLabel(meters: Int): String = when {
        meters <= 20 -> context.getString(R.string.turn_card_now)
        meters < 1000 -> context.getString(R.string.turn_card_distance_m, meters)
        else -> context.getString(
            R.string.turn_card_distance_km, meters / 100 / 10f,
        )
    }

    private fun roadLabel(next: ClusterTurnGuidance): String =
        if (next.roundaboutExit in 1..9) {
            context.getString(R.string.turn_card_exit, next.roundaboutExit)
        } else {
            next.road
        }

    private fun ellipsize(text: String, maxWidth: Float, paint: Paint): String {
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        var end = text.length
        while (end > 0 && paint.measureText(text.take(end) + ellipsis) > maxWidth) end--
        return if (end == 0) ellipsis else text.take(end) + ellipsis
    }
}
