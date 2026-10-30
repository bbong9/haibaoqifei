package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ClusterCardDiagnosticsTest {
    @Test fun transitionsContainOnlyEnumsAndNumbersAndSurviveRedaction() {
        val lines = mutableListOf<String>()
        val owner = Any()
        ClusterCardDiagnostics.listener = { lines += it }
        try {
            ClusterCardDiagnostics.appearance(true, 2, ClusterCardDiagnostics.NightSource.SCHEDULE)
            ClusterCardDiagnostics.music(owner, ClusterCardDiagnostics.MusicMode.LYRICS, 2, 3,
                ClusterLyricState.Field.ARTIST, ClusterCardDiagnostics.HideReason.NONE, 1234, 567,
                ClusterCardDiagnostics.Fit.SHRUNK)
            val expected = "Cluster cards: night=night themeSetting=night musicMode=lyrics lyricLines=2 titleChanges=3 nightSource=schedule lyricField=artist"
            assertEquals(expected, ClusterCardDiagnostics.report())
            assertEquals(expected, DiagnosticRedactor.redact(expected))
            assertTrue(lines.contains(expected))
            assertTrue(ClusterCardDiagnostics.musicReport().contains("cardVisible=lyrics hideReason=none lyricConfirmedAfterMs=1234 coverDelayMs=567"))
            ClusterCardDiagnostics.music(owner, ClusterCardDiagnostics.MusicMode.COVER, 0, 0,
                ClusterLyricState.Field.NONE, ClusterCardDiagnostics.HideReason.NONE, -1, -1,
                ClusterCardDiagnostics.Fit.NORMAL)
            assertTrue(lines.any { it.contains("musicMode=cover lyricLines=0 titleChanges=0") })
            ClusterCardDiagnostics.remove(owner)
            assertTrue(lines.any { it.contains("musicMode=none lyricLines=0 titleChanges=0") })
            for (variant in ClusterCardDiagnostics.TurnVariant.values()) for (night in listOf(false, true)) {
                ClusterCardDiagnostics.turn(owner, variant, night)
                assertEquals(ClusterCardDiagnostics.turnReport(), DiagnosticRedactor.redact(ClusterCardDiagnostics.turnReport()!!))
            }
            for (reason in ClusterCardDiagnostics.HideReason.values()) for (fit in ClusterCardDiagnostics.Fit.values()) {
                ClusterCardDiagnostics.music(owner, ClusterCardDiagnostics.MusicMode.NONE, 0, 0,
                    ClusterLyricState.Field.NONE, reason, -1, -1, fit)
                assertEquals(ClusterCardDiagnostics.musicReport(), DiagnosticRedactor.redact(ClusterCardDiagnostics.musicReport()))
            }
            for (line in lines) {
                assertEquals(line, DiagnosticRedactor.redact(line))
                for (part in line.substringAfter(": ").split(' ')) {
                    val (key, value) = part.split('=', limit = 2)
                    assertFalse(key.endsWith("name", ignoreCase = true))
                    assertFalse(listOf("ssid", "pass", "token", "host").any { key.contains(it, ignoreCase = true) })
                    assertTrue("invalid value in $line", value.matches(Regex("[A-Za-z]+|-?[0-9]+")))
                }
            }
        } finally {
            ClusterCardDiagnostics.listener = null
            ClusterCardDiagnostics.remove(owner)
        }
    }

    @Test fun countsDoNotLogPerLineAndSummaryRunsEveryMinute() {
        val lines = mutableListOf<String>()
        val owner = Any()
        val handler = Handler(Looper.getMainLooper())
        ClusterCardDiagnostics.summary()
        ClusterCardDiagnostics.listener = { lines += it }
        try {
            ClusterCardDiagnostics.music(owner, ClusterCardDiagnostics.MusicMode.LYRICS, 2, 2,
                ClusterLyricState.Field.TITLE, ClusterCardDiagnostics.HideReason.NONE, 900, -1,
                ClusterCardDiagnostics.Fit.NORMAL)
            ClusterCardDiagnostics.turn(owner, ClusterCardDiagnostics.TurnVariant.LANES, false)
            ClusterCardDiagnostics.startSummaries(handler)
            lines.clear()
            repeat(3) {
                ClusterCardDiagnostics.nowPlayingUpdate()
                ClusterCardDiagnostics.lineChange()
                ClusterCardDiagnostics.music(owner, ClusterCardDiagnostics.MusicMode.LYRICS, 2, 3 + it,
                    ClusterLyricState.Field.TITLE, ClusterCardDiagnostics.HideReason.NONE, 900, -1,
                    ClusterCardDiagnostics.Fit.NORMAL)
            }
            assertTrue(lines.isEmpty())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(59))
            assertTrue(lines.isEmpty())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertEquals(3, lines.size)
            assertTrue(lines.any { it.contains("nowPlayingUpdates=3 lineChanges=3") })
            assertTrue(lines.all { DiagnosticRedactor.redact(it) == it })
            lines.clear()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
            assertTrue(lines.any { it.contains("nowPlayingUpdates=0 lineChanges=0") })
        } finally {
            ClusterCardDiagnostics.stopSummaries(handler)
            ClusterCardDiagnostics.listener = null
            ClusterCardDiagnostics.remove(owner)
        }
    }

    @Test fun multipleViewsWithDifferentFitDoNotLogEveryLyricUpdate() {
        val lines = mutableListOf<String>()
        val first = Any()
        val second = Any()
        ClusterCardDiagnostics.listener = { lines += it }
        fun update(owner: Any, fit: ClusterCardDiagnostics.Fit, count: Int) {
            ClusterCardDiagnostics.music(owner, ClusterCardDiagnostics.MusicMode.LYRICS, 2, count,
                ClusterLyricState.Field.ARTIST, ClusterCardDiagnostics.HideReason.NONE, 900, 500, fit, 1)
        }
        try {
            update(first, ClusterCardDiagnostics.Fit.NORMAL, 2)
            update(second, ClusterCardDiagnostics.Fit.CLAMPED, 2)
            lines.clear()
            repeat(5) {
                update(first, ClusterCardDiagnostics.Fit.NORMAL, it + 3)
                update(second, ClusterCardDiagnostics.Fit.CLAMPED, it + 3)
            }
            assertTrue(lines.isEmpty())
            assertTrue(ClusterCardDiagnostics.musicReport().contains("fit=clamped"))
        } finally {
            ClusterCardDiagnostics.listener = null
            ClusterCardDiagnostics.remove(first)
            ClusterCardDiagnostics.remove(second)
        }
    }
}
