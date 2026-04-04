package dev.jdtech.jellyfin.film.presentation.downloads

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.core.Constants
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadProgress
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadQueueManager
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadStatus
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.CollectionSection
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.models.toFindroidEpisode
import dev.jdtech.jellyfin.models.toFindroidMovie
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.utils.Downloader
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class DownloadsViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val repository: JellyfinRepository,
    private val database: ServerDatabaseDao,
    private val downloader: Downloader,
    private val appPreferences: AppPreferences,
    private val downloadQueueManager: DownloadQueueManager,
) : ViewModel() {
    private val _state = MutableStateFlow(DownloadsState())
    val state = _state.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private var isPolling = false
    private var completionPollCount = 0
    private val dismissedIds = mutableSetOf<UUID>()

    init {
        // Observe queued items from SeasonViewModel and merge into state
        viewModelScope.launch {
            downloadQueueManager.queuedItems.collect { queuedItems ->
                val activeIds = _state.value.activeDownloads.map { it.item.id }.toSet()
                val queuedDownloads = queuedItems
                    .filter { it.id !in activeIds && it.id !in dismissedIds }
                    .map { item ->
                        ActiveDownload(
                            item = item,
                            progress = DownloadProgress(status = DownloadStatus.QUEUED),
                            downloadId = null,
                        )
                    }
                _state.emit(_state.value.copy(queuedDownloads = queuedDownloads))
            }
        }
    }

    fun loadItems() {
        viewModelScope.launch {
            dismissedIds.clear()
            _state.emit(_state.value.copy(isLoading = true, error = null))

            try {
                val items = repository.getDownloads()
                val sections = buildCompletedSections(items)
                val activeDownloads = loadActiveDownloads()
                val storageInfo = calculateStorageInfo()

                // Build queued downloads from the shared queue manager
                val activeIds = activeDownloads.map { it.item.id }.toSet()
                val queuedDownloads = downloadQueueManager.queuedItems.value
                    .filter { it.id !in activeIds && it.id !in dismissedIds }
                    .map { item ->
                        ActiveDownload(
                            item = item,
                            progress = DownloadProgress(status = DownloadStatus.QUEUED),
                            downloadId = null,
                        )
                    }

                _state.emit(
                    _state.value.copy(
                        isLoading = false,
                        sections = sections,
                        activeDownloads = activeDownloads,
                        queuedDownloads = queuedDownloads,
                        storageUsedBytes = storageInfo.first,
                        storageFreeBytes = storageInfo.second,
                        storageIsExternal = storageInfo.third,
                    )
                )

                if (activeDownloads.isNotEmpty()) {
                    startProgressPolling()
                }
            } catch (e: Exception) {
                _state.emit(_state.value.copy(isLoading = false, error = e))
            }
        }
    }

    fun cancelDownload(activeDownload: ActiveDownload) {
        viewModelScope.launch(Dispatchers.IO) {
            activeDownload.downloadId?.let {
                downloader.cancelDownload(activeDownload.item, it)
            }
            loadItems()
        }
    }

    fun dismissCompletedDownload(activeDownload: ActiveDownload) {
        viewModelScope.launch {
            dismissedIds.add(activeDownload.item.id)
            val updated = _state.value.activeDownloads.filter { it.item.id != activeDownload.item.id }
            _state.emit(_state.value.copy(activeDownloads = updated))
            if (updated.none {
                    it.progress.status == DownloadStatus.PENDING ||
                        it.progress.status == DownloadStatus.DOWNLOADING
                }
            ) {
                loadItems()
            }
        }
    }

    private suspend fun buildCompletedSections(
        items: List<FindroidItem>,
    ): List<CollectionSection> =
        withContext(Dispatchers.Default) {
            val sections = mutableListOf<CollectionSection>()
            CollectionSection(
                    Constants.FAVORITE_TYPE_MOVIES,
                    UiText.StringResource(CoreR.string.movies_label),
                    items.filterIsInstance<FindroidMovie>(),
                )
                .let { if (it.items.isNotEmpty()) sections.add(it) }
            CollectionSection(
                    Constants.FAVORITE_TYPE_SHOWS,
                    UiText.StringResource(CoreR.string.shows_label),
                    items.filterIsInstance<FindroidShow>(),
                )
                .let { if (it.items.isNotEmpty()) sections.add(it) }
            sections
        }

    private suspend fun loadActiveDownloads(): List<ActiveDownload> =
        withContext(Dispatchers.IO) {
            val userId = repository.getUserId()
            val activeSources = database.getActiveDownloadSources()
            activeSources.mapNotNull { source ->
                if (source.itemId in dismissedIds) return@mapNotNull null

                val item: FindroidItem? =
                    try {
                        database.getMovie(source.itemId).toFindroidMovie(database, userId)
                    } catch (_: Exception) {
                        try {
                            database.getEpisode(source.itemId).toFindroidEpisode(database, userId)
                        } catch (_: Exception) {
                            null
                        }
                    }
                if (item == null) return@mapNotNull null

                val (status, progress) = downloader.getProgress(source.downloadId)
                val downloadStatus =
                    when (status) {
                        DownloadManager.STATUS_PENDING -> DownloadStatus.PENDING
                        DownloadManager.STATUS_RUNNING -> DownloadStatus.DOWNLOADING
                        DownloadManager.STATUS_PAUSED -> DownloadStatus.PENDING
                        DownloadManager.STATUS_FAILED -> DownloadStatus.FAILED
                        DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.COMPLETED
                        else -> DownloadStatus.COMPLETED
                    }
                ActiveDownload(
                    item = item,
                    progress =
                        DownloadProgress(
                            status = downloadStatus,
                            progress = progress.coerceAtLeast(0) / 100f,
                        ),
                    downloadId = source.downloadId,
                )
            }
        }

    private suspend fun calculateStorageInfo(): Triple<Long, Long, Boolean> =
        withContext(Dispatchers.IO) {
            val storageIndex =
                appPreferences.getValue(appPreferences.downloadStorageIndex)?.toIntOrNull() ?: 0
            val dirs = context.getExternalFilesDirs(null)
            val storageDir =
                dirs.getOrNull(storageIndex)?.takeIf {
                    Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED
                } ?: dirs.getOrNull(0)

            if (storageDir == null) return@withContext Triple(0L, 0L, false)

            val isExternal = Environment.isExternalStorageRemovable(storageDir)
            val downloadsDir = File(storageDir, "downloads")
            val usedBytes =
                if (downloadsDir.exists()) {
                    downloadsDir.listFiles()?.sumOf { it.length() } ?: 0L
                } else {
                    0L
                }
            val stats = StatFs(storageDir.path)
            val freeBytes = stats.availableBytes

            Triple(usedBytes, freeBytes, isExternal)
        }

    private fun startProgressPolling() {
        if (isPolling) return
        isPolling = true
        handler.removeCallbacksAndMessages(null)
        val runnable =
            object : Runnable {
                override fun run() {
                    val self = this
                    viewModelScope.launch {
                        val activeDownloads = loadActiveDownloads()
                        val hasActive =
                            activeDownloads.any {
                                it.progress.status == DownloadStatus.PENDING ||
                                    it.progress.status == DownloadStatus.DOWNLOADING
                            }

                        _state.emit(_state.value.copy(activeDownloads = activeDownloads))

                        if (hasActive) {
                            completionPollCount = 0
                            handler.postDelayed(self, Constants.DOWNLOAD_POLL_INTERVAL_MS)
                        } else if (activeDownloads.isNotEmpty() && completionPollCount < 3) {
                            // Keep polling briefly so DownloadReceiver can rename files
                            completionPollCount++
                            handler.postDelayed(self, Constants.DOWNLOAD_POLL_INTERVAL_MS)
                        } else {
                            isPolling = false
                            completionPollCount = 0
                            // Reload everything to refresh completed sections
                            loadItems()
                        }
                    }
                }
            }
        handler.post(runnable)
    }

    override fun onCleared() {
        super.onCleared()
        handler.removeCallbacksAndMessages(null)
    }
}
