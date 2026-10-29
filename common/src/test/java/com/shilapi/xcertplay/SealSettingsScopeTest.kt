package com.shilapi.xcertplay

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.BydOutputSettings
import com.shilapi.xcertplay.setup.DiLinkGeneration
import com.shilapi.xcertplay.setup.SetupGuide
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w1280dp-h800dp")
class SealSettingsScopeTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var screen: DiPlayActivity

    @Before fun setUp() {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = "com.byd.carsettings"
            applicationInfo = ApplicationInfo().apply {
                packageName = "com.byd.carsettings"
                flags = ApplicationInfo.FLAG_SYSTEM
            }
        })
        DiLinkGeneration.save(context, DiLinkGeneration.DILINK_4)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings"))
        screen = controller.get()
        screen.sealEditionCheck = { true }
        controller.setup()
    }

    @After fun tearDown() {
        screen.finish()
        CarPlayBackgroundSession.clear()
        PendingReconnect.clear()
        AppAppearanceRuntime.resetForTest()
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, null)
        Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        context.getSharedPreferences("diplay", 0).edit().clear().commit()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        context.getSharedPreferences("diplay_cluster_layout", 0).edit().clear().commit()
    }

    @Test fun sealHidesOtherVehicleControlsAndSearchButPreservesEnabledValues() {
        AirPlayPersistence.saveLegacyClusterEnabled(context, true)
        AirPlayPersistence.saveCallSpeakerphone(context, true)
        BydOutputSettings.setCarPlayCallControls(context, true)
        BydOutputSettings.setCarPlayCalls(context, true)
        category(SettingsCategory.NAVIGATION)
        listOf(R.string.platform21_cluster_mode, R.string.carplay_call_controls_experimental,
            R.string.carplay_calls_on_dashboard).forEach { assertMissing(it) }
        category(SettingsCategory.AUDIO)
        assertMissing(R.string.settings_call_speakerphone)
        listOf(R.string.platform21_cluster_mode, R.string.carplay_call_controls_experimental,
            R.string.carplay_calls_on_dashboard, R.string.settings_call_speakerphone).forEach { assertNotIndexed(it) }
        assertTrue(AirPlayPersistence.loadLegacyClusterEnabled(context))
        assertTrue(AirPlayPersistence.loadCallSpeakerphone(context))
        assertTrue(BydOutputSettings.carPlayCallControls(context))
        assertTrue(BydOutputSettings.carPlayCalls(context))

        screen.sealEditionCheck = { false }
        category(SettingsCategory.ADVANCED)
        assertHas(R.string.platform21_cluster_mode)
        category(SettingsCategory.NAVIGATION)
        assertHas(R.string.carplay_call_controls_experimental)
        assertHas(R.string.carplay_calls_on_dashboard)
        category(SettingsCategory.AUDIO)
        assertHas(R.string.settings_call_speakerphone)
    }

    @Test fun usbEntrancesAndActiveHintAreHiddenOnlyOnSeal() {
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "${context.packageName}/com.shilapi.xcertplay.UsbAutoConfirmService")
        Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        AirPlayPersistence.saveWirelessEnabled(context, false)
        ReflectionHelpers.setField(screen, "setupError", null as String?)
        page("home")
        assertMissing(R.string.connect_with_usb)
        page("connection")
        assertMissing(R.string.prefer_a_cable)
        assertMissing(R.string.connect_with_usb)
        category(SettingsCategory.CONNECTION)
        assertMissing(R.string.usb_auto_confirm_active_hint)
        category(SettingsCategory.OVERVIEW)
        assertMissing(R.string.settings_ready_usb)
        assertMissing(R.string.settings_connection_summary)
        assertHas(R.string.settings_seal_ready_saved_connection)
        val autoDescription = descendants(screen.window.decorView).filterIsInstance<TextView>().single {
            it.text.toString() == screen.getString(R.string.settings_seal_auto_connection_description)
        }
        assertFalse(autoDescription.isShown)
        descendants(screen.window.decorView).single {
            it.contentDescription == screen.getString(R.string.settings_description_for,
                screen.getString(R.string.connect_when_diplay_opens))
        }.performClick()
        assertHas(R.string.settings_seal_auto_connection_description)
        assertFalse(AirPlayPersistence.loadWirelessEnabled(context))
        assertTrue(UsbPermissionSetup.Permission.ACCESSIBILITY.granted(context))

        screen.sealEditionCheck = { false }
        page("home")
        assertHas(R.string.connect_with_usb)
        page("connection")
        assertHas(R.string.prefer_a_cable)
        assertHas(R.string.connect_with_usb)
        category(SettingsCategory.CONNECTION)
        assertHas(R.string.usb_auto_confirm_active_hint)
        category(SettingsCategory.OVERVIEW)
        assertHas(R.string.settings_ready_usb)
        assertHas(R.string.settings_connection_summary)
    }

    @Test fun sealOmitsWiredOnlyExplanationsWithoutChangingOtherEditions() {
        category(SettingsCategory.CONNECTION)
        descendants(screen.window.decorView).single {
            it.contentDescription == screen.getString(R.string.settings_description_for, screen.getString(R.string.app_permissions))
        }.performClick()
        assertTextAbsent("USB mode may ask")
        assertTrue(texts().any { it.contains("Older Android versions also require Location") })
        category(SettingsCategory.ABOUT)
        assertTextAbsent("Wired and wireless connections")
        assertTrue(texts().any { it.contains("not an Apple-certified product") })

        screen.sealEditionCheck = { false }
        category(SettingsCategory.CONNECTION)
        descendants(screen.window.decorView).single {
            it.contentDescription == screen.getString(R.string.settings_description_for, screen.getString(R.string.app_permissions))
        }.performClick()
        assertHas(R.string.nearby_devices_connects_your_iphone_microphone_enables_sir)
        category(SettingsCategory.ABOUT)
        assertHas(R.string.an_independent_carplay_receiver_for_android_head_units_wir)
    }

    @Test fun dilink3And5GuideChoicesAndDilink3FeaturesAreHiddenWithoutChangingGeneration() {
        DiLinkGeneration.save(context, DiLinkGeneration.DILINK_3)
        AirPlayPersistence.saveLegacyClusterEnabled(context, true)
        guide(SetupGuide.STEP_CAR)
        assertTextAbsent(screen.getString(R.string.dilink_3))
        assertTextAbsent(screen.getString(R.string.dilink_5))
        assertHas(R.string.dilink_4)
        guide(SetupGuide.STEP_FEATURES)
        listOf(R.string.setup_cluster_route_conflict, R.string.setup_cluster_route_turn_off,
            R.string.carplay_call_controls_experimental, R.string.carplay_calls_on_dashboard).forEach { assertMissing(it) }
        assertTextAbsent(screen.getString(R.string.dilink_3))
        assertEquals(DiLinkGeneration.DILINK_3, DiLinkGeneration.confirmed(context))
        assertTrue(AirPlayPersistence.loadLegacyClusterEnabled(context))

        DiLinkGeneration.save(context, DiLinkGeneration.DILINK_5)
        guide(SetupGuide.STEP_FEATURES)
        assertTextAbsent(screen.getString(R.string.dilink_5))
        assertEquals(DiLinkGeneration.DILINK_5, DiLinkGeneration.confirmed(context))

        DiLinkGeneration.save(context, DiLinkGeneration.DILINK_3)
        screen.sealEditionCheck = { false }
        guide(SetupGuide.STEP_CAR)
        assertHas(R.string.dilink_3)
        assertHas(R.string.dilink_5)
        guide(SetupGuide.STEP_FEATURES)
        listOf(R.string.setup_cluster_route_conflict, R.string.setup_cluster_route_turn_off,
            R.string.carplay_call_controls_experimental, R.string.carplay_calls_on_dashboard).forEach { assertHas(it) }
    }

    @Test fun detectedOtherGenerationIsNotDisplayedOrRewrittenOnSeal() {
        val product = Build.PRODUCT
        try {
            ShadowBuild.setProduct("DiLink3.0")
            guide(SetupGuide.STEP_CAR)
            assertTextAbsent(screen.getString(R.string.dilink_3))
            assertTextAbsent("DiLink3.0")
            assertEquals(DiLinkGeneration.DILINK_4, DiLinkGeneration.confirmed(context))
            screen.sealEditionCheck = { false }
            guide(SetupGuide.STEP_CAR)
            assertTrue(texts().any { it.contains("DiLink3.0") })
        } finally {
            ShadowBuild.setProduct(product)
        }
    }

    @Test fun dilink3ProjectionShapeIsHiddenOnlyOnSealAndKeepsItsValue() {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = "com.example.amapservice"
            applicationInfo = ApplicationInfo().apply { packageName = "com.example.amapservice" }
        })
        DiLinkGeneration.save(context, DiLinkGeneration.DILINK_3)
        AirPlayPersistence.saveClusterMapEnabled(context, true)
        val shape = com.shilapi.xcertplay.hud.BydClusterProjectionShape.FULL
        BydOutputSettings.setClusterProjectionShape(context, shape)
        val display = presentationDisplay("w1280dp-h480dp-mdpi", DiLink4ClusterDisplay.NAME)
        try {
            category(SettingsCategory.NAVIGATION)
            assertMissing(R.string.cluster_projection_shape)
            assertNotIndexed(R.string.cluster_projection_shape)
            assertEquals(shape, BydOutputSettings.clusterProjectionShape(context))
            screen.sealEditionCheck = { false }
            category(SettingsCategory.ADVANCED)
            assertHas(R.string.cluster_projection_shape)
        } finally {
            ShadowDisplayManager.removeDisplay(display)
        }
    }

    @Test fun dilink51LayoutControlsAreHiddenOnlyOnSealAndKeepSavedValues() {
        val fingerprint = Build.FINGERPRINT
        ShadowBuild.setFingerprint(DiLink51ClusterLayout.FINGERPRINT)
        AirPlayPersistence.saveClusterMapEnabled(context, true)
        DiLink51ClusterLayout.saveTheme(context, DiLink51ClusterLayout.Theme.SIMPLE)
        DiLink51ClusterLayout.saveContrast(context, DiLink51ClusterLayout.Contrast.DARK)
        val display = presentationDisplay("w1920dp-h720dp-mdpi", DiLink51ClusterLayout.SIDE)
        try {
            category(SettingsCategory.NAVIGATION)
            listOf(R.string.follow_instrument_theme_and_map_card, R.string.automatic_map_setup_adb,
                R.string.instrument_theme, R.string.instrument_contrast).forEach { assertMissing(it) }
            assertNotIndexed(R.string.instrument_theme)
            assertEquals(DiLink51ClusterLayout.Theme.SIMPLE, DiLink51ClusterLayout.theme(context))
            assertEquals(DiLink51ClusterLayout.Contrast.DARK, DiLink51ClusterLayout.contrast(context))
            screen.sealEditionCheck = { false }
            category(SettingsCategory.ADVANCED)
            listOf(R.string.follow_instrument_theme_and_map_card, R.string.automatic_map_setup_adb,
                R.string.instrument_theme, R.string.instrument_contrast).forEach { assertHas(it) }
        } finally {
            ShadowDisplayManager.removeDisplay(display)
            ShadowBuild.setFingerprint(fingerprint)
        }
    }

    @Test fun manualPermissionDialogHidesUsbOnlyRowsButKeepsSharedActions() {
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "showManualPermissionDialog",
            ReflectionHelpers.ClassParameter(String::class.java, "Reason"))
        var dialog = ShadowAlertDialog.getLatestAlertDialog()
        fun dialogTexts() = descendants(dialog.window!!.decorView).filterIsInstance<TextView>()
            .map { it.text.toString() }.toList()
        assertFalse(dialogTexts().any { it.contains(screen.getString(R.string.usb_auto_confirm_title)) })
        assertFalse(dialogTexts().contains(screen.getString(R.string.btn_open_accessibility_setting)))
        assertTrue(dialogTexts().contains(screen.getString(R.string.btn_open_overlay_setting)))
        assertTrue(dialogTexts().contains(screen.getString(R.string.copy_command)))
        dialog.dismiss()
        screen.sealEditionCheck = { false }
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "showManualPermissionDialog",
            ReflectionHelpers.ClassParameter(String::class.java, "Reason"))
        dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialogTexts().any { it.contains(screen.getString(R.string.usb_auto_confirm_title)) })
        assertTrue(dialogTexts().contains(screen.getString(R.string.btn_open_accessibility_setting)))
        dialog.dismiss()
    }

    private fun presentationDisplay(spec: String, name: String): Int {
        val id = ShadowDisplayManager.addDisplay(spec, 5)
        shadowOf(context.getSystemService(DisplayManager::class.java).getDisplay(id)).apply {
            setName(name)
            setFlags(Display.FLAG_PRESENTATION)
        }
        return id
    }

    private fun guide(step: Int) {
        ReflectionHelpers.setField(screen, "setupStep", step)
        page("setup")
    }

    private fun page(page: String) {
        ReflectionHelpers.setField(screen, "page", page)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
    }

    private fun category(category: SettingsCategory) {
        ReflectionHelpers.setField(screen, "page", "settings")
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSettingsCategory",
            ReflectionHelpers.ClassParameter(SettingsCategory::class.java, category))
    }

    private fun texts(): List<String> = descendants(screen.window.decorView).filterIsInstance<TextView>()
        .filter { it.isShown }.map { it.text.toString() }.toList()

    private fun assertHas(id: Int) {
        val title = screen.getString(id)
        assertTrue("Missing $title", texts().any { it == title || it.startsWith("$title · ") || it == "✓  $title" })
    }

    private fun assertMissing(id: Int) {
        val title = screen.getString(id)
        assertFalse("Unexpected $title", texts().any { it == title || it.startsWith("$title · ") || it == "✓  $title" })
    }

    private fun assertTextAbsent(text: String) = assertFalse("Unexpected text: $text", texts().any { text in it })

    private fun assertNotIndexed(id: Int) {
        val index = ReflectionHelpers.callInstanceMethod<List<DiPlayActivity.SettingsSearchResult>>(screen, "buildSettingsSearchIndex")
        assertFalse(index.any { it.title == screen.getString(id) })
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
