package dev.jdtech.jellyfin.di

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.settings.domain.AppPreferences

/**
 * Allows non-Hilt-injected sites (e.g. AndroidView factory blocks inside Compose) to fetch the
 * singleton [AppPreferences] when they need to apply subtitle styling to a freshly-created
 * SubtitleView.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SubtitleStyleEntryPoint {
    fun appPreferences(): AppPreferences
}
