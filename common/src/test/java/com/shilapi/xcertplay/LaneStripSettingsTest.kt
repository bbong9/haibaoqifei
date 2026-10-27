package com.shilapi.xcertplay

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.host.R
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en", manifest = Config.NONE)
class LaneStripSettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @After fun reset() {
        AirPlayPersistence.overlaySettingsListener = null
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        PendingReconnect.clear()
    }

    @Test fun commonDefaultIsOffAndSavingNotifiesTheOverlayWithoutReconnect() {
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        assertFalse(AirPlayPersistence.loadClusterLaneStrip(context))
        var changes = 0
        AirPlayPersistence.overlaySettingsListener = { changes++ }
        AirPlayPersistence.saveClusterLaneStrip(context, true)
        assertTrue(AirPlayPersistence.loadClusterLaneStrip(context))
        AirPlayPersistence.saveClusterLaneStrip(context, false)
        assertFalse(AirPlayPersistence.loadClusterLaneStrip(context))
        assertEquals(2, changes)
        assertFalse(PendingReconnect.isPending(null))
    }

    @Test fun mobileResourceOverridesTheLaneDefaultToOn() {
        val root = File(requireNotNull(System.getProperty("user.dir")))
        val project = if (root.name == "common") root.parentFile else root
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(project, "mobile/src/main/res/values/config.xml")).getElementsByTagName("bool")
        val value = (0 until nodes.length).map { nodes.item(it) }
            .single { it.attributes.getNamedItem("name").nodeValue == "config_cluster_lane_strip_default" }
        assertEquals("true", value.textContent.trim())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "en-w320dp-h800dp-xhdpi")
    fun compactLargeFontSettingScreenshot() = captureRow("settings-compact", 320)

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "en-w600dp-h800dp-xhdpi")
    fun fullLargeFontSettingScreenshot() = captureRow("settings-full", 600)

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "ar-ldrtl-w600dp-h800dp-xhdpi")
    fun arabicLargeFontSettingScreenshot() = captureRow("settings-full-ar", 600)

    private fun captureRow(name: String, widthDp: Int) {
        AirPlayPersistence.saveAdbClusterEnabled(context, true)
        AirPlayPersistence.saveClusterContent(context, CarPlayClusterDisplay.Content.MAP_WITH_CUSTOM_CARD)
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.applicationInfo.flags = activity.applicationInfo.flags or android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        val configuration = Configuration(activity.resources.configuration).apply { fontScale = 1.6f }
        @Suppress("DEPRECATION")
        activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
        ReflectionHelpers.setField(activity, "settingsSectionFilter", setOf(SettingsSection.CLUSTER_MAP))
        val content = LinearLayout(activity)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "allSettingsSections",
            ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, content))
        val toggle = switches(content).single { it.contentDescription == activity.getString(R.string.settings_cluster_lane_strip) }
        val row = toggle.parent as ViewGroup
        row.layoutDirection = configuration.layoutDirection
        val width = (widthDp * activity.resources.displayMetrics.density).toInt()
        row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        row.layout(0, 0, width, row.measuredHeight)
        assertTrue(toggle.left >= 0 && toggle.right <= width && toggle.bottom <= row.height)
        fun checkText(view: View) {
            if (view is TextView) view.layout?.let { layout ->
                for (line in 0 until layout.lineCount) {
                    assertEquals("Truncated text: ${view.text}", 0, layout.getEllipsisCount(line))
                }
            }
            if (view is ViewGroup) (0 until view.childCount).forEach { checkText(view.getChildAt(it)) }
        }
        checkText(row)
        val bitmap = Bitmap.createBitmap(width, row.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(28, 32, 42))
        row.draw(canvas)
        val root = File(requireNotNull(System.getProperty("user.dir")))
        val project = if (root.name == "common") root.parentFile else root
        val directory = File(project, "build/lane-strip").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    private fun switches(view: View): List<Switch> = when (view) {
        is Switch -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { switches(view.getChildAt(it)) }
        else -> emptyList()
    }
}
