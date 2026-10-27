package com.shilapi.xcertplay

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StartupSummaryScreenshotsTest {
    @Test fun diagnosticSummaryFitsCompactFullAndRtlAtLargeFonts() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        val summary = StartupTimeline.summarize(listOf(
            "up=5000 pid=42 PROCESS_START boot=9",
            "up=9800 pid=42 BOOT_RECEIVED",
            "up=11900 pid=42 UI_CREATE",
            "up=18120 pid=42 CONNECT_REQUEST",
            "up=19000 pid=42 CP_MFI_START",
            "up=19900 pid=42 CP_MFI_READY",
            "up=20000 pid=42 CP_W_BEGIN gen=1",
            "up=20040 pid=42 CP_W_CLEANUP_DONE gen=1",
            "up=21000 pid=42 CP_W_LINK_READY gen=1",
            "up=22000 pid=42 CP_W_BT_START gen=1",
            "up=34040 pid=42 CP_W_BT_CONNECTED gen=1",
            "up=35540 pid=42 CP_W_WIFI_INFO_SENT gen=1",
            "up=40410 pid=42 CP_W_FIRST_TCP gen=1",
            "up=41230 pid=42 FIRST_FRAME gen=1",
        )).single()
        try {
            ReflectionHelpers.setField(activity, "page", "settings")
            ReflectionHelpers.setField(activity, "searchIndexSink", mutableListOf<String>())
            for ((filename, widthDp, language) in listOf(
                Triple("compact-zh", 320, "zh-CN"),
                Triple("full-en", 960, "en-US"),
                Triple("compact-ar-rtl", 320, "ar-SA"),
            )) {
                val locale = Locale.forLanguageTag(language)
                val config = Configuration(activity.resources.configuration).apply {
                    fontScale = 1.5f
                    setLocale(locale)
                    setLayoutDirection(locale)
                }
                @Suppress("DEPRECATION")
                activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
                val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                DiPlayActivity::class.java.getDeclaredMethod("diagnosticsSettings", LinearLayout::class.java)
                    .apply { isAccessible = true }.invoke(activity, column)
                val text = allViews(column).filterIsInstance<TextView>().single {
                    it.text.toString() == activity.getString(R.string.settings_startup_no_record)
                }
                text.text = StartupTimeline.renderLastSummary(activity, summary)
                assertTrue(text.text.toString().contains("bluetooth"))
                val width = (widthDp * activity.resources.displayMetrics.density).toInt()
                column.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                column.layout(0, 0, width, column.measuredHeight)
                for (line in 0 until text.layout.lineCount) assertEquals(0, text.layout.getEllipsisCount(line))
                assertTrue(text.layout.getLineBottom(text.layout.lineCount - 1) <= text.height)
                val bitmap = Bitmap.createBitmap(width, column.measuredHeight, Bitmap.Config.ARGB_8888)
                try {
                    column.draw(Canvas(bitmap))
                    val directory = File("build/reports/startup-summary-ui").apply { mkdirs() }
                    FileOutputStream(File(directory, "$filename.png")).use {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally {
                    bitmap.recycle()
                }
                println("STARTUP_UI $filename ${text.text}")
            }
        } finally {
            activity.finish()
        }
    }

    private fun allViews(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(allViews(view.getChildAt(index)))
    }
}
