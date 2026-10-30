package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.shilapi.xcertplay.host.R

internal data class StartupAttemptSummary(
    val bootCount: Int?,
    val generation: Int?,
    val boot: Long?,
    val launch: Long?,
    val decide: Long?,
    val mfi: Long?,
    val cleanup: Long?,
    val wifi: Long?,
    val bluetooth: Long?,
    val iap2: Long?,
    val iphoneJoin: Long?,
    val session: Long?,
    val bonjour: Long?,
    val bootToFrame: Long?,
    val connectToFrame: Long?,
    val retries: Int,
    val failure: String?,
) {
    val slowest: Pair<String, Long>?
        get() = listOf(
            "boot" to boot, "launch" to launch, "decide" to decide, "mfi" to mfi,
            "cleanup" to cleanup, "wifi" to wifi, "bluetooth" to bluetooth, "iap2" to iap2,
            "iphoneJoin" to iphoneJoin, "session" to session,
        ).mapNotNull { (code, value) -> value?.let { code to it } }.maxByOrNull { it.second }

    fun formatLine(index: Int): String {
        fun value(number: Long?) = number?.toString() ?: "-"
        val slow = slowest?.let { "${it.first}:${it.second}" } ?: "-"
        return "#$index boot=${bootCount ?: "-"} bootToFrame=${value(bootToFrame)} " +
            "connectToFrame=${value(connectToFrame)} slowest=$slow | " +
            "boot=${value(boot)} launch=${value(launch)} decide=${value(decide)} mfi=${value(mfi)} " +
            "cleanup=${value(cleanup)} wifi=${value(wifi)} bluetooth=${value(bluetooth)} " +
            "iap2=${value(iap2)} iphoneJoin=${value(iphoneJoin)} session=${value(session)} " +
            "bonjour=${value(bonjour)} gen=${generation ?: "-"} retries=$retries failure=${failure ?: "-"}"
    }
}

/** Startup decisions use their own rotation directory, independent of host sessions. */
internal object StartupTimeline {
    private var target: SessionLogFile? = null
    private val whitespace = Regex("\\s+")

    @Synchronized fun event(context: Context, event: String, vararg fields: Pair<String, Any?>) {
        runCatching {
            val file = target ?: run {
                val app = context.applicationContext
                val info = app.packageManager.getPackageInfo(app.packageName, 0)
                @Suppress("DEPRECATION")
                val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
                val boot = runCatching {
                    Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT)
                }.getOrDefault(-1)
                val log = SessionLogFile(File(app.filesDir, "logs/startup/timeline.log").apply {
                    parentFile?.mkdirs()
                })
                AsyncDiagnosticLog.append(
                    log,
                    formatLine(
                        SystemClock.elapsedRealtime(), Process.myPid(), "PROCESS_START",
                        listOf(
                            "boot" to boot,
                            "date" to SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()),
                            "version" to "${info.versionName ?: "none"}/$code",
                        ),
                    ),
                )
                target = log
                log
            }
            AsyncDiagnosticLog.append(file, formatLine(SystemClock.elapsedRealtime(), Process.myPid(), event, fields.toList()))
        }
    }

    internal fun formatLine(upMs: Long, pid: Int, event: String, fields: List<Pair<String, Any?>>): String = buildString {
        append("up=").append(upMs).append(" pid=").append(pid).append(' ').append(event)
        for ((key, value) in fields) {
            append(' ').append(key).append('=').append(value?.toString()?.replace(whitespace, "_") ?: "none")
        }
    }

    private data class Entry(val up: Long, val event: String, val fields: Map<String, String>, val order: Int)
    private val linePattern = Regex("(?:^|\\s)up=(\\d+)\\s+pid=\\d+\\s+(\\S+)(?:\\s+(.*))?$")

    internal fun summarize(lines: List<String>): List<StartupAttemptSummary> {
        val groups = mutableListOf<MutableList<Entry>>()
        lines.forEachIndexed { index, line ->
            val match = linePattern.find(line) ?: return@forEachIndexed
            val up = match.groupValues[1].toLongOrNull() ?: return@forEachIndexed
            val fields = match.groupValues[3].split(' ').mapNotNull { part ->
                val split = part.indexOf('=')
                if (split <= 0) null else part.substring(0, split) to part.substring(split + 1)
            }.toMap()
            val entry = Entry(up, match.groupValues[2], fields, index)
            if (entry.event == "PROCESS_START") groups.add(mutableListOf())
            groups.lastOrNull()?.add(entry)
        }
        return groups.takeLast(3).map(::summarizeGroup)
    }

    private fun summarizeGroup(entries: List<Entry>): StartupAttemptSummary {
        val attempts = mutableListOf<MutableList<Entry>>()
        for (entry in entries) {
            val gen = entry.fields["gen"]?.toIntOrNull() ?: continue
            if (entry.event == "CP_W_BEGIN") {
                attempts.add(mutableListOf(entry))
            } else {
                attempts.lastOrNull { it.first().fields["gen"]?.toIntOrNull() == gen }?.add(entry)
            }
        }
        val chosen = attempts.filter { attempt -> attempt.any { it.event == "FIRST_FRAME" } }
            .maxByOrNull { attempt -> attempt.last { it.event == "FIRST_FRAME" }.order }
            ?: attempts.lastOrNull()
        fun first(source: List<Entry>, event: String): Long? = source.firstOrNull { it.event == event }?.up
        fun delta(start: Long?, end: Long?): Long? =
            if (start != null && end != null && end >= start) end - start else null
        val wireless = chosen.orEmpty()
        val frame = first(wireless, "FIRST_FRAME")
        val bootReceived = first(entries, "BOOT_RECEIVED")
        val ui = first(entries, "UI_CREATE")
        val firstUiOrHost = listOfNotNull(ui, first(entries, "HOST_CREATE")).minOrNull()
        val connect = first(entries, "CONNECT_REQUEST")
        val mfiStart = first(entries, "CP_MFI_START")
        val firstConnect = listOfNotNull(connect, mfiStart).minOrNull()
        val begin = first(wireless, "CP_W_BEGIN")
        val cleanup = first(wireless, "CP_W_CLEANUP_DONE")
        val link = first(wireless, "CP_W_LINK_READY")
        val btStart = first(wireless, "CP_W_BT_START")
        val btConnected = first(wireless, "CP_W_BT_CONNECTED")
        val wifiInfo = first(wireless, "CP_W_WIFI_INFO_SENT")
        val tcp = first(wireless, "CP_W_FIRST_TCP")
        val process = entries.first()
        val failure = entries.lastOrNull { it.event == "CP_W_FAIL" }?.fields?.get("failure")
            ?.takeIf { it.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,79}")) }
        return StartupAttemptSummary(
            bootCount = process.fields["boot"]?.toIntOrNull(),
            generation = wireless.firstOrNull()?.fields?.get("gen")?.toIntOrNull(),
            boot = bootReceived,
            launch = delta(bootReceived, firstUiOrHost),
            decide = delta(ui, connect),
            mfi = delta(mfiStart, first(entries, "CP_MFI_READY")),
            cleanup = delta(begin, cleanup),
            wifi = delta(cleanup, link),
            bluetooth = delta(btStart, btConnected),
            iap2 = delta(btConnected, wifiInfo),
            iphoneJoin = delta(wifiInfo, tcp),
            session = delta(tcp, frame),
            bonjour = delta(first(wireless, "CP_W_BONJOUR_START"), first(wireless, "CP_W_BONJOUR_READY")),
            bootToFrame = frame?.takeIf { process.up < 300_000L },
            connectToFrame = delta(firstConnect, frame),
            retries = (attempts.size - 1).coerceAtLeast(0),
            failure = failure,
        )
    }

    /** Call off the main thread: rotated files are read in chronological order. */
    internal fun readLines(context: Context): List<String> = SessionLogFile.REPORT_NAMES.flatMap { name ->
        val timelineName = if (name == "diplay.log") "timeline.log" else name
        val file = File(context.filesDir, "logs/startup/$timelineName")
        runCatching { if (file.isFile) file.readLines() else emptyList() }.getOrDefault(emptyList())
    }

    internal fun awaitIdle(timeoutMillis: Long): Boolean {
        if (!AsyncDiagnosticLog.awaitIdle(timeoutMillis)) return false
        return synchronized(this) { target }?.awaitIdle(timeoutMillis) ?: true
    }

    internal fun renderLastSummary(context: Context, summary: StartupAttemptSummary?): String {
        if (summary == null) return context.getString(R.string.settings_startup_no_record)
        val slowest = summary.slowest
        if (slowest == null && summary.bootToFrame == null && summary.connectToFrame == null) {
            return context.getString(R.string.settings_startup_no_record)
        }
        fun seconds(value: Long?): String = value?.let { String.format(Locale.getDefault(), "%.1f", it / 1_000.0) } ?: "-"
        return context.getString(R.string.settings_startup_summary,
            seconds(summary.bootToFrame), seconds(summary.connectToFrame), slowest?.first ?: "-", seconds(slowest?.second))
    }
}
