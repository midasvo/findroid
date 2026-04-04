package dev.jdtech.jellyfin.film.presentation.season

import android.app.DownloadManager
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.core.Constants
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadProgress
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jellyfin.sdk.model.api.ItemFields
import timber.log.Timber

@HiltViewModel
class SeasonViewModel
@Inject
constructor(
    private val repository: JellyfinRepository,
    private val downloader: Downloader,
    private val appPreferences: AppPreferences,
) : ViewModel() {
    private val _state = MutableStateFlow(SeasonState())
    val state = _state.asStateFlow()

    private val eventsChannel = Channel<SeasonEvent>()
    val events = eventsChannel.receiveAsFlow()

    lateinit var seasonId: UUID

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var isPolling = false

    // Download queue: episodes waiting to be started
    private val queueMutex = Mutex()
    private val downloadQueue = ArrayDeque<FindroidEpisode>()

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
            val maxConcurrent =
                appPreferences.getValue(appPreferences.maxConcurrentDownloads)
            val toDownload = mutableListOf<FindroidEpisode>()
            var skipped = 0
            for (episode in _state.value.episodes) {
                if (episode.isDownloaded()) {
                    skipped++
                } else {
                    toDownload.add(episode)
                }
            }

            if (toDownload.isEmpty()) {
                eventsChannel.send(SeasonEvent.DownloadResult(0, skipped, 0))
                return@launch
            }

            // Start first batch, queue the rest
            val firstBatch = toDownload.take(maxConcurrent)
            val rest = toDownload.drop(maxConcurrent)
            queueMutex.withLock {
                downloadQueue.clear()
                downloadQueue.addAll(rest)
            }

            var started = 0
            var failed = 0
            for (episode in firstBatch) {
                val result = startEpisodeDownload(episode)
                if (result) {
                    started++
                } else {
                    failed++
                }
            }
            val queueSize = queueMutex.withLock { downloadQueue.size }
            eventsChannel.send(
                SeasonEvent.DownloadResult(
                    started = started + queueSize,
                    skipped = skipped,
                    failed = failed,
                )
            )
            // Reload episodes to pick up new LOCAL sources, then start polling
            loadSeason(seasonId)
        }
    }

    private suspend fun startEpisodeDownload(episode: FindroidEpisode): Boolean {
        val storageIndex =
            appPreferences.getValue(appPreferences.downloadStorageIndex)?.toIntOrNull() ?: 0
        val sources =
            try {
                repository.getMediaSources(episode.id)
            } catch (_: Exception) {
                return false
            }
        val sourceId = sources.firstOrNull()?.id ?: return false
        val (downloadId, _) =
            downloader.downloadItem(
                item = episode,
                sourceId = sourceId,
                storageIndex = storageIndex,
            )
        return downloadId != -1L
    }

    fun deleteSeasonDownloads() {
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

    private fun startProgressPollingIfNeeded() {
        val hasActiveDownloads =
            _state.value.episodes.any { it.isDownloading() }
        if (hasActiveDownloads && !isPolling) {
            isPolling = true
            pollDownloadProgress()
        }
    }

    private fun pollDownloadProgress() {
        handler.removeCallbacksAndMessages(null)
        val runnable =
            object : Runnable {
                override fun run() {
                    val self = this
                    viewModelScope.launch(Dispatchers.IO) {
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
                                // Active download — poll progress
                                val (status, progress) =
                                    downloader.getProgress(localSource.downloadId)
                                val downloadStatus =
                                    when (status) {
                                        DownloadManager.STATUS_PENDING -> DownloadStatus.PENDING
                                        DownloadManager.STATUS_RUNNING -> DownloadStatus.DOWNLOADING
                                        DownloadManager.STATUS_SUCCESSFUL ->
                                            DownloadStatus.COMPLETED
                                        DownloadManager.STATUS_FAILED -> DownloadStatus.FAILED
                                        // Unknown/missing entry = treat as completed, not active
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
                                // Completed download
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

                        // Start queued downloads synchronously as slots free up
                        val queueNotEmpty = queueMutex.withLock { downloadQueue.isNotEmpty() }
                        if (queueNotEmpty) {
                            val maxConcurrent =
                                appPreferences.getValue(appPreferences.maxConcurrentDownloads)
                            val currentActive =
                                progressMap.values.count {
                                    it.status == DownloadStatus.PENDING ||
                                        it.status == DownloadStatus.DOWNLOADING
                                }
                            val slotsAvailable =
                                (maxConcurrent - currentActive).coerceAtLeast(0)
                            var startedAny = false
                            repeat(slotsAvailable) {
                                val next = queueMutex.withLock {
                                    if (downloadQueue.isEmpty()) return@repeat
                                    downloadQueue.removeFirst()
                                }
                                if (startEpisodeDownload(next)) {
                                    startedAny = true
                                }
                            }
                            if (startedAny) {
                                // Reload episodes to pick up new LOCAL sources
                                try {
                                    val season = repository.getSeason(seasonId)
                                    val reloadedEpisodes =
                                        repository.getEpisodes(
                                            seriesId = season.seriesId,
                                            seasonId = seasonId,
                                            fields = listOf(ItemFields.OVERVIEW),
                                        )
                                    _state.emit(
                                        _state.value.copy(
                                            season = season,
                                            episodes = reloadedEpisodes,
                                            episodeDownloadProgress =
                                                buildDownloadProgressMap(reloadedEpisodes),
                                        )
                                    )
                                } catch (_: Exception) {}
                            }
                        }

                        val queueStillNotEmpty =
                            queueMutex.withLock { downloadQueue.isNotEmpty() }
                        if (hasActive || queueStillNotEmpty) {
                            handler.postDelayed(self, Constants.DOWNLOAD_POLL_INTERVAL_MS)
                        } else {
                            isPolling = false
                            // Reload to get final state (renamed files etc.)
                            loadSeason(seasonId)
                        }
                    }
                }
            }
        handler.post(runnable)
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
        handler.removeCallbacksAndMessages(null)
        eventsChannel.close()
    }
}
