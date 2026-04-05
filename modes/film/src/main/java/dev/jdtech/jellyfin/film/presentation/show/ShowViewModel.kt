package dev.jdtech.jellyfin.film.presentation.show

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadQueue
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItemPerson
import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.models.isDownloaded
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.utils.Downloader
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.PersonKind
import timber.log.Timber

@HiltViewModel
class ShowViewModel
@Inject
constructor(
    private val repository: JellyfinRepository,
    private val downloader: Downloader,
    private val downloadQueue: DownloadQueue,
) : ViewModel() {
    private val _state = MutableStateFlow(ShowState())
    val state = _state.asStateFlow()

    private val eventsChannel = Channel<ShowEvent>()
    val events = eventsChannel.receiveAsFlow()

    lateinit var showId: UUID
    private var downloadsOnly: Boolean = false

    fun loadShow(showId: UUID, downloadsOnly: Boolean = this.downloadsOnly) {
        this.showId = showId
        this.downloadsOnly = downloadsOnly
        viewModelScope.launch {
            try {
                val show = repository.getShow(showId)
                val nextUp = if (downloadsOnly) null else getNextUp(showId)
                val seasons = repository.getSeasons(showId, offline = downloadsOnly)
                val actors = getActors(show)
                val director = getDirector(show)
                val writers = getWriters(show)
                val seasonDownloadInfo = mutableMapOf<UUID, SeasonDownloadInfo>()
                var hasDownloads = false
                for (season in seasons) {
                    val episodes =
                        repository.getEpisodes(
                            seriesId = showId,
                            seasonId = season.id,
                            offline = downloadsOnly,
                        )
                    val downloadedCount = episodes.count { it.isDownloaded() }
                    if (downloadedCount > 0) hasDownloads = true
                    seasonDownloadInfo[season.id] =
                        SeasonDownloadInfo(
                            downloadedCount = downloadedCount,
                            totalCount = episodes.size,
                        )
                }
                _state.emit(
                    _state.value.copy(
                        show = show,
                        nextUp = nextUp,
                        seasons = seasons,
                        seasonDownloadInfo = seasonDownloadInfo,
                        actors = actors,
                        director = director,
                        writers = writers,
                        hasDownloads = hasDownloads,
                    )
                )
            } catch (e: Exception) {
                _state.emit(_state.value.copy(error = e))
            }
        }
    }

    fun downloadSeasons(seasonIds: Set<UUID>) {
        viewModelScope.launch(Dispatchers.IO) {
            // Episodes already in flight count as skipped so the toast count
            // matches what actually got added to the queue.
            val liveQueuedIds =
                downloadQueue.entries.value
                    .filter {
                        it.state is DownloadQueue.EntryState.Pending ||
                            it.state is DownloadQueue.EntryState.Downloading ||
                            it.state is DownloadQueue.EntryState.Paused
                    }
                    .map { it.id }
                    .toSet()
            val toQueue = mutableListOf<FindroidEpisode>()
            var skipped = 0
            for (seasonId in seasonIds) {
                val episodes = repository.getEpisodes(seriesId = showId, seasonId = seasonId)
                for (episode in episodes) {
                    if (episode.isDownloaded() || episode.id in liveQueuedIds) skipped++
                    else toQueue.add(episode)
                }
            }
            downloadQueue.enqueueAll(toQueue)
            eventsChannel.send(ShowEvent.DownloadResult(toQueue.size, skipped, 0))
            loadShow(showId)
        }
    }

    fun deleteShowDownloads() {
        viewModelScope.launch(Dispatchers.IO) {
            for (season in _state.value.seasons) {
                val episodes =
                    repository.getEpisodes(
                        seriesId = showId,
                        seasonId = season.id,
                        offline = true,
                    )
                for (episode in episodes) {
                    val localSource =
                        episode.sources.firstOrNull { it.type == FindroidSourceType.LOCAL }
                    if (localSource != null) {
                        downloader.deleteItem(item = episode, source = localSource)
                    }
                }
            }
            loadShow(showId)
        }
    }

    private suspend fun getNextUp(showId: UUID): FindroidEpisode? {
        val nextUpItems = repository.getNextUp(showId)
        return nextUpItems.getOrNull(0)
    }

    private suspend fun getActors(item: FindroidShow): List<FindroidItemPerson> {
        return withContext(Dispatchers.Default) {
            item.people.filter { it.type == PersonKind.ACTOR }
        }
    }

    private suspend fun getDirector(item: FindroidShow): FindroidItemPerson? {
        return withContext(Dispatchers.Default) {
            item.people.firstOrNull { it.type == PersonKind.DIRECTOR }
        }
    }

    private suspend fun getWriters(item: FindroidShow): List<FindroidItemPerson> {
        return withContext(Dispatchers.Default) {
            item.people.filter { it.type == PersonKind.WRITER }
        }
    }

    fun onAction(action: ShowAction) {
        when (action) {
            is ShowAction.MarkAsPlayed -> {
                viewModelScope.launch {
                    try {
                        repository.markAsPlayed(showId)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to mark as played")
                    }
                    loadShow(showId)
                }
            }
            is ShowAction.UnmarkAsPlayed -> {
                viewModelScope.launch {
                    try {
                        repository.markAsUnplayed(showId)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to unmark as played")
                    }
                    loadShow(showId)
                }
            }
            is ShowAction.MarkAsFavorite -> {
                viewModelScope.launch {
                    try {
                        repository.markAsFavorite(showId)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to mark as favorite")
                    }
                    loadShow(showId)
                }
            }
            is ShowAction.UnmarkAsFavorite -> {
                viewModelScope.launch {
                    try {
                        repository.unmarkAsFavorite(showId)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to unmark as favorite")
                    }
                    loadShow(showId)
                }
            }
            else -> Unit
        }
    }

    override fun onCleared() {
        super.onCleared()
        eventsChannel.close()
    }
}
