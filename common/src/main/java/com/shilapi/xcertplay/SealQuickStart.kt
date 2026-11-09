package com.shilapi.xcertplay

import android.content.Context
import java.util.UUID

/** The Seal's first connection uses the Wi-Fi Direct route already used by this edition. */
internal object SealQuickStart {
    private const val PREFERENCES = "diplay"
    private const val ENABLED = "seal_quick_start"
    private const val AIRPLAY_PREFERENCES = "xcertplay_airplay"
    private val iap2Service = UUID.fromString("00000000-deca-fade-deca-deafdecacafe")
    private val reversedIap2Service = UUID(
        java.lang.Long.reverseBytes(iap2Service.leastSignificantBits),
        java.lang.Long.reverseBytes(iap2Service.mostSignificantBits),
    )

    fun prepare(context: Context): Boolean = prepare(context, SealPerformanceProfile.enabled(context), freshInstall(context))

    /** Never replace restored preferences, a previous setup, or a connection choice on upgrade. */
    @Synchronized internal fun prepare(context: Context, sealEdition: Boolean, freshInstall: Boolean): Boolean {
        if (!sealEdition) return false
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (preferences.getBoolean(ENABLED, false)) return true
        if (!freshInstall || preferences.all.isNotEmpty() ||
            context.getSharedPreferences(AIRPLAY_PREFERENCES, Context.MODE_PRIVATE).all.isNotEmpty()) return false
        return preferences.edit().putBoolean(ENABLED, true).commit()
    }

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    private fun freshInstall(context: Context): Boolean = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.firstInstallTime > 0L && info.firstInstallTime == info.lastUpdateTime
    }.getOrDefault(false)

    /** Do not infer a target when more than one known iPhone is paired. */
    fun <T> singleLikelyIphone(
        devices: Iterable<T>,
        name: (T) -> String?,
        services: (T) -> Iterable<UUID>,
    ): T? = devices.filter { device ->
        name(device)?.contains("iPhone", ignoreCase = true) == true ||
            services(device).any { it == iap2Service || it == reversedIap2Service }
    }.singleOrNull()
}
