package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.Assert.*
import org.junit.Test

class ClusterLyricStateTest {
    private val song = CarPlayNowPlaying(title = "Song", artist = "Artist", album = "Album", durationMillis = 100_000)

    @Test fun eitherFieldCanSupplyLyricsWithoutRestartingTheTrack() {
        for (artistLyrics in listOf(false, true)) {
            val start = ClusterLyricState().update(CarPlayNowPlaying(), song, 100)
            val first = if (artistLyrics) song.copy(artist = "First") else song.copy(title = "First")
            val second = if (artistLyrics) song.copy(artist = "Second") else song.copy(title = "Second")
            val third = if (artistLyrics) song.copy(artist = "Third") else song.copy(title = "Third")
            val afterFirst = start.update(song, first, 200)
            assertFalse(afterFirst.hasLyrics)
            assertEquals(start.trackRevision, afterFirst.trackRevision)
            val afterSecond = afterFirst.update(first, second, 300)
            assertTrue("artistLyrics=$artistLyrics", afterSecond.hasLyrics)
            assertEquals(if (artistLyrics) ClusterLyricState.Field.ARTIST else ClusterLyricState.Field.TITLE,
                afterSecond.lyricField)
            assertEquals(if (artistLyrics) song.title else song.artist, afterSecond.information(second))
            assertEquals(start.trackRevision, afterSecond.trackRevision)
            assertEquals(100L, afterSecond.startedAt)
            assertEquals("First", afterSecond.previous)
            assertEquals("Second", afterSecond.current)
            val afterThird = afterSecond.update(second, third, 400)
            assertEquals(start.trackRevision, afterThird.trackRevision)
            assertEquals("Second", afterThird.previous)
            assertEquals("Third", afterThird.current)
        }
    }

    @Test fun stableInformationIsUnmodifiedAndFallsBackToAlbumOnlyWhenBlank() {
        for (artistLyrics in listOf(false, true)) {
            val info = "  Song — Singer  "
            val base = if (artistLyrics) song.copy(title = info) else song.copy(artist = info)
            val first = if (artistLyrics) base.copy(artist = "First") else base.copy(title = "First")
            val second = if (artistLyrics) base.copy(artist = "Second") else base.copy(title = "Second")
            val lyrics = ClusterLyricState().update(CarPlayNowPlaying(), base, 100)
                .update(base, first, 200).update(first, second, 300)
            assertEquals(info, lyrics.information(second))
            val blank = if (artistLyrics) second.copy(title = "  ") else second.copy(artist = "  ")
            assertEquals("Album", lyrics.information(blank))
            assertNull(lyrics.information(blank.copy(album = "  ")))
        }
    }

    @Test fun artistLyricsKeepHistoryWhenFirstArtworkArrivesButDifferentReferencesReset() {
        val first = song.copy(artist = "First")
        val second = song.copy(artist = "Second")
        val lyrics = ClusterLyricState().update(CarPlayNowPlaying(), song, 100)
            .update(song, first, 200).update(first, second, 300)
        val withCover = second.copy(artworkTransferId = 7)
        assertEquals(lyrics, lyrics.update(second, withCover, 400))
        val replacement = lyrics.update(withCover, withCover.copy(artworkTransferId = 8), 500)
        assertFalse(replacement.hasLyrics)
        assertEquals(500L, replacement.startedAt)
        val removed = lyrics.update(withCover, withCover.copy(artworkTransferId = null), 600)
        assertFalse(removed.hasLyrics)
        assertEquals(600L, removed.startedAt)
    }

    @Test fun artistLyricExceptionDoesNotMaskOtherTrackIdentityChanges() {
        val base = song.copy(sourceApp = "Player", artworkTransferId = 7)
        val first = base.copy(artist = "First")
        val second = base.copy(artist = "Second")
        val lyrics = ClusterLyricState().update(CarPlayNowPlaying(), base, 100)
            .update(base, first, 200).update(first, second, 300)
        val changes = listOf(
            second.copy(artist = "Third", album = "Other album"),
            second.copy(artist = "Third", sourceApp = "Other app"),
            second.copy(artist = "Third", durationMillis = 200_000),
            second.copy(artist = "Third", artworkTransferId = 8),
            second.copy(artist = "Third", title = "Other song"),
        )
        for (next in changes) {
            val changed = lyrics.update(second, next, 400)
            assertFalse(changed.hasLyrics)
            assertEquals(lyrics.trackRevision + 1, changed.trackRevision)
            assertEquals(400L, changed.startedAt)
        }
    }

    @Test fun twoChangesEnterLyricsAndBlankOrRepeatedTitlesAreIgnored() {
        var state = ClusterLyricState().update(CarPlayNowPlaying(), song, 100)
        val first = song.copy(title = "First")
        state = state.update(song, first, 200)
        assertFalse(state.hasLyrics)
        state = state.update(first, first.copy(title = "  "), 300)
        assertEquals("First", state.current)
        state = state.update(first.copy(title = "  "), first, 400)
        assertEquals(1, state.titleChanges)
        val second = first.copy(title = "Second")
        state = state.update(first, second, 500)
        assertTrue(state.hasLyrics)
        assertEquals("Artist", state.information(second))
        assertEquals("First", state.previous)
        assertEquals("Second", state.current)
        assertEquals(2, state.titleChanges)
    }

    @Test fun firstInformationFieldArrivalDoesNotCountAsStableLyricEvidence() {
        val initial = song.copy(artist = null)
        val first = initial.copy(title = "First", artist = "Singer")
        val second = first.copy(title = "Second")
        val third = first.copy(title = "Third")
        val started = ClusterLyricState().update(CarPlayNowPlaying(), initial, 100)
        val afterFirst = started.update(initial, first, 200)
        assertFalse(afterFirst.hasLyrics)
        assertEquals(0, afterFirst.titleChanges)
        val afterSecond = afterFirst.update(first, second, 300)
        assertFalse(afterSecond.hasLyrics)
        assertEquals(1, afterSecond.titleChanges)
        val afterThird = afterSecond.update(second, third, 400)
        assertTrue(afterThird.hasLyrics)
        assertEquals(started.trackRevision, afterThird.trackRevision)
        assertEquals("Second", afterThird.previous)
        assertEquals("Third", afterThird.current)
        assertEquals("Singer", afterThird.information(third))
    }

    @Test fun lateFirstArtworkKeepsTrackClockAndLyricHistory() {
        var state = ClusterLyricState().update(CarPlayNowPlaying(), song, 100)
        val first = song.copy(title = "First")
        val second = song.copy(title = "Second")
        state = state.update(song, first, 200).update(first, second, 300)
        val before = state
        state = state.update(second, second.copy(artworkTransferId = 7), 400)
        assertEquals(before, state)
        assertTrue(state.hasLyrics)
        assertEquals(100L, state.startedAt)
        val changed = state.update(second.copy(artworkTransferId = 7), second.copy(artworkTransferId = 8), 500)
        assertFalse(changed.hasLyrics)
        assertNull(changed.previous)
        assertEquals(500L, changed.startedAt)
    }

    @Test fun clearAndTrackChangeResetLyrics() {
        val first = song.copy(title = "First")
        val second = song.copy(title = "Second")
        var state = ClusterLyricState().update(CarPlayNowPlaying(), song, 100)
            .update(song, first, 200).update(first, second, 300)
        state = state.update(second, second.copy(artist = "Another"), 400)
        assertFalse(state.hasLyrics)
        assertNull(state.previous)
        assertEquals(0, state.titleChanges)
        state = state.update(second.copy(artist = "Another"), CarPlayNowPlaying(), 500)
        assertFalse(state.active)
        assertNull(state.current)
        assertNull(state.titleCurrent)
    }
}
