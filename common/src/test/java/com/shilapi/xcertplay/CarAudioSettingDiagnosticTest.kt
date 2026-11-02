package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import com.shilapi.xcertplay.host.R
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w600dp-h700dp")
@LooperMode(LooperMode.Mode.PAUSED)
class CarAudioSettingDiagnosticTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun heartbeatContainsEachAudioSettingInTheSpecifiedPosition() {
        val snapshot = HeartbeatSnapshot("resumed", "active", 60, 30, "valid", "on", "connected", "playing",
            "disconnected", "playing", "carplay", 2, 3)
        for (value in listOf("bluetooth", "carplay", "unknown")) {
            val line = snapshot.copy(carAudio = value).line()
            assertEquals("Heartbeat: activity=resumed session=active mainFps=60 clusterFps=30 " +
                "surface=valid btAdapter=on a2dp=connected a2dpPlaying=playing hfp=disconnected " +
                "playback=playing carAudio=$value wheelKeys=2 lastWheelKeyAgeS=3", line)
            assertEquals(line, DiagnosticRedactor.redact(line))
        }
    }

    @Test fun switchingTheSettingWritesOneEventAndTheNextHeartbeatReadsTheNewValue() {
        val app = RuntimeEnvironment.getApplication()
        CarPlayBackgroundSession.clear()
        AirPlayPersistence.saveCarBluetoothAudio(app, false)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(app, DiPlayActivity::class.java).putExtra("page", "settings"))
        val screen = controller.get().apply { sealEditionCheck = { false } }
        controller.setup()
        ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.ADVANCED)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        val setting = descendants(screen.window.decorView).filterIsInstance<Switch>()
            .single { it.contentDescription == screen.getString(R.string.settings_car_bluetooth_audio) }
        val handler = Handler(Looper.getMainLooper())
        DiagnosticHeartbeat.start(app, handler)
        val tick = ReflectionHelpers.getField<Runnable>(DiagnosticHeartbeat, "tick")
        val file = File(app.filesDir, "logs/diplay.log")
        fun heartbeat(): String {
            ReflectionHelpers.setField(DiagnosticHeartbeat, "lastHeartbeat", SystemClock.elapsedRealtime() - 60_000L)
            tick.run()
            assertTrue(SessionDiagnosticLog.awaitIdle(2_000))
            return file.readLines().last { it.contains("Heartbeat:") }
        }
        try {
            assertTrue(heartbeat().contains("playback=unknown carAudio=carplay wheelKeys="))
            assertFalse(setting.isChecked)
            setting.performClick()
            assertTrue(setting.isChecked)
            assertTrue(AirPlayPersistence.loadCarBluetoothAudio(app))
            val bluetooth = heartbeat()
            assertTrue(bluetooth, bluetooth.contains("playback=unknown carAudio=bluetooth wheelKeys="))
            setting.performClick()
            assertFalse(setting.isChecked)
            assertFalse(AirPlayPersistence.loadCarBluetoothAudio(app))
            assertTrue(heartbeat().contains("playback=unknown carAudio=carplay wheelKeys="))

            val events = file.readLines().filter { it.contains("Car audio: setting carAudio=") }
            assertEquals(2, events.size)
            assertTrue(events[0], events[0].endsWith("Car audio: setting carAudio=bluetooth"))
            assertTrue(events[1], events[1].endsWith("Car audio: setting carAudio=carplay"))
            (events + bluetooth).forEach { line -> assertEquals(line, DiagnosticRedactor.redact(line)) }
            println("CARD29_SAMPLE_BEGIN")
            println(bluetooth)
            println(events[0])
            println("CARD29_SAMPLE_END")
        } finally {
            screen.finish()
            AirPlayPersistence.saveCarBluetoothAudio(app, false)
            CarPlayBackgroundSession.clear()
            ReflectionHelpers.getField<Handler>(DiagnosticHeartbeat, "handler").removeCallbacks(tick)
        }
    }

    @Test fun heartbeatUsesUnknownWhenTheSettingCannotBeRead() {
        val app = RuntimeEnvironment.getApplication()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = temp.root
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
                throw SecurityException("unavailable")
        }
        CarPlayBackgroundSession.clear()
        DiagnosticHeartbeat.start(context, Handler(Looper.getMainLooper()))
        val tick = ReflectionHelpers.getField<Runnable>(DiagnosticHeartbeat, "tick")
        try {
            ReflectionHelpers.setField(DiagnosticHeartbeat, "lastHeartbeat", SystemClock.elapsedRealtime() - 60_000L)
            tick.run()
            assertTrue(SessionDiagnosticLog.awaitIdle(2_000))
            val line = File(temp.root, "logs/diplay.log").readLines().last { it.contains("Heartbeat:") }
            assertTrue(line, line.contains("playback=unknown carAudio=unknown wheelKeys="))
            assertEquals(line, DiagnosticRedactor.redact(line))
        } finally {
            ReflectionHelpers.getField<Handler>(DiagnosticHeartbeat, "handler").removeCallbacks(tick)
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
