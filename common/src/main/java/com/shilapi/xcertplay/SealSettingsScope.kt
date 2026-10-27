package com.shilapi.xcertplay

internal enum class SealHiddenControl {
    MFI_TARGET,
    DEFAULT_CONNECTION_MODE,
    USB_AUTO_CONFIRM,
    ADAPT_PIP_RESOLUTION,
    WIRED_MUSIC_BUFFER,
    RIGHT_HAND_DRIVE,
}

internal object SealSettingsScope {
    val hiddenCategories = setOf(SettingsCategory.ADVANCED)
    val relocatedSections = mapOf(
        SettingsSection.AMBIENT_LIGHTING to SettingsCategory.VEHICLE,
        SettingsSection.CLUSTER_MAP to SettingsCategory.NAVIGATION,
    )
    val hiddenSections = setOf(SettingsSection.USB_CONNECTION) +
        (SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.ADVANCED) - relocatedSections.keys)
    val hiddenControls = SealHiddenControl.entries.toSet()
}
