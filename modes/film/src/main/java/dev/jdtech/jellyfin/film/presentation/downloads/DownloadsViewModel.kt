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
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.CollectionSection
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private val database: ServerDatabaseDao,
) : ViewModel() {
    private val _state = MutableStateFlow(DownloadsState())
    val state = _state.asStateFlow()

    private var wasBusy = false
    private var delayedReloadJob: Job? = null

    init {
        // Observe queue entries -> queueItems
        viewModelScope.launch {
            downloadQueue.entries.collect { entries ->
                val items = entries.map { it.toActiveDownload() }
                val hasCompleted =
                    entries.any { it.state is DownloadQueue.EntryState.Completed }
                _state.emit(_state.value.copy(queueItems = items, hasCompleted = hasCompleted))
                // When downloads finish (busy -> not busy), reload sections so newly
                // completed items appear and removed shows disappear.
                val isBusy =
                    entries.any {
                        it.state is DownloadQueue.EntryState.Downloading ||
                            it.state is DownloadQueue.EntryState.Pending ||
                            it.state is DownloadQueue.EntryState.Paused
                    }
                if (isBusy) {
                    wasBusy = true
                } else if (wasBusy) {
                    wasBusy = false
                    // The pump detects STATUS_SUCCESSFUL before DownloadReceiver
                    // renames the .download file and updates the DB path. Reload
                    // immediately (catches items whose receiver already ran) and
                    // again after a short delay (catches the rest).
                    loadItems()
                    reloadAfterDelay()
                }
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

    private fun reloadAfterDelay() {
        delayedReloadJob?.cancel()
        delayedReloadJob = viewModelScope.launch {
            delay(2_500L)
            loadItems()
        }
    }

    fun cancelDownload(activeDownload: ActiveDownload) {
        downloadQueue.remove(activeDownload.item.id)
    }

    fun dismissCompletedDownload(activeDownload: ActiveDownload) {
        // Removing the queue entry emits a new queueItems list via the observer;
        // no need to reload completed sections (the completed item was already
        // present in sections once it finished downloading).
        downloadQueue.remove(activeDownload.item.id)
    }

    fun retryDownload(activeDownload: ActiveDownload) {
        downloadQueue.retry(activeDownload.item.id)
    }

    fun clearCompleted() {
        // Sections in the Downloads tab are keyed off repository.getDownloads(),
        // not queue state, so clearing completed queue entries doesn't affect them.
        downloadQueue.clearCompleted()
    }

    private fun DownloadQueue.Entry.toActiveDownload(): ActiveDownload {
        val downloadStatus =
            when (state) {
                is DownloadQueue.EntryState.Pending -> DownloadStatus.QUEUED
                is DownloadQueue.EntryState.Downloading -> DownloadStatus.DOWNLOADING
                is DownloadQueue.EntryState.Paused -> DownloadStatus.PAUSED
                is DownloadQueue.EntryState.Completed -> DownloadStatus.COMPLETED
                is DownloadQueue.EntryState.Failed -> DownloadStatus.FAILED
            }
        val failureText = (state as? DownloadQueue.EntryState.Failed)?.error
        return ActiveDownload(
            item = item,
            progress =
                DownloadProgress(status = downloadStatus, progress = progress / 100f),
            downloadId = downloadId,
            bytesDownloaded = bytesDownloaded,
            totalBytes = totalBytes,
            bytesPerSecond = bytesPerSecond,
            errorText = failureText,
        )
    }

    private suspend fun buildCompletedSections(
        items: List<FindroidItem>,
    ): List<CollectionSection> =
        withContext(Dispatchers.IO) {
            // A movie/show with ONLY a .download source is still being downloaded
            // and belongs in the Queue tab, not the Library tab. Filter by
            // completed-source presence before sorting.
            val sortedMovies =
                items
                    .filterIsInstance<FindroidMovie>()
                    .filter { hasCompletedSource(it.id) }
                    .sortedByDescending { movie -> maxSourceMtime(movie.id) }
            val sortedShows =
                items
                    .filterIsInstance<FindroidShow>()
                    .filter { show ->
                        database.getEpisodesByShowId(show.id)
                            .any { hasCompletedSource(it.id) }
                    }
                    .sortedByDescending { show ->
                        database.getEpisodesByShowId(show.id)
                            .maxOfOrNull { maxSourceMtime(it.id) } ?: 0L
                    }
            val sections = mutableListOf<CollectionSection>()
            CollectionSection(
                Constants.FAVORITE_TYPE_MOVIES,
                UiText.StringResource(CoreR.string.movies_label),
                sortedMovies,
            )
                .let { if (it.items.isNotEmpty()) sections.add(it) }
            CollectionSection(
                Constants.FAVORITE_TYPE_SHOWS,
                UiText.StringResource(CoreR.string.shows_label),
                sortedShows,
            )
                .let { if (it.items.isNotEmpty()) sections.add(it) }
            sections
        }

    private fun maxSourceMtime(itemId: java.util.UUID): Long =
        database.getSources(itemId).maxOfOrNull { File(it.path).lastModified() } ?: 0L

    private fun hasCompletedSource(itemId: java.util.UUID): Boolean =
        database.getSources(itemId).any { !it.path.endsWith(".download") }

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
            // Media files live in external storage, but trickplay tiles and
            // cached images live under the app's internal filesDir. Count both
            // so the reported usage matches what the user is actually storing.
            val videoBytes = File(storageDir, "downloads").sizeBytesRecursive()
            val trickplayBytes = File(context.filesDir, "trickplay").sizeBytesRecursive()
            val imagesBytes = File(context.filesDir, "images").sizeBytesRecursive()
            val usedBytes = videoBytes + trickplayBytes + imagesBytes

            val stats = StatFs(storageDir.path)
            val freeBytes = stats.availableBytes

            Triple(usedBytes, freeBytes, isExternal)
        }

    private fun File.sizeBytesRecursive(): Long {
        if (!exists()) return 0L
        if (isFile) return length()
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(this)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) stack.addLast(child) else total += child.length()
            }
        }
        return total
    }
}
