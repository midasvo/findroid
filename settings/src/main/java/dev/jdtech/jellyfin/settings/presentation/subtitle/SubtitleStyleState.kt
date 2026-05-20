package dev.jdtech.jellyfin.settings.presentation.subtitle

import dev.jdtech.jellyfin.settings.presentation.models.PreferenceGroup

data class SubtitleStyleState(
    val preferenceGroups: List<PreferenceGroup> = emptyList(),
    // Flattened values used by the preview pane so the screen does not need to
    // dig into every PreferenceSelect to read its current value.
    val foregroundColor: String,
    val backgroundColor: String,
    val edgeColor: String,
    val edgeType: String,
    val fontFamily: String,
    val fontScale: Int,
)
