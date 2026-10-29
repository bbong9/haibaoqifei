package com.shilapi.xcertplay.media

enum class SmoothVideoLatency(val preferenceValue: Int, val diagnosticValue: String) {
    BALANCED(0, "balanced"),
    LOW(1, "low");

    companion object {
        fun fromPreferenceValue(value: Int): SmoothVideoLatency =
            entries.firstOrNull { it.preferenceValue == value } ?: BALANCED
    }
}
