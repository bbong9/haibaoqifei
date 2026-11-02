package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.LiveVideoCounters
import java.io.File
import kotlin.math.roundToInt

/** Settings and the host use the active session target, or the existing asynchronous queue. */
internal object SessionDiagnosticLog {
    private var active: SessionLogFile? = null
    private var idlePath: String? = null
    private var idleHeader = ""
    private var idleHeaderWritten = false

    @Synchronized fun configure(context: Context) {
        val path = File(context.filesDir, "logs/diplay.log").absolutePath
        if (idlePath == path) return
        val version = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION")
            val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            val value = info.versionName?.takeIf { it.matches(Regex("[A-Za-z0-9._-]+")) } ?: "unknown"
            "$value/$code"
        }.getOrDefault("unknown")
        idlePath = path
        idleHeader = "DiPlay log started pid=${Process.myPid()} version=$version"
        SessionLogFile.setRotationHeader(File(path), idleHeader)
        idleHeaderWritten = active?.file?.absolutePath == path
    }
    @Synchronized fun bind(target: SessionLogFile) {
        active = target
        if (idlePath == target.file.absolutePath) idleHeaderWritten = true
    }
    @Synchronized fun unbind(target: SessionLogFile) { if (active === target) active = null }
    @Synchronized fun append(context: Context, line: String) {
        val file = File(context.filesDir, "logs/diplay.log")
        val target = active?.takeIf { it.file.absolutePath == file.absolutePath }
        if (target != null) target.append(line) else {
            configure(context)
            if (!idleHeaderWritten) {
                AsyncDiagnosticLog.append(file, idleHeader)
                idleHeaderWritten = true
            }
            AsyncDiagnosticLog.append(file, line)
        }
    }
    fun awaitIdle(timeoutMillis: Long): Boolean {
        if (!AsyncDiagnosticLog.awaitIdle(timeoutMillis)) return false
        return synchronized(this) { active }?.awaitIdle(timeoutMillis) ?: true
    }
}

internal data class HeartbeatSnapshot(
    val activity: String, val session: String, val mainFps: Int, val clusterFps: Int,
    val surface: String, val btAdapter: String, val a2dp: String, val a2dpPlaying: String,
    val hfp: String, val playback: String, val carAudio: String, val wheelKeys: Int, val lastWheelKeyAgeS: Long,
) {
    fun line(): String = "Heartbeat: activity=$activity session=$session mainFps=$mainFps clusterFps=$clusterFps " +
        "surface=$surface btAdapter=$btAdapter a2dp=$a2dp a2dpPlaying=$a2dpPlaying hfp=$hfp " +
        "playback=$playback carAudio=$carAudio wheelKeys=$wheelKeys lastWheelKeyAgeS=$lastWheelKeyAgeS"
}

internal class VideoStallTracker {
    private var lowSince: Long? = null
    private var reported = false
    private var problemReported = false

    fun sample(startMs: Long, nowMs: Long, receivedFps: Double, shownFps: Double,
        activity: String, surface: String): String? {
        if (receivedFps < 5.0 || shownFps < 5.0) {
            val start = lowSince ?: startMs.also { lowSince = it }
            val duration = ((nowMs - start).coerceAtLeast(0)) / 1000
            if (!reported && duration >= 10) {
                reported = true
                return "Video stall: stream=main fps=${receivedFps.roundToInt()} shownFps=${shownFps.roundToInt()} " +
                    "durationS=$duration activity=$activity surface=$surface"
            }
        } else {
            val start = lowSince
            lowSince = null
            if (reported && start != null) {
                reported = false
                problemReported = false
                return "Video stall: ended durationS=${((nowMs - start).coerceAtLeast(0)) / 1000}"
            }
        }
        return null
    }

    fun shouldRecordProblem(activity: String, surface: String): Boolean {
        if (!reported || problemReported || activity != "resumed" || surface != "valid") return false
        problemReported = true
        return true
    }

    fun reset() { lowSince = null; reported = false; problemReported = false }
}

private class DiagnosticVideoMeter {
    data class Reading(val startMs: Long, val received: Double, val shown: Double)
    private var previous: LiveVideoCounters? = null
    private var previousMs = 0L
    fun sample(counters: LiveVideoCounters?, nowMs: Long): Reading? {
        val old = previous
        val start = previousMs
        previous = counters
        previousMs = nowMs
        if (old == null || counters == null || nowMs <= start || counters.received < old.received ||
            counters.rendered < old.rendered) return null
        val seconds = (nowMs - start) / 1000.0
        return Reading(start, (counters.received - old.received) / seconds, (counters.rendered - old.rendered) / seconds)
    }
}

/** Uses the existing main looper, and reads only cached state and decoder counters. */
internal object DiagnosticHeartbeat {
    private const val SAMPLE_MS = 5_000L
    private const val HEARTBEAT_MS = 60_000L
    private const val MAIN = 110
    private const val CLUSTER = 111
    private var context: Context? = null
    private var handler: Handler? = null
    private var audio: CarAudioDiagnostics? = null
    private var owner: Any? = null
    private var sink: (() -> AndroidMediaSink?)? = null
    private var surface: (() -> Boolean)? = null
    private var activity = "stopped"
    private var lastHeartbeat = 0L
    private val mainMeter = DiagnosticVideoMeter()
    private val clusterMeter = DiagnosticVideoMeter()
    private val stall = VideoStallTracker()
    private var mainFps = -1
    private var clusterFps = -1
    private val noFrames = LiveVideoCounters(0, 0, 0, 0)
    private val tick = object : Runnable {
        override fun run() {
            val app = context ?: return
            val now = SystemClock.elapsedRealtime()
            val active = CarPlayBackgroundSession.active
            val current = if (active) sink?.invoke() ?: CarPlayBackgroundSession.snapshot()?.sink else null
            fun counters(type: Int): LiveVideoCounters? = current?.let {
                it.liveVideoCounters(type) ?: if (it.isScreenStreamActive(type)) noFrames else null
            }
            val main = mainMeter.sample(counters(MAIN), now)
            val cluster = clusterMeter.sample(counters(CLUSTER), now)
            mainFps = main?.shown?.roundToInt() ?: -1
            clusterFps = cluster?.shown?.roundToInt() ?: -1
            val valid = runCatching { surface?.invoke() == true }.getOrDefault(false)
            if (main == null) stall.reset() else {
                val surfaceState = if (valid) "valid" else "none"
                stall.sample(main.startMs, now, main.received, main.shown, activity, surfaceState)?.let {
                    SessionDiagnosticLog.append(app, it)
                }
                if (stall.shouldRecordProblem(activity, surfaceState)) {
                    LastConnectionProblem.record(app, LastConnectionProblem.Kind.VIDEO_STALL)
                }
            }
            if (now - lastHeartbeat >= HEARTBEAT_MS) {
                val car = audio ?: return
                val (keys, age) = WheelKeyLog.heartbeat(System.currentTimeMillis())
                val carAudio = runCatching {
                    if (AirPlayPersistence.loadCarBluetoothAudio(app)) "bluetooth" else "carplay"
                }.getOrDefault("unknown")
                SessionDiagnosticLog.append(app, HeartbeatSnapshot(activity, if (active) "active" else "none", mainFps,
                    clusterFps, if (valid) "valid" else "none", car.adapterState, car.a2dpState, car.a2dpPlaying,
                    car.hfpState, car.playback, carAudio, keys, age).line())
                lastHeartbeat = now
            }
            handler?.postDelayed(this, SAMPLE_MS)
        }
    }

    fun start(context: Context, handler: Handler) {
        val app = context.applicationContext
        if (this.context === app) return
        this.handler?.removeCallbacks(tick)
        audio?.stop()
        owner = null
        sink = null
        surface = null
        activity = "stopped"
        stall.reset()
        mainMeter.sample(null, 0)
        clusterMeter.sample(null, 0)
        this.context = app
        this.handler = handler
        SessionDiagnosticLog.configure(app)
        audio = CarAudioDiagnostics(app) { SessionDiagnosticLog.append(app, it) }.also { it.start() }
        CarPlayMediaKeys.diagnosticPlaybackListener = { playing -> handler.post { audio?.playbackChanged(playing) } }
        lastHeartbeat = SystemClock.elapsedRealtime()
        handler.postDelayed(tick, SAMPLE_MS)
    }

    fun attach(owner: Any, sink: () -> AndroidMediaSink?, surface: () -> Boolean) {
        this.owner = owner
        this.sink = sink
        this.surface = surface
    }
    fun state(owner: Any, value: String) { if (this.owner === owner) activity = value }
    fun detach(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        sink = null
        surface = null
        activity = "stopped"
    }
}
