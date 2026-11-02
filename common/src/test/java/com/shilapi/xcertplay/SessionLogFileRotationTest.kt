package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SessionLogFileRotationTest {
    @get:Rule val temp = TemporaryFolder()

    private val header = "DiPlay log started pid=42 version=0.2.22-seal.29/63"
    private val payload = "event=sample " + "x".repeat(680)

    @Test fun rotationRepeatsTheResetHeaderAndReportDirectoryFindsIt() {
        val file = temp.newFile("diplay.log")
        SessionLogFile(file).use { log ->
            log.reset(header)
            repeat(800) { log.appendNow(payload) }
        }
        val first = file.readLines().first()
        assertNotNull(DiagnosticLogTime.timestamp(first))
        assertTrue(first, first.endsWith(header))
        val section = DiagnosticLogSections.read("diplay.log", file)
        assertEquals("42", section.pid)
        assertEquals("0.2.22-seal.29/63", section.version)
        println("CARD28_ROTATION_BEGIN")
        println(first)
        println(section.directoryEntry())
        println("CARD28_ROTATION_END")
    }

    @Test fun standaloneWriterUsesTheSameRotationHeader() {
        val file = temp.newFile("diplay.log")
        SessionLogFile(file).use { it.reset(header) }
        repeat(800) { SessionLogFile.appendStandalone(file, payload) }
        assertTrue(file.readLines().first(), file.readLines().first().endsWith(header))
        val section = DiagnosticLogSections.read("diplay.log", file)
        assertEquals("42", section.pid)
        assertEquals("0.2.22-seal.29/63", section.version)
    }

    @Test fun idleDiagnosticsRegisterAHeaderBeforeStandaloneRotation() {
        val app = RuntimeEnvironment.getApplication()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = temp.root
        }
        SessionDiagnosticLog.append(context, "event=sample")
        assertTrue(SessionDiagnosticLog.awaitIdle(2_000))
        val file = File(temp.root, "logs/diplay.log")
        repeat(800) { SessionLogFile.appendStandalone(file, payload) }
        val first = file.readLines().first()
        assertTrue(first, first.contains("DiPlay log started pid="))
        val section = DiagnosticLogSections.read("diplay.log", file)
        assertEquals(android.os.Process.myPid().toString(), section.pid)
        assertTrue(section.version, section.version.contains('/'))
    }

    @Test fun startupTimelineRotationHasMetadataWithoutStartingANewAttempt() {
        val app = RuntimeEnvironment.getApplication()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = temp.root
        }
        val previous = ReflectionHelpers.getField<SessionLogFile?>(StartupTimeline, "target")
        ReflectionHelpers.setField(StartupTimeline, "target", null)
        try {
            StartupTimeline.event(context, "BOOT_RECEIVED")
            assertTrue(StartupTimeline.awaitIdle(2_000))
            val log = ReflectionHelpers.getField<SessionLogFile>(StartupTimeline, "target")
            repeat(800) { log.appendNow(payload) }
            val first = log.file.readLines().first()
            assertNotNull(DiagnosticLogTime.timestamp(first))
            val section = DiagnosticLogSections.read("startup/timeline.log", log.file)
            assertEquals(android.os.Process.myPid().toString(), section.pid)
            assertNotEquals("unknown", section.version)
            assertFalse(first, first.contains("PROCESS_START"))
            assertEquals(1, StartupTimeline.readLines(context).count { it.contains("PROCESS_START") })
            log.close()
        } finally {
            ReflectionHelpers.setField(StartupTimeline, "target", previous)
        }
    }
}
