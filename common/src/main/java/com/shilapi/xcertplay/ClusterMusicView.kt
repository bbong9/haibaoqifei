package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import java.util.Locale
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
    private var previousLyricState: ClusterLyricState? = null
    private var artworkChangedAt = 0L
    private var textChangedAt = 0L
    private var visibleUntil = 0L
    private var lastTrackRevision: Long? = null
    private var captionVisible = false
    private var night = false
    private var emptyReason = ClusterCardDiagnostics.HideReason.NO_TRACK
    private var diagnosticFit = ClusterCardDiagnostics.Fit.NORMAL
    private val expire = Runnable { updateVisibility() }
    private var progressTickScheduled = false
    private val progressTick = object : Runnable {
        override fun run() {
            progressTickScheduled = false
            if (shouldRefreshProgress()) {
                invalidate()
                updateProgressTick()
            }
        }
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val artworkPath = Path()
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val progressTextPaint = TextPaint(textPaint)

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
            val awaitingInfo = snapshot.lyrics.active && !hasDisplayableContent(snapshot)
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
                previousLyricState = snapshot.lyrics
                textChangedAt = now
            }
            snapshot = next
            if (trackChanged) {
                lastTrackRevision = next.lyrics.trackRevision
                visibleUntil = next.lyrics.startedAt + ClusterMusicSettings.temporaryMillis(context)
            } else if (awaitingInfo && hasDisplayableContent(next)) {
                visibleUntil = now + ClusterMusicSettings.temporaryMillis(context)
            }
            updateVisibility()
        }
    }

    override fun onDetachedFromWindow() {
        stopState?.invoke(); stopState = null
        stopSettings?.invoke(); stopSettings = null
        main.removeCallbacksAndMessages(null)
        progressTickScheduled = false
        visibility = GONE
        snapshot = ClusterMusicState.Snapshot()
        previousArtwork = null
        previousInfo = null
        previousLyric = null
        previousLyricState = null
        lastTrackRevision = null
        captionVisible = false
        emptyReason = ClusterCardDiagnostics.HideReason.NO_TRACK
        diagnosticFit = ClusterCardDiagnostics.Fit.NORMAL
        ClusterCardDiagnostics.remove(this)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateProgressTick()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateProgressTick()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        updateProgressTick()
    }

    private fun shouldRefreshProgress(): Boolean = isAttachedToWindow && isShown &&
        windowVisibility == VISIBLE && snapshot.info.playing && snapshot.hasProgress

    private fun updateProgressTick() {
        if (shouldRefreshProgress()) {
            if (!progressTickScheduled) {
                progressTickScheduled = true
                main.postDelayed(progressTick, 1_000L)
            }
        } else if (progressTickScheduled) {
            main.removeCallbacks(progressTick)
            progressTickScheduled = false
        }
    }

    private fun updateVisibility() {
        main.removeCallbacks(expire)
        val remaining = visibleUntil - SystemClock.elapsedRealtime()
        val permanent = ClusterMusicSettings.permanent(context)
        val lyrics = snapshot.lyrics.hasLyrics
        captionVisible = ClusterMusicSettings.enabled(context) && snapshot.lyrics.active && hasDisplayableContent(snapshot) &&
            (permanent || lyrics || remaining > 0)
        visibility = if (captionVisible) VISIBLE else GONE
        if (visibility == VISIBLE && !permanent && !lyrics && remaining > 0) {
            main.postDelayed(expire, if (remaining > FADE_MILLIS) remaining - FADE_MILLIS else remaining)
        }
        if (visibility != VISIBLE) {
            previousArtwork = null
            previousInfo = null
            previousLyric = null
            previousLyricState = null
        }
        updateProgressTick()
        reportDiagnostics(if (width > 0 && height > 0) cardLayout().fit else ClusterCardDiagnostics.Fit.NORMAL)
        invalidate()
    }

    private fun hasDisplayableContent(state: ClusterMusicState.Snapshot): Boolean =
        !state.info.title.isNullOrBlank() || !state.info.artist.isNullOrBlank() ||
            !state.info.album.isNullOrBlank() || state.lyrics.hasLyrics || state.artwork?.isRecycled == false

    private fun reportDiagnostics(fit: ClusterCardDiagnostics.Fit) {
        diagnosticFit = fit
        val lyrics = snapshot.lyrics
        val mode = if (visibility != VISIBLE) ClusterCardDiagnostics.MusicMode.NONE
            else if (lyrics.hasLyrics) ClusterCardDiagnostics.MusicMode.LYRICS else ClusterCardDiagnostics.MusicMode.COVER
        val reason = when {
            !ClusterMusicSettings.enabled(context) -> ClusterCardDiagnostics.HideReason.DISABLED
            !lyrics.active -> emptyReason
            !hasDisplayableContent(snapshot) -> ClusterCardDiagnostics.HideReason.NO_INFO
            visibility != VISIBLE -> ClusterCardDiagnostics.HideReason.EXPIRED
            else -> ClusterCardDiagnostics.HideReason.NONE
        }
        val lines = if (lyrics.hasLyrics) listOf(lyrics.previous, lyrics.current).count { it != null } else 0
        ClusterCardDiagnostics.music(this, mode, lines, lyrics.titleChanges, lyrics.lyricField, reason,
            lyrics.confirmedAfterMs, snapshot.coverDelayMs, fit, lyrics.trackRevision,
            if (mode != ClusterCardDiagnostics.MusicMode.NONE && snapshot.hasProgress)
                ClusterCardDiagnostics.Progress.SHOWN else ClusterCardDiagnostics.Progress.HIDDEN)
    }

    internal data class CardLayout(val card: RectF, val artwork: RectF, val text: RectF,
        val fit: ClusterCardDiagnostics.Fit, val progressRow: RectF?)

    internal fun cardLayout(): CardLayout {
        val naturalHeight = height * CARD_HEIGHT_FRACTION * ClusterMusicSettings.size(context) / 100f
        val cardHeight = minOf(naturalHeight, height.toFloat(), width / CARD_ASPECT)
        val cardWidth = cardHeight * CARD_ASPECT
        val pad = cardHeight * CARD_PADDING
        val side = cardHeight * ARTWORK_FRACTION
        val desiredLeft = width * ClusterMusicSettings.x(context) / 100f - side / 2f - pad
        val desiredTop = height * ClusterMusicSettings.y(context) / 100f - cardHeight / 2f
        val left = desiredLeft.coerceIn(0f, (width - cardWidth).coerceAtLeast(0f))
        val top = desiredTop.coerceIn(0f, (height - cardHeight).coerceAtLeast(0f))
        val card = RectF(left, top, left + cardWidth, top + cardHeight)
        val artworkTop = top + (cardHeight - side) / 2f
        val artwork = RectF(left + pad, artworkTop, left + pad + side, artworkTop + side)
        val progressRow = if (snapshot.hasProgress) {
            val center = (artwork.bottom + card.bottom) / 2f
            RectF(card.left + pad, center - cardHeight * .045f, card.right - pad, center + cardHeight * .045f)
        } else null
        val text = RectF(artwork.right + cardHeight * TEXT_GAP, top + pad, card.right - pad,
            if (progressRow != null) artwork.bottom else card.bottom - pad)
        val fit = when {
            left != desiredLeft || top != desiredTop -> ClusterCardDiagnostics.Fit.CLAMPED
            cardHeight < naturalHeight -> ClusterCardDiagnostics.Fit.SHRUNK
            else -> ClusterCardDiagnostics.Fit.NORMAL
        }
        return CardLayout(card, artwork, text, fit, progressRow)
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
        val layout = cardLayout()
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
        artworkPath.reset()
        artworkPath.addRoundRect(layout.artwork, radius, radius, Path.Direction.CW)
        val artClip = canvas.save()
        canvas.clipPath(artworkPath)
        paint.color = palette.coverPlaceholder
        paint.alpha = (Color.alpha(palette.coverPlaceholder) * fade).toInt()
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
            textPaint.color = palette.coverPlaceholderText
            textPaint.alpha = (255 * fade).toInt()
            canvas.drawText("♪", layout.artwork.centerX(), layout.artwork.centerY() -
                (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
        }
        canvas.restoreToCount(artClip)

        val clip = canvas.save()
        canvas.clipRect(layout.text)
        if (lyrics) drawLyrics(canvas, layout.text, layout.card.height(), palette, fade, textProgress,
            layout.progressRow != null)
        else drawCoverText(canvas, layout.text, layout.card.height(), palette, fade, textProgress)
        canvas.restoreToCount(clip)
        layout.progressRow?.let { drawProgress(canvas, layout, it, palette, fade, now) }
        if (artProgress >= 1f) previousArtwork = null
        if (textProgress >= 1f) { previousInfo = null; previousLyric = null; previousLyricState = null }
        if (artProgress < 1f || textProgress < 1f ||
            (!permanent && !lyrics && remaining in 1..FADE_MILLIS)) postInvalidateOnAnimation()
    }

    private fun drawCoverText(canvas: Canvas, bounds: RectF, cardHeight: Float, palette: ClusterCardPalette,
        fade: Float, textProgress: Float) {
        val current = snapshot.info.title.orEmpty()
        if (previousInfo != null && textProgress < 1f) {
            drawCoverLines(canvas, bounds, cardHeight, previousInfo?.title.orEmpty(), previousInfo?.artist,
                palette, fade * (1f - textProgress))
        }
        drawCoverLines(canvas, bounds, cardHeight, current, snapshot.info.artist, palette, fade * textProgress)
    }

    private fun drawCoverLines(canvas: Canvas, bounds: RectF, cardHeight: Float, title: String, artist: String?,
        palette: ClusterCardPalette, alpha: Float) {
        if (alpha <= 0f || bounds.width() <= 0f) return
        val layout = layoutText(bounds, cardHeight, title, null, artist, false, palette, alpha)
        drawMain(canvas, bounds.left, layout)
        layout.detail?.let { drawSingle(canvas, bounds.left, it) }
    }

    private fun drawLyrics(canvas: Canvas, bounds: RectF, cardHeight: Float, palette: ClusterCardPalette,
        fade: Float, textProgress: Float, protectMainLines: Boolean) {
        if (bounds.width() <= 0f) return
        if (previousLyric != null && textProgress < 1f) {
            val oldState = previousLyricState
            val old = layoutText(bounds, cardHeight, previousLyric.orEmpty(),
                oldState?.previous?.takeIf { oldState.hasLyrics },
                previousInfo?.let { oldState?.information(it) }, true, palette, fade * (1f - textProgress),
                protectMainLines)
            drawLyricsLayout(canvas, bounds.left, old)
        }
        val layout = layoutText(bounds, cardHeight, snapshot.lyrics.current.orEmpty(),
            snapshot.lyrics.previous, snapshot.lyrics.information(snapshot.info), true, palette, fade * textProgress,
            protectMainLines)
        drawLyricsLayout(canvas, bounds.left, layout)
    }

    private fun drawLyricsLayout(canvas: Canvas, x: Float, layout: CardTextLayout) {
        layout.previous?.let { drawSingle(canvas, x, it) }
        drawMain(canvas, x, layout)
        layout.detail?.let { drawSingle(canvas, x, it) }
    }

    internal data class ProgressLayout(val elapsed: RectF, val bar: RectF, val duration: RectF,
        val textScaleX: Float)

    internal fun progressLayout(row: RectF, cardHeight: Float, durationMillis: Long): ProgressLayout {
        progressTextPaint.textSize = maxOf(minimumTextSize(), cardHeight * .06f)
        progressTextPaint.textScaleX = 1f
        val reserved = maxOf(progressTextPaint.measureText("0:00:00"),
            progressTextPaint.measureText(formatTime(durationMillis)))
        val width = row.width().coerceAtLeast(0f)
        val gap = min(cardHeight * .025f, width * .04f)
        val labelWidth = min(reserved, ((width - 2f * gap) * .4f).coerceAtLeast(0f))
        val scale = if (reserved > 0f) (labelWidth / reserved).coerceAtMost(1f) else 1f
        val barHeight = min(cardHeight * .02f, row.height().coerceAtLeast(0f))
        return ProgressLayout(
            RectF(row.left, row.top, row.left + labelWidth, row.bottom),
            RectF(row.left + labelWidth + gap, row.centerY() - barHeight / 2f,
                row.right - labelWidth - gap, row.centerY() + barHeight / 2f),
            RectF(row.right - labelWidth, row.top, row.right, row.bottom), scale,
        )
    }

    private fun drawProgress(canvas: Canvas, layout: CardLayout, row: RectF, palette: ClusterCardPalette,
        fade: Float, now: Long) {
        val duration = snapshot.info.durationMillis?.takeIf { it > 0L } ?: return
        val position = snapshot.positionMillis(now) ?: return
        val progress = progressLayout(row, layout.card.height(), duration)
        val clip = canvas.save()
        canvas.clipRect(row.left, layout.artwork.bottom, row.right, layout.card.bottom)
        progressTextPaint.textScaleX = progress.textScaleX
        progressTextPaint.color = palette.secondary
        progressTextPaint.alpha = (Color.alpha(palette.secondary) * fade).toInt().coerceIn(0, 255)
        val baseline = row.centerY() - (progressTextPaint.ascent() + progressTextPaint.descent()) / 2f
        progressTextPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(formatTime(position), progress.elapsed.left, baseline, progressTextPaint)
        progressTextPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(formatTime(duration), progress.duration.right, baseline, progressTextPaint)

        val radius = progress.bar.height() / 2f
        paint.color = palette.secondary
        paint.alpha = (Color.alpha(palette.secondary) * fade * .25f).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(progress.bar, radius, radius, paint)
        val filled = RectF(progress.bar)
        filled.right = filled.left + filled.width() * (position.toDouble() / duration.toDouble()).toFloat()
        if (filled.width() > 0f) {
            paint.color = palette.lyric
            paint.alpha = (Color.alpha(palette.lyric) * fade).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(filled, radius, radius, paint)
        }
        canvas.restoreToCount(clip)
    }

    internal data class TextLine(val text: String, val paint: TextPaint, val top: Float) {
        val height: Float get() = paint.descent() - paint.ascent()
    }

    internal data class CardTextLayout(val main: StaticLayout, val mainTop: Float,
        val previous: TextLine?, val detail: TextLine?)

    internal fun layoutText(bounds: RectF, cardHeight: Float, current: String, previous: String?, detail: String?,
        lyrics: Boolean, palette: ClusterCardPalette, alpha: Float, protectMainLines: Boolean = false): CardTextLayout {
        val minimum = minimumTextSize()
        val gap = cardHeight * .03f
        fun line(text: String?, size: Float, color: Int, lineAlpha: Float): TextLine? {
            if (text.isNullOrBlank()) return null
            val linePaint = TextPaint(textPaint).apply {
                textAlign = Paint.Align.LEFT
                textSize = maxOf(minimum, size)
                this.color = color
                this.alpha = (255 * lineAlpha).toInt().coerceIn(0, 255)
            }
            val value = TextUtils.ellipsize(text, linePaint, bounds.width(), TextUtils.TruncateAt.END).toString()
            return TextLine(value, linePaint, 0f)
        }
        var previousLine = if (lyrics) line(previous, cardHeight * .08f, palette.previousLyric,
            alpha * palette.previousLyricAlpha / 255f) else null
        var detailLine = line(detail, cardHeight * if (lyrics) .065f else .085f, palette.secondary, alpha)
        val minMain = maxOf(minimum, cardHeight * if (lyrics) .085f else .095f)
        val maxMain = maxOf(minimum, cardHeight * if (lyrics) .115f else .14f)
        val minPaint = TextPaint(textPaint).apply { textSize = minMain }
        val minHeight = minPaint.descent() - minPaint.ascent() + 1f
        fun available(): Float = bounds.height() -
            (previousLine?.let { it.height + gap } ?: 0f) - (detailLine?.let { it.height + gap } ?: 0f)
        if (lyrics && protectMainLines) {
            val mainPaint = TextPaint(textPaint).apply { textSize = maxMain }
            val natural = StaticLayout.Builder.obtain(current, 0, current.length, mainPaint,
                bounds.width().toInt().coerceAtLeast(1)).setIncludePad(false).build()
            val mainHeight = natural.getLineBottom(min(natural.lineCount, 3) - 1).toFloat()
            if (available() < mainHeight) previousLine = null
            if (available() < mainHeight) detailLine = null
        }
        if (available() < minHeight) previousLine = null
        if (available() < minHeight) detailLine = null
        val main = fittedText(current, bounds.width(), maxMain, minMain, if (lyrics) 3 else 2,
            available().coerceAtLeast(0f), if (lyrics) palette.lyric else palette.primary, alpha)
        val blockHeight = main.height + (previousLine?.let { it.height + gap } ?: 0f) +
            (detailLine?.let { it.height + gap } ?: 0f)
        var top = maxOf(bounds.top, bounds.centerY() - blockHeight / 2f)
        previousLine = previousLine?.copy(top = top)?.also { top += it.height + gap }
        val mainTop = top
        top += main.height + gap
        detailLine = detailLine?.copy(top = top)
        return CardTextLayout(main, mainTop, previousLine, detailLine)
    }

    private fun minimumTextSize(): Float =
        maxOf(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics),
            width * 18f / 1920f)

    private fun fittedText(text: String, width: Float, maxSize: Float, minSize: Float,
        lines: Int, maxHeight: Float, color: Int, alpha: Float): StaticLayout {
        val paint = TextPaint(textPaint).apply {
            textAlign = Paint.Align.LEFT; this.color = color; this.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        }
        val available = width.toInt().coerceAtLeast(1)
        var size = maxSize
        do {
            paint.textSize = size
            val natural = StaticLayout.Builder.obtain(text, 0, text.length, paint, available).setIncludePad(false).build()
            if ((natural.lineCount <= lines && natural.height <= maxHeight) || size <= minSize) break
            size = (size - 1f).coerceAtLeast(minSize)
        } while (true)
        val metrics = paint.fontMetricsInt
        val lineHeight = (metrics.descent - metrics.ascent).coerceAtLeast(1)
        val fittingLines = min(lines, (maxHeight / lineHeight).toInt().coerceAtLeast(1))
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, available)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setMaxLines(fittingLines).setEllipsize(TextUtils.TruncateAt.END)
            .setIncludePad(false).build()
    }

    private fun drawMain(canvas: Canvas, x: Float, layout: CardTextLayout) {
        val save = canvas.save()
        canvas.translate(x, layout.mainTop)
        layout.main.draw(canvas)
        canvas.restoreToCount(save)
    }

    private fun drawSingle(canvas: Canvas, x: Float, line: TextLine) {
        canvas.drawText(line.text, x, line.top - line.paint.ascent(), line.paint)
    }

    companion object {
        private const val CARD_ASPECT = 1.7f
        private const val CARD_HEIGHT_FRACTION = .388f
        private const val CARD_PADDING = .035f
        private const val ARTWORK_FRACTION = .72f
        private const val TEXT_GAP = .05f

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
        internal fun formatTime(millis: Long): String {
            val seconds = millis.coerceAtLeast(0L) / 1_000L
            val hours = seconds / 3_600L
            return if (hours > 0L) String.format(Locale.ROOT, "%d:%02d:%02d", hours,
                (seconds / 60L) % 60L, seconds % 60L)
            else String.format(Locale.ROOT, "%d:%02d", seconds / 60L, seconds % 60L)
        }

        internal fun progress(now: Long, started: Long): Float =
            ((now - started).toFloat() / FADE_MILLIS).coerceIn(0f, 1f)
        const val TEMPORARY_MILLIS = 8_000L
    }
}
