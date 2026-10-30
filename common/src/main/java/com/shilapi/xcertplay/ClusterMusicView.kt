package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import kotlin.math.min

/** A local overlay; playback metadata and artwork remain owned by the shared media cache. */
internal class ClusterMusicView(context: Context) : View(context) {
    private val main = Handler(Looper.getMainLooper())
    private var stopState: (() -> Unit)? = null
    private var stopSettings: (() -> Unit)? = null
    private var snapshot = ClusterMusicState.Snapshot()
    private var previousArtwork: Bitmap? = null
    private var previousInfo: CarPlayNowPlaying? = null
    private var previousLyric: String? = null
    private var artworkChangedAt = 0L
    private var textChangedAt = 0L
    private var widthChangedAt = 0L
    private var widthFrom = COVER_WIDTH
    private var widthTarget = COVER_WIDTH
    private var visibleUntil = 0L
    private var lastTrackRevision: Long? = null
    private var captionVisible = false
    private var night = false
    private var emptyReason = ClusterCardDiagnostics.HideReason.NO_TRACK
    private var diagnosticFit = ClusterCardDiagnostics.Fit.NORMAL
    private val expire = Runnable { updateVisibility() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    init { visibility = GONE; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun setNightMode(night: Boolean) {
        if (this.night == night) return
        this.night = night
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        stopSettings = ClusterMusicSettings.observe(context) {
            visibleUntil = SystemClock.elapsedRealtime() + ClusterMusicSettings.temporaryMillis(context)
            updateVisibility()
        }
        stopState = ClusterMusicState.observe { next ->
            val now = SystemClock.elapsedRealtime()
            emptyReason = if (!next.lyrics.active && next.sessionEnded) ClusterCardDiagnostics.HideReason.DISCONNECT
                else ClusterCardDiagnostics.HideReason.NO_TRACK
            if (snapshot.artwork !== next.artwork) {
                previousArtwork = snapshot.artwork
                artworkChangedAt = now
            }
            val trackChanged = lastTrackRevision != next.lyrics.trackRevision
            if (trackChanged || snapshot.lyrics.current != next.lyrics.current) {
                previousInfo = snapshot.info
                previousLyric = snapshot.lyrics.current
                textChangedAt = now
            }
            val newWidth = if (next.lyrics.hasLyrics) LYRICS_WIDTH else COVER_WIDTH
            if (widthTarget != newWidth) {
                widthFrom = currentWidth(now)
                widthTarget = newWidth
                widthChangedAt = now
            }
            snapshot = next
            if (trackChanged) {
                lastTrackRevision = next.lyrics.trackRevision
                visibleUntil = next.lyrics.startedAt + ClusterMusicSettings.temporaryMillis(context)
            }
            updateVisibility()
        }
    }

    override fun onDetachedFromWindow() {
        stopState?.invoke(); stopState = null
        stopSettings?.invoke(); stopSettings = null
        main.removeCallbacksAndMessages(null)
        visibility = GONE
        snapshot = ClusterMusicState.Snapshot()
        previousArtwork = null
        previousInfo = null
        previousLyric = null
        lastTrackRevision = null
        captionVisible = false
        emptyReason = ClusterCardDiagnostics.HideReason.NO_TRACK
        diagnosticFit = ClusterCardDiagnostics.Fit.NORMAL
        widthFrom = COVER_WIDTH
        widthTarget = COVER_WIDTH
        ClusterCardDiagnostics.remove(this)
        super.onDetachedFromWindow()
    }

    private fun updateVisibility() {
        main.removeCallbacks(expire)
        val remaining = visibleUntil - SystemClock.elapsedRealtime()
        val permanent = ClusterMusicSettings.permanent(context)
        val lyrics = snapshot.lyrics.hasLyrics
        captionVisible = ClusterMusicSettings.enabled(context) && snapshot.lyrics.active &&
            (permanent || lyrics || remaining > 0)
        visibility = if (captionVisible) VISIBLE else GONE
        if (visibility == VISIBLE && !permanent && !lyrics && remaining > 0) {
            main.postDelayed(expire, if (remaining > FADE_MILLIS) remaining - FADE_MILLIS else remaining)
        }
        if (visibility != VISIBLE) {
            previousArtwork = null
            previousInfo = null
            previousLyric = null
        }
        reportDiagnostics(if (width > 0 && height > 0) cardLayout().fit else ClusterCardDiagnostics.Fit.NORMAL)
        invalidate()
    }

    private fun reportDiagnostics(fit: ClusterCardDiagnostics.Fit) {
        diagnosticFit = fit
        val lyrics = snapshot.lyrics
        val mode = if (visibility != VISIBLE) ClusterCardDiagnostics.MusicMode.NONE
            else if (lyrics.hasLyrics) ClusterCardDiagnostics.MusicMode.LYRICS else ClusterCardDiagnostics.MusicMode.COVER
        val reason = when {
            !ClusterMusicSettings.enabled(context) -> ClusterCardDiagnostics.HideReason.DISABLED
            !lyrics.active -> emptyReason
            visibility != VISIBLE -> ClusterCardDiagnostics.HideReason.EXPIRED
            else -> ClusterCardDiagnostics.HideReason.NONE
        }
        val lines = if (lyrics.hasLyrics) listOf(lyrics.previous, lyrics.current).count { it != null } else 0
        ClusterCardDiagnostics.music(this, mode, lines, lyrics.titleChanges, lyrics.lyricField, reason,
            lyrics.confirmedAfterMs, snapshot.coverDelayMs, fit, lyrics.trackRevision)
    }

    private fun currentWidth(now: Long): Float =
        widthFrom + (widthTarget - widthFrom) * progress(now, widthChangedAt)

    internal data class CardLayout(val card: RectF, val artwork: RectF, val text: RectF,
        val fit: ClusterCardDiagnostics.Fit)

    internal fun cardLayout(now: Long = SystemClock.elapsedRealtime()): CardLayout {
        val factor = currentWidth(now)
        val naturalSide = tileSide(width, height, ClusterMusicSettings.size(context))
        val minimumSide = minOf(80f, width.toFloat(), height.toFloat())
        val side = min(naturalSide, maxOf(minimumSide,
            min(width / factor, height / (1f + CARD_PADDING * 2))))
        val pad = minOf(side * CARD_PADDING, ((width - side) / 3f).coerceAtLeast(0f),
            ((height - side) / 2f).coerceAtLeast(0f))
        val cardWidth = minOf(width.toFloat(), maxOf(side + pad * 3f, side * factor))
        val cardHeight = side + pad * 2
        val desiredLeft = width * ClusterMusicSettings.x(context) / 100f - side / 2f - pad
        val desiredTop = height * ClusterMusicSettings.y(context) / 100f - cardHeight / 2f
        val left = desiredLeft.coerceIn(0f, (width - cardWidth).coerceAtLeast(0f))
        val top = desiredTop.coerceIn(0f, (height - cardHeight).coerceAtLeast(0f))
        val card = RectF(left, top, left + cardWidth, top + cardHeight)
        val artwork = RectF(left + pad, top + pad, left + pad + side, top + pad + side)
        val text = RectF(artwork.right + pad, artwork.top, (card.right - pad).coerceAtLeast(artwork.right + pad), artwork.bottom)
        val fit = when {
            left != desiredLeft || top != desiredTop -> ClusterCardDiagnostics.Fit.CLAMPED
            side < naturalSide || cardWidth < side * factor || pad < side * CARD_PADDING ->
                ClusterCardDiagnostics.Fit.SHRUNK
            else -> ClusterCardDiagnostics.Fit.NORMAL
        }
        return CardLayout(card, artwork, text, fit)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0 || visibility != VISIBLE) return
        val now = SystemClock.elapsedRealtime()
        val remaining = visibleUntil - now
        val permanent = ClusterMusicSettings.permanent(context)
        val lyrics = snapshot.lyrics.hasLyrics
        val fade = if (permanent || lyrics) 1f else (remaining.toFloat() / FADE_MILLIS).coerceIn(0f, 1f)
        val artProgress = progress(now, artworkChangedAt)
        val textProgress = progress(now, textChangedAt)
        val layout = cardLayout(now)
        if (layout.fit != diagnosticFit) reportDiagnostics(layout.fit)
        val palette = ClusterCardPalette.of(night)
        val radius = layout.artwork.height() * .12f
        paint.style = Paint.Style.FILL
        paint.color = palette.background
        paint.alpha = (Color.alpha(palette.background) * fade).toInt()
        canvas.drawRoundRect(layout.card, radius, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(1f, layout.artwork.height() * .008f)
        paint.color = palette.border
        paint.alpha = (Color.alpha(palette.border) * fade).toInt()
        canvas.drawRoundRect(layout.card, radius, radius, paint)
        paint.style = Paint.Style.FILL

        val art = snapshot.artwork?.takeUnless { it.isRecycled }
        val oldArt = previousArtwork?.takeUnless { it.isRecycled }
        paint.color = palette.chip
        paint.alpha = (Color.alpha(palette.chip) * fade).toInt()
        canvas.drawRect(layout.artwork, paint)
        if (oldArt != null && artProgress < 1f) {
            paint.color = Color.WHITE
            paint.alpha = (255 * fade).toInt()
            canvas.drawBitmap(oldArt, null, layout.artwork, paint)
        }
        if (art != null) {
            paint.color = Color.WHITE
            paint.alpha = (255 * fade * artProgress).toInt()
            canvas.drawBitmap(art, null, layout.artwork, paint)
        } else if (oldArt == null || artProgress >= 1f) {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = layout.artwork.height() * .40f
            textPaint.color = palette.secondary
            textPaint.alpha = (255 * fade).toInt()
            canvas.drawText("♪", layout.artwork.centerX(), layout.artwork.centerY() -
                (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
        }

        val clip = canvas.save()
        canvas.clipRect(layout.text)
        if (lyrics) drawLyrics(canvas, layout.text, palette, fade, textProgress)
        else drawCoverText(canvas, layout.text, palette, fade, textProgress)
        canvas.restoreToCount(clip)
        if (artProgress >= 1f) previousArtwork = null
        if (textProgress >= 1f) { previousInfo = null; previousLyric = null }
        if (artProgress < 1f || textProgress < 1f || progress(now, widthChangedAt) < 1f ||
            (!permanent && !lyrics && remaining in 1..FADE_MILLIS)) postInvalidateOnAnimation()
    }

    private fun drawCoverText(canvas: Canvas, bounds: RectF, palette: ClusterCardPalette,
        fade: Float, textProgress: Float) {
        val current = snapshot.info.title.orEmpty()
        if (previousInfo != null && textProgress < 1f) {
            drawCoverLines(canvas, bounds, previousInfo?.title.orEmpty(), previousInfo?.artist,
                palette, fade * (1f - textProgress))
        }
        drawCoverLines(canvas, bounds, current, snapshot.info.artist, palette, fade * textProgress)
    }

    private fun drawCoverLines(canvas: Canvas, bounds: RectF, title: String, artist: String?,
        palette: ClusterCardPalette, alpha: Float) {
        if (alpha <= 0f || bounds.width() <= 0f) return
        val side = bounds.height()
        val layout = fittedText(title, bounds.width(), side * .19f, side * .10f, 2, palette.primary, alpha)
        val detail = artist?.takeIf { it.isNotBlank() }
        val detailSize = side * .11f
        val gap = side * .06f
        val height = layout.height + if (detail != null) detailSize + gap else 0f
        val y = bounds.centerY() - height / 2f
        val save = canvas.save()
        canvas.translate(bounds.left, y)
        layout.draw(canvas)
        canvas.restoreToCount(save)
        if (detail != null) drawSingle(canvas, detail, bounds.left, y + layout.height + gap,
            bounds.width(), detailSize, palette.secondary, alpha)
    }

    private fun drawLyrics(canvas: Canvas, bounds: RectF, palette: ClusterCardPalette,
        fade: Float, textProgress: Float) {
        if (bounds.width() <= 0f) return
        val side = bounds.height()
        val previous = snapshot.lyrics.previous
        val detail = snapshot.lyrics.information(snapshot.info).orEmpty()
        val previousSize = side * .10f
        val detailSize = side * .10f
        val gap = side * .035f
        val current = snapshot.lyrics.current.orEmpty()
        val layout = fittedText(current, bounds.width(), side * .18f, side * .095f, 2, palette.lyric, fade * textProgress)
        val topHeight = if (previous != null) previousSize + gap else 0f
        val bottomHeight = if (detail.isNotEmpty()) detailSize + gap else 0f
        val start = bounds.centerY() - (topHeight + layout.height + bottomHeight) / 2f
        if (previous != null) drawSingle(canvas, previous, bounds.left, start, bounds.width(), previousSize,
            palette.secondary, fade * palette.previousLyricAlpha / 255f)
        val lyricTop = start + topHeight
        if (previousLyric != null && textProgress < 1f) {
            val old = fittedText(previousLyric.orEmpty(), bounds.width(), side * .18f, side * .095f,
                2, palette.lyric, fade * (1f - textProgress))
            val saved = canvas.save(); canvas.translate(bounds.left, lyricTop); old.draw(canvas); canvas.restoreToCount(saved)
        }
        val save = canvas.save(); canvas.translate(bounds.left, lyricTop); layout.draw(canvas); canvas.restoreToCount(save)
        if (detail.isNotEmpty()) drawSingle(canvas, detail, bounds.left, lyricTop + layout.height + gap,
            bounds.width(), detailSize, palette.secondary, fade)
    }

    private fun fittedText(text: String, width: Float, maxSize: Float, minSize: Float,
        lines: Int, color: Int, alpha: Float): StaticLayout {
        val paint = TextPaint(textPaint).apply { textAlign = Paint.Align.LEFT; this.color = color; this.alpha = (255 * alpha).toInt() }
        val available = width.toInt().coerceAtLeast(1)
        var size = maxSize
        do {
            paint.textSize = size
            val natural = StaticLayout.Builder.obtain(text, 0, text.length, paint, available).setIncludePad(false).build()
            if (natural.lineCount <= lines || size <= minSize) break
            size = (size - 1f).coerceAtLeast(minSize)
        } while (true)
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, available)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END)
            .setIncludePad(false).build()
    }

    private fun drawSingle(canvas: Canvas, text: String, x: Float, top: Float, width: Float,
        size: Float, color: Int, alpha: Float) {
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = size
        textPaint.color = color
        textPaint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        val ellipsized = TextUtils.ellipsize(text, textPaint, width, TextUtils.TruncateAt.END).toString()
        canvas.drawText(ellipsized, x, top - textPaint.ascent(), textPaint)
    }

    companion object {
        private const val COVER_WIDTH = 2.6f
        private const val LYRICS_WIDTH = 3.4f
        private const val CARD_PADDING = .06f

        // Speedometer also uses this selector, so retain it independently of the card border.
        internal fun coverFrameColor(artwork: Bitmap?): Int {
            if (artwork == null || artwork.isRecycled) return Color.rgb(28, 32, 40)
            val sample = Bitmap.createScaledBitmap(artwork, 24, 24, true)
            val pixels = IntArray(24 * 24)
            try { sample.getPixels(pixels, 0, 24, 0, 0, 24, 24) }
            finally { if (sample !== artwork) sample.recycle() }
            com.shilapi.xcertplay.media.AmbientAlbumPalette.dominantRgb(pixels)?.let { return it }
            var red = 0; var green = 0; var blue = 0; var count = 0
            for (pixel in pixels) if (Color.alpha(pixel) >= 128) {
                red += Color.red(pixel); green += Color.green(pixel); blue += Color.blue(pixel); count++
            }
            return if (count == 0) Color.rgb(28, 32, 40) else Color.rgb(red / count, green / count, blue / count)
        }

        const val FADE_MILLIS = 400L
        internal fun progress(now: Long, started: Long): Float =
            ((now - started).toFloat() / FADE_MILLIS).coerceIn(0f, 1f)
        const val TEMPORARY_MILLIS = 8_000L
        fun tileSide(width: Int, height: Int, sizePercent: Int): Float =
            (height * .30f * sizePercent.coerceIn(50, 300) / 100f)
                .coerceAtLeast(minOf(80f, width.toFloat(), height.toFloat()))
                .coerceAtMost(minOf(width.toFloat(), height.toFloat()))
    }
}
