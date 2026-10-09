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
}
