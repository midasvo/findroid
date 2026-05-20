package dev.jdtech.jellyfin.settings.presentation.subtitle

import android.content.Intent

sealed interface SubtitleStyleEvent {
    data class LaunchIntent(val intent: Intent) : SubtitleStyleEvent
}
