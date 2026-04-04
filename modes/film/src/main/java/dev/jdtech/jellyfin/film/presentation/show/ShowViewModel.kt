package dev.jdtech.jellyfin.film.presentation.show

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItemPerson
import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.models.isDownloaded
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.utils.Downloader
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.PersonKind

@HiltViewModel
class ShowViewModel
@Inject
constructor(
    private val repository: JellyfinRepository,
    private val downloader: Downloader,
    private val appPreferences: AppPreferences,
) : ViewModel() {
    private val _state = MutableStateFlow(ShowState())
    val state = _state.asStateFlow()

    private val eventsChannel = Channel<ShowEvent>()
    val events = eventsChannel.receiveAsFlow()

    lateinit var showId: UUID

    fun loadShow(showId: UUID) {
        this.showId = showId
        viewModelScope.launch {
            try {
                val show = repository.getShow(showId)
                val nextUp = getNextUp(showId)
                val seasons = repository.getSeasons(showId)
                val actors = getActors(show)
                val director = getDirector(show)
                val writers = getWriters(show)
                val seasonDownloadInfo = mutableMapOf<UUID, SeasonDownloadInfo>()
                var hasDownloads = false
                for (season in seasons) {
                    val episodes =
                        repository.getEpisodes(seriesId = showId, seasonId = season.id)
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
            val maxConcurrent =
                appPreferences.getValue(appPreferences.maxConcurrentDownloads)
            val storageIndex =
                appPreferences.getValue(appPreferences.downloadStorageIndex)?.toIntOrNull() ?: 0
            var started = 0
            var skipped = 0
            var failed = 0
            var activeCount = 0

            for (seasonId in seasonIds) {
                val episodes = repository.getEpisodes(seriesId = showId, seasonId = seasonId)
                for (episode in episodes) {
                    if (episode.isDownloaded()) {
                        skipped++
                        continue
                    }

                    // Wait for a slot to open up
                    while (activeCount >= maxConcurrent) {
                        delay(2000L)
                        // Recount active downloads
                        activeCount = countActiveDownloads()
                    }

                    val sources =
                        try {
                            repository.getMediaSources(episode.id)
                        } catch (_: Exception) {
                            failed++
                            continue
                        }
                    val sourceId = sources.firstOrNull()?.id
                    if (sourceId == null) {
                        failed++
                        continue
                    }
                    val (downloadId, _) =
                        downloader.downloadItem(
                            item = episode,
                            sourceId = sourceId,
                            storageIndex = storageIndex,
                        )
                    if (downloadId != -1L) {
                        started++
                        activeCount++
                    } else {
                        failed++
                    }
                }
            }
            eventsChannel.send(ShowEvent.DownloadResult(started, skipped, failed))
            loadShow(showId)
        }
    }

    private suspend fun countActiveDownloads(): Int {
        return withContext(Dispatchers.IO) {
            val allSeasons = _state.value.seasons
            var count = 0
            for (season in allSeasons) {
                val episodes = repository.getEpisodes(
                    seriesId = showId,
                    seasonId = season.id,
                    offline = true,
                )
                count += episodes.count { episode ->
                    episode.sources.any {
                        it.type == FindroidSourceType.LOCAL && it.path.endsWith(".download")
                    }
                }
            }
            count
        }
    }

    fun deleteShowDownloads() {
        viewModelScope.launch(Dispatchers.IO) {
            for (season in _state.value.seasons) {
                val episodes = repository.getEpisodes(seriesId = showId, seasonId = season.id)
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
                    repository.markAsPlayed(showId)
                    loadShow(showId)
                }
            }
            is ShowAction.UnmarkAsPlayed -> {
                viewModelScope.launch {
                    repository.markAsUnplayed(showId)
                    loadShow(showId)
                }
            }
            is ShowAction.MarkAsFavorite -> {
                viewModelScope.launch {
                    repository.markAsFavorite(showId)
                    loadShow(showId)
                }
            }
            is ShowAction.UnmarkAsFavorite -> {
                viewModelScope.launch {
                    repository.unmarkAsFavorite(showId)
                    loadShow(showId)
                }
            }
            else -> Unit
        }
    }
}
