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
                ColorUtils.setAlphaComponent(palette.secondary, palette.previousLyricAlpha),
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
}
