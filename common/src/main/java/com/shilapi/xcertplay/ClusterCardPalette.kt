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
) {
    companion object {
        const val DAY_PREVIOUS_LYRIC_ALPHA = 255
        const val NIGHT_PREVIOUS_LYRIC_ALPHA = 166 // 65.1%.

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
            background = Color.rgb(26, 7, 12),
            border = Color.argb(77, 255, 69, 97),
            primary = Color.WHITE,
            secondary = Color.rgb(217, 179, 187),
            previousLyric = Color.rgb(217, 179, 187),
            accent = Color.rgb(255, 69, 97),
            chip = Color.argb(46, 255, 69, 97),
            lyric = Color.rgb(255, 122, 144),
            previousLyricAlpha = NIGHT_PREVIOUS_LYRIC_ALPHA,
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
