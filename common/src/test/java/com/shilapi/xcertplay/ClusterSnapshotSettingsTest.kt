package com.shilapi.xcertplay

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en", manifest = Config.NONE)
class ClusterSnapshotSettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @After fun clearPreferences() {
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    @Test fun buttonAppearsOnlyForAdbClusterAndRequestsButtonSnapshot() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        var trigger: String? = null
        var complete: (() -> Unit)? = null
        activity.clusterSnapshotExecutor = { _, value, done -> trigger = value; complete = done }
        AirPlayPersistence.saveLegacyClusterEnabled(context, false)
        AirPlayPersistence.saveAdbClusterEnabled(context, false)
        assertFalse(labels(render(activity)).any { it.text == activity.getString(R.string.settings_cluster_snapshot) })
        AirPlayPersistence.saveAdbClusterEnabled(context, true)
        val content = render(activity)
        val button = labels(content).single { it.text == activity.getString(R.string.settings_cluster_snapshot) }
        val labels = labels(content).map { it.text.toString() }
        assertEquals(labels.indexOf(activity.getString(R.string.adb_cluster_open)) + 1,
            labels.indexOf(activity.getString(R.string.settings_cluster_snapshot)))
        button.performClick()
        assertEquals("button", trigger)
        requireNotNull(complete).invoke()
        assertEquals(activity.getString(R.string.settings_cluster_snapshot_done), ShadowToast.getTextOfLatestToast())
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "en-w320dp-h800dp-xhdpi")
    fun compactLargeFontSnapshotSetting() = screenshot("compact-en", 320)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "en-w600dp-h800dp-xhdpi")
    fun fullLargeFontSnapshotSetting() = screenshot("full-en", 600)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "ar-ldrtl-w320dp-h800dp-xhdpi")
    fun arabicLargeFontSnapshotSetting() = screenshot("compact-ar", 320)

    private fun screenshot(filename: String, widthDp: Int) {
        AirPlayPersistence.saveAdbClusterEnabled(context, true)
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        activity.applicationInfo.flags = activity.applicationInfo.flags or android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL
        val configuration = Configuration(activity.resources.configuration).apply { fontScale = 1.5f }
        @Suppress("DEPRECATION")
        activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
        val content = render(activity)
        val width = (widthDp * activity.resources.displayMetrics.density).toInt()
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        content.layout(0, 0, width, content.measuredHeight)
        val button = labels(content).single { it.text == activity.getString(R.string.settings_cluster_snapshot) }
        val layout = requireNotNull(button.layout)
        for (line in 0 until layout.lineCount) {
            assertEquals(0, layout.getEllipsisCount(line))
        }
        assertTrue("Snapshot label is clipped", layout.getLineBottom(layout.lineCount - 1) <=
            button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
        val bitmap = Bitmap.createBitmap(width, content.measuredHeight, Bitmap.Config.ARGB_8888)
        try {
            content.draw(Canvas(bitmap))
            val root = File(requireNotNull(System.getProperty("user.dir")))
            val project = if (root.name == "common") root.parentFile else root
            val directory = File(project, "build/cluster-snapshot").apply { mkdirs() }
            File(directory, "$filename.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    private fun render(activity: DiPlayActivity): LinearLayout {
        ReflectionHelpers.setField(activity, "settingsSectionFilter", setOf(SettingsSection.CLUSTER_MAP))
        return LinearLayout(activity).also { content ->
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "allSettingsSections",
                ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, content))
        }
    }

    private fun labels(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { labels(view.getChildAt(it)) }
        else -> emptyList()
    }
}
