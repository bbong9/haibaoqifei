package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.media.CarPlayNowPlaying

/** Main-thread snapshots reuse the bounded media artwork cache; they never own/recycle its bitmap. */
internal object ClusterMusicState {
    data class Snapshot(val info: CarPlayNowPlaying = CarPlayNowPlaying(), val artwork: Bitmap? = null,
        val changedAt: Long = 0L, val revision: Long = 0L, val lyrics: ClusterLyricState = ClusterLyricState(),
        val coverDelayMs: Long = -1L, val sessionEnded: Boolean = false,
        val elapsedAt: Long = 0L, val elapsedPosition: Long? = null) {
        val hasProgress: Boolean get() = info.durationMillis?.let { it > 0L } == true &&
            info.elapsedMillis != null && elapsedPosition != null

        fun positionMillis(now: Long): Long? {
            val duration = info.durationMillis?.takeIf { it > 0L } ?: return null
            if (info.elapsedMillis == null) return null
            return estimatedPosition(now)?.coerceIn(0L, duration)
        }

        private fun estimatedPosition(now: Long): Long? {
            val base = elapsedPosition?.coerceAtLeast(0L) ?: return null
            val delta = if (info.playing && now > elapsedAt) now - elapsedAt else 0L
            return base + delta.coerceAtMost(Long.MAX_VALUE - base)
        }

        fun withPlayback(next: CarPlayNowPlaying, newTrack: Boolean, now: Long): Pair<Long?, Long> {
            val elapsedChanged = info.elapsedMillis != next.elapsedMillis
            val playingChanged = info.playing != next.playing
            if (!elapsedChanged && !playingChanged && !newTrack) return elapsedPosition to elapsedAt
            val position = when {
                next.elapsedMillis == null -> null
                elapsedChanged -> next.elapsedMillis
                newTrack -> 0L
                else -> estimatedPosition(now) ?: next.elapsedMillis
            }
            return position to now
        }
    }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<(Snapshot) -> Unit>()
    private var state = Snapshot()

    fun publish(info: CarPlayNowPlaying, artwork: Bitmap?, sessionEnded: Boolean = false) {
        main.post {
            val changed = trackChanged(state.info, info)
            // A metadata-only update must not punch a hole in a permanent card. Keep the
            // previous cover while the next transfer arrives, just like Android's media session.
            // An empty playback snapshot (disconnect) always clears the retained image.
            val now = SystemClock.elapsedRealtime()
            val lyrics = state.lyrics.update(state.info, info, now)
            val retained = if (lyrics.active) artwork ?: state.artwork?.takeUnless { it.isRecycled } else null
            val newTrack = lyrics.trackRevision != state.lyrics.trackRevision
            // A title can contain lyrics, so a title-only change does not restart playback progress.
            val playbackTrackChanged = state.info.album != info.album || state.info.artist != info.artist ||
                state.info.durationMillis != info.durationMillis || state.info.artworkTransferId != info.artworkTransferId
            val (elapsedPosition, elapsedAt) = state.withPlayback(info, playbackTrackChanged, now)
            if (!newTrack && lyrics.active && lyrics.current != null && lyrics.current != state.lyrics.current) {
                ClusterCardDiagnostics.lineChange()
            }
            val actualCover = artwork?.takeUnless { it.isRecycled }
            val coverDelay = when {
                !lyrics.active -> -1L
                newTrack && actualCover != null && (!state.lyrics.active ||
                    state.info.artworkTransferId == info.artworkTransferId || actualCover !== state.artwork) -> 0L
                newTrack -> -1L
                state.coverDelayMs < 0 && actualCover != null && actualCover !== state.artwork ->
                    (now - lyrics.startedAt).coerceAtLeast(0L)
                else -> state.coverDelayMs
            }
            state = Snapshot(info, retained, if (changed) now else state.changedAt,
                state.revision + if (changed) 1 else 0, lyrics, coverDelay, sessionEnded && !lyrics.active,
                elapsedAt, elapsedPosition)
            listeners.toList().forEach { it(state) }
        }
    }

    fun observe(listener: (Snapshot) -> Unit): () -> Unit {
        check(Looper.myLooper() == Looper.getMainLooper())
        listeners.add(listener)
        listener(state)
        return { listeners.remove(listener) }
    }

    fun trackChanged(old: CarPlayNowPlaying, next: CarPlayNowPlaying): Boolean =
        old.title != next.title || old.artist != next.artist || old.album != next.album ||
            old.sourceApp != next.sourceApp ||
            (old.durationMillis != null && next.durationMillis != null && old.durationMillis != next.durationMillis)
}
