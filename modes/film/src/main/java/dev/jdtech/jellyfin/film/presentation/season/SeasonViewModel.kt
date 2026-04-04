package dev.jdtech.jellyfin.film.presentation.season

import android.app.DownloadManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadProgress
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadQueueManager
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadStatus
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.models.isDownloaded
import dev.jdtech.jellyfin.models.isDownloading
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.utils.Downloader
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
    private val appPreferences: AppPreferences,
    private val downloadQueueManager: DownloadQueueManager,
) : ViewModel() {
    private val _state = MutableStateFlow(SeasonState())
    val state = _state.asStateFlow()

    private val eventsChannel = Channel<SeasonEvent>()
    val events = eventsChannel.receiveAsFlow()

    lateinit var seasonId: UUID

    private var downloadQueueJob: Job? = null
    private var progressPollJob: Job? = null

    fun loadSeason(seasonId: UUID) {
        this.seasonId = seasonId
        viewModelScope.launch {
            try {
                val season = repository.getSeason(seasonId)
                val episodes =
                    repository.getEpisodes(
                        seriesId = season.seriesId,
                        seasonId = seasonId,
                        fields = listOf(ItemFields.OVERVIEW),
                    )
                _state.emit(
                    _state.value.copy(
                        season = season,
                        episodes = episodes,
                        episodeDownloadProgress = buildDownloadProgressMap(episodes),
                    )
                )
                startProgressPollingIfNeeded()
            } catch (e: Exception) {
                _state.emit(_state.value.copy(error = e))
            }
        }
    }

    fun downloadSeason() {
        viewModelScope.launch(Dispatchers.IO) {
            val toDownload = mutableListOf<FindroidEpisode>()
            var skipped = 0
            for (episode in _state.value.episodes) {
                if (episode.isDownloaded() || episode.isDownloading()) {
                    skipped++
                } else {
                    toDownload.add(episode)
                }
            }

            if (toDownload.isEmpty()) {
                eventsChannel.send(SeasonEvent.DownloadResult(0, skipped, 0))
                return@launch
            }

            eventsChannel.send(
                SeasonEvent.DownloadResult(
                    started = toDownload.size,
                    skipped = skipped,
                    failed = 0,
                )
            )

            startDownloadQueue(toDownload)
        }
    }

    /**
     * Manages the download queue in a single coroutine. Starts up to maxConcurrent
     * downloads, then polls DownloadManager to detect completions and start the next
     * episode. No race conditions — everything runs sequentially in one loop.
     */
    private fun startDownloadQueue(episodes: List<FindroidEpisode>) {
        // Cancel any existing queue processor
        downloadQueueJob?.cancel()
        downloadQueueJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val maxConcurrent =
                    appPreferences.getValue(appPreferences.maxConcurrentDownloads)
                val pending = ArrayDeque(episodes)

                // Track active downloads: episodeId -> downloadId
                val active = mutableMapOf<UUID, Long>()

                Timber.d("Download queue: ${pending.size} episodes, maxConcurrent=$maxConcurrent")

                // Publish pending queue for the Downloads Queue tab
                downloadQueueManager.setQueuedItems(pending.toList())

                // Fill initial slots
                val initialStarted = fillSlots(pending, active, maxConcurrent)
                Timber.d("Download queue: started $initialStarted, active=${active.size}, pending=${pending.size}")
                downloadQueueManager.setQueuedItems(pending.toList())
                reloadSeasonState()

                // Poll until everything is done
                while (active.isNotEmpty() || pending.isNotEmpty()) {
                    delay(1000L)

                    // Check which downloads finished
                    val completed = mutableListOf<UUID>()
                    for ((episodeId, downloadId) in active) {
                        val (status, _) = downloader.getProgress(downloadId)
                        Timber.d("Download queue: poll episodeId=$episodeId downloadId=$downloadId status=$status")
                        if (status != DownloadManager.STATUS_PENDING &&
                            status != DownloadManager.STATUS_RUNNING &&
                            status != DownloadManager.STATUS_PAUSED
                        ) {
                            completed.add(episodeId)
                        }
                    }

                    if (completed.isNotEmpty()) {
                        Timber.d("Download queue: ${completed.size} completed, filling slots")
                        for (id in completed) active.remove(id)

                        // Start next downloads to fill freed slots
                        val started = fillSlots(pending, active, maxConcurrent)
                        Timber.d("Download queue: started $started new, active=${active.size}, pending=${pending.size}")
                        downloadQueueManager.setQueuedItems(pending.toList())
                        reloadSeasonState()
                    }

                    // Update progress UI
                    updateProgressFromDownloadManager(active)
                }

                Timber.d("Download queue: all done")
                downloadQueueManager.clear()
                reloadSeasonState()
            } catch (e: Exception) {
                Timber.e(e, "Download queue coroutine failed")
                downloadQueueManager.clear()
            }
        }
    }

    /**
     * Starts downloads to fill available slots. Returns number of downloads started.
     */
    private suspend fun fillSlots(
        pending: ArrayDeque<FindroidEpisode>,
        active: MutableMap<UUID, Long>,
        maxConcurrent: Int,
    ): Int {
        var started = 0
        while (pending.isNotEmpty() && active.size < maxConcurrent) {
            val episode = pending.removeFirst()
            val downloadId = startEpisodeDownloadGetId(episode)
            if (downloadId != null) {
                active[episode.id] = downloadId
                started++
            }
            // If download failed to start, skip and try next
        }
        return started
    }

    /**
     * Starts a download and returns the DownloadManager download ID, or null on failure.
     */
    private suspend fun startEpisodeDownloadGetId(episode: FindroidEpisode): Long? {
        val storageIndex =
            appPreferences.getValue(appPreferences.downloadStorageIndex)?.toIntOrNull() ?: 0
        val sources =
            try {
                repository.getMediaSources(episode.id)
            } catch (e: Exception) {
                Timber.e(e, "Failed to get media sources for ${episode.name}")
                return null
            }
        val sourceId = sources.firstOrNull()?.id ?: return null
        val (downloadId, _) =
            downloader.downloadItem(
                item = episode,
                sourceId = sourceId,
                storageIndex = storageIndex,
            )
        return if (downloadId != -1L) downloadId else null
    }

    private suspend fun startEpisodeDownload(episode: FindroidEpisode): Boolean {
        return startEpisodeDownloadGetId(episode) != null
    }

    /**
     * Updates the progress map from DownloadManager without reloading episodes.
     */
    private suspend fun updateProgressFromDownloadManager(
        active: Map<UUID, Long>,
    ) {
        val progressMap = _state.value.episodeDownloadProgress.toMutableMap()
        for ((episodeId, downloadId) in active) {
            val (status, progress) = downloader.getProgress(downloadId)
            val downloadStatus =
                when (status) {
                    DownloadManager.STATUS_PENDING -> DownloadStatus.PENDING
                    DownloadManager.STATUS_RUNNING -> DownloadStatus.DOWNLOADING
                    DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.COMPLETED
                    DownloadManager.STATUS_FAILED -> DownloadStatus.FAILED
                    else -> DownloadStatus.COMPLETED
                }
            progressMap[episodeId] =
                DownloadProgress(
                    status = downloadStatus,
                    progress = progress.coerceAtLeast(0) / 100f,
                )
        }
        _state.emit(_state.value.copy(episodeDownloadProgress = progressMap))
    }

    /**
     * Reloads season data from repository and rebuilds the progress map.
     */
    private suspend fun reloadSeasonState() {
        try {
            val season = repository.getSeason(seasonId)
            val episodes =
                repository.getEpisodes(
                    seriesId = season.seriesId,
                    seasonId = seasonId,
                    fields = listOf(ItemFields.OVERVIEW),
                )
            _state.emit(
                _state.value.copy(
                    season = season,
                    episodes = episodes,
                    episodeDownloadProgress = buildDownloadProgressMap(episodes),
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to reload season state")
        }
    }

    fun deleteSeasonDownloads() {
        downloadQueueJob?.cancel()
        downloadQueueManager.clear()
        viewModelScope.launch(Dispatchers.IO) {
            for (episode in _state.value.episodes) {
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
    ): Map<UUID, DownloadProgress> {
        return episodes.associate { episode ->
            episode.id to
                when {
                    episode.isDownloaded() ->
                        DownloadProgress(status = DownloadStatus.COMPLETED, progress = 1f)
                    episode.isDownloading() ->
                        DownloadProgress(status = DownloadStatus.PENDING)
                    else -> DownloadProgress()
                }
        }
    }

    /**
     * Starts a progress polling loop for downloads that were already in progress
     * when the screen opened (not started by downloadSeason in this session).
     */
    private fun startProgressPollingIfNeeded() {
        val hasActiveDownloads = _state.value.episodes.any { it.isDownloading() }
        if (hasActiveDownloads && progressPollJob?.isActive != true && downloadQueueJob?.isActive != true) {
            progressPollJob = viewModelScope.launch(Dispatchers.IO) {
                while (true) {
                    delay(1000L)
                    val episodes = _state.value.episodes
                    val progressMap = _state.value.episodeDownloadProgress.toMutableMap()
                    var hasActive = false

                    for (episode in episodes) {
                        val localSource =
                            episode.sources.firstOrNull {
                                it.type == FindroidSourceType.LOCAL
                            }
                        if (localSource == null) continue

                        if (localSource.path.endsWith(".download")) {
                            val (status, progress) =
                                downloader.getProgress(localSource.downloadId)
                            val downloadStatus =
                                when (status) {
                                    DownloadManager.STATUS_PENDING -> DownloadStatus.PENDING
                                    DownloadManager.STATUS_RUNNING -> DownloadStatus.DOWNLOADING
                                    DownloadManager.STATUS_SUCCESSFUL ->
                                        DownloadStatus.COMPLETED
                                    DownloadManager.STATUS_FAILED -> DownloadStatus.FAILED
                                    else -> DownloadStatus.COMPLETED
                                }
                            progressMap[episode.id] =
                                DownloadProgress(
                                    status = downloadStatus,
                                    progress = progress.coerceAtLeast(0) / 100f,
                                )
                            if (
                                downloadStatus == DownloadStatus.PENDING ||
                                downloadStatus == DownloadStatus.DOWNLOADING
                            ) {
                                hasActive = true
                            }
                        } else {
                            progressMap[episode.id] =
                                DownloadProgress(
                                    status = DownloadStatus.COMPLETED,
                                    progress = 1f,
                                )
                        }
                    }

                    _state.emit(
                        _state.value.copy(episodeDownloadProgress = progressMap)
                    )

                    if (!hasActive) {
                        reloadSeasonState()
                        break
                    }
                }
            }
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
                        startEpisodeDownload(episode)
                        loadSeason(seasonId)
                    }
                }
            }
            is SeasonAction.DeleteEpisodeDownload -> {
                viewModelScope.launch(Dispatchers.IO) {
                    val item = action.episode
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
        downloadQueueJob?.cancel()
        progressPollJob?.cancel()
        eventsChannel.close()
    }
}
