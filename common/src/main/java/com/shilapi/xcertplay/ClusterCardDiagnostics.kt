package com.shilapi.xcertplay

import android.os.Handler
import java.util.Locale

/** Only enums and numeric measurements enter this object; media and route text stay outside it. */
internal object ClusterCardDiagnostics {
    enum class MusicMode { LYRICS, COVER, NONE }
    enum class NightSource { MANUAL, SCHEDULE, AMBIENT, SYSTEM, FALLBACK }
    enum class HideReason(val value: String) {
        NONE("none"), EXPIRED("expired"), DISABLED("disabled"), NO_TRACK("noTrack"),
        DISCONNECT("disconnect"), TRACK_CHANGE("trackChange"),
    }
    enum class Fit { NORMAL, SHRUNK, CLAMPED }
    enum class TurnVariant(val value: String) {
        FULL("full"), NO_REMAINING("noRemaining"), TRUNCATED_ROAD("truncatedRoad"),
        ROUNDABOUT("roundabout"), LANES("lanes"),
    }
    private data class Music(
        val mode: MusicMode = MusicMode.NONE,
        val lyricLines: Int = 0,
        val titleChanges: Int = 0,
        val lyricField: ClusterLyricState.Field = ClusterLyricState.Field.NONE,
        val hideReason: HideReason = HideReason.NO_TRACK,
        val confirmedAfterMs: Long = -1,
        val coverDelayMs: Long = -1,
        val fit: Fit = Fit.NORMAL,
        val trackRevision: Long = 0,
    )
    private data class Turn(val variant: TurnVariant, val night: Boolean)
    private val views = LinkedHashMap<Any, Music>()
    private val turns = LinkedHashMap<Any, Turn>()
    private var night = false
    private var themeSetting = "follow"
    private var source = NightSource.SYSTEM
    private var nowPlayingUpdates = 0
    private var lineChanges = 0
    var listener: ((String) -> Unit)? = null
    private var lastCardKey: List<Any>? = null
    private var lastMusicKey: List<Any>? = null
    private var lastTurn: Turn? = null
    private var summaryHandler: Handler? = null
    private val summaryTick = object : Runnable {
        override fun run() {
            summary().forEach { listener?.invoke(it) }
            summaryHandler?.postDelayed(this, SUMMARY_MILLIS)
        }
    }

    fun appearance(night: Boolean, setting: Int, source: NightSource) {
        this.night = night
        themeSetting = when (setting) { 1 -> "day"; 2 -> "night"; else -> "follow" }
        this.source = source
        changed()
    }

    fun music(owner: Any, mode: MusicMode, lyricLines: Int, titleChanges: Int,
        lyricField: ClusterLyricState.Field, hideReason: HideReason, confirmedAfterMs: Long,
        coverDelayMs: Long, fit: Fit, trackRevision: Long = 0) {
        views[owner] = Music(mode, lyricLines.coerceIn(0, 2), titleChanges.coerceAtLeast(0),
            lyricField, hideReason, confirmedAfterMs.coerceAtLeast(-1), coverDelayMs.coerceAtLeast(-1), fit,
            trackRevision)
        changed()
    }

    fun turn(owner: Any, variant: TurnVariant, night: Boolean) {
        turns[owner] = Turn(variant, night)
        val current = turns.values.lastOrNull()
        if (current != lastTurn) {
            lastTurn = current
            turnReport()?.let { listener?.invoke(it) }
        }
    }

    fun remove(owner: Any) {
        if (views.remove(owner) != null) changed()
        if (turns.remove(owner) != null) {
            val current = turns.values.lastOrNull()
            if (current != lastTurn) {
                lastTurn = current
                turnReport()?.let { listener?.invoke(it) }
            }
        }
    }

    fun nowPlayingUpdate() { if (nowPlayingUpdates < Int.MAX_VALUE) nowPlayingUpdates++ }
    fun lineChange() { if (lineChanges < Int.MAX_VALUE) lineChanges++ }

    fun report(): String {
        val music = currentMusic()
        return "Cluster cards: night=${if (night) "night" else "day"} themeSetting=$themeSetting " +
            "musicMode=${music.mode.name.lowercase(Locale.ROOT)} lyricLines=${music.lyricLines} " +
            "titleChanges=${music.titleChanges} nightSource=${source.name.lowercase(Locale.ROOT)} " +
            "lyricField=${music.lyricField.name.lowercase(Locale.ROOT)}"
    }

    fun musicReport(): String {
        val music = currentMusic()
        return "Cluster music: cardVisible=${music.mode.name.lowercase(Locale.ROOT)} " +
            "hideReason=${music.hideReason.value} lyricConfirmedAfterMs=${music.confirmedAfterMs} " +
            "coverDelayMs=${music.coverDelayMs} nowPlayingUpdates=$nowPlayingUpdates " +
            "lineChanges=$lineChanges fit=${music.fit.name.lowercase(Locale.ROOT)}"
    }

    fun turnReport(): String? = turns.values.lastOrNull()?.let {
        "Turn card: variant=${it.variant.value} night=${if (it.night) "night" else "day"}"
    }

    fun summary(): List<String> {
        val lines = listOfNotNull(report(), musicReport(), turnReport())
        nowPlayingUpdates = 0
        lineChanges = 0
        return lines
    }

    fun startSummaries(handler: Handler) {
        summaryHandler?.removeCallbacks(summaryTick)
        summaryHandler = handler
        handler.postDelayed(summaryTick, SUMMARY_MILLIS)
    }

    fun stopSummaries(handler: Handler) {
        if (summaryHandler !== handler) return
        handler.removeCallbacks(summaryTick)
        summaryHandler = null
    }

    private fun currentMusic() = views.values.lastOrNull() ?: Music()

    private fun changed() {
        val music = currentMusic()
        val cardKey = listOf(night, themeSetting, source, music.mode, music.lyricField, music.trackRevision)
        if (cardKey != lastCardKey) {
            lastCardKey = cardKey
            listener?.invoke(report())
        }
        val musicKey = listOf(music.mode, music.hideReason, music.fit, music.trackRevision,
            music.confirmedAfterMs, music.coverDelayMs)
        if (musicKey != lastMusicKey) {
            lastMusicKey = musicKey
            listener?.invoke(musicReport())
        }
    }

    internal const val SUMMARY_MILLIS = 60_000L
}
