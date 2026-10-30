package com.shilapi.xcertplay

import android.graphics.Color
import androidx.core.graphics.ColorUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ClusterCardPaletteTest {
    @Test fun explicitThemesOverrideTheControllerAndFollowUsesItsResult() {
        for (dark in listOf(false, true)) {
            assertEquals(false, ClusterCardNight.resolve(1, dark))
            assertEquals(true, ClusterCardNight.resolve(2, dark))
            assertEquals(dark, ClusterCardNight.resolve(0, dark))
            assertEquals(dark, ClusterCardNight.resolve(-1, dark))
        }
    }

    @Test fun allTextIncludingCompositedPreviousLyricsMeetsAa() {
        for (night in listOf(false, true)) {
            val palette = ClusterCardPalette.of(night)
            val previous = ColorUtils.compositeColors(
                ColorUtils.setAlphaComponent(palette.previousLyric, palette.previousLyricAlpha),
                palette.background,
            )
            for ((kind, color) in listOf("primary" to palette.primary, "secondary" to palette.secondary,
                "lyric" to palette.lyric, "previous" to previous)) {
                val ratio = ColorUtils.calculateContrast(color, palette.background)
                assertTrue("night=$night kind=$kind ratio=$ratio", ratio >= 4.5)
            }
            assertEquals(255, Color.alpha(palette.background))
        }
    }

    @Test fun daytimeColorsUseLavenderAndNightColorsUseIndigo() {
        val day = ClusterCardPalette.of(false)
        assertEquals(Color.rgb(196, 191, 242), day.background)
        assertEquals(Color.rgb(173, 167, 230), day.border)
        assertEquals(Color.rgb(42, 21, 25), day.primary)
        assertEquals(Color.rgb(63, 58, 87), day.secondary)
        assertEquals(Color.rgb(74, 69, 102), day.previousLyric)
        assertEquals(Color.rgb(163, 15, 42), day.lyric)
        assertEquals(Color.rgb(255, 69, 97), day.accent)
        assertEquals(Color.argb(89, 255, 255, 255), day.chip)
        assertEquals(255, day.previousLyricAlpha)
        assertEquals(day.chip, day.coverPlaceholder)
        assertEquals(day.secondary, day.coverPlaceholderText)
        val night = ClusterCardPalette.of(true)
        assertEquals(Color.rgb(75, 71, 168), night.background)
        assertEquals(Color.rgb(105, 101, 198), night.border)
        assertEquals(Color.WHITE, night.primary)
        assertEquals(Color.rgb(224, 222, 250), night.secondary)
        assertEquals(Color.rgb(201, 197, 242), night.previousLyric)
        assertEquals(Color.rgb(255, 208, 218), night.lyric)
        assertEquals(Color.rgb(255, 140, 160), night.accent)
        assertEquals(Color.argb(46, 255, 255, 255), night.chip)
        assertEquals(255, night.previousLyricAlpha)
        assertEquals(Color.argb(36, 255, 255, 255), night.coverPlaceholder)
        assertEquals(Color.WHITE, night.coverPlaceholderText)
        assertTrue(ColorUtils.calculateContrast(night.accent, night.background) >= 3.0)
    }
}
