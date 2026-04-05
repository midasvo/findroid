package dev.jdtech.jellyfin.core.presentation.downloader

import android.app.DownloadManager
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.utils.Downloader
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Central download scheduler. Holds a list of queued/active downloads, respects the
 * user's max-concurrent-downloads setting, and drives Downloader.downloadItem() as
 * slots open up. Single source of truth for download state across the app.
 */
@Singleton
class DownloadQueue
@Inject
constructor(
    private val downloader: Downloader,
    private val appPreferences: AppPreferences,
) {
    sealed interface EntryState {
        data object Pending : EntryState

        data object Downloading : EntryState

        data object Completed : EntryState

        data class Failed(val error: UiText?) : EntryState
    }

    data class Entry(
        val id: UUID,
        val item: FindroidItem,
        val addedAt: Long,
        val state: EntryState,
        val downloadId: Long? = null,
        val startedAt: Long? = null,
        /** 0..100 */
        val progress: Int = 0,
    )

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var pumpJob: Job? = null

    suspend fun enqueue(item: FindroidItem) {
        mutex.withLock {
            if (_entries.value.any { it.id == item.id && it.state !is EntryState.Failed && it.state !is EntryState.Completed }) {
                return@withLock
            }
            // If a previous Failed/Completed entry exists for this id, replace it.
            val filtered = _entries.value.filter { it.id != item.id }
            val newEntry =
                Entry(
                    id = item.id,
                    item = item,
                    addedAt = System.currentTimeMillis(),
                    state = EntryState.Pending,
                )
            _entries.value = sort(filtered + newEntry)
        }
        ensurePump()
    }

    /**
     * Re-attaches an in-flight download started in a previous app session. No-op if
     * the item is already tracked. Does not call Downloader — Android's DownloadManager
     * is already running this downloadId.
     */
    suspend fun restore(item: FindroidItem, downloadId: Long) {
        mutex.withLock {
            if (_entries.value.any { it.id == item.id }) return@withLock
            val entry =
                Entry(
                    id = item.id,
                    item = item,
                    addedAt = System.currentTimeMillis(),
                    state = EntryState.Downloading,
                    downloadId = downloadId,
                    startedAt = System.currentTimeMillis(),
                )
            _entries.value = sort(_entries.value + entry)
        }
        ensurePump()
    }

    suspend fun enqueueAll(items: List<FindroidItem>) {
        mutex.withLock {
            val existingIds =
                _entries.value
                    .filter { it.state !is EntryState.Failed && it.state !is EntryState.Completed }
                    .map { it.id }
                    .toSet()
            val now = System.currentTimeMillis()
            val newEntries =
                items
                    .filter { it.id !in existingIds }
                    .mapIndexed { idx, item ->
                        Entry(
                            id = item.id,
                            item = item,
                            addedAt = now + idx, // preserve insertion order
                            state = EntryState.Pending,
                        )
                    }
            if (newEntries.isEmpty()) return@withLock
            val newIds = newEntries.map { it.id }.toSet()
            val kept = _entries.value.filter { it.id !in newIds }
            _entries.value = sort(kept + newEntries)
        }
        ensurePump()
    }

    /** Cancels an active download or drops a pending/failed/completed entry. */
    fun remove(id: UUID) {
        scope.launch {
            var cancelEntry: Entry? = null
            mutex.withLock {
                val target = _entries.value.firstOrNull { it.id == id } ?: return@withLock
                if (target.state is EntryState.Downloading) {
                    cancelEntry = target
                }
                _entries.value = _entries.value.filter { it.id != id }
            }
            cancelEntry?.let { entry ->
                entry.downloadId?.let { dlId ->
                    try {
                        downloader.cancelDownload(entry.item, dlId)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to cancel download for ${entry.item.name}")
                    }
                }
            }
            ensurePump()
        }
    }

    /** Re-queues a failed entry. */
    fun retry(id: UUID) {
        scope.launch {
            mutex.withLock {
                _entries.value =
                    sort(
                        _entries.value.map { entry ->
                            if (entry.id == id && entry.state is EntryState.Failed) {
                                entry.copy(
                                    state = EntryState.Pending,
                                    addedAt = System.currentTimeMillis(),
                                    downloadId = null,
                                    startedAt = null,
                                    progress = 0,
                                )
                            } else {
                                entry
                            }
                        }
                    )
            }
            ensurePump()
        }
    }

    /** Removes all Completed entries from the queue view. */
    fun clearCompleted() {
        scope.launch {
            mutex.withLock {
                _entries.value = _entries.value.filter { it.state !is EntryState.Completed }
            }
        }
    }

    private fun sort(entries: List<Entry>): List<Entry> {
        fun priority(state: EntryState): Int =
            when (state) {
                is EntryState.Downloading -> 0
                is EntryState.Pending -> 1
                is EntryState.Failed -> 2
                is EntryState.Completed -> 3
            }
        return entries.sortedWith(
            compareBy({ priority(it.state) }, { it.startedAt ?: it.addedAt }, { it.addedAt })
        )
    }

    private fun ensurePump() {
        if (pumpJob?.isActive == true) return
        pumpJob =
            scope.launch {
                try {
                    pump()
                } catch (e: Exception) {
                    Timber.e(e, "DownloadQueue pump crashed")
                }
            }
    }

    private suspend fun pump() {
        while (true) {
            // 1. Poll active downloads and transition completed/failed
            val active = _entries.value.filter { it.state is EntryState.Downloading }
            if (active.isNotEmpty()) {
                val updates = mutableMapOf<UUID, Entry>()
                for (entry in active) {
                    val dlId = entry.downloadId ?: continue
                    val (status, progress) = downloader.getProgress(dlId)
                    val newState: EntryState? =
                        when (status) {
                            DownloadManager.STATUS_PENDING,
                            DownloadManager.STATUS_RUNNING,
                            DownloadManager.STATUS_PAUSED -> null // still running
                            DownloadManager.STATUS_SUCCESSFUL -> EntryState.Completed
                            DownloadManager.STATUS_FAILED -> EntryState.Failed(null)
                            else -> EntryState.Completed
                        }
                    val newProgress = progress.coerceAtLeast(0).coerceAtMost(100)
                    if (newState != null || newProgress != entry.progress) {
                        updates[entry.id] =
                            entry.copy(
                                state = newState ?: entry.state,
                                progress = if (newState == EntryState.Completed) 100 else newProgress,
                            )
                    }
                }
                if (updates.isNotEmpty()) {
                    mutex.withLock {
                        _entries.value =
                            sort(_entries.value.map { updates[it.id] ?: it })
                    }
                }
            }

            // 2. Fill free slots from Pending queue
            val maxConcurrent = appPreferences.getValue(appPreferences.maxConcurrentDownloads)
            val currentlyActive = _entries.value.count { it.state is EntryState.Downloading }
            val freeSlots = (maxConcurrent - currentlyActive).coerceAtLeast(0)
            if (freeSlots > 0) {
                val pending = _entries.value.filter { it.state is EntryState.Pending }.take(freeSlots)
                for (entry in pending) {
                    startDownload(entry)
                }
            }

            // 3. Decide whether to keep pumping
            val snap = _entries.value
            val hasWork =
                snap.any { it.state is EntryState.Downloading || it.state is EntryState.Pending }
            if (!hasWork) {
                pumpJob = null
                return
            }
            delay(1000L)
        }
    }

    private suspend fun startDownload(entry: Entry) {
        val storageIndex =
            appPreferences.getValue(appPreferences.downloadStorageIndex)?.toIntOrNull() ?: 0
        val (downloadId, errorText) =
            try {
                downloader.downloadItem(item = entry.item, storageIndex = storageIndex)
            } catch (e: Exception) {
                Timber.e(e, "downloadItem threw for ${entry.item.name}")
                Pair(-1L, null)
            }

        mutex.withLock {
            _entries.value =
                sort(
                    _entries.value.map { e ->
                        if (e.id != entry.id) {
                            e
                        } else if (downloadId != -1L) {
                            e.copy(
                                state = EntryState.Downloading,
                                downloadId = downloadId,
                                startedAt = System.currentTimeMillis(),
                            )
                        } else {
                            e.copy(state = EntryState.Failed(errorText))
                        }
                    }
                )
        }
    }
}
