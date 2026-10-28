package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.hud.BydOutputSettings
import java.io.File
import java.util.concurrent.Executors

/** A bounded, redacted record of the cluster's display and composition state. */
internal object ClusterSnapshot {
    private const val FILE = "cluster-snapshot.txt"
    private const val MAX_BYTES = 16 * 1024
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "cluster-snapshot").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    internal data class WindowState(
        val display: Int?,
        val confirmed: Boolean,
        val streamActive: Boolean,
        val surfaceValid: Boolean,
        val framesPresented: Long,
        val stockMapHold: String,
    )

    /** Called on the main thread; all ADB and file operations run on the worker. */
    fun captureAsync(context: Context, trigger: String, onComplete: () -> Unit = {}) {
        val app = context.applicationContext
        val state = WindowState(
            ClusterActivityOutput.diagnosticDisplay(), ClusterActivityOutput.hasConfirmedRoute(),
            ClusterActivityOutput.streamActive, ClusterActivityOutput.surface?.isValid == true,
            ClusterActivityOutput.framesPresented, BydOutputSettings.oemClusterHold(app).name,
        )
        val capturedAt = SystemClock.elapsedRealtime()
        worker.execute {
            val lines = runCatching {
                LocalAdb(AdbKeys.load(app)).use { adb ->
                    collect(trigger, capturedAt, adb.connect(mayAsk = false), app.packageName, state, adb::shell)
                }
            }.getOrElse {
                collect(trigger, capturedAt, LocalAdb.Access.UNREACHABLE, app.packageName, state) { null }
            }
            if (runCatching { save(app, lines) }.isSuccess) main.post(onComplete)
        }
    }

    internal fun collect(
        trigger: String,
        capturedAt: Long,
        access: LocalAdb.Access,
        packageName: String,
        state: WindowState,
        shell: (String) -> String?,
    ): List<String> {
        require(trigger in setOf("auto5s", "auto30s", "button"))
        val lines = mutableListOf("snapshot trigger=$trigger capturedAtUptimeMs=$capturedAt", "adbAccess=$access")
        if (access != LocalAdb.Access.READY) return lines
        lines += displays(shell("dumpsys display").orEmpty())
        lines += activities(shell("dumpsys activity activities").orEmpty(), packageName)
        lines += layers(shell("dumpsys SurfaceFlinger").orEmpty(), packageName)
        val enabled = Regex("(?m)^\\s*User 0:.*?\\benabled=(\\d+)\\b")
            .find(shell("pm dump com.byd.automap").orEmpty())?.groupValues?.get(1) ?: "-"
        lines += "diplayWindow display=${state.display ?: "-"} confirmed=${state.confirmed} " +
            "streamActive=${state.streamActive} surfaceValid=${state.surfaceValid} " +
            "framesPresented=${state.framesPresented} stockMapHold=${state.stockMapHold} automapEnabled=$enabled"
        return lines
    }

    internal fun displays(dump: String): List<String> {
        val ids = mutableSetOf<Int>()
        return dump.lineSequence().mapNotNull { line ->
            val info = line.substringAfter("mBaseDisplayInfo=DisplayInfo{", "")
            if (info.isEmpty()) return@mapNotNull null
            val id = Regex("\\bdisplayId\\s+(\\d+)").find(info)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            if (!ids.add(id)) return@mapNotNull null
            val label = Regex("^\\\"(.*?)(?:,\\s*displayId\\s+\\d+\\\"|\\\")")
                .find(info)?.groupValues?.get(1) ?: "-"
            val size = Regex("\\breal\\s+(\\d+)\\s*x\\s*(\\d+)").find(info)?.let {
                "${it.groupValues[1]}x${it.groupValues[2]}"
            } ?: "-"
            val flags = Regex("\\bflags(?:=|\\s+)([^,}]+)", RegexOption.IGNORE_CASE)
                .find(info)?.groupValues?.get(1)?.trim()?.ifEmpty { "-" } ?: "-"
            val owner = Regex("\\bowner\\s+([A-Za-z0-9_.]+)").find(info)?.groupValues?.get(1)
                ?.takeUnless { it == "null" } ?: "-"
            "display id=$id label=$label size=$size flags=$flags owner=$owner"
        }.toList()
    }

    internal fun activities(dump: String, packageName: String): List<String> {
        val stacks = linkedMapOf<Int, MutableList<String>>()
        var display: Int? = null
        for (line in dump.lineSequence()) {
            val header = Regex("^\\s*Display #(\\d+) \\(activities from top to bottom\\):\\s*$").matchEntire(line)
            if (header != null) {
                display = header.groupValues[1].toIntOrNull()
                display?.let { stacks.getOrPut(it) { mutableListOf() } }
                continue
            }
            if (line.isNotEmpty() && !line.first().isWhitespace()) display = null
            val stack = stacks[display] ?: continue
            if (stack.size >= 6) continue
            val component = Regex("^\\s{4,}\\* Hist #\\d+: ActivityRecord\\{.*?\\bu\\d+\\s+([A-Za-z0-9_.]+/[A-Za-z0-9_.$]+)")
                .find(line)?.groupValues?.get(1) ?: continue
            val pkg = component.substringBefore('/')
            stack += if (pkg == packageName || pkg.startsWith("com.byd.") || pkg.startsWith("com.xdja.") ||
                pkg.startsWith("com.android.") || pkg.startsWith("android")) component else "other"
        }
        return stacks.map { (id, stack) -> "stack display=$id top=${stack.joinToString(",")}" }
    }

    internal fun layers(dump: String, packageName: String): List<String> {
        val result = mutableListOf<String>()
        val layerStart = Regex("^\\s*\\+\\s+\\S*Layer\\b")
        val header = Regex("^\\s*\\+\\s+\\S*Layer\\s*\\((.*)\\)\\s*$")
        val markers = listOf(packageName, "com.byd.automap", "automap", "MeterActivity", "xdja", "fission")
        var label: String? = null
        val details = StringBuilder()
        fun safeLabel(value: String): String {
            val truncated = value.take(120)
            DiagnosticRedactor.redact(truncated)?.let { return it.take(120) }
            val start = markers.map { value.indexOf(it, ignoreCase = true) }.filter { it >= 0 }.minOrNull()
                ?: return "-"
            val component = Regex("^[A-Za-z0-9_./#@:-]+").find(value.substring(start))?.value ?: return "-"
            return DiagnosticRedactor.redact(component)?.take(120) ?: "-"
        }
        fun addLayer() {
            val current = label ?: return
            if (result.size >= 40) return
            val text = details.toString()
            fun field(key: String) = Regex("\\b$key\\s*=\\s*([^,\\s}]+)", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.get(1)
            fun integer(key: String) = field(key)?.takeIf { value ->
                value.toLongOrNull() != null || runCatching { java.lang.Long.decode(value) }.isSuccess
            }
            val hidden = field("isHidden")?.lowercase()?.let {
                when (it) { "true", "1" -> true; "false", "0" -> false; else -> null }
            } ?: field("flags")?.let { value ->
                runCatching { java.lang.Long.decode(value) and 1L != 0L }.getOrNull()
            }
            val visible = hidden?.let { (!it).toString() } ?: "-"
            val alpha = field("alpha")?.takeIf { it.toFloatOrNull()?.isFinite() == true } ?: "-"
            result += "layer label=${safeLabel(current)} stack=${integer("layerStack") ?: "-"} " +
                "z=${integer("z") ?: "-"} visible=$visible alpha=$alpha"
        }
        for (line in dump.lineSequence()) {
            if (layerStart.containsMatchIn(line)) {
                addLayer()
                val candidate = header.matchEntire(line)?.groupValues?.get(1)
                label = candidate?.takeIf { value -> markers.any { value.contains(it, ignoreCase = true) } }
                details.setLength(0)
            } else if (label != null) details.append(line).append('\n')
        }
        addLayer()
        return result
    }

    @Synchronized
    internal fun save(context: Context, lines: List<String>) {
        val file = File(context.filesDir, FILE)
        val safeLines = lines.mapNotNull(DiagnosticRedactor::redact)
        val window = safeLines.lastOrNull()?.takeIf { it.startsWith("diplayWindow ") }
        val current = StringBuilder()
        var currentBytes = 0
        val windowBytes = window?.let { 1 + it.toByteArray(Charsets.UTF_8).size } ?: 0
        for (line in if (window == null) safeLines else safeLines.dropLast(1)) {
            val entry = (if (current.isEmpty()) "" else "\n") + line
            val length = entry.toByteArray(Charsets.UTF_8).size
            if (currentBytes + length + windowBytes > MAX_BYTES) break
            current.append(entry)
            currentBytes += length
        }
        if (window != null) current.append('\n').append(window)
        val previous = if (file.isFile) file.readText().split("\n\n").filter { it.startsWith("snapshot trigger=") }
            else emptyList()
        val output = StringBuilder(current)
        var bytes = output.toString().toByteArray(Charsets.UTF_8).size
        for (snapshot in previous.take(2)) {
            val safe = snapshot.lineSequence().mapNotNull(DiagnosticRedactor::redact).joinToString("\n")
            val entry = "\n\n$safe"
            val length = entry.toByteArray(Charsets.UTF_8).size
            if (bytes + length > MAX_BYTES) break
            output.append(entry)
            bytes += length
        }
        file.writeText(output.toString())
    }

    @Synchronized
    fun report(context: Context): String = runCatching {
        File(context.filesDir, FILE).takeIf { it.isFile }?.readText()?.ifEmpty { null }
    }.getOrNull() ?: "No cluster snapshot yet."
}
