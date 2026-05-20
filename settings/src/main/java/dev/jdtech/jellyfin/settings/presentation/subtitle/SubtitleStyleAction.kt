package dev.jdtech.jellyfin.settings.presentation.subtitle

import dev.jdtech.jellyfin.settings.presentation.models.Preference

sealed interface SubtitleStyleAction {
    data object OnBackClick : SubtitleStyleAction

    data object OnOpenSystemCaptionSettings : SubtitleStyleAction

    data class OnUpdate(val preference: Preference) : SubtitleStyleAction
}
