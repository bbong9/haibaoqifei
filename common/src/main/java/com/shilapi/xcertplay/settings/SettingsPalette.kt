package com.shilapi.xcertplay.settings

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import androidx.core.graphics.ColorUtils

/** Colors and dimensions used only by the settings screen. */
internal data class SettingsPalette(
    val gradient: IntArray,
    val middleStop: Float,
    val card: Int,
    val divider: Int,
    val text: Int,
    val secondary: Int,
    val hint: Int,
    val accent: Int,
    val onAccent: Int,
    val selected: Int,
    val selectedText: Int,
    val switchOff: Int,
    val success: Int,
) {
    fun background(): GradientDrawable {
        val colors = if (Build.VERSION.SDK_INT >= 29) gradient else interpolatedColors()
        return GradientDrawable(GradientDrawable.Orientation.BL_TR, colors).apply {
            if (Build.VERSION.SDK_INT >= 29) setColors(gradient, floatArrayOf(0f, middleStop, 1f))
        }
    }

    // Before API 29 GradientDrawable accepts colors but not their positions.
    private fun interpolatedColors(): IntArray = IntArray(101) { index ->
        val position = index / 100f
        if (position <= middleStop) {
            ColorUtils.blendARGB(gradient[0], gradient[1], position / middleStop)
        } else {
            ColorUtils.blendARGB(gradient[1], gradient[2], (position - middleStop) / (1f - middleStop))
        }
    }

    companion object {
        const val ROW_HEIGHT_DP = 60
        const val CARD_CORNER_DP = ROW_HEIGHT_DP / 4
        const val DIVIDER_DP = 1
        const val ROW_INSET_DP = 16
        const val SUBITEM_INDENT_DP = 16
        const val SLIDER_VALUE_GAP_DP = 8
        const val HELP_SIZE_DP = 44
        const val SWITCH_MIN_HEIGHT_DP = 56
        const val MARKER_SLOT_DP = 28
        const val END_CONTROL_SLOT_DP = 64
        const val VALUE_SLOT_DP = 160
        const val STACKED_VALUE_WIDTH_DP = 480
        const val RAIL_WIDTH_DP = 140
        const val RAIL_GAP_DP = 16
        const val ICON_SIZE_DP = 24
        const val RAIL_ICON_GAP_DP = 8
        const val RAIL_INSET_DP = 12
        const val RAIL_ITEM_START_DP = 8
        const val RAIL_ITEM_END_DP = 12
        const val RAIL_TEXT_SLACK_DP = 8
        const val RAIL_TEXT_SP = 16
        const val RAIL_MIN_TEXT_SP = 12

        private val DARK = SettingsPalette(
            gradient = intArrayOf(0xFF4A0F1B.toInt(), 0xFF1A070C.toInt(), 0xFF0A0305.toInt()),
            middleStop = 0.38f,
            card = 0x12FFFFFF,
            divider = 0x17FFFFFF,
            text = Color.WHITE,
            secondary = 0x9EEBEBF5.toInt(),
            hint = 0x61EBEBF5,
            accent = 0xFFFF4561.toInt(),
            onAccent = 0xFF2A1519.toInt(),
            selected = 0x2EFF4561,
            selectedText = 0xFFFF7A90.toInt(),
            switchOff = 0x38FFFFFF,
            success = 0xFF3DD6A0.toInt(),
        )
        private val LIGHT = SettingsPalette(
            gradient = intArrayOf(0xFFFFD3DC.toInt(), 0xFFFFEEF2.toInt(), Color.WHITE),
            middleStop = 0.40f,
            card = 0xE0FFFFFF.toInt(),
            divider = 0x14781E32,
            text = 0xFF2A1519.toInt(),
            secondary = 0xFF7A5A62.toInt(),
            hint = 0xFFB09AA0.toInt(),
            accent = 0xFFFF4561.toInt(),
            onAccent = 0xFF2A1519.toInt(),
            selected = 0xFFFFE1E7.toInt(),
            selectedText = 0xFFD0193A.toInt(),
            switchOff = 0xFFE6D3D8.toInt(),
            success = 0xFF16A678.toInt(),
        )

        fun of(night: Boolean): SettingsPalette = if (night) DARK else LIGHT
    }
}
