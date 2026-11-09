package com.shilapi.xcertplay

import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import android.widget.ScrollView
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers

/** Reproducible review captures; the Bluetooth device is simulated, not a real iPhone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PairingReviewScreenshotsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var activity: DiPlayActivity? = null
    private var pairing: IphonePairing? = null

    @After fun tearDown() {
        pairing?.close()
        activity?.finish()
        RuntimeEnvironment.setFontScale(1f)
        AppAppearanceRuntime.resetForTest()
        context.getSharedPreferences("diplay", 0).edit().clear().commit()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    @Test @Config(qualifiers = "zh-rCN-w480dp-h800dp-mdpi")
    fun chineseCompact() = capture("zh-compact")

    @Test @Config(qualifiers = "zh-rCN-w1280dp-h800dp-mdpi")
    fun chineseFull() = capture("zh-full")

    @Test @Config(qualifiers = "en-w480dp-h800dp-mdpi")
    fun englishCompact() = capture("en-compact")

    @Test @Config(qualifiers = "en-w1280dp-h800dp-mdpi")
    fun englishFull() = capture("en-full")

    private fun capture(name: String) {
        context.getSharedPreferences("diplay", 0).edit().clear().commit()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        assertTrue(SealQuickStart.prepare(context, sealEdition = true, freshInstall = true))
        // The common module's test resources use the standard edition, so seed the Seal default.
        DiPlayPreferences.saveAutoConnect(context, SealPerformanceProfile.defaultAutoConnect(true))
        RuntimeEnvironment.setFontScale(1.3f)

        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings"))
        val screen = controller.get().apply { sealEditionCheck = { true } }
        activity = screen
        controller.setup()
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSettingsCategory",
            ReflectionHelpers.ClassParameter(SettingsCategory::class.java, SettingsCategory.CONNECTION))
        val root = screen.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val metrics = screen.resources.displayMetrics
        root.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
        idle()

        val scroll = ReflectionHelpers.getField<ScrollView>(screen, "rootScroll")
        val maxScroll = (scroll.getChildAt(0).height - scroll.height).coerceAtLeast(0)
        val step = (scroll.height * 4 / 5).coerceAtLeast(1)
        var position = 0
        var page = 0
        while (true) {
            scroll.scrollTo(0, position)
            idle()
            writeImage(root, "$name-connection-${page.toString().padStart(2, '0')}.png", scroll)
            if (position == maxScroll) break
            position = (position + step).coerceAtMost(maxScroll)
            page++
        }
        scroll.scrollTo(0, 0)

        val adapter = BluetoothAdapter.getDefaultAdapter()
        shadowOf(adapter).setEnabled(true)
        shadowOf(adapter).setBondedDevices(emptySet())
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "choosePhone")
        captureDialog(ShadowAlertDialog.getLatestAlertDialog(), "$name-first-pairing.png")
        ShadowAlertDialog.getLatestAlertDialog().dismiss()

        ReflectionHelpers.callInstanceMethod<Unit>(screen, "pairingScanPermissionHelp")
        captureDialog(ShadowAlertDialog.getLatestAlertDialog(), "$name-permission-denied.png")
        ShadowAlertDialog.getLatestAlertDialog().dismiss()

        pairing = IphonePairing(screen, adapter, {}, {}, {})
        pairing!!.show()
        val device = adapter.getRemoteDevice("00:11:22:33:44:55")
        shadowOf(device).setName("Test iPhone")
        shadowOf(device).setBondState(BluetoothDevice.BOND_NONE)
        screen.sendBroadcast(Intent(BluetoothDevice.ACTION_FOUND).putExtra(BluetoothDevice.EXTRA_DEVICE, device))
        idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val list = descendants(dialog.window!!.decorView).filterIsInstance<ListView>().single()
        assertEquals(1, list.adapter.count)
        captureDialog(dialog, "$name-scan.png")
    }

    private fun captureDialog(dialog: AlertDialog, name: String) {
        val decor = dialog.window!!.decorView
        val metrics = context.resources.displayMetrics
        decor.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.AT_MOST))
        decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
        idle()
        writeImage(decor, name)
    }

    private fun writeImage(view: View, name: String, scroll: ScrollView? = null) {
        assertTrue("$name must have nonzero dimensions", view.width > 0 && view.height > 0)
        val base = File(requireNotNull(System.getProperty("user.dir")))
        val project = if (base.name == "common") base.parentFile else base
        val output = File(project, "build/pairing-review/$name")
        requireNotNull(output.parentFile).mkdirs()
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val background = view.background
            if (view === scroll) {
                background?.setBounds(0, 0, view.width, view.height)
                background?.draw(canvas)
                view.background = null
                canvas.translate(0f, -scroll.scrollY.toFloat())
            }
            try { view.draw(canvas) } finally { if (view === scroll) view.background = background }
            output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            assertTrue(output.length() > 0)
        } finally { bitmap.recycle() }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
