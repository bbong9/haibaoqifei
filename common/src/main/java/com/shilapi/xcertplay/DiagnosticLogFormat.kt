package com.shilapi.xcertplay

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object DiagnosticLogTime {
    private val prefix = Regex("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}(?=\\s|$)")

    fun format(millis: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(millis))

    fun offset(millis: Long): String = SimpleDateFormat("XXX", Locale.US).format(Date(millis)).let {
        if (it == "Z") "+00:00" else it
    }

    fun line(message: String, millis: Long = System.currentTimeMillis()): String =
        if (prefix.containsMatchIn(message)) message else "${format(millis)}  $message"

    fun timestamp(line: String?): String? = line?.let { prefix.find(it)?.value }
}

internal data class DiagnosticLogSection(
    val label: String,
    val lines: List<String>,
    val start: String,
    val end: String,
    val pid: String,
    val version: String,
) {
    fun heading(): String = "--- $label  $start → $end  pid=$pid lines=${lines.size} ---"
    fun directoryEntry(): String = "$label  $start → $end  pid=$pid version=$version lines=${lines.size}"
}

internal object DiagnosticLogSections {
    private val pid = Regex("(?:^|\\s)pid=(\\d+)(?:\\s|$)")
    private val version = Regex("(?:^|\\s)version=([A-Za-z0-9._/-]+)(?:\\s|$)")

    fun read(context: Context): List<DiagnosticLogSection> {
        val result = mutableListOf<DiagnosticLogSection>()
        for (startup in listOf(true, false)) {
            for (name in SessionLogFile.REPORT_NAMES) {
                val actual = if (startup && name == "diplay.log") "timeline.log" else name
                val label = if (startup) "startup/$actual" else actual
                val file = File(context.filesDir, if (startup) "logs/startup/$actual" else "logs/$actual")
                if (file.isFile) result += read(label, file)
            }
        }
        return result
    }

    internal fun read(label: String, file: File): DiagnosticLogSection {
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        fun unique(pattern: Regex): String {
            val values = lines.mapNotNull { pattern.find(it)?.groupValues?.get(1) }.distinct()
            return when (values.size) { 0 -> "unknown"; 1 -> values.single(); else -> "mixed" }
        }
        return DiagnosticLogSection(
            label, lines,
            DiagnosticLogTime.timestamp(lines.firstOrNull()) ?: "unknown",
            DiagnosticLogTime.timestamp(lines.lastOrNull()) ?: "unknown",
            unique(pid), unique(version),
        )
    }

    fun chronological(sections: List<DiagnosticLogSection>): List<DiagnosticLogSection> =
        sections.sortedWith(compareBy<DiagnosticLogSection>({ it.start == "unknown" }, { it.start }))
}
