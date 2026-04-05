package dev.jdtech.jellyfin.film.presentation.season

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadProgress
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadQueue
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadStatus
import dev.jdtech.jellyfin.models.FindroidEpisode
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
import org.jellyfin.sdk.model.api.ItemFields
import timber.log.Timber

@HiltViewModel
class SeasonViewModel
@Inject
constructor(
    private val repository: JellyfinRepository,
    private val downloader: Downloader,
    private val downloadQueue: DownloadQueue,
) : ViewModel() {
    private val _state = MutableStateFlow(SeasonState())
    val state = _state.asStateFlow()

    private val eventsChannel = Channel<SeasonEvent>()
    val events = eventsChannel.receiveAsFlow()

    lateinit var seasonId: UUID
    private var downloadsOnly: Boolean = false

    private var wasBusy = false

    init {
        // Observe queue entries and merge into per-episode progress map.
        viewModelScope.launch {
            downloadQueue.entries.collect { entries ->
                val episodes = _state.value.episodes
                if (episodes.isEmpty()) return@collect
                _state.emit(
                    _state.value.copy(
                        episodeDownloadProgress = buildDownloadProgressMap(episodes, entries)
                    )
                )
                // Refresh season data once all of this season's downloads have settled,
                // so episode.sources gets the new LOCAL entry. Firing per-episode would
                // cause N network roundtrips.
                val episodeIds = episodes.map { it.id }.toSet()
                val isBusy =
                    entries.any {
                        it.id in episodeIds &&
                            (it.state is DownloadQueue.EntryState.Downloading ||
                                it.state is DownloadQueue.EntryState.Pending)
                    }
                if (isBusy) {
                    wasBusy = true
                } else if (wasBusy && ::seasonId.isInitialized) {
                    wasBusy = false
                    loadSeason(seasonId)
                }
            }
        }
    }

    fun loadSeason(seasonId: UUID, downloadsOnly: Boolean = this.downloadsOnly) {
        this.seasonId = seasonId
        this.downloadsOnly = downloadsOnly
        viewModelScope.launch {
            try {
                val season = repository.getSeason(seasonId)
                val episodes =
                    repository.getEpisodes(
                        seriesId = season.seriesId,
                        seasonId = seasonId,
                        fields = listOf(ItemFields.OVERVIEW),
                        offline = downloadsOnly,
                    )
                _state.emit(
                    _state.value.copy(
                        season = season,
                        episodes = episodes,
                        episodeDownloadProgress =
                            buildDownloadProgressMap(episodes, downloadQueue.entries.value),
                    )
                )
            } catch (e: Exception) {
                _state.emit(_state.value.copy(error = e))
            }
        }
    }

    fun downloadSeason() {
        viewModelScope.launch(Dispatchers.IO) {
            // An episode already enqueued (pending/downloading/paused) is "in
            // progress" from the user's perspective, so count it as skipped rather
            // than re-enqueuing. enqueueAll dedupes too, but without this the
            // "Started N downloads" toast would overstate the count.
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
            for (episode in _state.value.episodes) {
                if (episode.isDownloaded() || episode.id in liveQueuedIds) {
                    skipped++
                } else {
                    toQueue.add(episode)
                }
            }

            if (toQueue.isEmpty()) {
                eventsChannel.send(SeasonEvent.DownloadResult(0, skipped, 0))
                return@launch
            }

            downloadQueue.enqueueAll(toQueue)
            eventsChannel.send(
                SeasonEvent.DownloadResult(started = toQueue.size, skipped = skipped, failed = 0)
            )
        }
    }

    fun deleteSeasonDownloads() {
        viewModelScope.launch(Dispatchers.IO) {
            for (episode in _state.value.episodes) {
                downloadQueue.remove(episode.id)
                val localSource =
                    episode.sources.firstOrNull { it.type == FindroidSourceType.LOCAL }
                if (localSource != null) {
                    downloader.deleteItem(item = episode, source = localSource)
                }
            }
            loadSeason(seasonId)
        }
    }

    private fun buildDownloadProgressMap(
        episodes: List<FindroidEpisode>,
        entries: List<DownloadQueue.Entry>,
    ): Map<UUID, DownloadProgress> {
        val byId = entries.associateBy { it.id }
        return episodes.associate { episode ->
            val entry = byId[episode.id]
            val progress =
                when (val s = entry?.state) {
                    is DownloadQueue.EntryState.Downloading ->
                        DownloadProgress(
                            status = DownloadStatus.DOWNLOADING,
                            progress = entry.progress / 100f,
                        )
                    is DownloadQueue.EntryState.Pending ->
                        DownloadProgress(status = DownloadStatus.QUEUED)
                    is DownloadQueue.EntryState.Paused ->
                        DownloadProgress(
                            status = DownloadStatus.PAUSED,
                            progress = entry.progress / 100f,
                        )
                    is DownloadQueue.EntryState.Failed -> DownloadProgress(status = DownloadStatus.FAILED)
                    is DownloadQueue.EntryState.Completed ->
                        DownloadProgress(status = DownloadStatus.COMPLETED, progress = 1f)
                    null ->
                        if (episode.isDownloaded())
                            DownloadProgress(status = DownloadStatus.COMPLETED, progress = 1f)
                        else DownloadProgress()
                }
            episode.id to progress
        }
    }

    fun onAction(action: SeasonAction) {
        when (action) {
            is SeasonAction.MarkAsPlayed -> {
                viewModelScope.launch {
                    try {
                        repository.markAsPlayed(seasonId)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to mark as played")
                    }
                    loadSeason(seasonId)
                }
            }
            is SeasonAction.UnmarkAsPlayed -> {
                viewModelScope.launch {
                    try {
                        repository.markAsUnplayed(seasonId)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to unmark as played")
                    }
                    loadSeason(seasonId)
                }
            }
            is SeasonAction.MarkAsFavorite -> {
                viewModelScope.launch {
                    try {
                        repository.markAsFavorite(seasonId)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to mark as favorite")
                    }
                    loadSeason(seasonId)
                }
            }
            is SeasonAction.UnmarkAsFavorite -> {
                viewModelScope.launch {
                    try {
                        repository.unmarkAsFavorite(seasonId)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to unmark as favorite")
                    }
                    loadSeason(seasonId)
                }
            }
            is SeasonAction.DownloadEpisode -> {
                viewModelScope.launch(Dispatchers.IO) {
                    val episode = action.episode
                    if (episode is FindroidEpisode) {
                        downloadQueue.enqueue(episode)
                    }
                }
            }
            is SeasonAction.DeleteEpisodeDownload -> {
                viewModelScope.launch(Dispatchers.IO) {
                    val item = action.episode
                    downloadQueue.remove(item.id)
                    val localSource =
                        item.sources.firstOrNull { it.type == FindroidSourceType.LOCAL }
                    if (localSource != null) {
                        downloader.deleteItem(item = item, source = localSource)
                    }
                    loadSeason(seasonId)
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
