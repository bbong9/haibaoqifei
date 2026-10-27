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
        assertTrue(AirPlayPersistence.loadDirectVideoOutput(seal))
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

    @Test fun oldDirectVideoFalseMigratesAndRemovesTheOldKey() {
        val prefs = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        prefs.edit().remove("direct_video_output").putBoolean("direct_video", false).commit()

        assertFalse(AirPlayPersistence.loadDirectVideoOutput(context))
        assertFalse(prefs.contains("direct_video"))
        assertTrue(prefs.contains("direct_video_output"))
    }

    @Test fun oldDirectVideoTrueMigrates() {
        val prefs = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        prefs.edit().remove("direct_video_output").putBoolean("direct_video", true).commit()

        assertTrue(AirPlayPersistence.loadDirectVideoOutput(context))
        assertFalse(prefs.contains("direct_video"))
    }

    @Test fun existingDirectVideoOutputWinsAndOldKeyIsRemoved() {
        val prefs = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        for (newValue in listOf(false, true)) {
            prefs.edit().putBoolean("direct_video", !newValue)
                .putBoolean("direct_video_output", newValue).commit()

            assertEquals(newValue, AirPlayPersistence.loadDirectVideoOutput(context))
            assertFalse(prefs.contains("direct_video"))
        }
    }

    private fun editionContext(seal: Boolean): Context {
        val resources = spy(context.resources)
        doReturn(seal).`when`(resources).getBoolean(R.bool.config_seal_edition)
        return object : ContextWrapper(context) {
            override fun getResources() = resources
        }
    }
}
