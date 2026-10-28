package com.shilapi.xcertplay

import android.content.Context
import android.content.SharedPreferences

internal object ClusterMusicSettings {
    private const val PREFIX = "cluster_music_"
    private fun prefs(context: Context) = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean(PREFIX + "enabled", false)
    fun permanent(context: Context) = prefs(context).getBoolean(PREFIX + "permanent", false)
    fun temporarySeconds(context: Context): Int = prefs(context).getInt(PREFIX + "seconds", 8)
        .takeIf { it in setOf(3, 5, 8) } ?: 8
    fun temporaryMillis(context: Context): Long = temporarySeconds(context) * 1_000L
    fun x(context: Context) = prefs(context).getInt(PREFIX + "x", 50).coerceIn(5, 95)
    fun y(context: Context) = prefs(context).getInt(PREFIX + "y", 75).coerceIn(5, 95)
    fun size(context: Context) = prefs(context).getInt(PREFIX + "size", 100).coerceIn(50, 300)
    fun saveEnabled(context: Context, value: Boolean) { prefs(context).edit().putBoolean(PREFIX + "enabled", value).apply() }
    fun savePermanent(context: Context, value: Boolean) { prefs(context).edit().putBoolean(PREFIX + "permanent", value).apply() }
    fun saveTemporarySeconds(context: Context, seconds: Int) {
        prefs(context).edit().putInt(PREFIX + "seconds", seconds.takeIf { it in setOf(3, 5, 8) } ?: 8).apply()
    }
    fun save(context: Context, key: String, value: Int) {
        require(key in setOf("x", "y", "size"))
        prefs(context).edit().putInt(PREFIX + key, value.coerceIn(if (key == "size") 50..300 else 5..95)).apply()
    }
    fun observe(context: Context, changed: () -> Unit): () -> Unit {
        val prefs = prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key.startsWith(PREFIX)) changed()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
