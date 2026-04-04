package dev.jdtech.jellyfin.film.presentation.movie

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.film.domain.VideoMetadataParser
import dev.jdtech.jellyfin.models.FindroidItemPerson
import dev.jdtech.jellyfin.models.FindroidMovie
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
class MovieViewModel
@Inject
constructor(
    private val repository: JellyfinRepository,
    private val appPreferences: AppPreferences,
    private val videoMetadataParser: VideoMetadataParser,
) : ViewModel() {
    private val _state = MutableStateFlow(MovieState())
    val state = _state.asStateFlow()

    lateinit var movieId: UUID

    fun loadMovie(movieId: UUID) {
        this.movieId = movieId
        viewModelScope.launch {
            try {
                val movie = repository.getMovie(movieId)
                val videoMetadata = movie.sources.firstOrNull()?.let { videoMetadataParser.parse(it) }
                val actors = getActors(movie)
                val director = getDirector(movie)
                val writers = getWriters(movie)
                val displayExtraInfo = appPreferences.getValue(appPreferences.displayExtraInfo)
                _state.emit(
                    _state.value.copy(
                        movie = movie,
                        videoMetadata = videoMetadata,
                        actors = actors,
                        director = director,
                        writers = writers,
                        displayExtraInfo = displayExtraInfo,
                    )
                )
            } catch (e: Exception) {
                _state.emit(_state.value.copy(error = e))
            }
        }
    }

    private suspend fun getActors(item: FindroidMovie): List<FindroidItemPerson> {
        return withContext(Dispatchers.Default) {
            item.people.filter { it.type == PersonKind.ACTOR }
        }
    }

    private suspend fun getDirector(item: FindroidMovie): FindroidItemPerson? {
        return withContext(Dispatchers.Default) {
            item.people.firstOrNull { it.type == PersonKind.DIRECTOR }
        }
    }

    private suspend fun getWriters(item: FindroidMovie): List<FindroidItemPerson> {
        return withContext(Dispatchers.Default) {
            item.people.filter { it.type == PersonKind.WRITER }
        }
    }

    fun onAction(action: MovieAction) {
        when (action) {
            is MovieAction.MarkAsPlayed -> {
                viewModelScope.launch {
                    try {
                        repository.markAsPlayed(movieId)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to mark as played")
                    }
                    loadMovie(movieId)
                }
            }
            is MovieAction.UnmarkAsPlayed -> {
                viewModelScope.launch {
                    try {
                        repository.markAsUnplayed(movieId)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to unmark as played")
                    }
                    loadMovie(movieId)
                }
            }
            is MovieAction.MarkAsFavorite -> {
                viewModelScope.launch {
                    try {
                        repository.markAsFavorite(movieId)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to mark as favorite")
                    }
                    loadMovie(movieId)
                }
            }
            is MovieAction.UnmarkAsFavorite -> {
                viewModelScope.launch {
                    try {
                        repository.unmarkAsFavorite(movieId)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to unmark as favorite")
                    }
                    loadMovie(movieId)
                }
            }
            else -> Unit
        }
    }
}
