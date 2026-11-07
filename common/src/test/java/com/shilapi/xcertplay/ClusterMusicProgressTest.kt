package com.shilapi.xcertplay

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import java.io.File
import java.time.Duration
import kotlin.math.ceil
import kotlin.math.floor
import org.junit.After
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
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en", manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClusterMusicProgressTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun publish(info: CarPlayNowPlaying, artwork: Bitmap? = null) {
        ClusterMusicState.publish(info, artwork)
        idle()
    }

    @Before fun reset() {
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        publish(CarPlayNowPlaying())
    }

    @After fun clear() { publish(CarPlayNowPlaying()) }

    @Test fun clockInterpolatesPausesCalibratesAndResetsForTracks() {
        var snapshot = ClusterMusicState.Snapshot()
        val stop = ClusterMusicState.observe { snapshot = it }
        try {
            val song = CarPlayNowPlaying(title = "Song", artist = "Artist", album = "Album",
                artworkTransferId = 7, durationMillis = 180_000, elapsedMillis = 5_000, playing = true)
            publish(song)
            val start = snapshot.elapsedAt
            assertEquals(5_000L, snapshot.positionMillis(start))
            assertEquals(7_000L, snapshot.positionMillis(start + 2_000))

            val firstLine = song.copy(title = "First lyric")
            publish(firstLine)
            assertEquals(start, snapshot.elapsedAt)
            assertEquals(5_000L, snapshot.positionMillis(start))
            val calibrated = firstLine.copy(elapsedMillis = 5_001)
            publish(calibrated)
            val lyricStart = snapshot.elapsedAt
            publish(calibrated.copy(title = "Second lyric"))
            assertTrue(snapshot.lyrics.hasLyrics)
            assertEquals(lyricStart, snapshot.elapsedAt)
            assertEquals(7_001L, snapshot.positionMillis(lyricStart + 2_000))
            publish(calibrated.copy(title = "Third lyric"))
            assertEquals(lyricStart, snapshot.elapsedAt)

            advance(2_000)
            val paused = calibrated.copy(title = "Third lyric", playing = false)
            publish(paused)
            assertEquals(7_001L, snapshot.positionMillis(snapshot.elapsedAt))
            assertEquals(7_001L, snapshot.positionMillis(snapshot.elapsedAt + 9_000))
            publish(paused.copy(elapsedMillis = 123_000))
            assertEquals(123_000L, snapshot.positionMillis(snapshot.elapsedAt))
            publish(paused.copy(elapsedMillis = 123_000, playing = true))
            assertEquals(125_000L, snapshot.positionMillis(snapshot.elapsedAt + 2_000))

            val next = paused.copy(album = "Next album", elapsedMillis = 123_000, playing = true)
            publish(next)
            assertEquals(0L, snapshot.positionMillis(snapshot.elapsedAt))
            publish(next.copy(elapsedMillis = 2_000))
            assertEquals(2_000L, snapshot.positionMillis(snapshot.elapsedAt))
            publish(next.copy(album = "Third album", elapsedMillis = 42_000))
            assertEquals(42_000L, snapshot.positionMillis(snapshot.elapsedAt))
        } finally { stop() }
    }

    @Test fun titleOnlyChangesKeepTheClockRunning() {
        var snapshot = ClusterMusicState.Snapshot()
        val stop = ClusterMusicState.observe { snapshot = it }
        try {
            val song = CarPlayNowPlaying(title = "First song", artist = "Artist", album = "Album",
                artworkTransferId = 7, durationMillis = 180_000, elapsedMillis = 30_000, playing = true)
            publish(song)
            val started = snapshot.elapsedAt
            advance(2_000)
            publish(song.copy(title = "First lyric"))
            assertEquals(started, snapshot.elapsedAt)
            assertEquals(32_000L, snapshot.positionMillis(SystemClock.elapsedRealtime()))
            advance(3_000)
            publish(song.copy(title = "Second lyric"))
            assertTrue(snapshot.lyrics.hasLyrics)
            assertEquals(started, snapshot.elapsedAt)
            assertEquals(35_000L, snapshot.positionMillis(SystemClock.elapsedRealtime()))
            advance(1_000)
            publish(song.copy(title = "Third lyric"))
            assertEquals(started, snapshot.elapsedAt)
            assertEquals(36_000L, snapshot.positionMillis(SystemClock.elapsedRealtime()))
        } finally { stop() }
    }

    @Test fun titleAndSourceArrivalDoNotRestartThePlaybackClock() {
        var snapshot = ClusterMusicState.Snapshot()
        val stop = ClusterMusicState.observe { snapshot = it }
        try {
            val initial = CarPlayNowPlaying(artist = "Artist", album = "Album", artworkTransferId = 7,
                durationMillis = 180_000, elapsedMillis = 30_000, playing = true)
            publish(initial)
            val started = snapshot.elapsedAt
            advance(3_000)
            val title = initial.copy(title = "Song")
            for (update in listOf(title, title.copy(sourceApp = "Player"))) {
                publish(update)
                assertEquals(started, snapshot.elapsedAt)
                assertEquals(33_000L, snapshot.positionMillis(SystemClock.elapsedRealtime()))
            }
        } finally { stop() }
    }

    @Test fun titleChangesKeepTheClockFrozenAcrossAPause() {
        var snapshot = ClusterMusicState.Snapshot()
        val stop = ClusterMusicState.observe { snapshot = it }
        try {
            val song = CarPlayNowPlaying(title = "Song", artist = "Artist", album = "Album",
                artworkTransferId = 7, durationMillis = 180_000, elapsedMillis = 5_000, playing = true)
            publish(song)
            advance(2_000)
            val first = song.copy(title = "First lyric")
            publish(first)
            advance(1_000)
            val paused = first.copy(playing = false)
            publish(paused)
            val pausedAt = snapshot.elapsedAt
            advance(4_000)
            publish(paused.copy(title = "Second lyric"))
            assertTrue(snapshot.lyrics.hasLyrics)
            assertEquals(pausedAt, snapshot.elapsedAt)
            assertEquals(8_000L, snapshot.positionMillis(snapshot.elapsedAt))
            assertEquals(8_000L, snapshot.positionMillis(snapshot.elapsedAt + 10_000))
        } finally { stop() }
    }

    @Test fun eachTrackIdentityChangeResetsProgressEvenInLyricMode() {
        var snapshot = ClusterMusicState.Snapshot()
        val stop = ClusterMusicState.observe { snapshot = it }
        try {
            val song = CarPlayNowPlaying(title = "Song", artist = "Artist", album = "Album",
                artworkTransferId = 7, durationMillis = 180_000, elapsedMillis = 30_000, playing = true)
            val changes = listOf<Pair<String, (CarPlayNowPlaying) -> CarPlayNowPlaying>>(
                "album" to { it.copy(album = "Other album") },
                "artist" to { it.copy(artist = "Other artist") },
                "duration" to { it.copy(durationMillis = 200_000) },
                "artwork" to { it.copy(artworkTransferId = 8) },
            )
            for ((field, change) in changes) {
                publish(CarPlayNowPlaying())
                publish(song)
                publish(song.copy(title = "First lyric"))
                val lyric = song.copy(title = "Second lyric")
                publish(lyric)
                assertTrue(field, snapshot.lyrics.hasLyrics)
                advance(2_000)
                publish(change(lyric))
                assertEquals(field, 0L, snapshot.positionMillis(snapshot.elapsedAt))
                assertEquals(field, 1_000L, snapshot.positionMillis(snapshot.elapsedAt + 1_000))
            }
        } finally { stop() }
    }

    @Test fun trackIdentityChangesToOrFromNullResetProgress() {
        var snapshot = ClusterMusicState.Snapshot()
        val stop = ClusterMusicState.observe { snapshot = it }
        try {
            val song = CarPlayNowPlaying(title = "Song", artist = "Artist", album = "Album",
                artworkTransferId = 7, durationMillis = 180_000, elapsedMillis = 30_000, playing = true)
            val missing = listOf(
                song.copy(album = null), song.copy(artist = null),
                song.copy(durationMillis = null), song.copy(artworkTransferId = null),
            )
            for (without in missing) {
                for ((old, next) in listOf(without to song, song to without)) {
                    publish(CarPlayNowPlaying())
                    publish(old)
                    val started = snapshot.elapsedAt
                    advance(1_000)
                    publish(next)
                    assertEquals(0L, snapshot.elapsedPosition)
                    assertTrue(snapshot.elapsedAt > started)
                }
            }
        } finally { stop() }
    }

    @Test fun artistChangeResetsProgressEvenWhenArtistCarriesLyrics() {
        var snapshot = ClusterMusicState.Snapshot()
        val stop = ClusterMusicState.observe { snapshot = it }
        try {
            val song = CarPlayNowPlaying(title = "Song", artist = "Artist", album = "Album",
                artworkTransferId = 7, durationMillis = 180_000, elapsedMillis = 30_000, playing = true)
            publish(song)
            publish(song.copy(artist = "First lyric"))
            publish(song.copy(artist = "Second lyric"))
            assertEquals(ClusterLyricState.Field.ARTIST, snapshot.lyrics.lyricField)
            advance(2_000)
            publish(song.copy(artist = "Third lyric"))
            assertEquals(0L, snapshot.positionMillis(snapshot.elapsedAt))
        } finally { stop() }
    }

    @Test fun phonePositionWinsWhenTrackIdentityChanges() {
        var snapshot = ClusterMusicState.Snapshot()
        val stop = ClusterMusicState.observe { snapshot = it }
        try {
            val song = CarPlayNowPlaying(title = "Song", artist = "Artist", album = "Album",
                artworkTransferId = 7, durationMillis = 180_000, elapsedMillis = 5_000, playing = true)
            val updates = listOf(
                song.copy(album = "Other album"), song.copy(artist = "Other artist"),
                song.copy(durationMillis = 200_000), song.copy(artworkTransferId = 8),
            )
            for (update in updates) {
                publish(CarPlayNowPlaying())
                publish(song)
                advance(2_000)
                publish(update.copy(elapsedMillis = 42_000))
                assertEquals(42_000L, snapshot.positionMillis(snapshot.elapsedAt))
                assertEquals(43_000L, snapshot.positionMillis(snapshot.elapsedAt + 1_000))
            }
        } finally { stop() }
    }

    @Test fun progressRequiresBothValuesAndClampsPosition() {
        for ((duration, elapsed) in listOf(null to 1_000L, 0L to 1_000L, -1L to 1_000L,
            5_000L to null)) {
            val snapshot = ClusterMusicState.Snapshot(
                info = CarPlayNowPlaying(durationMillis = duration, elapsedMillis = elapsed, playing = true),
                elapsedAt = 100, elapsedPosition = elapsed,
            )
            assertFalse(snapshot.hasProgress)
            assertNull(snapshot.positionMillis(2_000))
        }
        val info = CarPlayNowPlaying(durationMillis = 10_000, elapsedMillis = 1_000, playing = true)
        val snapshot = ClusterMusicState.Snapshot(info = info, elapsedAt = 100, elapsedPosition = -500)
        assertEquals(0L, snapshot.positionMillis(100))
        assertEquals(10_000L, snapshot.positionMillis(30_000))
        assertEquals(10_000L, snapshot.copy(elapsedPosition = Long.MAX_VALUE).positionMillis(Long.MAX_VALUE))
    }

    @Test fun absentProgressKeepsLegacyGeometryAndAllSizesStayInsideTheCard() {
        val view = ClusterMusicView(context)
        val info = CarPlayNowPlaying(durationMillis = 221_000, elapsedMillis = 84_000)
        for ((width, height) in listOf(1920 to 720, 1280 to 480, 60 to 200)) {
            size(view, width, height)
            for (percent in listOf(50, 100, 200, 300)) {
                ClusterMusicSettings.save(context, "size", percent)
                setSnapshot(view, info.copy(durationMillis = null))
                val old = view.cardLayout()
                val pad = old.card.height() * .035f
                assertNull(old.progressRow)
                assertEquals(old.card.bottom - pad, old.text.bottom, 0f)

                setSnapshot(view, info)
                val layout = view.cardLayout()
                assertEquals(old.card, layout.card)
                assertEquals(old.artwork, layout.artwork)
                assertEquals(layout.artwork.bottom, layout.text.bottom, 0f)
                val row = requireNotNull(layout.progressRow)
                assertTrue(row.left >= layout.card.left && row.right <= layout.card.right)
                assertTrue(row.top >= layout.artwork.bottom && row.bottom <= layout.card.bottom)
                val progress = view.progressLayout(row, layout.card.height(), 221_000)
                assertTrue(progress.elapsed.left >= row.left && progress.elapsed.right <= progress.bar.left)
                assertTrue(progress.bar.right <= progress.duration.left && progress.duration.right <= row.right)
                assertTrue(progress.bar.width() >= 0f && progress.bar.top >= row.top && progress.bar.bottom <= row.bottom)
            }
        }
    }

    @Test fun threeLineLyricsKeepTheirPriorityWithAProgressRow() {
        val view = ClusterMusicView(context)
        size(view, 1920, 720)
        setSnapshot(view, CarPlayNowPlaying(durationMillis = 221_000, elapsedMillis = 84_000))
        val geometry = view.cardLayout()
        val text = "First line\nSecond line\nThird line"
        val palette = ClusterCardPalette.of(false)
        val tall = RectF(geometry.text.left, 0f, geometry.text.right, 2_000f)
        val full = view.layoutText(tall, geometry.card.height(), text, "Previous", "Artist", true, palette, 1f, true)
        assertEquals(3, full.main.lineCount)
        val gap = geometry.card.height() * .03f
        val previousHeight = requireNotNull(full.previous).height
        val detailHeight = requireNotNull(full.detail).height
        fun fitted(height: Float) = view.layoutText(RectF(tall.left, 0f, tall.right, height), geometry.card.height(),
            text, "Previous", "Artist", true, palette, 1f, true)

        val dropPrevious = fitted(full.main.height + detailHeight + gap + .1f)
        assertNull(dropPrevious.previous)
        assertNotNull(dropPrevious.detail)
        assertEquals(3, dropPrevious.main.lineCount)
        val dropDetail = fitted(full.main.height + .1f)
        assertNull(dropDetail.previous)
        assertNull(dropDetail.detail)
        assertEquals(3, dropDetail.main.lineCount)
        val smaller = fitted(full.main.height - minOf(10f, previousHeight / 2f))
        assertNull(smaller.previous)
        assertNull(smaller.detail)
        assertEquals(3, smaller.main.lineCount)
        assertTrue(smaller.main.paint.textSize < full.main.paint.textSize)
        val actual = view.layoutText(geometry.text, geometry.card.height(), text, "Previous", "Artist",
            true, palette, 1f, true)
        assertEquals(3, actual.main.lineCount)
        assertTrue(actual.mainTop + actual.main.height <= geometry.text.bottom + .1f)
    }

    @Test fun formatsTimesWithoutChangingTheReservedLabelWidth() {
        assertEquals("0:05", ClusterMusicView.formatTime(5_000))
        assertEquals("3:41", ClusterMusicView.formatTime(221_000))
        assertEquals("1:02:03", ClusterMusicView.formatTime(3_723_000))
        val view = ClusterMusicView(context)
        size(view, 1920, 720)
        setSnapshot(view, CarPlayNowPlaying(durationMillis = 3_723_000, elapsedMillis = 5_000))
        val card = view.cardLayout()
        val row = requireNotNull(card.progressRow)
        val first = view.progressLayout(row, card.card.height(), 3_723_000)
        setSnapshot(view, CarPlayNowPlaying(durationMillis = 3_723_000, elapsedMillis = 3_600_000))
        val later = view.progressLayout(row, card.card.height(), 3_723_000)
        assertEquals(first.bar, later.bar)
        assertEquals(first.elapsed.width(), later.elapsed.width(), 0f)
    }

    @Test fun refreshRunsOnlyWhileAnAttachedVisibleCardIsPlaying() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val parent = FrameLayout(controller.get())
        val view = ClusterMusicView(controller.get())
        parent.addView(view)
        controller.get().setContentView(parent)
        size(view, 1920, 720)
        ClusterMusicSettings.saveEnabled(context, true)
        ClusterMusicSettings.savePermanent(context, true)
        val song = CarPlayNowPlaying(title = "Song", artworkTransferId = 7,
            durationMillis = 180_000, elapsedMillis = 5_000, playing = true)
        val handler = ReflectionHelpers.getField<Handler>(view, "main")
        val tick = ReflectionHelpers.getField<Runnable>(view, "progressTick")
        fun scheduled() = handler.hasCallbacks(tick)
        try {
            publish(song)
            assertTrue(scheduled())
            advance(2_100)
            assertTrue(scheduled())
            val active = ReflectionHelpers.getField<ClusterMusicState.Snapshot>(view, "snapshot")
            assertTrue(active.positionMillis(SystemClock.elapsedRealtime())!! >= 7_000L)
            publish(song.copy(playing = false))
            assertFalse(scheduled())
            publish(song)
            assertTrue(scheduled())
            publish(song.copy(durationMillis = null))
            assertFalse(scheduled())
            publish(song)
            assertTrue(scheduled())
            parent.visibility = View.INVISIBLE
            assertFalse(scheduled())
            parent.visibility = View.VISIBLE
            assertTrue(scheduled())
            ClusterMusicSettings.saveEnabled(context, false); idle()
            assertFalse(scheduled())
            ClusterMusicSettings.saveEnabled(context, true)
            ClusterMusicSettings.saveTemporarySeconds(context, 3)
            ClusterMusicSettings.savePermanent(context, false); idle()
            assertTrue(scheduled())
            advance(3_100)
            assertEquals(View.GONE, view.visibility)
            assertFalse(scheduled())
        } finally {
            controller.pause().stop().destroy()
            assertFalse(scheduled())
        }
    }

    @Test fun progressFadesWithTheTemporaryCard() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val view = ClusterMusicView(controller.get())
        controller.get().setContentView(view)
        size(view, 1920, 720)
        ClusterMusicSettings.saveEnabled(context, true)
        ClusterMusicSettings.saveTemporarySeconds(context, 3)
        try {
            publish(CarPlayNowPlaying(title = "Song", durationMillis = 180_000, elapsedMillis = 90_000))
            advance(500)
            val layout = view.cardLayout()
            val progress = view.progressLayout(requireNotNull(layout.progressRow), layout.card.height(), 180_000)
            val x = (progress.bar.left + progress.bar.width() * .25f).toInt()
            val y = progress.bar.centerY().toInt()
            fun alpha(): Int {
                val bitmap = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                return Color.alpha(bitmap.getPixel(x, y)).also { bitmap.recycle() }
            }
            assertEquals(255, alpha())
            val deadline = ReflectionHelpers.getField<Long>(view, "visibleUntil")
            advance((deadline - SystemClock.elapsedRealtime() - 200).coerceAtLeast(0))
            assertTrue(alpha() in 1..254)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun screenshotsCoverLyricsThemesAndMissingProgress() {
        val cases = listOf(
            Capture("cover-day-progress", false, true, null, 0),
            Capture("cover-night-progress", true, true, null, 0),
            Capture("cover-day-no-progress", false, false, null, 0),
            Capture("cover-night-no-progress", true, false, null, 0),
            Capture("lyric-one-day-progress", false, true, "Drive on", 1),
            Capture("lyric-three-night-progress", true, true, "City lights\nThe long road\nAlmost home", 3),
            Capture("lyric-one-night-no-progress", true, false, "Drive on", 1),
            Capture("lyric-three-day-no-progress", false, false, "City lights\nThe long road\nAlmost home", 3),
        )
        val directory = File("../build/seal-card31")
        assertTrue(directory.isDirectory || directory.mkdirs())
        for (case in cases) {
            publish(CarPlayNowPlaying())
            val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
            val view = ClusterMusicView(controller.get())
            val art = artwork()
            try {
                controller.get().setContentView(view)
                size(view, 1920, 720)
                view.setNightMode(case.night)
                ClusterMusicSettings.saveEnabled(context, true)
                ClusterMusicSettings.savePermanent(context, true)
                val song = CarPlayNowPlaying(title = "Midnight Drive", artist = "Mia Lin", album = "Night Drive",
                    artworkTransferId = 7, durationMillis = if (case.progress) 221_000 else null,
                    elapsedMillis = if (case.progress) 84_000 else null)
                publish(song, art)
                if (case.lyric != null) {
                    publish(song.copy(title = "Previous lyric"), art)
                    publish(song.copy(title = case.lyric,
                        elapsedMillis = if (case.progress) 84_001 else null), art)
                }
                advance(500)
                size(view, 1920, 720)
                if (case.lyric != null) {
                    val state = ReflectionHelpers.getField<ClusterMusicState.Snapshot>(view, "snapshot")
                    val geometry = view.cardLayout()
                    val text = view.layoutText(geometry.text, geometry.card.height(), state.lyrics.current.orEmpty(),
                        state.lyrics.previous, state.lyrics.information(state.info), true,
                        ClusterCardPalette.of(case.night), 1f, case.progress)
                    assertEquals(case.name, case.lines, text.main.lineCount)
                    assertEquals(case.name, 0, text.main.getEllipsisCount(text.main.lineCount - 1))
                }
                val frame = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888)
                frame.eraseColor(Color.rgb(33, 38, 41))
                view.draw(Canvas(frame))
                val card = view.cardLayout().card
                val left = (floor(card.left).toInt() - 10).coerceAtLeast(0)
                val top = (floor(card.top).toInt() - 10).coerceAtLeast(0)
                val right = (ceil(card.right).toInt() + 10).coerceAtMost(frame.width)
                val bottom = (ceil(card.bottom).toInt() + 10).coerceAtMost(frame.height)
                val crop = Bitmap.createBitmap(frame, left, top, right - left, bottom - top)
                File(directory, "${case.name}.png").outputStream().use {
                    assertTrue(crop.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                crop.recycle()
                frame.recycle()
            } finally {
                controller.pause().stop().destroy()
                publish(CarPlayNowPlaying())
                art.recycle()
            }
        }
    }

    private data class Capture(val name: String, val night: Boolean, val progress: Boolean, val lyric: String?,
        val lines: Int)

    private fun size(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    private fun setSnapshot(view: ClusterMusicView, info: CarPlayNowPlaying) {
        ReflectionHelpers.setField(view, "snapshot", ClusterMusicState.Snapshot(info = info,
            elapsedPosition = info.elapsedMillis))
    }

    private fun artwork(): Bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply {
        val canvas = Canvas(this)
        canvas.drawColor(Color.rgb(26, 99, 110))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(242, 194, 120) }
        canvas.drawCircle(64f, 54f, 31f, paint)
        paint.color = Color.rgb(31, 48, 70)
        canvas.drawRect(0f, 82f, 128f, 128f, paint)
    }
}
