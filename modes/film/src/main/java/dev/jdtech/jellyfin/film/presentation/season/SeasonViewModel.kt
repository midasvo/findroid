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
            val storageIndex =
                appPreferences.getValue(appPreferences.downloadStorageIndex)?.toIntOrNull() ?: 0
            var started = 0
            var skipped = 0
            var failed = 0
            for (episode in _state.value.episodes) {
                if (episode.isDownloaded()) {
                    skipped++
                    continue
                }
                // The episode list from getEpisodes() may not include mediaSources,
                // so fetch them separately for each episode we want to download.
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
                } else {
                    failed++
                }
            }
            eventsChannel.send(SeasonEvent.DownloadResult(started, skipped, failed))
            // Reload episodes to pick up new LOCAL sources, then start polling
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

                        if (hasActive) {
                            handler.postDelayed(self, 1000L)
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
