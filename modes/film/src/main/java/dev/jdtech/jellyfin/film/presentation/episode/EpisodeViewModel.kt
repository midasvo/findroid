package dev.jdtech.jellyfin.film.presentation.episode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.film.domain.VideoMetadataParser
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItemPerson
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.PersonKind

@HiltViewModel
class EpisodeViewModel
@Inject
constructor(
    private val repository: JellyfinRepository,
    private val appPreferences: AppPreferences,
    private val videoMetadataParser: VideoMetadataParser,
) : ViewModel() {
    private val _state = MutableStateFlow(EpisodeState())
    val state = _state.asStateFlow()

    lateinit var episodeId: UUID

    fun loadEpisode(episodeId: UUID) {
        this.episodeId = episodeId
        viewModelScope.launch {
            try {
                val episode = repository.getEpisode(episodeId)
                val videoMetadata = episode.sources.firstOrNull()?.let { videoMetadataParser.parse(it) }
                val actors = getActors(episode)
                val displayExtraInfo = appPreferences.getValue(appPreferences.displayExtraInfo)
                _state.emit(
                    _state.value.copy(
                        episode = episode,
                        videoMetadata = videoMetadata,
                        actors = actors,
                        displayExtraInfo = displayExtraInfo,
                    )
                )
            } catch (e: Exception) {
                _state.emit(_state.value.copy(error = e))
            }
        }
    }

    private suspend fun getActors(item: FindroidEpisode): List<FindroidItemPerson> {
        return withContext(Dispatchers.Default) {
            item.people.filter { it.type == PersonKind.ACTOR }
        }
    }

    fun onAction(action: EpisodeAction) {
        when (action) {
            is EpisodeAction.MarkAsPlayed -> {
                viewModelScope.launch {
                    try {
                        repository.markAsPlayed(episodeId)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to mark as played")
                    }
                    loadEpisode(episodeId)
                }
            }
            is EpisodeAction.UnmarkAsPlayed -> {
                viewModelScope.launch {
                    try {
                        repository.markAsUnplayed(episodeId)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to unmark as played")
                    }
                    loadEpisode(episodeId)
                }
            }
            is EpisodeAction.MarkAsFavorite -> {
                viewModelScope.launch {
                    try {
                        repository.markAsFavorite(episodeId)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to mark as favorite")
                    }
                    loadEpisode(episodeId)
                }
            }
            is EpisodeAction.UnmarkAsFavorite -> {
                viewModelScope.launch {
                    try {
                        repository.unmarkAsFavorite(episodeId)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to unmark as favorite")
                    }
                    loadEpisode(episodeId)
                }
            }
            else -> Unit
        }
    }
}
