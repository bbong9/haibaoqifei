package com.shilapi.xcertplay

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.LiveVideoCounters
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DiagnosticEnhancementTest {
    @get:Rule val temp = TemporaryFolder()

    private fun localContext(): Context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = temp.root
    }

    @Test fun formattedHostLineCrossesMidnightAndKeepsItsDate() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val before = format.parse("2026-10-30 23:59:59.999")!!.time
            val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
            fun line(at: Long) = ReflectionHelpers.callInstanceMethod<String>(host, "formattedLogLine",
                from(String::class.java, "event=ok"), from(Long::class.javaPrimitiveType, at))
            assertEquals("2026-10-30 23:59:59.999  event=ok", line(before))
            assertEquals("2026-10-31 00:00:00.000  event=ok", line(before + 1))
            assertEquals("+00:00", DiagnosticLogTime.offset(before))
        } finally { TimeZone.setDefault(original) }
    }

    @Test fun sectionRangesUseFirstAndLastLinesAndKeepLegacyUnknown() {
        val file = temp.newFile("normal.log")
        file.writeText("2026-10-30 23:59:59.999  pid=42 version=0.2.22-seal.28/62\n" +
            "2026-10-31 00:00:00.000  event=ok\n")
        val section = DiagnosticLogSections.read("normal.log", file)
        assertEquals("2026-10-30 23:59:59.999", section.start)
        assertEquals("2026-10-31 00:00:00.000", section.end)
        assertEquals("42", section.pid)
        assertEquals("0.2.22-seal.28/62", section.version)
        assertEquals("--- normal.log  2026-10-30 23:59:59.999 → 2026-10-31 00:00:00.000  pid=42 lines=2 ---", section.heading())
        val empty = DiagnosticLogSections.read("empty.log", temp.newFile("empty.log"))
        assertEquals("unknown", empty.start)
        assertEquals("unknown", empty.end)
        file.writeText("old header\n2026-10-31 00:00:00.000  event=ok\n")
        val legacy = DiagnosticLogSections.read("legacy.log", file)
        assertEquals("unknown", legacy.start)
        assertEquals("2026-10-31 00:00:00.000", legacy.end)
        assertEquals(listOf(section, legacy, empty), DiagnosticLogSections.chronological(listOf(legacy, section, empty)))
        file.writeText("2026-10-30 12:00:00.000  pid=42 version=0.2.22-seal.27/61\n" +
            "2026-10-30 13:00:00.000  pid=43 version=0.2.22-seal.28/62\n")
        val mixed = DiagnosticLogSections.read("mixed.log", file)
        assertEquals("mixed", mixed.pid)
        assertEquals("mixed", mixed.version)
    }

    @Test fun bluetoothMappingsAndBroadcastsOnlyExposeStates() {
        assertEquals(listOf("on", "off", "turningOn", "turningOff", "unknown"), listOf(
            BluetoothAdapter.STATE_ON, BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_ON,
            BluetoothAdapter.STATE_TURNING_OFF, -1).map(CarAudioDiagnostics::adapterState))
        assertEquals(listOf("connected", "connecting", "disconnecting", "disconnected", "unknown"), listOf(
            BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_CONNECTING, BluetoothProfile.STATE_DISCONNECTING,
            BluetoothProfile.STATE_DISCONNECTED, -1).map(CarAudioDiagnostics::connectionState))
        assertEquals(listOf("playing", "stopped", "unknown"), listOf(BluetoothA2dp.STATE_PLAYING,
            BluetoothA2dp.STATE_NOT_PLAYING, -1).map(CarAudioDiagnostics::playingState))
        assertEquals(listOf("normal", "ringtone", "inCall", "inComm", "unknown"), listOf(AudioManager.MODE_NORMAL,
            AudioManager.MODE_RINGTONE, AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, -1).map(CarAudioDiagnostics::audioMode))

        val lines = mutableListOf<String>()
        val context = RuntimeEnvironment.getApplication()
        val audio = CarAudioDiagnostics(context, lines::add)
        val receiver = ReflectionHelpers.getField<BroadcastReceiver>(audio, "receiver")
        fun emit(action: String, state: Int, previous: Int = -1) = receiver.onReceive(context, Intent(action)
            .putExtra(BluetoothProfile.EXTRA_STATE, state).putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, previous)
            .putExtra(BluetoothAdapter.EXTRA_STATE, state).putExtra("private", "手机名 歌词 路名 192.168.1.2 AA:BB:CC:DD:EE:FF"))
        emit(BluetoothAdapter.ACTION_STATE_CHANGED, BluetoothAdapter.STATE_ON)
        emit(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED, BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_CONNECTING)
        emit(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED, BluetoothA2dp.STATE_PLAYING)
        emit(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED, BluetoothProfile.STATE_DISCONNECTED)
        emit(BluetoothAdapter.ACTION_STATE_CHANGED, BluetoothAdapter.STATE_TURNING_OFF)
        assertEquals("Car audio: adapter state=on", lines[0])
        assertEquals("Car audio: a2dp state=connected prev=connecting", lines[2])
        assertEquals("Car audio: a2dp playing=playing", lines[4])
        assertEquals("Car audio: hfp state=disconnected", lines[6])
        assertEquals("Car audio: adapter state=turningOff", lines[8])
        assertEquals(5, lines.count { it.startsWith("Car audio: route ") })
        audio.playbackChanged(true); audio.playbackChanged(true); audio.playbackChanged(false)
        assertEquals(listOf("Now playing: state=playing", "Now playing: state=paused"), lines.takeLast(2))
        assertSafe(lines)
    }

    @Test fun heartbeatAndVideoStallAreBoundedAndDeduplicated() {
        val line = HeartbeatSnapshot("stopped", "none", -1, -1, "none", "off", "unknown", "unknown",
            "disconnected", "unknown", "carplay", 0, -1).line()
        assertTrue(line.contains("session=none"))
        val stall = VideoStallTracker()
        assertNull(stall.sample(0, 5000, 4.0, 0.0, "stopped", "none"))
        assertNull(stall.sample(5000, 9999, 4.0, 0.0, "stopped", "none"))
        val first = stall.sample(9999, 10000, 4.0, 0.0, "stopped", "none")!!
        assertEquals("Video stall: stream=main fps=4 shownFps=0 durationS=10 activity=stopped surface=none", first)
        assertNull(stall.sample(10000, 15000, 1.0, 0.0, "stopped", "none"))
        val ended = stall.sample(15000, 20000, 60.0, 59.0, "resumed", "valid")!!
        assertEquals("Video stall: ended durationS=20", ended)
        assertNull(stall.sample(20000, 25000, 60.0, 60.0, "resumed", "valid"))
        assertSafe(listOf(line, first, ended))
    }

    @Test fun carBluetoothAudioKeepsItsNoticeWithoutSchedulingAProbe() {
        val app = RuntimeEnvironment.getApplication()
        DiagnosticHeartbeat.start(app, Handler(Looper.getMainLooper()))
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val file = temp.newFile("audio.log")
        val log = SessionLogFile(file)
        ReflectionHelpers.setField(host, "sessionLog", log)
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(host, "logCarBluetoothAudio")
            shadowOf(Looper.getMainLooper()).idleFor(35, TimeUnit.SECONDS)
            assertTrue(log.awaitIdle(2_000))
            assertTrue(SessionDiagnosticLog.awaitIdle(2_000))
            val lines = file.readLines()
            assertEquals(1, lines.count { it.endsWith("Car Bluetooth audio: on; CarPlay audio not offered") })
            val diagnosticFile = File(app.filesDir, "logs/diplay.log")
            assertFalse(diagnosticFile.takeIf { it.isFile }?.readText().orEmpty().contains("Car audio: probe"))
            assertSafe(lines)
        } finally {
            log.close()
            ReflectionHelpers.getField<Handler>(DiagnosticHeartbeat, "handler")
                .removeCallbacks(ReflectionHelpers.getField(DiagnosticHeartbeat, "tick"))
        }
    }

    @Test fun realHeartbeatWritesWhenThereIsNoSession() {
        val app = RuntimeEnvironment.getApplication()
        CarPlayBackgroundSession.clear()
        DiagnosticHeartbeat.start(app, Handler(Looper.getMainLooper()))
        ReflectionHelpers.setField(DiagnosticHeartbeat, "lastHeartbeat", android.os.SystemClock.elapsedRealtime() - 60_000L)
        ReflectionHelpers.getField<Runnable>(DiagnosticHeartbeat, "tick").run()
        assertTrue(SessionDiagnosticLog.awaitIdle(2_000))
        val file = File(app.filesDir, "logs/diplay.log")
        val line = file.readLines().last { it.contains("Heartbeat:") }
        assertNotNull(DiagnosticLogTime.timestamp(line))
        assertTrue(line, line.contains("session=none"))
        val section = DiagnosticLogSections.read("diplay.log", file)
        assertEquals(android.os.Process.myPid().toString(), section.pid)
        assertTrue(section.version, section.version.contains('/'))
        assertSafe(listOf(line))
    }

    @Test fun processEntryStartsDiagnosticsWithoutOpeningAnActivity() {
        val provider = Robolectric.buildContentProvider(CrashRecordInstaller::class.java).create().get()
        assertNotNull(provider.context)
        assertSame(provider.context!!.applicationContext, ReflectionHelpers.getField<Context>(DiagnosticHeartbeat, "context"))
        assertNotNull(ReflectionHelpers.getField<Handler>(DiagnosticHeartbeat, "handler"))
    }

    @Test fun realBackgroundSamplingLogsAStallOnceThenItsRecovery() {
        val app = RuntimeEnvironment.getApplication()
        LastConnectionProblem.clear(app)
        CarPlayBackgroundSession.clear()
        val handler = Handler(Looper.getMainLooper())
        DiagnosticHeartbeat.start(app, handler)
        val sink = mock(AndroidMediaSink::class.java)
        val low = LiveVideoCounters(0, 0, 0, 0)
        val recovered = LiveVideoCounters(300, 300, 0, 0)
        `when`(sink.liveVideoCounters(110)).thenReturn(low, low, low, recovered)
        val owner = Any()
        DiagnosticHeartbeat.attach(owner, { sink }, { false })
        DiagnosticHeartbeat.state(owner, "stopped")
        CarPlayBackgroundSession.active = true
        try {
            shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.SECONDS)
            assertTrue(SessionDiagnosticLog.awaitIdle(2_000))
            val events = File(app.filesDir, "logs/diplay.log").readLines().filter { it.contains("Video stall:") }
            assertEquals(events.toString(), 2, events.size)
            assertTrue(events[0], events[0].contains("stream=main fps=0 shownFps=0 durationS=10 activity=stopped surface=none"))
            assertTrue(events[1], events[1].contains("Video stall: ended durationS=15"))
            assertTrue(LastConnectionProblem.recentProblems(app).isEmpty())
            assertSafe(events)
        } finally {
            DiagnosticHeartbeat.detach(owner)
            CarPlayBackgroundSession.clear()
            LastConnectionProblem.clear(app)
            handler.removeCallbacks(ReflectionHelpers.getField(DiagnosticHeartbeat, "tick"))
        }
    }

    @Test fun anActiveStreamWithoutADecoderCountsAsZeroButAnAbsentStreamDoesNot() {
        val app = RuntimeEnvironment.getApplication()
        LastConnectionProblem.clear(app)
        CarPlayBackgroundSession.clear()
        DiagnosticHeartbeat.start(app, Handler(Looper.getMainLooper()))
        val sink = mock(AndroidMediaSink::class.java)
        val owner = Any()
        DiagnosticHeartbeat.attach(owner, { sink }, { true })
        DiagnosticHeartbeat.state(owner, "resumed")
        CarPlayBackgroundSession.active = true
        try {
            shadowOf(Looper.getMainLooper()).idleFor(15, TimeUnit.SECONDS)
            assertTrue(LastConnectionProblem.recentProblems(app).isEmpty())
            `when`(sink.isScreenStreamActive(110)).thenReturn(true)
            shadowOf(Looper.getMainLooper()).idleFor(15, TimeUnit.SECONDS)
            assertEquals("videoStall", LastConnectionProblem.recentProblems(app).single().kind)
            assertTrue(SessionDiagnosticLog.awaitIdle(2_000))
            val lines = File(app.filesDir, "logs/diplay.log").readLines().filter { it.contains("Video stall:") }
            assertEquals(1, lines.size)
            assertTrue(lines.single(), lines.single().contains("fps=0 shownFps=0 durationS=10"))
            assertSafe(lines)
        } finally {
            DiagnosticHeartbeat.detach(owner)
            CarPlayBackgroundSession.clear()
            LastConnectionProblem.clear(app)
            ReflectionHelpers.getField<Handler>(DiagnosticHeartbeat, "handler")
                .removeCallbacks(ReflectionHelpers.getField(DiagnosticHeartbeat, "tick"))
        }
    }

    @Test fun backgroundDisplayStallWithNormalReceiveIsLoggedButNotAProblem() {
        val app = RuntimeEnvironment.getApplication()
        LastConnectionProblem.clear(app)
        CarPlayBackgroundSession.clear()
        DiagnosticHeartbeat.start(app, Handler(Looper.getMainLooper()))
        val sink = mock(AndroidMediaSink::class.java)
        `when`(sink.liveVideoCounters(110)).thenReturn(
            LiveVideoCounters(0, 0, 0, 0), LiveVideoCounters(150, 0, 0, 0),
            LiveVideoCounters(300, 0, 0, 0), LiveVideoCounters(450, 0, 0, 0),
            LiveVideoCounters(600, 0, 0, 0),
        )
        val owner = Any()
        DiagnosticHeartbeat.attach(owner, { sink }, { false })
        DiagnosticHeartbeat.state(owner, "paused")
        CarPlayBackgroundSession.active = true
        try {
            shadowOf(Looper.getMainLooper()).idleFor(25, TimeUnit.SECONDS)
            assertTrue(SessionDiagnosticLog.awaitIdle(2_000))
            val events = File(app.filesDir, "logs/diplay.log").readLines().filter { it.contains("Video stall:") }
            assertEquals(events.toString(), 1, events.size)
            assertTrue(events.single(), events.single().contains("fps=30 shownFps=0 durationS=10 activity=paused surface=none"))
            assertTrue(LastConnectionProblem.recentProblems(app).isEmpty())

            `when`(sink.liveVideoCounters(110)).thenReturn(
                LiveVideoCounters(610, 0, 0, 0), LiveVideoCounters(620, 0, 0, 0), LiveVideoCounters(630, 0, 0, 0),
            )
            shadowOf(Looper.getMainLooper()).idleFor(15, TimeUnit.SECONDS)
            assertTrue(SessionDiagnosticLog.awaitIdle(2_000))
            assertTrue(LastConnectionProblem.recentProblems(app).isEmpty())
            assertEquals(1, File(app.filesDir, "logs/diplay.log").readLines().count { it.contains("Video stall:") })
        } finally {
            DiagnosticHeartbeat.detach(owner)
            CarPlayBackgroundSession.clear()
            LastConnectionProblem.clear(app)
            ReflectionHelpers.getField<Handler>(DiagnosticHeartbeat, "handler")
                .removeCallbacks(ReflectionHelpers.getField(DiagnosticHeartbeat, "tick"))
        }
    }

    @Test fun backgroundLowReceiveIsLoggedButNotRecordedAsAProblem() {
        val app = RuntimeEnvironment.getApplication()
        LastConnectionProblem.clear(app)
        CarPlayBackgroundSession.clear()
        DiagnosticHeartbeat.start(app, Handler(Looper.getMainLooper()))
        val sink = mock(AndroidMediaSink::class.java)
        `when`(sink.liveVideoCounters(110)).thenReturn(
            LiveVideoCounters(0, 0, 0, 0), LiveVideoCounters(5, 0, 0, 0),
            LiveVideoCounters(10, 0, 0, 0), LiveVideoCounters(15, 0, 0, 0),
        )
        val owner = Any()
        DiagnosticHeartbeat.attach(owner, { sink }, { false })
        DiagnosticHeartbeat.state(owner, "paused")
        CarPlayBackgroundSession.active = true
        try {
            shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.SECONDS)
            assertTrue(SessionDiagnosticLog.awaitIdle(2_000))
            val events = File(app.filesDir, "logs/diplay.log").readLines().filter { it.contains("Video stall:") }
            assertEquals(events.toString(), 1, events.size)
            assertTrue(events.single(), events.single().contains("fps=1 shownFps=0 durationS=10 activity=paused surface=none"))
            assertTrue(LastConnectionProblem.recentProblems(app).isEmpty())
            assertSafe(events)
            println("CARD28_BACKGROUND_BEGIN")
            println(events.single())
            println(LastConnectionProblem.report(app))
            println("CARD28_BACKGROUND_END")
        } finally {
            DiagnosticHeartbeat.detach(owner)
            CarPlayBackgroundSession.clear()
            LastConnectionProblem.clear(app)
            ReflectionHelpers.getField<Handler>(DiagnosticHeartbeat, "handler")
                .removeCallbacks(ReflectionHelpers.getField(DiagnosticHeartbeat, "tick"))
        }
    }

    @Test fun foregroundZeroFramesAreLoggedAndRecordedAsAProblem() {
        val app = RuntimeEnvironment.getApplication()
        LastConnectionProblem.clear(app)
        CarPlayBackgroundSession.clear()
        DiagnosticHeartbeat.start(app, Handler(Looper.getMainLooper()))
        val sink = mock(AndroidMediaSink::class.java)
        `when`(sink.liveVideoCounters(110)).thenReturn(LiveVideoCounters(0, 0, 0, 0))
        val owner = Any()
        DiagnosticHeartbeat.attach(owner, { sink }, { true })
        DiagnosticHeartbeat.state(owner, "resumed")
        CarPlayBackgroundSession.active = true
        try {
            shadowOf(Looper.getMainLooper()).idleFor(17, TimeUnit.SECONDS)
            assertTrue(SessionDiagnosticLog.awaitIdle(2_000))
            val events = File(app.filesDir, "logs/diplay.log").readLines().filter { it.contains("Video stall:") }
            assertEquals(events.toString(), 1, events.size)
            assertTrue(events.single(), events.single().contains("fps=0 shownFps=0 durationS=10 activity=resumed surface=valid"))
            assertEquals("videoStall", LastConnectionProblem.recentProblems(app).single().kind)
            assertSafe(events)
            println("CARD28_FOREGROUND_BEGIN")
            println(events.single())
            println(LastConnectionProblem.report(app))
            println("CARD28_FOREGROUND_END")
        } finally {
            DiagnosticHeartbeat.detach(owner)
            CarPlayBackgroundSession.clear()
            LastConnectionProblem.clear(app)
            ReflectionHelpers.getField<Handler>(DiagnosticHeartbeat, "handler")
                .removeCallbacks(ReflectionHelpers.getField(DiagnosticHeartbeat, "tick"))
        }
    }

    @Test fun backgroundStallBecomesOneProblemOnlyAfterReturningToAValidForeground() {
        val app = RuntimeEnvironment.getApplication()
        LastConnectionProblem.clear(app)
        CarPlayBackgroundSession.clear()
        DiagnosticHeartbeat.start(app, Handler(Looper.getMainLooper()))
        val sink = mock(AndroidMediaSink::class.java)
        `when`(sink.liveVideoCounters(110)).thenReturn(LiveVideoCounters(0, 0, 0, 0))
        val owner = Any()
        var valid = false
        DiagnosticHeartbeat.attach(owner, { sink }, { valid })
        DiagnosticHeartbeat.state(owner, "stopped")
        CarPlayBackgroundSession.active = true
        try {
            shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.SECONDS)
            assertTrue(LastConnectionProblem.recentProblems(app).isEmpty())
            DiagnosticHeartbeat.state(owner, "resumed")
            shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.SECONDS)
            assertTrue(LastConnectionProblem.recentProblems(app).isEmpty())
            valid = true
            shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.SECONDS)
            assertEquals("videoStall", LastConnectionProblem.recentProblems(app).single().kind)
            assertTrue(SessionDiagnosticLog.awaitIdle(2_000))
            val events = File(app.filesDir, "logs/diplay.log").readLines().filter { it.contains("Video stall:") }
            assertEquals(events.toString(), 1, events.size)
            assertTrue(events.single(), events.single().contains("activity=stopped surface=none"))
        } finally {
            DiagnosticHeartbeat.detach(owner)
            CarPlayBackgroundSession.clear()
            LastConnectionProblem.clear(app)
            ReflectionHelpers.getField<Handler>(DiagnosticHeartbeat, "handler")
                .removeCallbacks(ReflectionHelpers.getField(DiagnosticHeartbeat, "tick"))
        }
    }

    @Test fun stallTrackerReportsAProblemOnlyOnceAfterForegroundAndSurfaceAreValid() {
        val stall = VideoStallTracker()
        assertNull(stall.sample(0, 5_000, 1.0, 0.0, "paused", "none"))
        assertNotNull(stall.sample(5_000, 10_000, 1.0, 0.0, "paused", "none"))
        assertFalse(stall.shouldRecordProblem("paused", "none"))
        assertFalse(stall.shouldRecordProblem("stopped", "valid"))
        assertFalse(stall.shouldRecordProblem("resumed", "none"))
        assertTrue(stall.shouldRecordProblem("resumed", "valid"))
        assertFalse(stall.shouldRecordProblem("resumed", "valid"))
    }

    @Test fun foregroundDisplayStallWithValidSurfaceIsRecordedAsAProblem() {
        val app = RuntimeEnvironment.getApplication()
        LastConnectionProblem.clear(app)
        CarPlayBackgroundSession.clear()
        DiagnosticHeartbeat.start(app, Handler(Looper.getMainLooper()))
        val sink = mock(AndroidMediaSink::class.java)
        `when`(sink.liveVideoCounters(110)).thenReturn(
            LiveVideoCounters(0, 0, 0, 0), LiveVideoCounters(150, 0, 0, 0), LiveVideoCounters(300, 0, 0, 0),
        )
        val owner = Any()
        DiagnosticHeartbeat.attach(owner, { sink }, { true })
        DiagnosticHeartbeat.state(owner, "resumed")
        CarPlayBackgroundSession.active = true
        try {
            shadowOf(Looper.getMainLooper()).idleFor(15, TimeUnit.SECONDS)
            assertEquals("videoStall", LastConnectionProblem.recentProblems(app).single().kind)
            assertTrue(SessionDiagnosticLog.awaitIdle(2_000))
            val events = File(app.filesDir, "logs/diplay.log").readLines().filter { it.contains("Video stall:") }
            assertEquals(events.toString(), 1, events.size)
            assertTrue(events.single(), events.single().contains("fps=30 shownFps=0 durationS=10 activity=resumed surface=valid"))
        } finally {
            DiagnosticHeartbeat.detach(owner)
            CarPlayBackgroundSession.clear()
            LastConnectionProblem.clear(app)
            ReflectionHelpers.getField<Handler>(DiagnosticHeartbeat, "handler")
                .removeCallbacks(ReflectionHelpers.getField(DiagnosticHeartbeat, "tick"))
        }
    }

    @Test fun recentProblemsCoalesceSameKindWithinAMinuteButMarksDoNot() {
        val app = RuntimeEnvironment.getApplication()
        LastConnectionProblem.clear(app)
        try {
            LastConnectionProblem.record(app, LastConnectionProblem.Kind.DECODER_FAILED, 2_000)
            repeat(10) { LastConnectionProblem.record(app, LastConnectionProblem.Kind.HOST_FAILED, 1_000L + it * 5_000L) }
            assertEquals(listOf(
                LastConnectionProblem.Event(2_000, "decoderFailed"),
                LastConnectionProblem.Event(46_000, "hostFailed"),
            ), LastConnectionProblem.recentProblems(app))
            LastConnectionProblem.record(app, LastConnectionProblem.Kind.HOST_FAILED, 1_000)
            assertEquals(46_000, LastConnectionProblem.recentProblems(app).last().atMillis)
            LastConnectionProblem.record(app, LastConnectionProblem.Kind.HOST_FAILED, 106_000)
            assertEquals(2, LastConnectionProblem.recentProblems(app).size)
            assertEquals(106_000, LastConnectionProblem.recentProblems(app).last().atMillis)
            LastConnectionProblem.record(app, LastConnectionProblem.Kind.HOST_FAILED, 166_001)
            assertEquals(3, LastConnectionProblem.recentProblems(app).size)
            repeat(2) { LastConnectionProblem.mark(app, LastConnectionProblem.Mark.VIDEO, 3_000) }
            assertEquals(listOf(LastConnectionProblem.Event(3_000, "video"),
                LastConnectionProblem.Event(3_000, "video")), LastConnectionProblem.recentMarks(app))
        } finally { LastConnectionProblem.clear(app) }
    }

    @Test fun repeatedProblemDoesNotDisplaceOtherKinds() {
        val app = RuntimeEnvironment.getApplication()
        LastConnectionProblem.clear(app)
        try {
            LastConnectionProblem.Kind.entries.forEachIndexed { index, kind ->
                LastConnectionProblem.record(app, kind, 1_000L + index * 1_000L)
            }
            repeat(10) { LastConnectionProblem.record(app, LastConnectionProblem.Kind.HOST_FAILED, 1_000L + it * 5_000L) }
            assertEquals(LastConnectionProblem.Kind.entries.map { it.value }.toSet(),
                LastConnectionProblem.recentProblems(app).map { it.kind }.toSet())
            assertEquals(5, LastConnectionProblem.recentProblems(app).size)
        } finally { LastConnectionProblem.clear(app) }
    }

    @Test fun problemDialogWritesAMarkWithoutAnActiveSessionAndKeepsOnlyFive() {
        val app = RuntimeEnvironment.getApplication()
        LastConnectionProblem.clear(app)
        CarPlayBackgroundSession.clear()
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "markProblem")
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(4, dialog.listView.count)
        dialog.listView.performItemClick(dialog.listView.getChildAt(1), 1, dialog.listView.getItemIdAtPosition(1))
        assertEquals("video", LastConnectionProblem.recentMarks(app).single().kind)
        assertTrue(SessionDiagnosticLog.awaitIdle(2_000))
        val written = File(app.filesDir, "logs/diplay.log").readLines().last { it.contains("USER_MARK") }
        assertTrue(written, written.endsWith("USER_MARK kind=video"))
        assertSafe(listOf(written))
        repeat(6) { LastConnectionProblem.mark(app, LastConnectionProblem.Mark.OTHER, it + 1_000L) }
        assertEquals(5, LastConnectionProblem.recentMarks(app).size)
        LastConnectionProblem.clear(app)
    }

    @Test fun failuresAreRecordedBeforeMenuFilteringAndNormalEofDoesNotCreateAProblem() {
        val app = RuntimeEnvironment.getApplication()
        LastConnectionProblem.clear(app)
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        ReflectionHelpers.setField(host, "menuOpen", true)
        val reporter = ReflectionHelpers.callInstanceMethod<(CarPlayStatus) -> Unit>(host, "createStatusReporter", from(Int::class.javaPrimitiveType, 0))
        reporter(CarPlayStatus.Failed("unspecified"))
        assertEquals("hostFailed", LastConnectionProblem.recentProblems(app).last().kind)
        val listener = ReflectionHelpers.callInstanceMethod<AirPlaySessionListener>(host, "createSessionListener", from(Int::class.javaPrimitiveType, 0))
        listener.onDebugLog("Video: decoder failed api=29")
        assertEquals("decoderFailed", LastConnectionProblem.recentProblems(app).last().kind)
        listener.onDebugLog("Wireless problem: kind=rfcommEofBeforeSession")
        assertEquals("rfcommEofBeforeSession", LastConnectionProblem.recentProblems(app).last().kind)
        val before = LastConnectionProblem.recentProblems(app)
        listener.onDebugLog("wireless RFCOMM control ended after tunnel handoff")
        assertEquals(before, LastConnectionProblem.recentProblems(app))
        LastConnectionProblem.clearConnection(app)
        assertEquals(before, LastConnectionProblem.recentProblems(app))
        LastConnectionProblem.clear(app)
    }

    @Test fun reportContainsActualWrittenSectionsHeartbeatBluetoothAndMark() {
        val context = localContext()
        LastConnectionProblem.clear(context)
        val logs = File(context.filesDir, "logs").apply { mkdirs() }
        File(logs, "startup").mkdirs()
        SessionLogFile(File(logs, "startup/timeline.log")).use { log ->
            log.appendNow("2026-10-30 12:00:00.000  up=1 pid=42 PROCESS_START version=0.2.22-seal.28/62")
        }
        SessionLogFile(File(logs, "diplay.log")).use { log ->
            log.appendNow("2026-10-30 12:00:00.000  DiPlay log started pid=42 version=0.2.22-seal.28/62")
            log.appendNow("2026-10-30 12:01:00.000  " + HeartbeatSnapshot("stopped", "none", -1, -1,
                "none", "on", "connected", "playing", "disconnected", "paused", "carplay", 3, 12).line())
            log.appendNow("2026-10-30 12:01:01.000  Car audio: a2dp state=connected prev=connecting")
            log.appendNow("2026-10-30 12:01:02.000  USER_MARK kind=video")
        }
        LastConnectionProblem.mark(context, LastConnectionProblem.Mark.VIDEO, 1_000)
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        val report = ReflectionHelpers.callInstanceMethod<String>(activity, "diagnosticReportText", from(Context::class.java, context))
        val selected = report.lines().filter { it.startsWith("--- startup/timeline.log  ") || it.startsWith("--- diplay.log  ") ||
            it.contains("Heartbeat:") || it.contains("Car audio: a2dp state=") || it.contains("USER_MARK") }
        assertEquals(5, selected.size)
        assertTrue(report.contains("--- Log directory ---"))
        assertTrue(report.contains("--- Recent user marks ---"))
        assertSafe(selected.filterNot { it.startsWith("---") })
        println("CARD27_SAMPLE_BEGIN")
        selected.forEach(::println)
        println("CARD27_SAMPLE_END")
        LastConnectionProblem.clear(context)
    }

    private fun assertSafe(lines: List<String>) {
        val forbidden = listOf("手机名", "歌词", "路名", "192.168.1.2", "AA:BB:CC:DD:EE:FF")
        for (line in lines) {
            assertEquals(line, DiagnosticRedactor.redact(line))
            forbidden.forEach { assertFalse(line, line.contains(it)) }
            Regex("([A-Za-z][A-Za-z0-9]*)=([^\\s]+)").findAll(line).forEach { match ->
                val key = match.groupValues[1].lowercase(Locale.ROOT)
                assertFalse(key, key.endsWith("name") || listOf("ssid", "pass", "token", "host").any(key::contains))
                assertTrue(match.value, match.groupValues[2].matches(Regex("[A-Za-z][A-Za-z0-9]*|-?\\d+")))
            }
        }
    }
}
