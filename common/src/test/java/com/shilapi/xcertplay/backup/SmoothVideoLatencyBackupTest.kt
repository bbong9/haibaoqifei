package com.shilapi.xcertplay.backup

import android.content.Context
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.media.SmoothVideoLatency
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SmoothVideoLatencyBackupTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)

    @After fun clear() { prefs.edit().clear().commit() }

    @Test fun defaultAndPersistedValuesSurviveBackupAndRestore() {
        prefs.edit().clear().commit()
        assertEquals(SmoothVideoLatency.BALANCED, AirPlayPersistence.loadSmoothVideoLatency(context))
        AirPlayPersistence.saveSmoothVideoLatency(context, SmoothVideoLatency.LOW)
        assertEquals(1, prefs.getInt("smooth_video_latency", -1))
        val document = SettingsBackupCodec.export(context, "0.2.22-seal.19")
        assertEquals(1, JSONObject(String(document)).getJSONObject("settings")
            .getJSONObject("xcertplay_airplay").getInt("smooth_video_latency"))
        prefs.edit().clear().commit()
        val result = SettingsBackupCodec.import(context, document)
        assertFalse(result.invalid)
        assertEquals(SmoothVideoLatency.LOW, AirPlayPersistence.loadSmoothVideoLatency(context))
        AirPlayPersistence.saveSmoothVideoLatency(context, SmoothVideoLatency.BALANCED)
        assertEquals(0, prefs.getInt("smooth_video_latency", -1))
        assertEquals(SmoothVideoLatency.BALANCED, AirPlayPersistence.loadSmoothVideoLatency(context))
    }

    @Test fun invalidBackupTypesAreSkippedOnAFreshInstall() {
        for (wrong in listOf("\"low\"", "true", "1.5", "99999999999")) {
            prefs.edit().clear().commit()
            val result = SettingsBackupCodec.import(context, """{"format":"diplay-settings","version":1,"settings":{"xcertplay_airplay":{"smooth_video_latency":$wrong}}}""".toByteArray())
            assertEquals(0, result.applied)
            assertEquals(1, result.skipped)
            assertEquals(SmoothVideoLatency.BALANCED, AirPlayPersistence.loadSmoothVideoLatency(context))
        }
    }
}
