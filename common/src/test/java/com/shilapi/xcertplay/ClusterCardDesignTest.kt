package com.shilapi.xcertplay

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.os.SystemClock
import android.view.View
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import com.shilapi.xcertplay.iap2.message.LaneGuidanceLane
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import java.io.File
import java.time.Duration
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en", manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClusterCardDesignTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun waitFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun size(view: View, width: Int = 1920, height: Int = 720) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    @Before fun reset() {
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        ClusterMusicState.publish(CarPlayNowPlaying(), null)
        idle()
    }

    @Test fun coverExpiresAtEveryAllowedDurationAndNewTrackShowsAgain() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val view = ClusterMusicView(controller.get())
        controller.get().setContentView(view); size(view)
        ClusterMusicSettings.saveEnabled(context, true)
        ClusterMusicSettings.savePermanent(context, false)
        try {
            for (seconds in listOf(3, 5, 8)) {
                ClusterMusicState.publish(CarPlayNowPlaying(), null); idle()
                ClusterMusicSettings.saveTemporarySeconds(context, seconds); idle()
                val song = CarPlayNowPlaying(title = "Episode", artist = "Host", artworkTransferId = 1)
                ClusterMusicState.publish(song, null); idle()
                assertEquals(View.VISIBLE, view.visibility)
                val deadline = org.robolectric.util.ReflectionHelpers.getField<Long>(view, "visibleUntil")
                waitFor((deadline - SystemClock.elapsedRealtime() - 1).coerceAtLeast(0))
                assertEquals(View.VISIBLE, view.visibility)
                waitFor(1)
                assertEquals(View.GONE, view.visibility)
                ClusterMusicState.publish(song.copy(title = "Next episode", artworkTransferId = 2), null); idle()
                assertEquals(View.VISIBLE, view.visibility)
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun expiredCoverBecomesPermanentLyricsWithoutRestartingOnLaterLines() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val view = ClusterMusicView(controller.get())
        controller.get().setContentView(view); size(view)
        ClusterMusicSettings.saveEnabled(context, true)
        ClusterMusicSettings.savePermanent(context, false)
        try {
            for (seconds in listOf(3, 5, 8)) for (artistLyrics in listOf(false, true)) {
                ClusterMusicState.publish(CarPlayNowPlaying(), null); idle()
                ClusterMusicSettings.saveTemporarySeconds(context, seconds); idle()
                val song = CarPlayNowPlaying(title = "Song - Singer", artist = "Singer", album = "Album", artworkTransferId = 1)
                val first = if (artistLyrics) song.copy(artist = "First lyric") else song.copy(title = "First lyric")
                val second = if (artistLyrics) song.copy(artist = "Second lyric") else song.copy(title = "Second lyric")
                val third = if (artistLyrics) song.copy(artist = "Third lyric") else song.copy(title = "Third lyric")
                ClusterMusicState.publish(song, null); idle()
                val deadline = org.robolectric.util.ReflectionHelpers.getField<Long>(view, "visibleUntil")
                waitFor((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                assertEquals(View.GONE, view.visibility)
                ClusterMusicState.publish(first, null); idle()
                assertEquals("first line must not reopen artistLyrics=$artistLyrics", View.GONE, view.visibility)
                ClusterMusicState.publish(second, null); idle()
                assertEquals("lyrics must reopen artistLyrics=$artistLyrics", View.VISIBLE, view.visibility)
                assertEquals(deadline, org.robolectric.util.ReflectionHelpers.getField<Long>(view, "visibleUntil"))
                val widthStarted = org.robolectric.util.ReflectionHelpers.getField<Long>(view, "widthChangedAt")
                size(view)
                val narrow = view.cardLayout().card.width()
                waitFor(200)
                size(view)
                val intermediate = view.cardLayout().card.width()
                waitFor(250)
                size(view)
                val wide = view.cardLayout().card.width()
                assertTrue("width transition $narrow $intermediate $wide", narrow < intermediate && intermediate < wide)
                ClusterMusicState.publish(third, null); idle()
                assertEquals(View.VISIBLE, view.visibility)
                assertEquals(widthStarted, org.robolectric.util.ReflectionHelpers.getField<Long>(view, "widthChangedAt"))
                assertEquals(deadline, org.robolectric.util.ReflectionHelpers.getField<Long>(view, "visibleUntil"))
                waitFor((seconds + 1) * 1_000L)
                assertEquals(View.VISIBLE, view.visibility)
                ClusterMusicSettings.saveEnabled(context, false); idle()
                assertEquals(View.GONE, view.visibility)
                ClusterMusicSettings.saveEnabled(context, true); idle()
                assertEquals(View.VISIBLE, view.visibility)
                ClusterMusicState.publish(song.copy(title = "Next", artworkTransferId = 2), null); idle()
                assertEquals(View.VISIBLE, view.visibility)
                val next = org.robolectric.util.ReflectionHelpers.getField<ClusterMusicState.Snapshot>(view, "snapshot")
                assertFalse(next.lyrics.hasLyrics)
                val nextDeadline = org.robolectric.util.ReflectionHelpers.getField<Long>(view, "visibleUntil")
                waitFor((nextDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                assertEquals(View.GONE, view.visibility)
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun lyricsRemainVisibleLateArtworkDoesNotRestartAndDisconnectClears() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val view = ClusterMusicView(controller.get())
        controller.get().setContentView(view); size(view)
        ClusterMusicSettings.saveEnabled(context, true)
        ClusterMusicSettings.saveTemporarySeconds(context, 3)
        val song = CarPlayNowPlaying(title = "Song", artist = "Singer", album = "Album")
        try {
            ClusterMusicState.publish(song, null); idle()
            val deadline = org.robolectric.util.ReflectionHelpers.getField<Long>(view, "visibleUntil")
            val first = song.copy(title = "First lyric")
            val second = song.copy(title = "Second lyric")
            ClusterMusicState.publish(first, null); idle()
            ClusterMusicState.publish(second, null); idle()
            val before = org.robolectric.util.ReflectionHelpers.getField<ClusterMusicState.Snapshot>(view, "snapshot").lyrics
            assertTrue(before.hasLyrics)
            val art = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            ClusterMusicState.publish(second.copy(artworkTransferId = 7), art); idle()
            val after = org.robolectric.util.ReflectionHelpers.getField<ClusterMusicState.Snapshot>(view, "snapshot").lyrics
            assertEquals(before, after)
            assertEquals(deadline, org.robolectric.util.ReflectionHelpers.getField<Long>(view, "visibleUntil"))
            waitFor(4_000)
            assertEquals(View.VISIBLE, view.visibility)
            ClusterMusicState.publish(second.copy(title = " ", artworkTransferId = 7), null); idle()
            assertEquals("Second lyric", org.robolectric.util.ReflectionHelpers
                .getField<ClusterMusicState.Snapshot>(view, "snapshot").lyrics.current)
            ClusterMusicState.publish(CarPlayNowPlaying(), null); idle()
            assertEquals(View.GONE, view.visibility)
            assertFalse(org.robolectric.util.ReflectionHelpers.getField<ClusterMusicState.Snapshot>(view, "snapshot").lyrics.active)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun lateFirstArtworkDoesNotReopenAnExpiredCoverOrClearLyricHistory() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val view = ClusterMusicView(controller.get())
        controller.get().setContentView(view); size(view)
        ClusterMusicSettings.saveEnabled(context, true)
        ClusterMusicSettings.savePermanent(context, false)
        ClusterMusicSettings.saveTemporarySeconds(context, 3)
        val song = CarPlayNowPlaying(title = "Song", artist = "Singer", album = "Album")
        val first = song.copy(title = "First lyric")
        val art = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        try {
            ClusterMusicState.publish(song, null); idle()
            ClusterMusicState.publish(first, null); idle()
            val deadline = org.robolectric.util.ReflectionHelpers.getField<Long>(view, "visibleUntil")
            waitFor((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            assertEquals(View.GONE, view.visibility)
            val before = org.robolectric.util.ReflectionHelpers.getField<ClusterMusicState.Snapshot>(view, "snapshot").lyrics
            assertEquals("Song", before.previous)
            assertEquals("First lyric", before.current)
            assertFalse(before.hasLyrics)
            val withCover = first.copy(artworkTransferId = 7)
            ClusterMusicState.publish(withCover, art); idle()
            val after = org.robolectric.util.ReflectionHelpers.getField<ClusterMusicState.Snapshot>(view, "snapshot")
            assertEquals(View.GONE, view.visibility)
            assertEquals(deadline, org.robolectric.util.ReflectionHelpers.getField<Long>(view, "visibleUntil"))
            assertEquals(before, after.lyrics)
            assertSame(art, after.artwork)
            ClusterMusicState.publish(withCover.copy(title = "Second lyric"), null); idle()
            val confirmed = org.robolectric.util.ReflectionHelpers.getField<ClusterMusicState.Snapshot>(view, "snapshot")
            assertEquals(View.VISIBLE, view.visibility)
            assertTrue(confirmed.lyrics.hasLyrics)
            assertEquals("First lyric", confirmed.lyrics.previous)
            assertSame(art, confirmed.artwork)
        } finally { controller.pause().stop().destroy(); art.recycle() }
    }

    @Test fun diagnosticsNeverContainChineseMediaOrRoadTextAndDoNotLogEachLine() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val music = ClusterMusicView(controller.get())
        val turn = ClusterTurnCardView(context)
        controller.get().setContentView(music); size(music); size(turn)
        ClusterMusicSettings.saveEnabled(context, true)
        ClusterMusicSettings.savePermanent(context, true)
        val lines = mutableListOf<String>()
        ClusterCardDiagnostics.listener = { lines += it }
        val title = "私密歌名原文"
        val artist = "私密歌手原文"
        val album = "私密专辑原文"
        val first = "春风吹过山岗"
        val second = "星光落在海面"
        val third = "月亮照亮归途"
        val road = "绝不写入日志的长安大道"
        try {
            val song = CarPlayNowPlaying(title = title, artist = artist, album = album, artworkTransferId = 9)
            ClusterMusicState.publish(song, null); idle()
            ClusterMusicState.publish(song.copy(title = first), null); idle()
            ClusterMusicState.publish(song.copy(title = second), null); idle()
            turn.setGuidance(ClusterTurnGuidance(2, 0, 218, road))
            val image = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888)
            size(music); music.draw(Canvas(image)); turn.draw(Canvas(image))
            assertTrue(lines.any { it.startsWith("Cluster cards:") })
            assertTrue(lines.any { it.startsWith("Cluster music:") })
            assertTrue(lines.any { it.startsWith("Turn card:") })
            val before = lines.size
            ClusterMusicState.publish(song.copy(title = third), null); idle()
            assertEquals(before, lines.size)
            val all = lines + ClusterCardDiagnostics.summary()
            for (line in all) {
                assertEquals(line, DiagnosticRedactor.redact(line))
                for (privateText in listOf(title, artist, album, first, second, third, road)) {
                    assertFalse("private text in $line", line.contains(privateText))
                }
            }
            image.recycle()
        } finally {
            ClusterCardDiagnostics.listener = null
            ClusterCardDiagnostics.remove(turn)
            controller.pause().stop().destroy()
        }
    }

    @Test fun clearedMetadataAndSessionEndHaveDifferentHideReasons() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val view = ClusterMusicView(controller.get())
        controller.get().setContentView(view); size(view)
        ClusterMusicSettings.saveEnabled(context, true)
        ClusterMusicSettings.savePermanent(context, true)
        val lines = mutableListOf<String>()
        ClusterCardDiagnostics.listener = { lines += it }
        try {
            ClusterMusicState.publish(CarPlayNowPlaying(title = "Song"), null); idle()
            ClusterMusicState.publish(CarPlayNowPlaying(), null); idle()
            assertEquals(View.GONE, view.visibility)
            assertTrue(ClusterCardDiagnostics.musicReport().contains("hideReason=noTrack"))
            assertTrue(lines.any { it.contains("hideReason=noTrack") })
            lines.clear()
            ClusterMusicState.publish(CarPlayNowPlaying(title = "Song"), null); idle()
            ClusterMusicState.publish(CarPlayNowPlaying(), null, sessionEnded = true); idle()
            assertEquals(View.GONE, view.visibility)
            assertTrue(ClusterCardDiagnostics.musicReport().contains("hideReason=disconnect"))
            assertTrue(lines.any { it.contains("hideReason=disconnect") })
            assertTrue(lines.all { DiagnosticRedactor.redact(it) == it })
        } finally {
            ClusterCardDiagnostics.listener = null
            controller.pause().stop().destroy()
        }
    }

    @Test fun diagnosticsMeasureLateCoverWithoutCountingRetainedOldCover() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val view = ClusterMusicView(controller.get())
        controller.get().setContentView(view); size(view)
        ClusterMusicSettings.saveEnabled(context, true)
        ClusterMusicSettings.savePermanent(context, true)
        val lines = mutableListOf<String>()
        ClusterCardDiagnostics.listener = { lines += it }
        val firstArt = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val secondArt = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            val song = CarPlayNowPlaying(title = "Song", artist = "Singer")
            ClusterMusicState.publish(song, null); idle()
            assertTrue(ClusterCardDiagnostics.musicReport().contains("coverDelayMs=-1"))
            val start = org.robolectric.util.ReflectionHelpers.getField<ClusterMusicState.Snapshot>(view, "snapshot").lyrics.startedAt
            waitFor(250)
            val beforeFirst = SystemClock.elapsedRealtime()
            ClusterMusicState.publish(song.copy(artworkTransferId = 7), firstArt); idle()
            val firstDelay = org.robolectric.util.ReflectionHelpers.getField<ClusterMusicState.Snapshot>(view, "snapshot").coverDelayMs
            assertTrue(firstDelay in (beforeFirst - start)..(SystemClock.elapsedRealtime() - start))
            assertTrue(firstDelay >= 250)
            assertTrue(ClusterCardDiagnostics.musicReport().contains("coverDelayMs=$firstDelay"))
            assertTrue(lines.any { it.contains("coverDelayMs=$firstDelay") })
            val next = song.copy(title = "Next", artworkTransferId = 8)
            ClusterMusicState.publish(next, firstArt); idle()
            assertTrue(ClusterCardDiagnostics.musicReport().contains("coverDelayMs=-1"))
            val nextStart = org.robolectric.util.ReflectionHelpers.getField<ClusterMusicState.Snapshot>(view, "snapshot").lyrics.startedAt
            waitFor(300)
            val beforeSecond = SystemClock.elapsedRealtime()
            ClusterMusicState.publish(next, secondArt); idle()
            val secondDelay = org.robolectric.util.ReflectionHelpers.getField<ClusterMusicState.Snapshot>(view, "snapshot").coverDelayMs
            assertTrue(secondDelay in (beforeSecond - nextStart)..(SystemClock.elapsedRealtime() - nextStart))
            assertTrue(secondDelay >= 300)
            assertTrue(ClusterCardDiagnostics.musicReport().contains("coverDelayMs=$secondDelay"))
            assertTrue(lines.all { DiagnosticRedactor.redact(it) == it })
        } finally {
            ClusterCardDiagnostics.listener = null
            controller.pause().stop().destroy()
            firstArt.recycle(); secondArt.recycle()
        }
    }

    @Test fun permanentCardKeepsBothTextColorsVisibleAfterDeadlineAndCoverUncovered() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val view = ClusterMusicView(controller.get())
        controller.get().setContentView(view); size(view)
        ClusterMusicSettings.saveEnabled(context, true)
        ClusterMusicSettings.savePermanent(context, true)
        ClusterMusicSettings.saveTemporarySeconds(context, 3)
        val art = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        try {
            ClusterMusicState.publish(CarPlayNowPlaying(title = "Episode title", artist = "Presenter"), art); idle()
            waitFor(3_500)
            assertEquals(View.VISIBLE, view.visibility)
            val layout = view.cardLayout()
            assertTrue(layout.text.left > layout.artwork.right)
            val image = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
            for (fraction in listOf(.1f, .5f, .9f)) for (y in listOf(.1f, .5f, .9f)) {
                assertEquals(Color.BLUE, image.getPixel((layout.artwork.left + layout.artwork.width() * fraction).toInt(),
                    (layout.artwork.top + layout.artwork.height() * y).toInt()))
            }
            val palette = ClusterCardPalette.of(false)
            var primary = 0
            var secondary = 0
            for (y in layout.text.top.toInt() until layout.text.bottom.toInt())
                for (x in layout.text.left.toInt() until layout.text.right.toInt()) {
                    when (image.getPixel(x, y)) {
                        palette.primary -> primary++
                        palette.secondary -> secondary++
                    }
                }
            assertTrue("title pixels=$primary", primary > 0)
            assertTrue("artist pixels=$secondary", secondary > 0)
            image.recycle()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun cardSizeAndPositionAreClampedAndAnimationsStopInvalidating() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val view = ClusterMusicView(controller.get())
        controller.get().setContentView(view); size(view)
        ClusterMusicSettings.saveEnabled(context, true)
        ClusterMusicSettings.savePermanent(context, true)
        try {
            ClusterMusicState.publish(CarPlayNowPlaying(title = "Song", artworkTransferId = 3), null); idle()
            for (percent in listOf(50, 100, 300)) for (position in listOf(5, 95)) {
                ClusterMusicSettings.save(context, "size", percent)
                ClusterMusicSettings.save(context, "x", position)
                ClusterMusicSettings.save(context, "y", position); idle()
                val layout = view.cardLayout()
                assertTrue(layout.card.left >= 0f && layout.card.right <= 1920f)
                assertTrue(layout.card.top >= 0f && layout.card.bottom <= 720f)
                assertEquals(layout.artwork.width(), layout.artwork.height(), .01f)
                assertTrue(layout.text.left >= layout.artwork.right)
            }
            ClusterMusicState.publish(CarPlayNowPlaying(title = "line one", artworkTransferId = 3), null); idle()
            ClusterMusicState.publish(CarPlayNowPlaying(title = "line two", artworkTransferId = 3), null); idle()
            waitFor(500)
            val bitmap = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            shadowOf(view).clearWasInvalidated()
            view.draw(Canvas(bitmap))
            waitFor(100)
            assertFalse(shadowOf(view).wasInvalidated())
            bitmap.recycle()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun narrowAndShortWindowsPreserveMinimumArtworkAndCardBounds() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val view = ClusterMusicView(controller.get())
        controller.get().setContentView(view)
        ClusterMusicSettings.saveEnabled(context, true)
        ClusterMusicSettings.savePermanent(context, true)
        try {
            val song = CarPlayNowPlaying(title = "Song", artist = "Singer", artworkTransferId = 3)
            ClusterMusicState.publish(song, null); idle()
            for (lyrics in listOf(false, true)) {
                if (lyrics) {
                    ClusterMusicState.publish(song.copy(title = "First lyric"), null); idle()
                    ClusterMusicState.publish(song.copy(title = "Second lyric"), null); idle()
                    waitFor(500)
                }
                for ((width, height) in listOf(60 to 200, 180 to 720, 320 to 720, 320 to 60,
                    1920 to 80, 1920 to 720)) for (percent in listOf(50, 100, 300)) {
                    ClusterMusicSettings.save(context, "size", percent)
                    size(view, width, height); idle(); size(view, width, height)
                    val layout = view.cardLayout()
                    val label = "${width}x$height size=$percent lyrics=$lyrics layout=$layout"
                    val minimum = minOf(80f, width.toFloat(), height.toFloat())
                    assertTrue(label, layout.artwork.width() + .01f >= minimum)
                    assertEquals(label, layout.artwork.width(), layout.artwork.height(), .01f)
                    assertTrue(label, layout.card.left >= 0f && layout.card.top >= 0f &&
                        layout.card.right <= width + .01f && layout.card.bottom <= height + .01f)
                    assertTrue(label, layout.artwork.left >= layout.card.left && layout.artwork.top >= layout.card.top &&
                        layout.artwork.right <= layout.card.right + .01f && layout.artwork.bottom <= layout.card.bottom + .01f)
                    assertTrue(label, layout.text.left >= layout.artwork.right &&
                        layout.text.right >= layout.text.left && layout.text.right <= layout.card.right + .01f)
                    if (width == 1920 && height == 720 && percent == 100) {
                        assertEquals(label, if (lyrics) 3.4f else 2.6f,
                            layout.card.width() / layout.artwork.width(), .01f)
                    }
                }
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun turnGlyphAndDistanceFitPracticalPanelWidths() {
        val palette = ClusterCardPalette.of(false)
        for (width in listOf(180, 320, 720, 1280, 1920)) {
            val view = ClusterTurnCardView(context)
            size(view, width, 720)
            view.setOpacity(100)
            view.setGuidance(ClusterTurnGuidance(2, 0, 218, ""))
            val card = view.cardRect()
            val image = Bitmap.createBitmap(width, 720, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
            var glyphPixels = 0
            var distancePixels = 0
            for (y in 0 until 720) for (x in 0 until width) {
                val pixel = image.getPixel(x, y)
                if (pixel == palette.accent || pixel == palette.primary) {
                    assertTrue("width=$width x=$x y=$y card=$card",
                        x > card.left && x < card.left + card.width - 1 &&
                            y > card.top && y < card.top + card.height - 1)
                    if (pixel == palette.accent) glyphPixels++ else distancePixels++
                }
            }
            assertTrue("width=$width glyph=$glyphPixels", glyphPixels > 0)
            assertTrue("width=$width distance=$distancePixels", distancePixels > 0)
            image.recycle()
            ClusterCardDiagnostics.remove(view)
        }
    }

    @Test fun turnVariantsReflectActualRemainingRoadRoundaboutAndLanes() {
        val view = ClusterTurnCardView(context)
        size(view)
        val image = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888)
        fun draw(guidance: ClusterTurnGuidance, expected: String) {
            view.setGuidance(guidance)
            view.draw(Canvas(image))
            assertTrue(ClusterCardDiagnostics.turnReport(),
                ClusterCardDiagnostics.turnReport()!!.contains("variant=$expected "))
        }
        try {
            draw(ClusterTurnGuidance(2, 0, 218, "Rd", remainingMeters = 4_200), "full")
            draw(ClusterTurnGuidance(2, 0, 218, "Rd"), "noRemaining")
            val longRoad = "An exceptionally long road name that cannot fit in the available banner"
            draw(ClusterTurnGuidance(2, 0, 218, longRoad, remainingMeters = 4_200), "truncatedRoad")
            val roadPaint = org.robolectric.util.ReflectionHelpers.getField<android.graphics.Paint>(view, "roadPaint")
            val shortened = org.robolectric.util.ReflectionHelpers.callInstanceMethod<String>(view, "ellipsize",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(String::class.java, longRoad),
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType, 45f),
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(android.graphics.Paint::class.java, roadPaint))
            assertTrue(shortened.endsWith("…"))
            assertTrue(roadPaint.measureText(shortened) <= 45f)
            draw(ClusterTurnGuidance(11, 3, 600, "Roundabout"), "roundabout")
            view.setLanes(listOf(LaneGuidanceLane(0, 2, 0, null), LaneGuidanceLane(1, 0, 90, null)))
            draw(ClusterTurnGuidance(2, 0, 218, "Rd"), "lanes")
            for (night in listOf(false, true)) {
                view.setNightMode(night)
                image.eraseColor(Color.TRANSPARENT)
                view.draw(Canvas(image))
                val background = org.robolectric.util.ReflectionHelpers.getField<android.graphics.Paint>(view, "glassPaint").color
                val card = view.cardRect()
                val row = view.laneRowRect()!!
                fun backgroundPixels(left: Int, top: Int, right: Int, bottom: Int): Int {
                    var count = 0
                    for (y in top until bottom) for (x in left until right) {
                        val pixel = image.getPixel(x, y)
                        if (kotlin.math.abs(Color.alpha(pixel) - Color.alpha(background)) <= 2 &&
                            kotlin.math.abs(Color.red(pixel) - Color.red(background)) <= 2 &&
                            kotlin.math.abs(Color.green(pixel) - Color.green(background)) <= 2 &&
                            kotlin.math.abs(Color.blue(pixel) - Color.blue(background)) <= 2) count++
                    }
                    return count
                }
                val cardPixels = backgroundPixels(card.left, card.top, card.left + card.width, card.top + card.height)
                val lanePixels = backgroundPixels(row.left.toInt(), row.top.toInt(), row.right.toInt(), row.bottom.toInt())
                assertTrue("night=$night background=$background card=$cardPixels", cardPixels > 0)
                assertTrue("night=$night background=$background lanes=$lanePixels", lanePixels > 0)
            }
        } finally { ClusterCardDiagnostics.remove(view); image.recycle() }
    }

    @Test fun captureTurnAndMusicCardsAtRealClusterResolution() {
        val root = File(requireNotNull(System.getProperty("user.dir")))
        val project = if (root.name == "common") root.parentFile else root
        val directory = File(project, "build/cluster-cards").apply { mkdirs() }
        fun capture(view: View, name: String, night: Boolean) {
            size(view)
            val image = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(image)
            canvas.drawColor(if (night) Color.rgb(29, 34, 41) else Color.rgb(210, 221, 230))
            view.draw(canvas)
            File(directory, "$name.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            image.recycle()
        }
        val turn = ClusterTurnCardView(context)
        size(turn)
        turn.setGuidance(ClusterTurnGuidance(2, 0, 218, "Long Street", remainingMeters = 11_000,
            arrivalEpochSeconds = 1_700_000_000))
        val lanes = listOf(LaneGuidanceLane(0, 2, 0, null), LaneGuidanceLane(1, 0, 90, null))
        for (night in listOf(false, true)) for (lane in listOf(false, true)) {
            turn.setNightMode(night); turn.setLanes(if (lane) lanes else emptyList())
            capture(turn, "turn-${if (night) "night" else "day"}-${if (lane) "lanes" else "no-lanes"}", night)
        }
        turn.setLanes(emptyList()); turn.setNightMode(false)
        turn.setGuidance(ClusterTurnGuidance(2, 0, 218,
            "A very long road name that must be truncated without cutting the maneuver"))
        capture(turn, "turn-long-road", false)
        turn.setGuidance(ClusterTurnGuidance(11, 3, 600, "Roundabout"))
        capture(turn, "turn-roundabout", false)

        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val music = ClusterMusicView(controller.get())
        controller.get().setContentView(music); size(music)
        ClusterMusicSettings.saveEnabled(context, true)
        ClusterMusicSettings.savePermanent(context, true)
        val art = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        Canvas(art).apply {
            drawColor(Color.rgb(31, 89, 120))
            drawRect(0f, 64f, 64f, 128f, android.graphics.Paint().apply { color = Color.rgb(210, 95, 82) })
            drawRect(64f, 0f, 128f, 64f, android.graphics.Paint().apply { color = Color.rgb(238, 190, 84) })
        }
        try {
            for (lyrics in listOf(false, true)) for (night in listOf(false, true)) for (long in listOf(false, true)) {
                ClusterMusicState.publish(CarPlayNowPlaying(), null); idle()
                val title = if (long) "A very long song or episode title that should wrap and then be ellipsized" else "Song"
                val info = CarPlayNowPlaying(title = title, artist = if (long) null else "Singer", artworkTransferId = 8)
                ClusterMusicState.publish(info, art); idle()
                if (lyrics) {
                    ClusterMusicState.publish(info.copy(title = "The previous line"), art); idle()
                    ClusterMusicState.publish(info.copy(title = if (long) "A long current lyric line that wraps before it is shortened for the available space" else "Current lyric"), art); idle()
                }
                music.setNightMode(night); waitFor(500)
                capture(music, "music-${if (lyrics) "lyrics" else "cover"}-${if (night) "night" else "day"}-${if (long) "long" else "short"}", night)
            }
        } finally { controller.pause().stop().destroy(); art.recycle() }
    }
}
