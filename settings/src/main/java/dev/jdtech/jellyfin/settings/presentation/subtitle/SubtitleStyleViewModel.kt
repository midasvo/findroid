package dev.jdtech.jellyfin.settings.presentation.subtitle

import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.settings.R
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.Constants
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceGroup
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceIntInput
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceSelect
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class SubtitleStyleViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
) : ViewModel() {
    private val _state =
        MutableStateFlow(
            SubtitleStyleState(
                preferenceGroups = emptyList(),
                foregroundColor =
                    appPreferences.getValue(appPreferences.subtitleForegroundColor)
                        ?: Constants.SubtitleStyle.DEFAULT_FG_COLOR,
                backgroundColor =
                    appPreferences.getValue(appPreferences.subtitleBackgroundColor)
                        ?: Constants.SubtitleStyle.DEFAULT_BG_COLOR,
                edgeColor =
                    appPreferences.getValue(appPreferences.subtitleEdgeColor)
                        ?: Constants.SubtitleStyle.DEFAULT_EDGE_COLOR,
                edgeType =
                    appPreferences.getValue(appPreferences.subtitleEdgeType)
                        ?: Constants.SubtitleStyle.EDGE_OUTLINE,
                fontFamily =
                    appPreferences.getValue(appPreferences.subtitleFontFamily)
                        ?: Constants.SubtitleStyle.FONT_DEFAULT,
                fontScale = appPreferences.getValue(appPreferences.subtitleFontScale),
            )
        )
    val state = _state.asStateFlow()

    private val eventsChannel = Channel<SubtitleStyleEvent>()
    val events = eventsChannel.receiveAsFlow()

    init {
        rebuildPreferences()
    }

    fun onAction(action: SubtitleStyleAction) {
        when (action) {
            is SubtitleStyleAction.OnUpdate -> {
                when (val preference = action.preference) {
                    is PreferenceSelect ->
                        appPreferences.setValue(
                            preference.backendPreference,
                            preference.value,
                        )
                    is PreferenceIntInput ->
                        appPreferences.setValue(
                            preference.backendPreference,
                            preference.value.coerceIn(
                                Constants.SubtitleStyle.FONT_SCALE_MIN,
                                Constants.SubtitleStyle.FONT_SCALE_MAX,
                            ),
                        )
                    else -> Unit
                }
                rebuildPreferences()
            }
            is SubtitleStyleAction.OnOpenSystemCaptionSettings -> {
                viewModelScope.launch {
                    eventsChannel.send(
                        SubtitleStyleEvent.LaunchIntent(
                            Intent(Settings.ACTION_CAPTIONING_SETTINGS)
                        )
                    )
                }
            }
            is SubtitleStyleAction.OnBackClick -> Unit
        }
    }

    private fun rebuildPreferences() {
        val groups =
            listOf(
                PreferenceGroup(
                    nameStringResource = R.string.subtitle_style,
                    preferences =
                        listOf(
                            PreferenceSelect(
                                nameStringResource = R.string.subtitle_foreground_color,
                                iconDrawableId = R.drawable.ic_palette,
                                backendPreference = appPreferences.subtitleForegroundColor,
                                options = R.array.subtitle_foreground_colors,
                                optionValues = R.array.subtitle_foreground_color_values,
                                value =
                                    appPreferences.getValue(
                                        appPreferences.subtitleForegroundColor
                                    ),
                            ),
                            PreferenceSelect(
                                nameStringResource = R.string.subtitle_background_color,
                                backendPreference = appPreferences.subtitleBackgroundColor,
                                options = R.array.subtitle_background_colors,
                                optionValues = R.array.subtitle_background_color_values,
                                value =
                                    appPreferences.getValue(
                                        appPreferences.subtitleBackgroundColor
                                    ),
                            ),
                            PreferenceSelect(
                                nameStringResource = R.string.subtitle_edge_type,
                                backendPreference = appPreferences.subtitleEdgeType,
                                options = R.array.subtitle_edge_types,
                                optionValues = R.array.subtitle_edge_type_values,
                                value = appPreferences.getValue(appPreferences.subtitleEdgeType),
                            ),
                            PreferenceSelect(
                                nameStringResource = R.string.subtitle_edge_color,
                                backendPreference = appPreferences.subtitleEdgeColor,
                                options = R.array.subtitle_edge_colors,
                                optionValues = R.array.subtitle_edge_color_values,
                                value =
                                    appPreferences.getValue(appPreferences.subtitleEdgeColor),
                            ),
                            PreferenceSelect(
                                nameStringResource = R.string.subtitle_font_family,
                                backendPreference = appPreferences.subtitleFontFamily,
                                options = R.array.subtitle_font_families,
                                optionValues = R.array.subtitle_font_family_values,
                                value =
                                    appPreferences.getValue(appPreferences.subtitleFontFamily),
                            ),
                            PreferenceIntInput(
                                nameStringResource = R.string.subtitle_font_scale,
                                backendPreference = appPreferences.subtitleFontScale,
                                suffixRes = R.string.subtitle_font_scale_suffix,
                                value = appPreferences.getValue(appPreferences.subtitleFontScale),
                            ),
                        ),
                )
            )

        _state.update {
            it.copy(
                preferenceGroups = groups,
                foregroundColor =
                    appPreferences.getValue(appPreferences.subtitleForegroundColor)
                        ?: Constants.SubtitleStyle.DEFAULT_FG_COLOR,
                backgroundColor =
                    appPreferences.getValue(appPreferences.subtitleBackgroundColor)
                        ?: Constants.SubtitleStyle.DEFAULT_BG_COLOR,
                edgeColor =
                    appPreferences.getValue(appPreferences.subtitleEdgeColor)
                        ?: Constants.SubtitleStyle.DEFAULT_EDGE_COLOR,
                edgeType =
                    appPreferences.getValue(appPreferences.subtitleEdgeType)
                        ?: Constants.SubtitleStyle.EDGE_OUTLINE,
                fontFamily =
                    appPreferences.getValue(appPreferences.subtitleFontFamily)
                        ?: Constants.SubtitleStyle.FONT_DEFAULT,
                fontScale = appPreferences.getValue(appPreferences.subtitleFontScale),
            )
        }
    }
}
