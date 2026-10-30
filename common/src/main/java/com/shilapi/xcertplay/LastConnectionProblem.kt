package com.shilapi.xcertplay

import android.content.Context
import android.content.res.Configuration
import android.text.format.DateUtils
import androidx.annotation.StringRes
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.ConnectionFailureCause
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal sealed interface ConnectionProblem {
    data class Cause(val cause: ConnectionFailureCause) : ConnectionProblem
    data class Startup(val failure: WirelessStartupFailure) : ConnectionProblem
    data class UsbNotListed(val otherDevice: Boolean) : ConnectionProblem

    @StringRes
    fun line(): Int = when (this) {
        is Cause -> ConnectionFailureGuidance.stage(cause)
        is Startup -> when (failure) {
            WirelessStartupFailure.HOTSPOT_NOT_READY,
            WirelessStartupFailure.HOTSPOT_CONFIGURATION -> R.string.hotspot_network_not_ready
            WirelessStartupFailure.FIRST_TCP_TIMEOUT -> R.string.first_tcp_timeout
            WirelessStartupFailure.WIFI_DIRECT_BESIDE_HOTSPOT -> R.string.wifi_direct_refused_beside_hotspot
            WirelessStartupFailure.BLUETOOTH_UNUSABLE -> R.string.connection_help_bluetooth_unusable
        }
        is UsbNotListed -> if (otherDevice) R.string.car_sees_usb_device_not_iphone else R.string.car_sees_no_usb_device
    }

    val token: String get() = when (this) {
        is Cause -> "cause:${cause.name}"
        is Startup -> "startup:${failure.name}"
        is UsbNotListed -> if (otherDevice) "usb:other" else "usb:none"
    }
}

internal object LastConnectionProblem {
    data class Record(val problem: ConnectionProblem, val atMillis: Long)
    enum class Kind(val value: String) {
        HOST_FAILED("hostFailed"), DECODER_FAILED("decoderFailed"),
        RFCOMM_EOF_BEFORE_SESSION("rfcommEofBeforeSession"), VIDEO_STALL("videoStall"),
        PROCESS_KILLED("processKilled"),
    }
    enum class Mark(val value: String) { AUDIO("audio"), VIDEO("video"), LINK("link"), OTHER("other") }
    data class Event(val atMillis: Long, val kind: String)

    private const val PREFS = "diplay_last_problem"
    private const val KEY_PROBLEM = "problem"
    private const val KEY_AT = "at"
    private const val KEY_EVENTS = "diagnostic_events"
    private const val KEY_MARKS = "diagnostic_marks"
    private const val MAX_HISTORY = 5
    private const val PROBLEM_COALESCE_MILLIS = 60_000L

    fun follow(context: Context, status: CarPlayStatus, recordDiagnostic: Boolean = true) {
        if (recordDiagnostic && status is CarPlayStatus.Failed) record(context, Kind.HOST_FAILED)
        val problem = when (status) {
            is CarPlayStatus.Failed -> status.knownCause?.let(ConnectionProblem::Cause)
                ?: status.startupFailure?.let(ConnectionProblem::Startup)
                ?: return clearConnection(context)
            is CarPlayStatus.IphoneNotListed -> ConnectionProblem.UsbNotListed(status.nonHubDeviceListed)
            else -> return
        }
        prefs(context).edit().putString(KEY_PROBLEM, problem.token).putLong(KEY_AT, System.currentTimeMillis()).apply()
    }

    fun clear(context: Context) = prefs(context).edit().clear().apply()

    fun clearConnection(context: Context) = prefs(context).edit().remove(KEY_PROBLEM).remove(KEY_AT).apply()

    @Synchronized fun record(context: Context, kind: Kind, atMillis: Long = System.currentTimeMillis()) {
        appendEvent(context, KEY_EVENTS, Event(atMillis, kind.value), Kind.entries.map { it.value }.toSet())
    }

    @Synchronized fun mark(context: Context, kind: Mark, atMillis: Long = System.currentTimeMillis()) {
        appendEvent(context, KEY_MARKS, Event(atMillis, kind.value), Mark.entries.map { it.value }.toSet())
    }

    @Synchronized fun recentProblems(context: Context): List<Event> =
        readEvents(context, KEY_EVENTS, Kind.entries.map { it.value }.toSet())

    @Synchronized fun recentMarks(context: Context): List<Event> =
        readEvents(context, KEY_MARKS, Mark.entries.map { it.value }.toSet())

    private fun appendEvent(context: Context, key: String, event: Event, allowed: Set<String>) {
        val existing = readEvents(context, key, allowed)
        val matches = if (key == KEY_EVENTS) existing.filter {
            it.kind == event.kind && kotlin.math.abs(it.atMillis - event.atMillis) <= PROBLEM_COALESCE_MILLIS
        } else emptyList()
        val latest = (matches + event).maxBy { it.atMillis }
        val events = (existing.filterNot { it in matches } + latest).sortedBy { it.atMillis }.takeLast(MAX_HISTORY)
        val array = JSONArray()
        events.forEach { array.put(JSONObject().put("at", it.atMillis).put("kind", it.kind)) }
        prefs(context).edit().putString(key, array.toString()).apply()
    }

    private fun readEvents(context: Context, key: String, allowed: Set<String>): List<Event> = runCatching {
        val array = JSONArray(prefs(context).getString(key, "[]"))
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val at = item.optLong("at", -1)
            val kind = item.optString("kind")
            if (at >= 0 && kind in allowed) Event(at, kind) else null
        }.sortedBy { it.atMillis }.takeLast(MAX_HISTORY)
    }.getOrDefault(emptyList())

    fun load(context: Context): Record? {
        val prefs = prefs(context)
        val token = prefs.getString(KEY_PROBLEM, null) ?: return null
        val problem = parse(token)
        val at = prefs.getLong(KEY_AT, -1)
        if (problem == null || at < 0) {
            clearConnection(context)
            return null
        }
        return Record(problem, at)
    }

    fun homeLine(context: Context): String? {
        val record = load(context) ?: return null
        val time = DateUtils.formatSameDayTime(record.atMillis, System.currentTimeMillis(), DateFormat.SHORT, DateFormat.SHORT)
        return context.getString(R.string.last_connection_attempt, time, context.getString(record.problem.line()))
    }

    fun report(context: Context): String {
        val diagnostic = recentProblems(context).lastOrNull()?.let {
            "Last problem: ${DiagnosticLogTime.format(it.atMillis).substringBeforeLast('.')} kind=${it.kind}"
        }
        val record = load(context) ?: return diagnostic ?: "Last problem: none recorded"
        val english = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) },
        ).resources
        val at = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(record.atMillis))
        val detail = "Last problem: ${english.getString(record.problem.line())} (${record.problem.token}) at $at"
        return if (diagnostic == null) detail else "$diagnostic\n$detail"
    }

    private fun parse(token: String): ConnectionProblem? {
        val value = token.substringAfter(':')
        return when (token.substringBefore(':')) {
            "cause" -> ConnectionFailureCause.entries.firstOrNull { it.name == value }?.let(ConnectionProblem::Cause)
            "startup" -> WirelessStartupFailure.entries.firstOrNull { it.name == value }?.let(ConnectionProblem::Startup)
            "usb" -> when (value) {
                "none" -> ConnectionProblem.UsbNotListed(false)
                "other" -> ConnectionProblem.UsbNotListed(true)
                else -> null
            }
            else -> null
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
