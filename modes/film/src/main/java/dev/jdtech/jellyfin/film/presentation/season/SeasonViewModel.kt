package dev.jdtech.jellyfin.film.presentation.season

import android.app.DownloadManager
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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
import org.jellyfin.sdk.model.api.ItemFields

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
    private var isPolling = false

    // Download queue: episodes waiting to be started
    private val downloadQueue = ArrayDeque<FindroidEpisode>()
    private var activeDownloadCount = 0

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
            downloadQueue.clear()
            downloadQueue.addAll(rest)

            var started = 0
            var failed = 0
            activeDownloadCount = 0
            for (episode in firstBatch) {
                val result = startEpisodeDownload(episode)
                if (result) {
                    started++
                    activeDownloadCount++
                } else {
                    failed++
                }
            }
            eventsChannel.send(
                SeasonEvent.DownloadResult(
                    started = started + downloadQueue.size,
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

    private fun startNextQueuedDownload() {
        if (downloadQueue.isEmpty()) return
        val next = downloadQueue.removeFirst()
        viewModelScope.launch(Dispatchers.IO) {
            if (startEpisodeDownload(next)) {
                activeDownloadCount++
            } else {
                // Try the next one if this one failed
                startNextQueuedDownload()
            }
            loadSeason(seasonId)
        }
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
                    viewModelScope.launch {
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
                                        DownloadManager.STATUS_SUCCESSFUL -> {
                                            // Will be picked up as COMPLETED after reload
                                            DownloadStatus.COMPLETED
                                        }
                                        DownloadManager.STATUS_FAILED -> DownloadStatus.FAILED
                                        else -> DownloadStatus.PENDING
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

                        if (hasActive || downloadQueue.isNotEmpty()) {
                            handler.postDelayed(self, 1000L)
                        } else {
                            isPolling = false
                            activeDownloadCount = 0
                            // Reload to get final state (renamed files etc.)
                            loadSeason(seasonId)
                        }

                        // Start queued downloads as active ones complete
                        if (downloadQueue.isNotEmpty()) {
                            val maxConcurrent =
                                appPreferences.getValue(appPreferences.maxConcurrentDownloads)
                            val currentActive =
                                progressMap.values.count {
                                    it.status == DownloadStatus.PENDING ||
                                        it.status == DownloadStatus.DOWNLOADING
                                }
                            val slotsAvailable = maxConcurrent - currentActive
                            repeat(slotsAvailable.coerceAtLeast(0)) {
                                startNextQueuedDownload()
                            }
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
                    repository.markAsPlayed(seasonId)
                    loadSeason(seasonId)
                }
            }
            is SeasonAction.UnmarkAsPlayed -> {
                viewModelScope.launch {
                    repository.markAsUnplayed(seasonId)
                    loadSeason(seasonId)
                }
            }
            is SeasonAction.MarkAsFavorite -> {
                viewModelScope.launch {
                    repository.markAsFavorite(seasonId)
                    loadSeason(seasonId)
                }
            }
            is SeasonAction.UnmarkAsFavorite -> {
                viewModelScope.launch {
                    repository.unmarkAsFavorite(seasonId)
                    loadSeason(seasonId)
                }
            }
            else -> Unit
        }
    }

    override fun onCleared() {
        super.onCleared()
        handler.removeCallbacksAndMessages(null)
    }
}
