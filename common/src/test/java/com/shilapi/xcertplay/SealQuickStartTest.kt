package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.AdditionalAnswers.delegatesTo
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class SealQuickStartTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val appPrefs get() = context.getSharedPreferences("diplay", Context.MODE_PRIVATE)
    private val airplayPrefs get() = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)

    @Before fun clear() {
        appPrefs.edit().clear().commit()
        airplayPrefs.edit().clear().commit()
    }

    @After fun cleanup() = clear()

    @Test fun freshSealUsesWifiDirectWithoutOverwritingLaterChoices() {
        assertTrue(SealQuickStart.prepare(context, sealEdition = true, freshInstall = true))
        assertTrue(SealQuickStart.enabled(context))
        assertEquals(WirelessHotspotMode.WIFI_P2P, AirPlayPersistence.loadWirelessHotspotMode(context))
        assertTrue(AirPlayPersistence.loadAutoStartOnBoot(context))
        assertTrue(DiPlayPreferences.connectOnPhoneBluetooth(context))

        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.EXISTING_WIFI)
        DiPlayPreferences.saveAutoConnect(context, false)
        AirPlayPersistence.saveAutoStartOnBoot(context, false)
        DiPlayPreferences.saveConnectOnPhoneBluetooth(context, false)
        assertTrue(SealQuickStart.prepare(context, sealEdition = true, freshInstall = false))
        assertEquals(WirelessHotspotMode.EXISTING_WIFI, AirPlayPersistence.loadWirelessHotspotMode(context))
        assertFalse(DiPlayPreferences.autoConnect(context))
        assertFalse(AirPlayPersistence.loadAutoStartOnBoot(context))
        assertFalse(DiPlayPreferences.connectOnPhoneBluetooth(context))
    }

    @Test fun upgradesAndOtherEditionsKeepTheirExistingFirstRunBehavior() {
        assertFalse(SealQuickStart.prepare(context, sealEdition = true, freshInstall = false))
        assertFalse(SealQuickStart.prepare(context, sealEdition = false, freshInstall = true))
        assertFalse(SealQuickStart.enabled(context))
        assertEquals(WirelessHotspotMode.MANUAL, AirPlayPersistence.loadWirelessHotspotMode(context))
        assertFalse(AirPlayPersistence.loadAutoStartOnBoot(context))
        assertFalse(DiPlayPreferences.connectOnPhoneBluetooth(context))
    }

    @Test fun restoredOrPreviouslySavedConnectionSettingsAreNotReplaced() {
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.MANUAL)
        AirPlayPersistence.saveManualHotspotSsid(context, "Existing car hotspot")
        DiPlayPreferences.savePhone(context, "00:11:22:33:44:55", "Saved phone")

        assertFalse(SealQuickStart.prepare(context, sealEdition = true, freshInstall = true))
        assertEquals(WirelessHotspotMode.MANUAL, AirPlayPersistence.loadWirelessHotspotMode(context))
        assertEquals("Existing car hotspot", AirPlayPersistence.loadManualHotspotSsid(context))
        assertEquals("00:11:22:33:44:55", DiPlayPreferences.phoneAddress(context))
    }

    @Test @Config(sdk = [33])
    fun activityPreparesFreshSealBeforeAndroid13LanguageMigration() {
        val resources = mock(Resources::class.java, delegatesTo<Resources>(context.resources))
        doReturn(true).`when`(resources).getBoolean(R.bool.config_seal_edition)
        val packages = mock(PackageManager::class.java, delegatesTo<PackageManager>(context.packageManager))
        val installed = PackageInfo().apply { firstInstallTime = 1000; lastUpdateTime = 1000 }
        doReturn(installed).`when`(packages).getPackageInfo(context.packageName, 0)
        val sealContext = object : ContextWrapper(context) {
            override fun getResources(): Resources = resources
            override fun getPackageManager(): PackageManager = packages
        }

        val activity = DiPlayActivity()
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "attachBaseContext",
            ReflectionHelpers.ClassParameter(Context::class.java, sealContext))

        assertTrue(SealQuickStart.enabled(context))
        assertTrue(appPrefs.getBoolean("app_language_platform_migrated", false))
        assertEquals(WirelessHotspotMode.WIFI_P2P, AirPlayPersistence.loadWirelessHotspotMode(context))
    }

    @Test fun onlyOneRecognizablePairedIphoneIsChosenAutomatically() {
        data class Device(val name: String?, val services: List<UUID> = emptyList())
        fun choose(devices: List<Device>) = SealQuickStart.singleLikelyIphone(devices, Device::name, Device::services)
        val other = Device("Car audio")
        val byName = Device("Alice's iPhone")
        val byService = Device("Alice's phone", listOf(UUID.fromString("00000000-deca-fade-deca-deafdecacafe")))
        val unknown = Device("Phone")

        assertEquals(byName, choose(listOf(other, byName, unknown)))
        assertEquals(byService, choose(listOf(other, byService)))
        assertNull(choose(listOf(other, unknown)))
        assertNull(choose(listOf(byName, byService)))
    }
}
