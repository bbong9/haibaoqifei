package com.shilapi.xcertplay

internal enum class SealHiddenControl {
    MFI_TARGET,
    DEFAULT_CONNECTION_MODE,
    USB_AUTO_CONFIRM,
    ADAPT_PIP_RESOLUTION,
    WIRED_MUSIC_BUFFER,
    RIGHT_HAND_DRIVE,
    USB_CONNECTION_ENTRY,
    USB_AUTO_CONFIRM_HINT,
    PLATFORM21_CLUSTER,
    DILINK3_ROUTE_CONFLICT,
    DILINK3_PROJECTION_SHAPE,
    DILINK3_CALL_CONTROLS,
    OTHER_DILINK_GENERATIONS,
    DILINK51_CLUSTER_LAYOUT,
    PHONE_TABLET_SPEAKERPHONE,
}

internal object SealSettingsScope {
    val hiddenCategories = setOf(SettingsCategory.ADVANCED, SettingsCategory.LANGUAGE, SettingsCategory.ABOUT)
    val categoryAliases = mapOf(
        SettingsCategory.LANGUAGE to SettingsCategory.DIAGNOSTICS,
        SettingsCategory.ABOUT to SettingsCategory.DIAGNOSTICS,
    )
    val relocatedSections = mapOf(
        SettingsSection.AMBIENT_LIGHTING to SettingsCategory.VEHICLE,
        SettingsSection.CLUSTER_MAP to SettingsCategory.NAVIGATION,
        SettingsSection.LANGUAGE to SettingsCategory.DIAGNOSTICS,
    )
    val hiddenSections = setOf(SettingsSection.USB_CONNECTION) +
        (SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.ADVANCED) - relocatedSections.keys)
    val hiddenControls = SealHiddenControl.entries.toSet()
}
