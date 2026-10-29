package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.SmoothVideoLatency
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w600dp-h800dp-xhdpi")
class SmoothVideoLatencySettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var activity: DiPlayActivity? = null

    @After fun tearDown() {
        CarPlayBackgroundSession.clear()
        PendingReconnect.clear()
        activity?.finish()
        RuntimeEnvironment.setFontScale(1f)
        AppAppearanceRuntime.resetForTest()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        context.getSharedPreferences("diplay", 0).edit().clear().commit()
    }

    @Test fun latencyIsVisibleAndSearchableOnlyWhileSmoothVideoIsEnabled() {
        AirPlayPersistence.saveSmoothVideo(context, false)
        val screen = openSettings()
        openCategory(screen, SettingsCategory.DISPLAY)
        assertFalse(isVisible(latencyButton(screen)))
        assertFalse(searchIndex(screen).any { it.title == screen.getString(R.string.settings_smooth_video_latency) })

        smoothSwitch(screen).isChecked = true
        assertTrue(isVisible(latencyButton(screen)))
        assertEquals(SettingsCategory.DISPLAY, searchIndex(screen)
            .single { it.title == screen.getString(R.string.settings_smooth_video_latency) }.category)

        smoothSwitch(screen).isChecked = false
        assertFalse(isVisible(latencyButton(screen)))
        assertFalse(searchIndex(screen).any { it.title == screen.getString(R.string.settings_smooth_video_latency) })
    }

    @Test fun savingLatencyMarksReconnectWithoutStoppingTheRunningSession() {
        AirPlayPersistence.saveSmoothVideo(context, true)
        val screen = openSettings()
        val session = mock(CarPlayController::class.java)
        var stops = 0
        CarPlayBackgroundSession.store(session, mock(AndroidMediaSink::class.java), 800, 480, Any(),
            CarPlaySessionDisplay(800, 480, Surface.ROTATION_0, false, false, 800, 480)) { stops++ }
        CarPlayBackgroundSession.active = true
        openCategory(screen, SettingsCategory.DISPLAY)
        val button = latencyButton(screen)
        assertEquals("${screen.getString(R.string.settings_smooth_video_latency)} · ${screen.getString(R.string.settings_smooth_video_latency_balanced)}", button.text.toString())
        button.performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(screen.getString(R.string.save), dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        shadowOf(dialog).clickOnItem(1)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(SmoothVideoLatency.LOW, AirPlayPersistence.loadSmoothVideoLatency(context))
        assertTrue(PendingReconnect.isPending(session))
        assertEquals(View.VISIBLE, ReflectionHelpers.getField<View>(screen, "reconnectBar").visibility)
        assertEquals(0, stops)
        assertSame(session, CarPlayBackgroundSession.snapshot()?.controller)
        assertTrue(button.text.toString().endsWith(screen.getString(R.string.settings_smooth_video_latency_low)))
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "en-w320dp-h800dp-xhdpi")
    fun compactLargeFontScreenshot() = screenshot("compact-en")

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "en-w600dp-h800dp-xhdpi")
    fun fullLargeFontScreenshot() = screenshot("full-en")

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "ar-ldrtl-w320dp-h800dp-xhdpi")
    fun arabicLargeFontScreenshot() = screenshot("compact-ar")

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = [25], qualifiers = "en-w320dp-h800dp-xhdpi")
    fun preAndroidOCompactLargeFontFits() = screenshot("compact-en-api25", save = false)

    private fun screenshot(name: String, save: Boolean = true) {
        RuntimeEnvironment.setFontScale(1.5f)
        AirPlayPersistence.saveSmoothVideo(context, true)
        AirPlayPersistence.saveSmoothVideoLatency(context, SmoothVideoLatency.LOW)
        val screen = openSettings()
        screen.applicationInfo.flags = screen.applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
        openCategory(screen, SettingsCategory.DISPLAY)
        val root = screen.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val metrics = screen.resources.displayMetrics
        root.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
        val button = latencyButton(screen)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Latency choice must be visible", isVisible(button))
        val layout = requireNotNull(button.layout)
        for (line in 0 until layout.lineCount) assertEquals("Clipped latency choice", 0, layout.getEllipsisCount(line))
        assertTrue("Latency choice text extends outside its row: ${button.text}", layout.getLineBottom(layout.lineCount - 1) <=
            button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
        if (!save) return
        val smoothRow = smoothSwitch(screen).parent as View
        val directRow = descendants(screen.window.decorView).filterIsInstance<Switch>()
            .single { it.contentDescription == screen.getString(R.string.settings_direct_video_output) }.parent as View
        val card = smoothRow.parent as ViewGroup
        assertSame(card, (button.parent as View).parent)
        assertSame(card, directRow.parent)
        val bitmap = Bitmap.createBitmap(card.width, directRow.bottom - smoothRow.top, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.translate(0f, -smoothRow.top.toFloat())
            card.draw(canvas)
            val cwd = File(requireNotNull(System.getProperty("user.dir")))
            val project = if (cwd.name == "common") cwd.parentFile else cwd
            val directory = File(project, "build/smooth-video-latency").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun openSettings(): DiPlayActivity {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings"))
        val screen = controller.get()
        screen.sealEditionCheck = { true }
        controller.setup()
        activity = screen
        return screen
    }

    private fun openCategory(screen: DiPlayActivity, category: SettingsCategory) =
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSettingsCategory",
            ReflectionHelpers.ClassParameter(SettingsCategory::class.java, category))

    private fun searchIndex(screen: DiPlayActivity): List<DiPlayActivity.SettingsSearchResult> =
        ReflectionHelpers.callInstanceMethod(screen, "buildSettingsSearchIndex")

    private fun latencyButton(screen: DiPlayActivity): TextView = descendants(screen.window.decorView)
        .filterIsInstance<TextView>().single { it.text.toString().startsWith(screen.getString(R.string.settings_smooth_video_latency) + " · ") }

    private fun smoothSwitch(screen: DiPlayActivity): Switch = descendants(screen.window.decorView)
        .filterIsInstance<Switch>().single { it.contentDescription == screen.getString(R.string.smooth_video) }

    private fun isVisible(view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current.visibility != View.VISIBLE) return false
            current = current.parent as? View
        }
        return true
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
