package com.shilapi.xcertplay

import android.graphics.Color

internal data class ClusterCardPalette(
    val background: Int,
    val border: Int,
    val primary: Int,
    val secondary: Int,
    val previousLyric: Int,
    val accent: Int,
    val chip: Int,
    val lyric: Int,
    val previousLyricAlpha: Int,
    val coverPlaceholder: Int = chip,
    val coverPlaceholderText: Int = secondary,
) {
    companion object {
        const val DAY_PREVIOUS_LYRIC_ALPHA = 255
        const val NIGHT_PREVIOUS_LYRIC_ALPHA = 255

        private val day = ClusterCardPalette(
            background = Color.rgb(196, 191, 242),
            border = Color.rgb(173, 167, 230),
            primary = Color.rgb(42, 21, 25),
            secondary = Color.rgb(63, 58, 87),
            previousLyric = Color.rgb(74, 69, 102),
            accent = Color.rgb(255, 69, 97),
            chip = Color.argb(89, 255, 255, 255),
            lyric = Color.rgb(163, 15, 42),
            previousLyricAlpha = DAY_PREVIOUS_LYRIC_ALPHA,
        )
        private val night = ClusterCardPalette(
            background = Color.rgb(75, 71, 168),
            border = Color.rgb(105, 101, 198),
            primary = Color.WHITE,
            secondary = Color.rgb(224, 222, 250),
            previousLyric = Color.rgb(201, 197, 242),
            accent = Color.rgb(255, 140, 160),
            chip = Color.argb(46, 255, 255, 255),
            lyric = Color.rgb(255, 208, 218),
            previousLyricAlpha = NIGHT_PREVIOUS_LYRIC_ALPHA,
            coverPlaceholder = Color.argb(36, 255, 255, 255),
            coverPlaceholderText = Color.WHITE,
        )

        fun of(night: Boolean): ClusterCardPalette = if (night) this.night else day
    }
}

internal object ClusterCardNight {
    fun resolve(themeSetting: Int, darkMode: Boolean): Boolean = when (themeSetting) {
        1 -> false
        2 -> true
        else -> darkMode
    }
}
