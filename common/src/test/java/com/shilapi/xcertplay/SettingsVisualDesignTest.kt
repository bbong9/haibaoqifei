package com.shilapi.xcertplay

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import android.widget.SeekBar
import androidx.core.graphics.ColorUtils
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.settings.SettingsPalette
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w1280dp-h800dp")
class SettingsVisualDesignTest {
    @Test fun primaryAndSecondaryTextRemainLegibleOnBothGradients() {
        for (night in listOf(true, false)) {
            val palette = SettingsPalette.of(night)
            for (background in palette.gradient) {
                val card = ColorUtils.compositeColors(palette.card, background)
                val secondary = ColorUtils.compositeColors(palette.secondary, card)
                assertTrue("primary contrast on $background", ColorUtils.calculateContrast(palette.text, card) >= 4.5)
                assertTrue("secondary contrast on $background", ColorUtils.calculateContrast(secondary, card) >= 4.5)
            }
            assertTrue(ColorUtils.calculateContrast(palette.onAccent, palette.accent) >= 4.5)
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun modernGradientUsesBottomLeftToTopRightAndSpecifiedStops() {
        for (night in listOf(true, false)) {
            val palette = SettingsPalette.of(night)
            val drawable = palette.background()
            assertEquals(GradientDrawable.Orientation.BL_TR, drawable.orientation)
            val bitmap = Bitmap.createBitmap(1001, 1, Bitmap.Config.ARGB_8888)
            try {
                drawable.setBounds(0, 0, bitmap.width, bitmap.height)
                drawable.draw(Canvas(bitmap))
                assertColorNear(palette.gradient[0], bitmap.getPixel(0, 0))
                assertColorNear(palette.gradient[1], bitmap.getPixel((1000 * palette.middleStop).toInt(), 0))
                assertColorNear(palette.gradient[2], bitmap.getPixel(1000, 0))
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test @Config(sdk = [28])
    fun olderGradientPreservesTheSameStops() {
        for (night in listOf(true, false)) {
            val palette = SettingsPalette.of(night)
            val drawable = palette.background()
            assertEquals(GradientDrawable.Orientation.BL_TR, drawable.orientation)
            val colors = drawable.colors!!
            assertEquals(101, colors.size)
            assertEquals(palette.gradient[0], colors.first())
            assertEquals(palette.gradient[1], colors[(100 * palette.middleStop).toInt()])
            assertEquals(palette.gradient[2], colors.last())
        }
    }

    @Test fun leavingOrDestroyingSettingsReleasesItsBackground() {
        val context = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings"))
        val screen = controller.get().apply { sealEditionCheck = { true } }
        controller.setup()
        val background = ReflectionHelpers.getField<GradientDrawable>(screen, "settingsBackground")
        val owner = ReflectionHelpers.getField<View>(screen, "settingsBackgroundOwner")
        assertSame(background, owner.background)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSettingsCategory",
            ReflectionHelpers.ClassParameter(SettingsCategory::class.java, SettingsCategory.DISPLAY))
        assertSame(background, ReflectionHelpers.getField<GradientDrawable>(screen, "settingsBackground"))
        assertNull(owner.background)
        ReflectionHelpers.setField(screen, "page", "home")
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        assertNull(ReflectionHelpers.getField<Any?>(screen, "settingsBackground"))
        assertNull(ReflectionHelpers.getField<Any?>(screen, "settingsBackgroundOwner"))
        ReflectionHelpers.setField(screen, "page", "settings")
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        val lastOwner = ReflectionHelpers.getField<View>(screen, "settingsBackgroundOwner")
        controller.pause().stop().destroy()
        assertNull(lastOwner.background)
        assertNull(ReflectionHelpers.getField<Any?>(screen, "settingsBackground"))
    }

    @Test fun rowHeightAndCardRadiusUseTheSharedDimensions() {
        val context = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings"))
        val screen = controller.get().apply { sealEditionCheck = { true } }
        controller.setup()
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSettingsCategory",
            ReflectionHelpers.ClassParameter(SettingsCategory::class.java, SettingsCategory.DISPLAY))
        val root = screen.window.decorView
        val title = screen.getString(R.string.frame_rate)
        val button = descendants(root).filterIsInstance<TextView>()
            .first { it.text.toString().startsWith("$title · ") }
        val density = screen.resources.displayMetrics.density
        assertEquals((SettingsPalette.ROW_HEIGHT_DP * density).roundToInt(), button.minimumHeight)
        val card = generateSequence(button.parent as? View) { it.parent as? View }
            .first { it.background is GradientDrawable }
        assertEquals((SettingsPalette.CARD_CORNER_DP * density).roundToInt().toFloat(),
            (card.background as GradientDrawable).cornerRadius, 0.01f)
        assertEquals(SettingsPalette.ROW_HEIGHT_DP / 4, SettingsPalette.CARD_CORNER_DP)
        controller.pause().stop().destroy()
    }

    @Test fun settingsSlidersUseThePaletteAccent() {
        val context = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings"))
        val screen = controller.get().apply { sealEditionCheck = { true } }
        controller.setup()
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSettingsCategory",
            ReflectionHelpers.ClassParameter(SettingsCategory::class.java, SettingsCategory.AUDIO))
        val slider = descendants(screen.window.decorView).filterIsInstance<SeekBar>().first()
        val palette = SettingsPalette.of(screen.currentAppNight)
        assertEquals(palette.accent, slider.progressTintList!!.defaultColor)
        assertEquals(palette.accent, slider.thumbTintList!!.defaultColor)
        assertEquals(palette.switchOff, slider.progressBackgroundTintList!!.defaultColor)
        controller.pause().stop().destroy()
    }

    @Test fun deferredControlsKeepAVisibleConnectionMarker() {
        val context = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings"))
        val screen = controller.get().apply { sealEditionCheck = { true } }
        controller.setup()
        for (id in listOf(R.string.settings_smooth_video_latency, R.string.settings_mtk_decoder_tuning,
            R.string.settings_low_latency_decoder, R.string.call_echo_cancellation, R.string.call_voice_filter,
            R.string.settings_disable_media_key_audio_focus, R.string.settings_navigation_prompt_audio_focus,
            R.string.split_screen_areas, R.string.carplay_rotation, R.string.side_panel,
            R.string.settings_usb_charging, R.string.settings_call_at_call_rate, R.string.bt_suspend_during_carplay,
            R.string.settings_wifi_direct_scan_pause, R.string.mfi_certificate_signing_target,
            R.string.cluster_small_window_marker, R.string.cluster_safe_area_edit, R.string.cluster_safe_area_reset,
            R.string.choose_image, R.string.default_icon, R.string.car_button_name, R.string.carplay_dock)) {
            assertTrue(screen.getString(id), ReflectionHelpers.callInstanceMethod(screen, "showsNextConnectionMarker",
                ReflectionHelpers.ClassParameter(String::class.java, screen.getString(id))))
        }
        assertFalse(ReflectionHelpers.callInstanceMethod(screen, "showsNextConnectionMarker",
            ReflectionHelpers.ClassParameter(String::class.java, screen.getString(R.string.smooth_video))))
        controller.pause().stop().destroy()
    }

    private fun assertColorNear(expected: Int, actual: Int) {
        for ((a, b) in listOf(Color.red(expected) to Color.red(actual), Color.green(expected) to Color.green(actual),
            Color.blue(expected) to Color.blue(actual))) assertTrue("$expected != $actual", kotlin.math.abs(a - b) <= 2)
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is android.view.ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
