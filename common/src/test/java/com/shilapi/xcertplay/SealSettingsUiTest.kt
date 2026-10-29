package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.setup.DiLinkGeneration
import java.io.File
import java.util.concurrent.CompletableFuture
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
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
@Config(sdk = [29], qualifiers = "en-w1280dp-h800dp")
class SealSettingsUiTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var activity: DiPlayActivity? = null

    @Before fun setUp() {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = "com.byd.carsettings"
            applicationInfo = ApplicationInfo().apply {
                packageName = "com.byd.carsettings"
                flags = ApplicationInfo.FLAG_SYSTEM
            }
        })
        DiLinkGeneration.save(context, DiLinkGeneration.DILINK_4)
        AirPlayPersistence.saveMfiTarget(context, MfiTarget.LOCAL)
        PendingReconnect.clear()
    }

    @After fun tearDown() {
        CarPlayBackgroundSession.clear()
        PendingReconnect.clear()
        activity?.finish()
        RuntimeEnvironment.setFontScale(1f)
        AppAppearanceRuntime.resetForTest()
        context.getSharedPreferences("diplay", 0).edit().clear().commit()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    @Test fun advancedIsAbsentFromSealRailOverviewAndDirectEntries() {
        val screen = openSettings()
        val advancedLink = screen.getString(R.string.settings_open_category, screen.getString(R.string.settings_advanced))
        val rail = ReflectionHelpers.getField<ScrollView>(screen, "settingsRailScroll")
        assertFalse(descendants(rail).any { it.contentDescription == advancedLink })
        assertFalse(descendants(categoryContent(screen)).any { it.contentDescription == advancedLink })
        assertTrue(search(screen, R.string.settings_advanced).isEmpty())

        openCategory(screen, SettingsCategory.ADVANCED)
        assertEquals(SettingsCategory.OVERVIEW, currentCategory(screen))
        ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.ADVANCED)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        assertEquals(SettingsCategory.OVERVIEW, currentCategory(screen))

        ReflectionHelpers.setField(screen, "page", "backup")
        ReflectionHelpers.setField(screen, "backupOpenedFrom", SettingsCategory.ADVANCED)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "navigateBack")
        assertEquals(SettingsCategory.OVERVIEW, currentCategory(screen))
        ReflectionHelpers.setField(screen, "page", "connection")
        ReflectionHelpers.setField(screen, "connectionSettingsReturnCategory", SettingsCategory.ADVANCED)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "navigateBack")
        assertEquals(SettingsCategory.OVERVIEW, currentCategory(screen))
    }

    @Test fun restoredAdvancedStateReturnsToOverview() {
        val state = Bundle().apply {
            putString("page", "settings")
            putString("settings_category", SettingsCategory.ADVANCED.name)
        }
        val screen = openSettings(savedState = state)
        assertEquals(SettingsCategory.OVERVIEW, currentCategory(screen))
    }

    @Test fun sealShowsVideoOutputOnDisplayAndIndexesOnlyVisibleSettings() {
        val screen = openSettings()
        openCategory(screen, SettingsCategory.DISPLAY)
        listOf(R.string.smooth_video, R.string.settings_direct_video_output, R.string.efficient_video,
            R.string.settings_low_latency_decoder).forEach { assertHas(screen, it) }
        assertMissing(screen, R.string.adapt_pip_resolution)
        assertEquals(SettingsCategory.DISPLAY, search(screen, R.string.smooth_video).single {
            it.title == screen.getString(R.string.smooth_video)
        }.category)
        assertTrue(search(screen, R.string.split_screen_areas).isEmpty())
        assertTrue(search(screen, R.string.settings_usb_charging).isEmpty())
    }

    @Test fun sealVideoControlsKeepTheMtkDecoderGateAndOrder() {
        val screen = openSettings()
        screen.selectedMtkDecoderName = { "OMX.MTK.VIDEO.DECODER.AVC" }
        openCategory(screen, SettingsCategory.DISPLAY)
        val titles = descendants(categoryContent(screen)).filterIsInstance<TextView>().map { it.text.toString() }.toList()
        val frame = titles.indexOfFirst { it.startsWith(screen.getString(R.string.frame_rate) + " · ") }
        val hevc = titles.indexOf(screen.getString(R.string.efficient_video))
        val mtk = titles.indexOf(screen.getString(R.string.settings_mtk_decoder_tuning))
        val smooth = titles.indexOf(screen.getString(R.string.smooth_video))
        val direct = titles.indexOf(screen.getString(R.string.settings_direct_video_output))
        val lowLatency = titles.indexOf(screen.getString(R.string.settings_low_latency_decoder))
        assertTrue(frame >= 0 && frame < hevc && hevc < mtk && mtk < smooth && smooth < direct && direct < lowLatency)

        AirPlayPersistence.saveHevcEnabled(context, true)
        AirPlayPersistence.saveHevcSoftwareDecoderEnabled(context, true)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        assertMissing(screen, R.string.settings_mtk_decoder_tuning)
    }

    @Test fun sealHidesWiredControlsAndPreservesTheirValues() {
        AirPlayPersistence.saveRightHandDrive(context, true)
        AirPlayPersistence.saveAdaptPipResolution(context, true)
        DiPlayPreferences.saveDefaultConnectionMode(context, DefaultConnectionMode.USB)
        AirPlayPersistence.saveMediaBufferMillis(context, wireless = false, millis = 500)
        val screen = openSettings()

        openCategory(screen, SettingsCategory.CONNECTION)
        listOf(R.string.settings_usb_charging, R.string.default_connection_mode,
            R.string.usb_auto_confirm_title, R.string.mfi_certificate_signing_target).forEach { assertMissing(screen, it) }
        openCategory(screen, SettingsCategory.AUDIO)
        assertHas(screen, R.string.settings_music_buffer_wireless)
        assertMissing(screen, R.string.settings_music_buffer_wired)
        openCategory(screen, SettingsCategory.VEHICLE)
        assertMissing(screen, R.string.right_hand_drive)
        assertTrue(AirPlayPersistence.loadRightHandDrive(context))
        assertTrue(AirPlayPersistence.loadAdaptPipResolution(context))
        assertEquals(DefaultConnectionMode.USB, DiPlayPreferences.defaultConnectionMode(context))
        assertEquals(500, AirPlayPersistence.loadMediaBufferMillis(context, wireless = false))
        assertEquals(MfiTarget.LOCAL, AirPlayPersistence.loadMfiTarget(context))
    }

    @Test fun sealMovesAmbientAndPopupToVehicleWithoutReconnect() {
        val screen = openSettings()
        openCategory(screen, SettingsCategory.VEHICLE)
        assertHas(screen, R.string.settings_ambient_title)
        val popupTitle = screen.getString(R.string.settings_hide_byd_call_popup)
        val popup = descendants(categoryContent(screen)).filterIsInstance<Switch>()
            .single { it.contentDescription == popupTitle }
        val prefs = screen.getSharedPreferences("xcertplay_airplay", 0)
        val before = prefs.all.toMap()
        val session = mock(CarPlayController::class.java)
        val sink = mock(AndroidMediaSink::class.java)
        var disconnects = 0
        CarPlayBackgroundSession.store(session, sink, 800, 480, Any(),
            CarPlaySessionDisplay(800, 480, Surface.ROTATION_0, false, false, 800, 480)) { disconnects++ }
        CarPlayBackgroundSession.active = true
        popup.isChecked = true
        assertEquals(before + ("hide_byd_call_popup" to true), prefs.all)
        assertFalse(PendingReconnect.isPending(session))
        assertEquals(0, disconnects)
        popup.isChecked = false
        assertEquals(before + ("hide_byd_call_popup" to false), prefs.all)
        assertFalse(PendingReconnect.isPending(session))
        assertEquals(0, disconnects)
        CarPlayBackgroundSession.clear()
        assertSearchCategory(screen, R.string.settings_ambient_title, SettingsCategory.VEHICLE)
        assertSearchCategory(screen, R.string.settings_hide_byd_call_popup, SettingsCategory.VEHICLE)
    }

    @Test fun sealMovesClusterCardAndItsSearchEntriesToNavigation() {
        val screen = openSettings()
        openCategory(screen, SettingsCategory.NAVIGATION)
        assertHas(screen, R.string.carplay_map_on_instrument_cluster_experimental)
        assertHas(screen, R.string.adb_cluster_activity_mode)
        assertSearchCategory(screen, R.string.carplay_map_on_instrument_cluster_experimental, SettingsCategory.NAVIGATION)
        assertSearchCategory(screen, R.string.adb_cluster_activity_mode, SettingsCategory.NAVIGATION)
    }

    @Test fun laneStripAppearsOnlyForTheCustomCardInSealNavigationAndAppliesImmediately() {
        AirPlayPersistence.saveAdbClusterEnabled(context, true)
        AirPlayPersistence.saveClusterContent(context, CarPlayClusterDisplay.Content.MAP_WITH_CUSTOM_CARD)
        val screen = openSettings()
        openCategory(screen, SettingsCategory.NAVIGATION)
        assertHas(screen, R.string.settings_cluster_lane_strip)
        assertSearchCategory(screen, R.string.settings_cluster_lane_strip, SettingsCategory.NAVIGATION)
        var refreshed = 0
        AirPlayPersistence.overlaySettingsListener = { refreshed++ }
        val toggle = descendants(categoryContent(screen)).filterIsInstance<Switch>()
            .single { it.contentDescription == screen.getString(R.string.settings_cluster_lane_strip) }
        toggle.isChecked = true
        assertTrue(AirPlayPersistence.loadClusterLaneStrip(context))
        assertEquals(1, refreshed)
        assertFalse(PendingReconnect.isPending(null))

        for (content in CarPlayClusterDisplay.Content.entries.filter { it != CarPlayClusterDisplay.Content.MAP_WITH_CUSTOM_CARD }) {
            AirPlayPersistence.saveClusterContent(context, content)
            openCategory(screen, SettingsCategory.NAVIGATION)
            assertMissing(screen, R.string.settings_cluster_lane_strip)
        }
        AirPlayPersistence.overlaySettingsListener = null
    }

    @Test fun musicDurationChoiceIsVisibleOnlyWhenEnabledAndSavesWithoutReconnect() {
        AirPlayPersistence.saveClusterMapEnabled(context, true)
        AirPlayPersistence.saveAdbClusterEnabled(context, true)
        val screen = openSettings()
        openCategory(screen, SettingsCategory.NAVIGATION)
        assertMissing(screen, R.string.settings_cluster_music_seconds)
        fun musicSwitch() = descendants(categoryContent(screen)).filterIsInstance<Switch>()
            .single { it.contentDescription == screen.getString(R.string.settings_cluster_music) }
        musicSwitch().isChecked = true
        assertHas(screen, R.string.settings_cluster_music_seconds)
        val title = screen.getString(R.string.settings_cluster_music_seconds)
        descendants(categoryContent(screen)).filterIsInstance<TextView>()
            .first { it.text.toString().startsWith("$title · ") }.performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(3, dialog.listView.adapter.count)
        assertEquals(listOf(3, 5, 8).map { screen.getString(R.string.settings_cluster_music_seconds_value, it) },
            (0 until dialog.listView.adapter.count).map { dialog.listView.adapter.getItem(it).toString() })
        dialog.listView.performItemClick(null, 0, 0L)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(3, ClusterMusicSettings.temporarySeconds(context))
        assertFalse(PendingReconnect.isPending(null))
        musicSwitch().isChecked = false
        assertMissing(screen, R.string.settings_cluster_music_seconds)
    }

    @Test fun otherEditionsKeepAdvancedAndItsOriginalControls() {
        val screen = openSettings(seal = false)
        val advancedLink = screen.getString(R.string.settings_open_category, screen.getString(R.string.settings_advanced))
        assertTrue(descendants(ReflectionHelpers.getField<ScrollView>(screen, "settingsRailScroll"))
            .any { it.contentDescription == advancedLink })
        openCategory(screen, SettingsCategory.ADVANCED)
        listOf(R.string.smooth_video, R.string.settings_direct_video_output, R.string.efficient_video,
            R.string.settings_ambient_title, R.string.settings_hide_byd_call_popup,
            R.string.carplay_map_on_instrument_cluster_experimental, R.string.adb_cluster_activity_mode).forEach { assertHas(screen, it) }
        openCategory(screen, SettingsCategory.DISPLAY)
        listOf(R.string.smooth_video, R.string.settings_direct_video_output, R.string.efficient_video).forEach { assertMissing(screen, it) }
        openCategory(screen, SettingsCategory.VEHICLE)
        listOf(R.string.settings_ambient_title, R.string.settings_hide_byd_call_popup).forEach { assertMissing(screen, it) }
        openCategory(screen, SettingsCategory.NAVIGATION)
        listOf(R.string.carplay_map_on_instrument_cluster_experimental, R.string.adb_cluster_activity_mode).forEach { assertMissing(screen, it) }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = [29], qualifiers = "zh-rCN-w480dp-h800dp")
    fun compactLargeFontCategoryScreenshots() = captureCategories("compact")

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = [29], qualifiers = "zh-rCN-w1280dp-h800dp")
    fun expandedLargeFontCategoryScreenshots() = captureCategories("expanded")

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = [29], qualifiers = "zh-rCN-w480dp-h800dp")
    fun clusterCompactLargeFontScreenshot() = captureClusterSettings("compact-zh")

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = [29], qualifiers = "zh-rCN-w1280dp-h800dp")
    fun clusterExpandedLargeFontScreenshot() = captureClusterSettings("expanded-zh")

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = [29], qualifiers = "ar-w480dp-h800dp")
    fun clusterArabicLargeFontScreenshot() = captureClusterSettings("compact-ar")

    private fun captureClusterSettings(name: String) {
        RuntimeEnvironment.setFontScale(1.3f)
        AirPlayPersistence.saveClusterMapEnabled(context, true)
        AirPlayPersistence.saveAdbClusterEnabled(context, true)
        AirPlayPersistence.saveLegacyClusterEnabled(context, false)
        AirPlayPersistence.saveClusterContent(context, CarPlayClusterDisplay.Content.MAP_WITH_CUSTOM_CARD)
        ClusterMusicSettings.saveEnabled(context, true)
        val screen = openSettings()
        openCategory(screen, SettingsCategory.NAVIGATION)
        listOf(R.string.settings_cluster_speedometer, R.string.settings_cluster_music,
            R.string.settings_cluster_music_mode, R.string.settings_cluster_music_seconds,
            R.string.dashboard_shows, R.string.turn_card_overlay_size,
            R.string.settings_cluster_lane_strip).forEach { assertHas(screen, it) }
        layoutRoot(screen)
        val scroll = categoryContent(screen)
        val title = screen.getString(R.string.settings_cluster_music_seconds)
        val target = descendants(scroll).filterIsInstance<TextView>()
            .first { it.text.toString().startsWith("$title · ") }
        val dashboardTitle = screen.getString(R.string.dashboard_shows)
        val dashboard = descendants(scroll).filterIsInstance<TextView>()
            .first { it.text.toString().startsWith("$dashboardTitle · ") }
        var card: View = target
        while (descendants(card).none { it === dashboard }) {
            card = card.parent as View
        }
        assertTrue(card.width > 0 && card.height > 0)
        val base = File(requireNotNull(System.getProperty("user.dir")))
        val project = if (base.name == "common") base.parentFile else base
        val directory = File(project, "build/cluster-settings-card16").apply { mkdirs() }
        val cardBitmap = Bitmap.createBitmap(card.width, card.height, Bitmap.Config.ARGB_8888)
        try {
            card.draw(Canvas(cardBitmap))
            File(directory, "$name-card.png").outputStream().use {
                assertTrue(cardBitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { cardBitmap.recycle() }
        val rect = Rect()
        target.getDrawingRect(rect)
        scroll.offsetDescendantRectToMyCoords(target, rect)
        scroll.scrollTo(0, (rect.top - (24 * screen.resources.displayMetrics.density).toInt()).coerceAtLeast(0))
        assertTrue(rect.top - scroll.scrollY in 0 until scroll.height)
        val root = screen.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            if (root === scroll) {
                canvas.drawColor(ReflectionHelpers.getField<DiPlayPalette>(screen, "palette").background)
                canvas.translate(0f, -scroll.scrollY.toFloat())
            }
            root.draw(canvas)
            File(directory, "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    private fun captureCategories(layout: String) {
        RuntimeEnvironment.setFontScale(1.3f)
        AirPlayPersistence.saveSmoothVideo(context, true)
        AirPlayPersistence.saveDirectVideoOutput(context, true)
        val screen = openSettings()
        val rootDir = File(requireNotNull(System.getProperty("user.dir")))
        val project = if (rootDir.name == "common") rootDir.parentFile else rootDir
        val directory = File(project, "build/seal-settings-screenshots").apply { mkdirs() }
        val auditDirectory = File(project, "build/seal-settings-card19").apply { mkdirs() }
        for (category in SettingsInformationArchitecture.visibleCategories(true, SettingsCategory.OVERVIEW)) {
            openCategory(screen, category)
            layoutRoot(screen)
            val root = screen.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            assertTrue(root.width > 0 && root.height > 0)
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            try {
                root.draw(Canvas(bitmap))
                File(directory, "$layout-${category.name.lowercase()}.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally {
                bitmap.recycle()
            }
            captureCategoryPages(screen, root, auditDirectory, "$layout-${category.name.lowercase()}")
            val focusTitle = when (category) {
                SettingsCategory.DISPLAY -> R.string.efficient_video
                SettingsCategory.VEHICLE -> R.string.settings_ambient_title
                else -> continue
            }
            val scroll = categoryContent(screen)
            val target = descendants(scroll).filterIsInstance<TextView>()
                .first { it.text.toString() == screen.getString(focusTitle) }
            val targetRect = Rect()
            target.getDrawingRect(targetRect)
            scroll.offsetDescendantRectToMyCoords(target, targetRect)
            scroll.scrollTo(0, (targetRect.top - (24 * screen.resources.displayMetrics.density).toInt()).coerceAtLeast(0))
            assertTrue("$layout $category: target=${targetRect.top}, scroll=${scroll.scrollY}, child=${scroll.getChildAt(0).height}, viewport=${scroll.height}",
                scroll.scrollY > 0)
            assertTrue(targetRect.top - scroll.scrollY in 0 until scroll.height)
            val focused = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(focused)
                if (root === scroll) {
                    canvas.drawColor(ReflectionHelpers.getField<DiPlayPalette>(screen, "palette").background)
                    canvas.translate(0f, -scroll.scrollY.toFloat())
                }
                root.draw(canvas)
                File(directory, "$layout-${category.name.lowercase()}-relocated.png").outputStream().use {
                    assertTrue(focused.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally {
                focused.recycle()
            }
        }
    }

    private fun captureCategoryPages(screen: DiPlayActivity, root: View, directory: File, name: String) {
        val scroll = categoryContent(screen)
        val visibleTexts = descendants(scroll).filterIsInstance<TextView>()
            .filter { it.isShown }.toList()
        val allText = visibleTexts.map { it.text.toString() }.filter { it.isNotBlank() }
        assertTrue("$name has no visible text", allText.isNotEmpty())
        val forbidden = Regex("唐|汉|宋|秦|平台\\s*21|DiLink\\s*3", RegexOption.IGNORE_CASE)
        assertFalse("$name contains another model: ${allText.filter { forbidden.containsMatchIn(it) }}",
            allText.any { forbidden.containsMatchIn(it) })
        File(directory, "$name-all.txt").writeText(allText.joinToString("\n", postfix = "\n"))

        val maximum = (scroll.getChildAt(0).height - scroll.height).coerceAtLeast(0)
        val step = (scroll.height * 4 / 5).coerceAtLeast(1)
        var position = 0
        var page = 0
        while (true) {
            scroll.scrollTo(0, position)
            shadowOf(Looper.getMainLooper()).idle()
            val base = "$name-${page.toString().padStart(3, '0')}"
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                if (root === scroll) {
                    canvas.drawColor(ReflectionHelpers.getField<DiPlayPalette>(screen, "palette").background)
                    canvas.translate(0f, -scroll.scrollY.toFloat())
                }
                root.draw(canvas)
                File(directory, "$base.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally {
                bitmap.recycle()
            }
            val viewport = Rect(0, scroll.scrollY, scroll.width, scroll.scrollY + scroll.height)
            val pageText = visibleTexts.filter { view ->
                val bounds = Rect()
                view.getDrawingRect(bounds)
                scroll.offsetDescendantRectToMyCoords(view, bounds)
                Rect.intersects(bounds, viewport)
            }.map { it.text.toString() }.filter { it.isNotBlank() }
            File(directory, "$base.txt").writeText(pageText.joinToString("\n", postfix = "\n"))
            if (position == maximum) break
            position = (position + step).coerceAtMost(maximum)
            page++
        }
        scroll.scrollTo(0, 0)
    }

    private fun openSettings(seal: Boolean = true, savedState: Bundle? = null): DiPlayActivity {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings"))
        val screen = controller.get()
        screen.sealEditionCheck = { seal }
        screen.ambientSupportCheck = { CompletableFuture.completedFuture(true) }
        screen.carRhythmSupportCheck = { CompletableFuture.completedFuture(true) }
        if (savedState == null) controller.setup() else controller.create(savedState).start().resume().visible()
        activity = screen
        return screen
    }

    private fun openCategory(screen: DiPlayActivity, category: SettingsCategory) {
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSettingsCategory",
            ReflectionHelpers.ClassParameter(SettingsCategory::class.java, category))
    }

    private fun currentCategory(screen: DiPlayActivity) =
        ReflectionHelpers.getField<SettingsCategory>(screen, "settingsCategory")

    private fun categoryContent(screen: DiPlayActivity) = ReflectionHelpers.getField<ScrollView>(screen, "rootScroll")

    private fun assertHas(screen: DiPlayActivity, id: Int) {
        val title = screen.getString(id)
        assertTrue("Expected $title", descendants(categoryContent(screen)).filterIsInstance<TextView>()
            .any { it.text.toString() == title || it.text.toString().startsWith("$title · ") })
    }

    private fun assertMissing(screen: DiPlayActivity, id: Int) {
        val title = screen.getString(id)
        assertFalse("Unexpected $title", descendants(categoryContent(screen)).filterIsInstance<TextView>()
            .any { it.text.toString() == title || it.text.toString().startsWith("$title · ") })
    }

    private fun search(screen: DiPlayActivity, title: Int): List<DiPlayActivity.SettingsSearchResult> {
        val index = ReflectionHelpers.callInstanceMethod<List<DiPlayActivity.SettingsSearchResult>>(screen, "buildSettingsSearchIndex")
        return ReflectionHelpers.callInstanceMethod(screen, "searchSettings",
            ReflectionHelpers.ClassParameter(List::class.java, index),
            ReflectionHelpers.ClassParameter(String::class.java, screen.getString(title)))
    }

    private fun assertSearchCategory(screen: DiPlayActivity, title: Int, category: SettingsCategory) {
        assertEquals(category, search(screen, title).single { it.title == screen.getString(title) }.category)
    }

    private fun layoutRoot(screen: DiPlayActivity) {
        val root = screen.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val metrics = screen.resources.displayMetrics
        root.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
