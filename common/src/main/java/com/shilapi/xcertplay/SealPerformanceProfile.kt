package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.host.R

/** Video defaults for this fork, including upgrades from old buffered and non-smooth setups. */
internal object SealPerformanceProfile {
    fun enabled(context: Context): Boolean = context.resources.getBoolean(R.bool.config_seal_edition)

    fun defaultAutoConnect(sealEdition: Boolean): Boolean = sealEdition

    fun defaultConnectionMode(sealEdition: Boolean): DefaultConnectionMode =
        if (sealEdition) DefaultConnectionMode.WIRELESS else DefaultConnectionMode.LAST_USED

    /** Run before creating any display output; preserve resolution, pairing, connection and audio. */
    @Synchronized fun migrateIfNeeded(context: Context) {
        if (!enabled(context)) return
        val prefs = context.getSharedPreferences("seal_performance", Context.MODE_PRIVATE)
        val version = prefs.getInt("video_defaults_version", 0)
        if (version >= 2) return
        if (version < 1) applyVideoDefaults(context)
        if (version < 2) AirPlayPersistence.saveSmoothVideo(context, true)
        prefs.edit().putInt("video_defaults_version", 2).apply()
    }

    private fun applyVideoDefaults(context: Context) {
        AirPlayPersistence.saveFps(context, 60)
        AirPlayPersistence.saveHevcEnabled(context, false)
        AirPlayPersistence.saveHevcSoftwareDecoderEnabled(context, false)
        AirPlayPersistence.saveDirectVideoOutput(context, true)
        AirPlayPersistence.saveSmoothVideo(context, true)
        AirPlayPersistence.saveClusterMapEnabled(context, false)
        AirPlayPersistence.saveAdbClusterEnabled(context, false)
        AirPlayPersistence.saveCenterMapOverlay(context, false)
        AirPlayPersistence.saveLauncherMapSharing(context, false)
        CarPlayRotation.setEnabled(context, false)
        SplitScreenSettings.setEnabled(context, false)
        SidePanelSettings.setEnabled(context, false)
    }
}
