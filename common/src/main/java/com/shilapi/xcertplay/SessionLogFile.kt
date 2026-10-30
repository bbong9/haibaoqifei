package com.shilapi.xcertplay

import java.io.Closeable
import java.io.File

/** Bounded, private diagnostics. Each write is redacted before touching storage. */
internal class SessionLogFile(val file: File) : Closeable {
    private val lock = fileLock(file)
    @Volatile private var closed = false
    // Raw lines queue here; the writer thread redacts, rotates and writes them in order.
    private data class Entry(val line: String, val atMillis: Long)
    private val writer: BoundedDiagnosticWriter<Entry> = BoundedDiagnosticWriter<Entry>(MAX_PENDING, ::drain)
    private var reportedDrops = 0L

    fun reset(header: String) = synchronized(lock) {
        if (!closed) {
            file.parentFile?.mkdirs()
            rotate()
            file.writeText("")
            write(header)
        }
    }

    /** Never waits for disk. Under overload the oldest queued line is discarded. */
    fun append(line: String) {
        if (!closed && !DiagnosticRedactor.neverExported(line)) writer.enqueue(Entry(line, System.currentTimeMillis()))
    }

    /** Writer thread only. Lines queued before [close] are still written. */
    private fun drain(entry: Entry): Unit = synchronized(lock) {
        val dropped = writer.droppedCount
        if (dropped > reportedDrops) {
            write("${dropped - reportedDrops} lines dropped")
            reportedDrops = dropped
        }
        write(DiagnosticLogTime.line(entry.line, entry.atMillis))
    }

    /** Synchronous write for callers that are already off the hot path. */
    fun appendNow(line: String) = synchronized(lock) { if (!closed) write(line) }

    private fun write(line: String) = writeFile(file, line)

    internal fun awaitIdle(timeoutMillis: Long): Boolean = writer.awaitIdle(timeoutMillis)

    private fun rotate() = rotateFile(file)
    /** Refuses new lines and waits a bounded time; the writer thread keeps draining what is queued. */
    override fun close() {
        closed = true
        writer.close()
        writer.awaitIdle(CLOSE_FLUSH_MILLIS)
    }
    companion object {
        private val fileLocks = mutableMapOf<String, Any>()
        private fun fileLock(file: File): Any = synchronized(fileLocks) {
            fileLocks.getOrPut(file.absolutePath) { Any() }
        }
        internal fun appendStandalone(file: File, line: String) = synchronized(fileLock(file)) { writeFile(file, line) }

        private fun writeFile(file: File, line: String) {
            val safe = DiagnosticRedactor.redact(DiagnosticLogTime.line(line)) ?: return
            runCatching {
                file.parentFile?.let { if (!it.exists()) it.mkdirs() }
                val bytes = (safe + "\n").toByteArray()
                if (file.length() > MAX_BYTES) {
                    rotateFile(file)
                    file.writeText("")
                }
                file.appendBytes(bytes)
            }
        }

        private fun rotateFile(file: File) {
            if (!file.exists() || file.length() == 0L) return
            for (index in ARCHIVE_NAMES.lastIndex downTo 1) {
                val source = File(file.parentFile, ARCHIVE_NAMES[index - 1])
                val destination = File(file.parentFile, ARCHIVE_NAMES[index])
                if (source.exists()) source.copyTo(destination, overwrite = true)
            }
            file.copyTo(File(file.parentFile, ARCHIVE_NAMES.first()), overwrite = true)
        }
        const val MAX_BYTES = 512 * 1024L
        private const val MAX_PENDING = 4096
        private const val CLOSE_FLUSH_MILLIS = 250L
        private val ARCHIVE_NAMES = listOf("previous.log") + (2..7).map { "previous-$it.log" }
        val REPORT_NAMES = ARCHIVE_NAMES.reversed() + "diplay.log"
    }
}
