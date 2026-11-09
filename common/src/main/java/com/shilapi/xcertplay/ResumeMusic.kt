package com.shilapi.xcertplay

import android.content.Context
import java.security.MessageDigest
import java.util.Locale

/** A short-lived, phone-specific record. Disconnect-generated playback resets are not recorded here. */
internal object ResumeMusic {
    private const val PREFS = "diplay_music_resume"
    private const val MAX_AGE_MILLIS = 24 * 60 * 60 * 1000L

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences("diplay", Context.MODE_PRIVATE).getBoolean("resume_music", false)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit().putBoolean("resume_music", value).apply()
        if (!value) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("playing", false).apply()
    }

    fun record(context: Context, phone: String?, playing: Boolean, now: Long = System.currentTimeMillis()) {
        if (!enabled(context) || !matchesSelectedPhone(context, phone)) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("phone", phoneHash(phone!!))
            .putBoolean("playing", playing)
            .putLong("at", now)
            .apply()
    }

    fun shouldResume(context: Context, phone: String?, now: Long = System.currentTimeMillis()): Boolean {
        if (!enabled(context) || !matchesSelectedPhone(context, phone)) return false
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val age = now - saved.getLong("at", 0)
        return saved.getBoolean("playing", false) && saved.getString("phone", null) == phoneHash(phone!!) &&
            age in 0..MAX_AGE_MILLIS
    }

    /** Refresh only an already-confirmed playing record; never undo a user or phone pause. */
    fun refreshPlayingRecord(context: Context, phone: String?, now: Long = System.currentTimeMillis()) {
        if (!enabled(context) || !matchesSelectedPhone(context, phone)) return
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (saved.getBoolean("playing", false) && saved.getString("phone", null) == phoneHash(phone!!)) {
            saved.edit().putLong("at", now).apply()
        }
    }

    private fun matchesSelectedPhone(context: Context, phone: String?): Boolean =
        !phone.isNullOrBlank() && phone.equals(DiPlayPreferences.phoneAddress(context), ignoreCase = true)

    private fun phoneHash(phone: String): String = MessageDigest.getInstance("SHA-256")
        .digest(phone.uppercase(Locale.ROOT).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
