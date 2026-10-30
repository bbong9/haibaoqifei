package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.CarPlayNowPlaying

/** Session-only state; lyric text is never persisted or sent to diagnostics. */
internal data class ClusterLyricState(
    val active: Boolean = false,
    val lyricField: Field = Field.NONE,
    val titleCurrent: String? = null,
    val titlePrevious: String? = null,
    val artistCurrent: String? = null,
    val artistPrevious: String? = null,
    val titleChanges: Int = 0,
    val artistChanges: Int = 0,
    val startedAt: Long = 0L,
    val confirmedAfterMs: Long = -1L,
    val trackRevision: Long = 0L,
) {
    enum class Field { TITLE, ARTIST, NONE }

    val hasLyrics: Boolean get() = lyricField != Field.NONE
    private val artistIsCandidate: Boolean get() = lyricField == Field.ARTIST ||
        (lyricField == Field.NONE && artistChanges > 0 && titleChanges == 0)
    val current: String? get() = if (artistIsCandidate) artistCurrent else titleCurrent
    val previous: String? get() = if (artistIsCandidate) artistPrevious else titlePrevious

    fun information(info: CarPlayNowPlaying): String? =
        (if (lyricField == Field.ARTIST) info.title else info.artist)?.takeIf { it.isNotBlank() }
            ?: info.album?.takeIf { it.isNotBlank() }

    fun update(old: CarPlayNowPlaying, next: CarPlayNowPlaying, now: Long): ClusterLyricState {
        if (next == CarPlayNowPlaying()) return ClusterLyricState(trackRevision = trackRevision + if (active) 1 else 0)
        val lateFirstArtwork = old.artworkTransferId == null && next.artworkTransferId != null &&
            old.sourceApp == next.sourceApp && old.artist == next.artist && old.album == next.album &&
            old.durationMillis == next.durationMillis && old.title == next.title
        // With a stable title, artist may be the lyric field. Leave the shared identity
        // function and its media-cache caller untouched; other identity fields still apply.
        val identityOld = if (old.title == next.title && lyricField != Field.TITLE) old.copy(artist = next.artist) else old
        val newTrack = !active || (albumTrackChanged(identityOld, next) && !lateFirstArtwork)
        val title = next.title?.takeIf { it.isNotBlank() }
        val artist = next.artist?.takeIf { it.isNotBlank() }
        if (newTrack) return ClusterLyricState(
            active = true, titleCurrent = title, artistCurrent = artist, startedAt = now,
            trackRevision = trackRevision + 1,
        )
        val titleChanged = title != null && titleCurrent != null && title != titleCurrent
        val artistChanged = artist != null && artistCurrent != null && artist != artistCurrent
        val titleBecameUnstable = title != null && title != titleCurrent
        val artistBecameUnstable = artist != null && artist != artistCurrent
        val nextTitleChanges = when {
            artistBecameUnstable -> 0
            titleChanged -> titleChanges + 1
            else -> titleChanges
        }
        val nextArtistChanges = when {
            titleBecameUnstable -> 0
            artistChanged -> artistChanges + 1
            else -> artistChanges
        }
        val nextField = when {
            lyricField != Field.NONE -> lyricField
            nextTitleChanges >= 2 -> Field.TITLE
            nextArtistChanges >= 2 -> Field.ARTIST
            else -> Field.NONE
        }
        return copy(
            lyricField = nextField,
            titleCurrent = title ?: titleCurrent,
            titlePrevious = if (titleChanged) titleCurrent else titlePrevious,
            artistCurrent = artist ?: artistCurrent,
            artistPrevious = if (artistChanged) artistCurrent else artistPrevious,
            titleChanges = nextTitleChanges,
            artistChanges = nextArtistChanges,
            confirmedAfterMs = if (lyricField == Field.NONE && nextField != Field.NONE) (now - startedAt).coerceAtLeast(0L)
                else confirmedAfterMs,
        )
    }
}
