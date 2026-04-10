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
import dev.jdtech.jellyfin.models.toFindroidEpisode
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.utils.Downloader
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
    private val downloader: Downloader,
    private val database: ServerDatabaseDao,
) : ViewModel() {
    private val _state = MutableStateFlow(DownloadsState())
    val state = _state.asStateFlow()

    private var wasBusy = false

    init {
        // Load library sections on first open.
        loadItems()

        // Observe queue entries -> queueItems
        viewModelScope.launch {
            downloadQueue.entries.collect { entries ->
                val items = entries.map { it.toActiveDownload() }
                val hasCompleted =
                    entries.any { it.state is DownloadQueue.EntryState.Completed }
                _state.emit(_state.value.copy(queueItems = items, hasCompleted = hasCompleted))
                // When downloads finish (busy -> not busy), reload sections so newly
                // completed items appear and removed shows disappear. The pump now
                // finalizes downloads (rename + DB update) before flipping to
                // Completed, so a single reload is enough — no race window.
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
                    loadItems()
                }
            }
        }
    }

    fun loadItems() {
        viewModelScope.launch {
            _state.emit(_state.value.copy(isLoading = true, error = null))
            try {
                reconcileStuckDownloads()
                val items = repository.getDownloads()
                val itemSizes = computeItemSizes(items)
                val sections = buildCompletedSections(items, itemSizes)
                val storageInfo = calculateStorageInfo()
                _state.emit(
                    _state.value.copy(
                        isLoading = false,
                        sections = sections,
                        itemSizes = itemSizes,
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

    /**
     * Self-heal pass: any source still tagged `.download` in the DB whose final
     * file is already on disk gets fixed up. This rescues items left stuck by
     * older buggy builds, missed broadcasts, or process death between rename
     * and DB write — without it, those items would never appear in the Library
     * tab again, even after restart.
     */
    private suspend fun reconcileStuckDownloads() = withContext(Dispatchers.IO) {
        for (src in database.getActiveDownloadSources()) {
            val finalPath = src.path.removeSuffix(".download")
            val finalFile = File(finalPath)
            if (finalFile.exists() && finalFile.length() > 0) {
                database.setSourcePath(src.id, finalPath)
                File(src.path).takeIf { it.exists() }?.delete()
            }
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


    fun setSortOrder(order: DownloadSortOrder) {
        viewModelScope.launch {
            _state.emit(_state.value.copy(sortOrder = order))
            loadItems()
        }
    }

    fun deleteDownloadedItem(item: FindroidItem) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (item is FindroidShow) {
                    val userId = repository.getUserId()
                    val episodes = database.getEpisodesByShowId(item.id)
                    for (epDto in episodes) {
                        val ep = epDto.toFindroidEpisode(database, userId)
                        downloadQueue.remove(ep.id)
                        val source = ep.sources.firstOrNull { it.type == FindroidSourceType.LOCAL }
                            ?: continue
                        downloader.deleteItem(ep, source)
                    }
                } else {
                    downloadQueue.remove(item.id)
                    val source = item.sources.firstOrNull { it.type == FindroidSourceType.LOCAL }
                        ?: return@withContext
                    downloader.deleteItem(item, source)
                }
            }
            loadItems()
        }
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
        itemSizes: Map<java.util.UUID, Long>,
    ): List<CollectionSection> =
        withContext(Dispatchers.IO) {
            val sortOrder = _state.value.sortOrder
            // A movie/show with ONLY a .download source is still being downloaded
            // and belongs in the Queue tab, not the Library tab. Filter by
            // completed-source presence before sorting.
            val filteredMovies = items
                .filterIsInstance<FindroidMovie>()
                .filter { hasCompletedSource(it.id) }
            val sortedMovies = when (sortOrder) {
                DownloadSortOrder.NAME -> filteredMovies.sortedBy { it.name.lowercase() }
                DownloadSortOrder.DATE -> filteredMovies.sortedByDescending { maxSourceMtime(it.id) }
                DownloadSortOrder.SIZE -> filteredMovies.sortedByDescending { itemSizes[it.id] ?: 0L }
            }
            val filteredShows = items
                .filterIsInstance<FindroidShow>()
                .filter { show ->
                    database.getEpisodesByShowId(show.id)
                        .any { hasCompletedSource(it.id) }
                }
            val sortedShows = when (sortOrder) {
                DownloadSortOrder.NAME -> filteredShows.sortedBy { it.name.lowercase() }
                DownloadSortOrder.DATE -> filteredShows.sortedByDescending { show ->
                    database.getEpisodesByShowId(show.id)
                        .maxOfOrNull { maxSourceMtime(it.id) } ?: 0L
                }
                DownloadSortOrder.SIZE -> filteredShows.sortedByDescending { show ->
                    database.getEpisodesByShowId(show.id)
                        .sumOf { ep -> itemSizes[ep.id] ?: 0L }
                }
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

    private suspend fun computeItemSizes(
        items: List<FindroidItem>,
    ): Map<java.util.UUID, Long> = withContext(Dispatchers.IO) {
        val sizes = mutableMapOf<java.util.UUID, Long>()
        for (item in items) {
            if (item is FindroidShow) {
                val episodes = database.getEpisodesByShowId(item.id)
                val totalBytes = episodes.sumOf { ep ->
                    database.getSources(ep.id).sumOf { src ->
                        val f = File(src.path)
                        if (f.exists()) f.length() else 0L
                    }
                }
                if (totalBytes > 0) sizes[item.id] = totalBytes
            } else {
                val sources = database.getSources(item.id)
                val totalBytes = sources.sumOf { src ->
                    val f = File(src.path)
                    if (f.exists()) f.length() else 0L
                }
                if (totalBytes > 0) sizes[item.id] = totalBytes
            }
        }
        sizes
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
