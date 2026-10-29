package com.shilapi.xcertplay.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.method.TransformationMethod
import android.text.style.ReplacementSpan
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import kotlin.math.ceil

internal object SettingsVisuals {
    private const val BADGE_TEXT_SP = 12f
    private const val BADGE_INSET_DP = 6
    private const val BADGE_HEIGHT_DP = 22

    class DescriptionToggle(context: Context, title: String, theme: SettingsTheme) : TextView(context) {
        private val descriptions = mutableListOf<View>()
        private var expanded = false

        init {
            text = "?"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(theme.textSecondary)
            contentDescription = context.getString(R.string.settings_description_for, title)
            isFocusable = true
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                theme.dp(context, SettingsPalette.HELP_SIZE_DP),
                theme.dp(context, SettingsPalette.ROW_HEIGHT_DP),
            )
            setOnClickListener {
                expanded = !expanded
                descriptions.forEach { it.visibility = if (expanded) View.VISIBLE else View.GONE }
            }
        }

        fun addDescription(view: View) {
            descriptions.add(view)
            view.visibility = if (expanded) View.VISIBLE else View.GONE
        }
    }

    fun descriptionToggle(context: Context, title: String, description: View, theme: SettingsTheme): DescriptionToggle =
        DescriptionToggle(context, title, theme).apply { addDescription(description) }

    fun nextConnectionMarker(context: Context, theme: SettingsTheme): TextView = TextView(context).apply {
        text = "↻"
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(theme.textSecondary)
        contentDescription = context.getString(R.string.saved_for_your_next_connection)
        if (Build.VERSION.SDK_INT >= 26) tooltipText = contentDescription
        tag = "settings_next_connection"
        layoutParams = LinearLayout.LayoutParams(theme.dp(context, BADGE_HEIGHT_DP),
            theme.dp(context, SettingsPalette.ROW_HEIGHT_DP))
    }

    /** Keep the original localized title for search and accessibility; only its rendering changes. */
    fun experimentalBadge(view: TextView) {
        val context = view.context
        val badge = context.getString(R.string.setup_badge_experimental)
        val suffix = Regex("\\s*[（(]${Regex.escape(badge)}[）)](?=\\s*(?:·|$))", RegexOption.IGNORE_CASE)
        view.transformationMethod = object : TransformationMethod {
            override fun getTransformation(source: CharSequence?, target: View?): CharSequence? {
                source ?: return null
                val matches = suffix.findAll(source).toList()
                if (matches.isEmpty()) return source
                val palette = SettingsPalette.of((context as? com.shilapi.xcertplay.AppAppearanceOwner)?.currentAppNight == true)
                return SpannableString(source).apply {
                    matches.forEach { match ->
                        setSpan(BadgeSpan(context, badge, palette), match.range.first, match.range.last + 1,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }

            override fun onFocusChanged(view: View?, sourceText: CharSequence?, focused: Boolean,
                direction: Int, previouslyFocusedRect: android.graphics.Rect?) = Unit
        }
    }

    private class BadgeSpan(context: Context, private val title: String, palette: SettingsPalette) : ReplacementSpan() {
        private val density = context.resources.displayMetrics.density
        private val textSize = BADGE_TEXT_SP * context.resources.displayMetrics.scaledDensity
        private val inset = BADGE_INSET_DP * density
        private val height = BADGE_HEIGHT_DP * density
        private val background = palette.selected
        private val foreground = palette.selectedText

        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            val old = paint.textSize
            paint.textSize = textSize
            val width = ceil(paint.measureText(title) + inset * 2 + inset).toInt()
            paint.textSize = old
            return width
        }

        override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float,
            top: Int, y: Int, bottom: Int, paint: Paint) {
            val oldSize = paint.textSize
            val oldColor = paint.color
            paint.textSize = textSize
            val width = paint.measureText(title) + inset * 2
            val center = (top + bottom) / 2f
            val bounds = RectF(x + inset, center - height / 2, x + inset + width, center + height / 2)
            paint.color = background
            canvas.drawRoundRect(bounds, height / 2, height / 2, paint)
            paint.color = foreground
            val metrics = paint.fontMetrics
            canvas.drawText(title, bounds.left + inset, center - (metrics.ascent + metrics.descent) / 2, paint)
            paint.textSize = oldSize
            paint.color = oldColor
        }
    }
}
