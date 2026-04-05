package dev.jdtech.jellyfin.film.presentation.downloads

import android.content.Context
import android.os.Environment
import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.core.Constants
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadProgress
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadQueue
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadStatus
import dev.jdtech.jellyfin.models.CollectionSection
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.io.File
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
    private val appPreferences: AppPreferences,
    private val downloadQueue: DownloadQueue,
) : ViewModel() {
    private val _state = MutableStateFlow(DownloadsState())
    val state = _state.asStateFlow()

    init {
        // Observe queue entries -> queueItems
        viewModelScope.launch {
            downloadQueue.entries.collect { entries ->
                val items = entries.map { it.toActiveDownload() }
                val hasCompleted =
                    entries.any { it.state is DownloadQueue.EntryState.Completed }
                _state.emit(_state.value.copy(queueItems = items, hasCompleted = hasCompleted))
            }
        }
    }

    fun loadItems() {
        viewModelScope.launch {
            _state.emit(_state.value.copy(isLoading = true, error = null))
            try {
                val items = repository.getDownloads()
                val sections = buildCompletedSections(items)
                val storageInfo = calculateStorageInfo()
                _state.emit(
                    _state.value.copy(
                        isLoading = false,
                        sections = sections,
                        storageUsedBytes = storageInfo.first,
                        storageFreeBytes = storageInfo.second,
                        storageIsExternal = storageInfo.third,
                    )
                )
            } catch (e: Exception) {
                _state.emit(_state.value.copy(isLoading = false, error = e))
            }
        }
    }

    fun cancelDownload(activeDownload: ActiveDownload) {
        downloadQueue.remove(activeDownload.item.id)
    }

    fun dismissCompletedDownload(activeDownload: ActiveDownload) {
        // Remove from queue and refresh completed sections
        downloadQueue.remove(activeDownload.item.id)
        loadItems()
    }

    fun retryDownload(activeDownload: ActiveDownload) {
        downloadQueue.retry(activeDownload.item.id)
    }

    fun clearCompleted() {
        downloadQueue.clearCompleted()
        loadItems()
    }

    private fun DownloadQueue.Entry.toActiveDownload(): ActiveDownload {
        val downloadStatus =
            when (state) {
                is DownloadQueue.EntryState.Pending -> DownloadStatus.QUEUED
                is DownloadQueue.EntryState.Downloading -> DownloadStatus.DOWNLOADING
                is DownloadQueue.EntryState.Completed -> DownloadStatus.COMPLETED
                is DownloadQueue.EntryState.Failed -> DownloadStatus.FAILED
            }
        return ActiveDownload(
            item = item,
            progress =
                DownloadProgress(status = downloadStatus, progress = progress / 100f),
            downloadId = downloadId,
        )
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
}
