package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SealPerformanceProfileTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun sealFreshInstallDefaultsToWirelessAutoConnect() {
        assertTrue(SealPerformanceProfile.defaultAutoConnect(sealEdition = true))
        assertFalse(SealPerformanceProfile.defaultAutoConnect(sealEdition = false))
        assertEquals(
            DefaultConnectionMode.WIRELESS,
            SealPerformanceProfile.defaultConnectionMode(sealEdition = true),
        )
        assertEquals(
            DefaultConnectionMode.LAST_USED,
            SealPerformanceProfile.defaultConnectionMode(sealEdition = false),
        )
    }

    @Test fun profileRemovesExtraVideoWorkWithoutChangingConnectionOrAudio() {
        AirPlayPersistence.saveWirelessEnabled(context, false)
        AirPlayPersistence.saveMediaBufferMillis(context, 500)
        AirPlayPersistence.saveHevcEnabled(context, true)
        AirPlayPersistence.saveSmoothVideo(context, true)
        AirPlayPersistence.saveAdbClusterEnabled(context, true)
        AirPlayPersistence.saveCenterMapOverlay(context, true)
        AirPlayPersistence.saveLauncherMapSharing(context, true)
        CarPlayRotation.setEnabled(context, true)
        SplitScreenSettings.setEnabled(context, true)
        SidePanelSettings.setEnabled(context, true)
        SealPerformanceProfile.apply(context, 83)
        assertEquals(60, AirPlayPersistence.loadFps(context))
        assertEquals(83, AirPlayPersistence.loadDisplayScalePercent(context))
        assertTrue(AirPlayPersistence.loadDirectVideo(context))
        assertFalse(AirPlayPersistence.loadSmoothVideo(context))
        assertFalse(AirPlayPersistence.loadHevcEnabled(context))
        assertFalse(AirPlayPersistence.loadClusterMapEnabled(context))
        assertFalse(AirPlayPersistence.loadAdbClusterEnabled(context))
        assertFalse(AirPlayPersistence.loadCenterMapOverlay(context))
        assertFalse(AirPlayPersistence.loadLauncherMapSharing(context))
        assertFalse(CarPlayRotation.enabled(context))
        assertFalse(SplitScreenSettings.enabled(context))
        assertFalse(SidePanelSettings.enabled(context))
        assertFalse(AirPlayPersistence.loadWirelessEnabled(context))
        assertEquals(500, AirPlayPersistence.loadMediaBufferMillis(context))
    }

    @Test fun unsupportedPresetDoesNotPartiallyApplyAndSavedOverridesRemainEffective() {
        AirPlayPersistence.saveFps(context, 45)
        AirPlayPersistence.saveDisplayScalePercent(context, 90)
        AirPlayPersistence.saveDirectVideo(context, false)
        assertThrows(IllegalArgumentException::class.java) { SealPerformanceProfile.apply(context, 0) }
        assertEquals(45, AirPlayPersistence.loadFps(context))
        assertEquals(90, AirPlayPersistence.loadDisplayScalePercent(context))
        assertFalse(AirPlayPersistence.loadDirectVideo(context))
    }

    @Test fun upgradeRemovesOldBufferedAndClusterConfigurationButKeepsNativeResolution() {
        val seal = editionContext(true)
        AirPlayPersistence.saveDisplayScalePercent(seal, 100)
        AirPlayPersistence.saveWirelessEnabled(seal, false)
        DiPlayPreferences.saveDefaultConnectionMode(seal, DefaultConnectionMode.USB)
        AirPlayPersistence.saveMediaBufferMillis(seal, 500)
        AirPlayPersistence.saveFps(seal, 30)
        AirPlayPersistence.saveSmoothVideo(seal, true)
        AirPlayPersistence.saveAdbClusterEnabled(seal, true)
        AirPlayPersistence.saveCenterMapOverlay(seal, true)
        AirPlayPersistence.saveLauncherMapSharing(seal, true)
        SealPerformanceProfile.migrateIfNeeded(seal)
        assertEquals(100, AirPlayPersistence.loadDisplayScalePercent(seal))
        assertEquals(60, AirPlayPersistence.loadFps(seal))
        assertTrue(AirPlayPersistence.loadDirectVideo(seal))
        assertFalse(AirPlayPersistence.loadSmoothVideo(seal))
        assertFalse(AirPlayPersistence.loadClusterMapEnabled(seal))
        assertFalse(AirPlayPersistence.loadAdbClusterEnabled(seal))
        assertFalse(AirPlayPersistence.loadCenterMapOverlay(seal))
        assertFalse(AirPlayPersistence.loadLauncherMapSharing(seal))
        assertFalse(AirPlayPersistence.loadWirelessEnabled(seal))
        assertEquals(DefaultConnectionMode.USB, DiPlayPreferences.defaultConnectionMode(seal))
        assertEquals(500, AirPlayPersistence.loadMediaBufferMillis(seal))
    }

    @Test fun upgradePreservesOtherResolutionChoicesAndOnlyAppliesOnce() {
        val seal = editionContext(true)
        AirPlayPersistence.saveDisplayScalePercent(seal, 90)
        SealPerformanceProfile.migrateIfNeeded(seal)
        assertEquals(90, AirPlayPersistence.loadDisplayScalePercent(seal))
        AirPlayPersistence.saveFps(seal, 45)
        AirPlayPersistence.saveSmoothVideo(seal, true)
        SealPerformanceProfile.migrateIfNeeded(seal)
        assertEquals(45, AirPlayPersistence.loadFps(seal))
        assertTrue(AirPlayPersistence.loadSmoothVideo(seal))
    }

    @Test fun otherEditionsDoNotMigrate() {
        AirPlayPersistence.saveSmoothVideo(context, true)
        AirPlayPersistence.saveAdbClusterEnabled(context, true)
        SealPerformanceProfile.migrateIfNeeded(editionContext(false))
        assertTrue(AirPlayPersistence.loadSmoothVideo(context))
        assertTrue(AirPlayPersistence.loadAdbClusterEnabled(context))
        // A no-op for another edition must not consume the Seal migration.
        SealPerformanceProfile.migrateIfNeeded(editionContext(true))
        assertFalse(AirPlayPersistence.loadSmoothVideo(context))
    }

    private fun editionContext(seal: Boolean): Context {
        val resources = spy(context.resources)
        doReturn(seal).`when`(resources).getBoolean(R.bool.config_seal_edition)
        return object : ContextWrapper(context) {
            override fun getResources() = resources
        }
    }
}
