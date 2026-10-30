package com.shilapi.xcertplay

import java.io.File

/** Queued entries retain only their exact file target and a redacted, bounded metadata string. */
internal object AsyncDiagnosticLog {
    private sealed interface Entry {
        data class Session(val target: SessionLogFile, val line: String) : Entry
        data class Standalone(val file: File, val line: String) : Entry
    }
    private val writer = BoundedDiagnosticWriter<Entry> {
        when (it) {
            is Entry.Session -> it.target.appendNow(it.line)
            is Entry.Standalone -> SessionLogFile.appendStandalone(it.file, it.line)
        }
    }

    fun append(target: SessionLogFile?, message: String, nowMillis: Long = System.currentTimeMillis()) {
        if (target == null) return
        val safe = DiagnosticRedactor.redact(message) ?: return
        runCatching { writer.enqueue(Entry.Session(target, DiagnosticLogTime.line(safe, nowMillis))) }
    }

    fun append(file: File, message: String, nowMillis: Long = System.currentTimeMillis()) {
        val safe = DiagnosticRedactor.redact(message) ?: return
        runCatching { writer.enqueue(Entry.Standalone(file, DiagnosticLogTime.line(safe, nowMillis))) }
    }

    internal fun awaitIdle(timeoutMillis: Long): Boolean = writer.awaitIdle(timeoutMillis)
}
