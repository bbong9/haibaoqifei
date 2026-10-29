package com.shilapi.xcertplay.settings

import android.content.Context
import com.shilapi.xcertplay.DiPlayPalette

class SettingsTheme private constructor(
    val isOverlay: Boolean,
    val isSettingsPage: Boolean,
    val textPrimary: Int,
    val textSecondary: Int,
    val accent: Int,
    val accentTrack: Int,
    val trackOff: Int,
    val buttonText: Int,
    val ripple: Int,
    val focusRing: Int,
) {

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    companion object {
        /** Dark aliases keep existing overlay callers source-compatible until Phase 4. */
        @JvmField val CARD = card(DiPlayPalette.DARK)
        @JvmField val OVERLAY = overlay(DiPlayPalette.DARK)
        @JvmField val entries = listOf(CARD, OVERLAY)

        internal fun card(palette: DiPlayPalette) = SettingsTheme(
            isOverlay = false,
            isSettingsPage = false,
            textPrimary = palette.primaryText,
            textSecondary = palette.secondaryText,
            accent = palette.accent,
            accentTrack = palette.accentTrack,
            trackOff = palette.trackOff,
            buttonText = palette.onAccent,
            ripple = palette.ripple,
            focusRing = palette.focusRing,
        )

        internal fun overlay(palette: DiPlayPalette) = SettingsTheme(
            isOverlay = true,
            isSettingsPage = false,
            textPrimary = palette.overlayPrimaryText,
            textSecondary = palette.overlaySecondaryText,
            accent = palette.overlayAccent,
            accentTrack = palette.overlayAccentTrack,
            trackOff = palette.overlayTrackOff,
            buttonText = palette.overlayOnAccent,
            ripple = palette.ripple,
            focusRing = palette.focusRing,
        )

        internal fun settings(palette: SettingsPalette) = SettingsTheme(
            isOverlay = false,
            isSettingsPage = true,
            textPrimary = palette.text,
            textSecondary = palette.secondary,
            accent = palette.accent,
            accentTrack = palette.accent,
            trackOff = palette.switchOff,
            buttonText = palette.onAccent,
            ripple = palette.selected,
            focusRing = palette.selectedText,
        )
    }
}
