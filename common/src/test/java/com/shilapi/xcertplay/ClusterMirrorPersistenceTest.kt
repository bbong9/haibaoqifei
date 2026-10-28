package com.shilapi.xcertplay

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ClusterMirrorPersistenceTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @After fun cleanup() {
        AirPlayPersistence.overlaySettingsListener = null
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    @Test fun defaultsRoundTripAndUnknownSizeFallback() {
        assertFalse(AirPlayPersistence.loadClusterMirrorMain(context))
        assertEquals(ClusterMirrorSize.SAFE_AREA, AirPlayPersistence.loadClusterMirrorSize(context))
        var changes = 0
        AirPlayPersistence.overlaySettingsListener = { changes++ }
        AirPlayPersistence.saveClusterMirrorMain(context, true)
        AirPlayPersistence.saveClusterMirrorSize(context, ClusterMirrorSize.FULL_HEIGHT)
        assertTrue(AirPlayPersistence.loadClusterMirrorMain(context))
        assertEquals(ClusterMirrorSize.FULL_HEIGHT, AirPlayPersistence.loadClusterMirrorSize(context))
        assertEquals(2, changes)
        context.getSharedPreferences("xcertplay_airplay", 0).edit()
            .putString("cluster_mirror_size", "unknown").commit()
        assertEquals(ClusterMirrorSize.SAFE_AREA, AirPlayPersistence.loadClusterMirrorSize(context))
    }

    @Test fun diagnosticsAndAllMirrorLogVariantsSurviveRedaction() {
        val lines = listOf(
            ClusterMirrorDiagnostics.report(context),
            ClusterMirrorDiagnostics.decoderPerformance(),
            "Cluster mirror: on size=SAFE_AREA",
            "Cluster mirror: on size=FULL_HEIGHT",
            "Cluster mirror: off reason=setting",
            "Cluster mirror: off reason=route",
            "Cluster mirror: off reason=surface",
            "Cluster mirror: off reason=session",
            "Cluster mirror: off reason=not-navigating",
            "Cluster mirror: navigation hold 15000ms",
            "Cluster mirror: resolution cap saved=100 effective=75 fps=60",
            "decoderPerf=1920x1080@60,3840x2160@30",
            "mirrorMain=on mirrorSize=SAFE_AREA mirrorAttached=true mirrorRect=542,111,1298,537 clusterUiPaused=true",
            "mirrorMain=on mirrorSize=FULL_HEIGHT mirrorAttached=true mirrorRect=325,0,1515,720 " +
                "mirrorCrop=136,0,1940,1092 dockEdge=DRIVER_SIDE clusterUiPaused=true navOnly=on navigating=true dockCrop=7",
        )
        lines.forEach { assertEquals(it, DiagnosticRedactor.redact(it)) }
    }

    @Test fun navigationAndCropDefaultsPersistAndClamp() {
        assertTrue(AirPlayPersistence.loadClusterMirrorNavOnly(context))
        assertEquals(7, AirPlayPersistence.loadClusterMirrorDockCrop(context))
        var changes = 0
        AirPlayPersistence.overlaySettingsListener = { changes++ }
        AirPlayPersistence.saveClusterMirrorNavOnly(context, false)
        AirPlayPersistence.saveClusterMirrorDockCrop(context, 99)
        assertFalse(AirPlayPersistence.loadClusterMirrorNavOnly(context))
        assertEquals(20, AirPlayPersistence.loadClusterMirrorDockCrop(context))
        AirPlayPersistence.saveClusterMirrorDockCrop(context, -5)
        assertEquals(0, AirPlayPersistence.loadClusterMirrorDockCrop(context))
        context.getSharedPreferences("xcertplay_airplay", 0).edit().putInt("cluster_mirror_dock_crop", 42).commit()
        assertEquals(20, AirPlayPersistence.loadClusterMirrorDockCrop(context))
        assertEquals(3, changes)
    }

    @Test fun customBoundsClampOnReadAndWriteAndOldSizesStillRoundTrip() {
        assertEquals(ClusterMirrorCustomRect(), AirPlayPersistence.loadClusterMirrorCustom(context))
        for (size in listOf(ClusterMirrorSize.SAFE_AREA, ClusterMirrorSize.FULL_HEIGHT, ClusterMirrorSize.CUSTOM)) {
            AirPlayPersistence.saveClusterMirrorSize(context, size)
            assertEquals(size, AirPlayPersistence.loadClusterMirrorSize(context))
        }
        var changes = 0
        AirPlayPersistence.overlaySettingsListener = { changes++ }
        AirPlayPersistence.saveClusterMirrorCustom(context, ClusterMirrorCustomRect(-1, 101, 5, 130))
        assertEquals(ClusterMirrorCustomRect(0, 100, 20, 100), AirPlayPersistence.loadClusterMirrorCustom(context))
        val prefs = context.getSharedPreferences("xcertplay_airplay", 0)
        assertEquals(0, prefs.getInt("cluster_mirror_custom_x", -1))
        assertEquals(20, prefs.getInt("cluster_mirror_custom_w", -1))
        prefs.edit().putInt("cluster_mirror_custom_x", 300).putInt("cluster_mirror_custom_y", -2)
            .putInt("cluster_mirror_custom_w", 200).putInt("cluster_mirror_custom_h", 1).commit()
        assertEquals(ClusterMirrorCustomRect(100, 0, 100, 20), AirPlayPersistence.loadClusterMirrorCustom(context))
        assertEquals(1, changes)
    }

    @Test fun customDiagnosticsSurviveRedaction() {
        AirPlayPersistence.saveClusterMirrorSize(context, ClusterMirrorSize.CUSTOM)
        AirPlayPersistence.saveClusterMirrorCustom(context, ClusterMirrorCustomRect(25, 60, 70, 80))
        val line = ClusterMirrorDiagnostics.report(context)
        assertTrue(line, line.contains("mirrorSize=CUSTOM mirrorCustom=25,60,70,80"))
        assertEquals(line, DiagnosticRedactor.redact(line))
        AirPlayPersistence.saveClusterMirrorSize(context, ClusterMirrorSize.SAFE_AREA)
        assertFalse(ClusterMirrorDiagnostics.report(context).contains("mirrorCustom="))
    }

    @Test fun frameAndTurnCardDefaultsSaveAndClampOnReadAndWrite() {
        assertEquals(ClusterMirrorView(), AirPlayPersistence.loadClusterMirrorView(context))
        assertFalse(AirPlayPersistence.loadClusterMirrorTurnCard(context))
        var changes = 0
        AirPlayPersistence.overlaySettingsListener = { changes++ }
        AirPlayPersistence.saveClusterMirrorView(context, ClusterMirrorView(300, -1, 101))
        AirPlayPersistence.saveClusterMirrorTurnCard(context, true)
        assertEquals(ClusterMirrorView(250, 0, 100), AirPlayPersistence.loadClusterMirrorView(context))
        assertTrue(AirPlayPersistence.loadClusterMirrorTurnCard(context))
        val prefs = context.getSharedPreferences("xcertplay_airplay", 0)
        assertEquals(250, prefs.getInt("cluster_mirror_view_zoom", -1))
        assertEquals(0, prefs.getInt("cluster_mirror_view_focus_x", -1))
        assertEquals(100, prefs.getInt("cluster_mirror_view_focus_y", -1))
        prefs.edit().putInt("cluster_mirror_view_zoom", 1).putInt("cluster_mirror_view_focus_x", 200)
            .putInt("cluster_mirror_view_focus_y", -5).commit()
        assertEquals(ClusterMirrorView(100, 100, 0), AirPlayPersistence.loadClusterMirrorView(context))
        assertEquals(2, changes)
    }

    @Test fun frameAndTurnCardDiagnosticsSurviveRedaction() {
        AirPlayPersistence.saveClusterMirrorSize(context, ClusterMirrorSize.CUSTOM)
        AirPlayPersistence.saveClusterMirrorView(context, ClusterMirrorView(175, 10, 90))
        AirPlayPersistence.saveClusterMirrorTurnCard(context, true)
        val line = ClusterMirrorDiagnostics.report(context)
        assertTrue(line, line.contains("mirrorView=175,10,90"))
        assertTrue(line, line.contains("mirrorTurnCard=on"))
        assertEquals(line, DiagnosticRedactor.redact(line))
        AirPlayPersistence.saveClusterMirrorTurnCard(context, false)
        AirPlayPersistence.saveClusterMirrorSize(context, ClusterMirrorSize.FULL_HEIGHT)
        val legacy = ClusterMirrorDiagnostics.report(context)
        assertTrue(legacy, legacy.contains("mirrorTurnCard=off"))
        assertFalse(legacy, legacy.contains("mirrorView="))
        assertEquals(legacy, DiagnosticRedactor.redact(legacy))
    }
}
