package dev.jdtech.jellyfin.core.presentation.downloader

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.repository.JellyfinRepository
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
    private val repository: JellyfinRepository,
    private val database: ServerDatabaseDao,
    @ApplicationContext private val context: Context,
) {
    sealed interface EntryState {
        data object Pending : EntryState

        data object Downloading : EntryState

        data object Paused : EntryState

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
        /** Bytes downloaded so far, -1 if unknown. */
        val bytesDownloaded: Long = -1L,
        /** Total bytes, -1 if unknown. */
        val totalBytes: Long = -1L,
        /** Moving-average bytes/sec, or 0 if not computed yet. */
        val bytesPerSecond: Long = 0L,
        /** How many times this entry has been auto-retried. */
        val retryCount: Int = 0,
        /** Epoch ms at which an auto-retry should fire, or null. */
        val retryAt: Long? = null,
    )

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var pumpJob: Job? = null

    /** Previous bytes + wall-clock (ms) sample per downloadId, for speed calc. */
    private val lastSamples = mutableMapOf<Long, Pair<Long, Long>>()

    suspend fun enqueue(item: FindroidItem) {
        var persisted = false
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
            persisted = true
        }
        if (persisted) {
            try {
                downloader.savePendingDownload(item)
            } catch (e: Exception) {
                Timber.e(e, "Failed to persist pending download ${item.name}")
            }
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
            val existing = _entries.value.firstOrNull { it.id == item.id }
            // If it's already live (pending/downloading), leave it alone.
            if (
                existing != null &&
                    existing.state !is EntryState.Failed &&
                    existing.state !is EntryState.Completed
            ) {
                return@withLock
            }
            // Replace stale Failed/Completed entry so the UI reflects the
            // in-flight download instead of the old terminal state.
            val filtered = _entries.value.filter { it.id != item.id }
            val entry =
                Entry(
                    id = item.id,
                    item = item,
                    addedAt = System.currentTimeMillis(),
                    state = EntryState.Downloading,
                    downloadId = downloadId,
                    startedAt = System.currentTimeMillis(),
                )
            _entries.value = sort(filtered + entry)
        }
        ensurePump()
    }

    /**
     * Re-attaches every in-flight download from the DB. Call on app startup to
     * recover queue state after process death. Android DownloadManager runs
     * independently, so completion broadcasts still update the DB; this just
     * reattaches the UI-facing queue so the user can see/cancel those downloads.
     */
    suspend fun restoreAll() {
        val active =
            try {
                downloader.getActiveDownloads()
            } catch (e: Exception) {
                Timber.e(e, "Failed to query active downloads for restore")
                emptyList()
            }
        val pending =
            try {
                downloader.getPendingDownloads()
            } catch (e: Exception) {
                Timber.e(e, "Failed to query pending downloads for restore")
                emptyList()
            }
        if (active.isEmpty() && pending.isEmpty()) return
        mutex.withLock {
            val known = _entries.value.map { it.id }.toSet()
            val now = System.currentTimeMillis()
            val addedActive =
                active.filter { (item, _) -> item.id !in known }.map { (item, downloadId) ->
                    Entry(
                        id = item.id,
                        item = item,
                        addedAt = now,
                        state = EntryState.Downloading,
                        downloadId = downloadId,
                        startedAt = now,
                    )
                }
            // Pending items entered after active downloads so they don't cut in line.
            val activeIds = addedActive.map { it.id }.toSet()
            val addedPending =
                pending
                    .filter { (item, _) -> item.id !in known && item.id !in activeIds }
                    .map { (item, addedAt) ->
                        Entry(
                            id = item.id,
                            item = item,
                            addedAt = addedAt,
                            state = EntryState.Pending,
                        )
                    }
            val added = addedActive + addedPending
            if (added.isEmpty()) return@withLock
            _entries.value = sort(_entries.value + added)
        }
        ensurePump()
    }

    suspend fun enqueueAll(items: List<FindroidItem>) {
        val persistedItems = mutableListOf<FindroidItem>()
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
            persistedItems.addAll(newEntries.map { it.item })
        }
        for (item in persistedItems) {
            try {
                downloader.savePendingDownload(item)
            } catch (e: Exception) {
                Timber.e(e, "Failed to persist pending download ${item.name}")
            }
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
            try {
                downloader.removePendingDownload(id)
            } catch (e: Exception) {
                Timber.e(e, "Failed to drop persisted pending row for $id")
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
            var retried: FindroidItem? = null
            mutex.withLock {
                _entries.value =
                    sort(
                        _entries.value.map { entry ->
                            if (entry.id == id && entry.state is EntryState.Failed) {
                                retried = entry.item
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
            retried?.let { item ->
                try {
                    downloader.savePendingDownload(item)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to persist retried download ${item.name}")
                }
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
                is EntryState.Paused -> 0
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
        // Keep the app process alive while we have work to pump. The service
        // stops itself when the queue drains.
        DownloadPumpService.start(context)
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
            // 1. Poll active downloads and transition completed/failed. Paused entries
            //    keep their DownloadManager job attached, so poll them too so we can
            //    flip back to Downloading when DM resumes (e.g. wifi reconnects).
            val active =
                _entries.value.filter {
                    it.state is EntryState.Downloading || it.state is EntryState.Paused
                }
            // Prune speed samples for download ids no longer active (removed,
            // completed, or failed).
            if (lastSamples.isNotEmpty()) {
                val activeDlIds = active.mapNotNull { it.downloadId }.toSet()
                lastSamples.keys.retainAll(activeDlIds)
            }
            if (active.isNotEmpty()) {
                val updates = mutableMapOf<UUID, Entry>()
                val now = System.currentTimeMillis()
                val dlIds = active.mapNotNull { it.downloadId }
                val snapshots = downloader.getProgress(dlIds)
                for (entry in active) {
                    val dlId = entry.downloadId ?: continue
                    val snapshot =
                        snapshots[dlId]
                            ?: Downloader.Progress(
                                DownloadManager.STATUS_FAILED,
                                0,
                                -1L,
                                -1L,
                            )
                    val newState: EntryState? =
                        when (snapshot.status) {
                            DownloadManager.STATUS_PENDING,
                            DownloadManager.STATUS_RUNNING ->
                                // If we were Paused and DM is running again, flip back
                                // to Downloading so the user sees progress resume.
                                if (entry.state is EntryState.Paused) EntryState.Downloading
                                else null
                            DownloadManager.STATUS_PAUSED ->
                                if (entry.state is EntryState.Paused) null
                                else EntryState.Paused
                            DownloadManager.STATUS_SUCCESSFUL -> EntryState.Completed
                            DownloadManager.STATUS_FAILED -> EntryState.Failed(null)
                            // Unknown/unhandled status (e.g. DM entry disappeared between
                            // process death and restore). Treat as failed rather than silently
                            // claiming success.
                            else -> EntryState.Failed(null)
                        }
                    val newProgress = snapshot.progress.coerceAtLeast(0).coerceAtMost(100)
                    val prevSample = lastSamples[dlId]
                    val speed =
                        if (prevSample != null && snapshot.bytesDownloaded >= 0) {
                            val (prevBytes, prevAt) = prevSample
                            val elapsedMs = now - prevAt
                            if (elapsedMs > 0) {
                                ((snapshot.bytesDownloaded - prevBytes) * 1000L / elapsedMs)
                                    .coerceAtLeast(0L)
                            } else {
                                entry.bytesPerSecond
                            }
                        } else {
                            0L
                        }
                    if (snapshot.bytesDownloaded >= 0) {
                        lastSamples[dlId] = snapshot.bytesDownloaded to now
                    }
                    val bytesChanged =
                        snapshot.bytesDownloaded != entry.bytesDownloaded ||
                            snapshot.totalBytes != entry.totalBytes
                    val speedChanged = speed != entry.bytesPerSecond
                    if (
                        newState != null ||
                            newProgress != entry.progress ||
                            bytesChanged ||
                            speedChanged
                    ) {
                        updates[entry.id] =
                            entry.copy(
                                state = newState ?: entry.state,
                                progress = if (newState == EntryState.Completed) 100 else newProgress,
                                bytesDownloaded = snapshot.bytesDownloaded,
                                totalBytes = snapshot.totalBytes,
                                // Clear speed once terminal; stale numbers confuse the UI.
                                bytesPerSecond = if (newState != null) 0L else speed,
                            )
                    }
                }
                if (updates.isNotEmpty()) {
                    // Finalize completed downloads (rename .download → final, update
                    // DB path) BEFORE pushing the new state to observers. The
                    // DownloadsViewModel reacts to busy→idle by reloading the
                    // library — if we push first, that reload races the rename
                    // and the library tab comes up empty.
                    val completedNow =
                        updates.values.filter { it.state is EntryState.Completed }
                    for (entry in completedNow) {
                        val dlId = entry.downloadId ?: continue
                        try {
                            downloader.finalizeDownload(dlId)
                        } catch (e: Exception) {
                            Timber.e(
                                e,
                                "finalizeDownload failed for ${entry.item.name} (id=$dlId)",
                            )
                        }
                    }
                    mutex.withLock {
                        _entries.value =
                            sort(_entries.value.map { updates[it.id] ?: it })
                    }
                    // Any entry that transitioned to Failed has an orphaned .download
                    // file + source row on disk/DB. Clean them up so retry creates a
                    // fresh download and restoreAll() on next launch doesn't resurrect
                    // a dead DownloadManager id.
                    val failedEntries =
                        updates.values.filter { it.state is EntryState.Failed }
                    // Clean up DM jobs for failed entries first.
                    for (failed in failedEntries) {
                        val dlId = failed.downloadId ?: continue
                        lastSamples.remove(dlId)
                        try {
                            downloader.cancelDownload(failed.item, dlId)
                        } catch (e: Exception) {
                            Timber.e(
                                e,
                                "Failed to clean up orphaned download for ${failed.item.name}",
                            )
                        }
                    }
                    // Schedule auto-retry for eligible entries; notify for permanent failures.
                    val retryUpdates = mutableMapOf<UUID, Entry>()
                    for (failed in failedEntries) {
                        if (failed.retryCount < MAX_AUTO_RETRIES) {
                            val backoffMs = RETRY_BACKOFF_MS[failed.retryCount.coerceAtMost(RETRY_BACKOFF_MS.lastIndex)]
                            retryUpdates[failed.id] = failed.copy(
                                state = EntryState.Pending,
                                downloadId = null,
                                startedAt = null,
                                progress = 0,
                                retryCount = failed.retryCount + 1,
                                retryAt = System.currentTimeMillis() + backoffMs,
                            )
                            Timber.i("Auto-retry #${failed.retryCount + 1} for ${failed.item.name} in ${backoffMs / 1000}s")
                        } else {
                            notifyFailure(failed.item)
                        }
                    }
                    if (retryUpdates.isNotEmpty()) {
                        mutex.withLock {
                            _entries.value = sort(
                                _entries.value.map { retryUpdates[it.id] ?: it }
                            )
                        }
                    }
                    val completedEntries =
                        updates.values.filter { it.state is EntryState.Completed }
                    for (entry in completedEntries) {
                        entry.downloadId?.let { lastSamples.remove(it) }
                        // Smart Downloads: auto-queue next episode
                        val item = entry.item
                        if (item is FindroidEpisode) {
                            scope.launch { smartEnqueueNext(item) }
                        }
                    }
                }
            }

            // 2. Fill free slots from Pending queue
            val maxConcurrent = appPreferences.getValue(appPreferences.maxConcurrentDownloads)
            val currentlyActive =
                _entries.value.count {
                    it.state is EntryState.Downloading || it.state is EntryState.Paused
                }
            val freeSlots = (maxConcurrent - currentlyActive).coerceAtLeast(0)
            if (freeSlots > 0) {
                val now = System.currentTimeMillis()
                val pending = _entries.value.filter {
                    it.state is EntryState.Pending && (it.retryAt == null || it.retryAt <= now)
                }.take(freeSlots)
                for (entry in pending) {
                    startDownload(entry)
                }
            }

            // 3. Decide whether to keep pumping (under mutex to avoid starvation race
            //    with enqueue calling ensurePump between our check and our nulling of
            //    pumpJob).
            val shouldExit =
                mutex.withLock {
                    val snap = _entries.value
                    val hasWork =
                        snap.any {
                            it.state is EntryState.Downloading ||
                                it.state is EntryState.Pending ||
                                it.state is EntryState.Paused
                        }
                    if (!hasWork) {
                        pumpJob = null
                        true
                    } else {
                        false
                    }
                }
            if (shouldExit) return
            delay(1000L)
        }
    }

    private suspend fun startDownload(entry: Entry) {
        val storageIndex =
            appPreferences.getValue(appPreferences.downloadStorageIndex)?.toIntOrNull() ?: 0
        // Drop the pending row *before* running setup. If the process dies
        // mid-setup, restoreAll() on next launch would otherwise resurrect the
        // pending entry and enqueue a duplicate DM job + duplicate source row
        // on top of whatever partial state setup left behind.
        try {
            downloader.removePendingDownload(entry.id)
        } catch (e: Exception) {
            Timber.e(e, "Failed to drop persisted pending row for ${entry.item.name}")
        }
        val (downloadId, errorText) =
            try {
                downloader.downloadItem(item = entry.item, storageIndex = storageIndex)
            } catch (e: Exception) {
                Timber.e(e, "downloadItem threw for ${entry.item.name}")
                // Surface something to the Queue UI instead of a blank error — the
                // raw exception message could leak URLs/stack info, so pick a
                // generic localized string.
                Pair(-1L, UiText.StringResource(CoreR.string.downloading_error))
            }

        var orphaned = false
        mutex.withLock {
            val stillPresent = _entries.value.any { it.id == entry.id }
            if (!stillPresent) {
                // Entry was removed (user cancelled) while we were starting the
                // download. Android DM is running it but we no longer track it —
                // cancel to avoid leaking.
                orphaned = downloadId != -1L
                return@withLock
            }
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
        if (orphaned) {
            try {
                downloader.cancelDownload(entry.item, downloadId)
            } catch (e: Exception) {
                Timber.e(e, "Failed to clean up orphaned download ${entry.item.name}")
            }
        }
    }

    private fun notifyFailure(item: FindroidItem) {
        val nm = context.getSystemService<NotificationManager>() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(FAILURE_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    FAILURE_CHANNEL_ID,
                    context.getString(CoreR.string.download_failures_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
        }
        val title = if (item is FindroidEpisode) {
            "${item.seriesName} · S%02dE%02d".format(item.parentIndexNumber, item.indexNumber)
        } else {
            item.name
        }
        val notification = NotificationCompat.Builder(context, FAILURE_CHANNEL_ID)
            .setSmallIcon(CoreR.drawable.ic_x)
            .setContentTitle(context.getString(CoreR.string.download_failed))
            .setContentText(title)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        // Use item hashCode as id so each failed item gets its own notification.
        nm.notify(item.id.hashCode(), notification)
    }

    /**
     * Smart Downloads: when an episode finishes downloading, automatically
     * queue the next episode in the same season (if it exists and isn't
     * already downloaded or queued).
     */
    private suspend fun smartEnqueueNext(episode: FindroidEpisode) {
        if (!appPreferences.getValue(appPreferences.smartDownloads)) return
        try {
            val episodes = repository.getEpisodes(
                seriesId = episode.seriesId,
                seasonId = episode.seasonId,
            )
            val currentIdx = episodes.indexOfFirst { it.id == episode.id }
            if (currentIdx == -1 || currentIdx >= episodes.lastIndex) return

            val next = episodes[currentIdx + 1]
            // Skip if already downloaded (has a source with a non-.download path)
            val existingSources = database.getSources(next.id)
            if (existingSources.any { !it.path.endsWith(".download") }) {
                Timber.d("Smart Downloads: ${next.name} already downloaded, skipping")
                return
            }
            // Skip if already in the queue
            if (_entries.value.any { it.id == next.id }) {
                Timber.d("Smart Downloads: ${next.name} already queued, skipping")
                return
            }
            Timber.i("Smart Downloads: auto-queueing next episode ${next.seriesName} S%02dE%02d".format(next.parentIndexNumber, next.indexNumber))
            enqueue(next)
        } catch (e: Exception) {
            Timber.e(e, "Smart Downloads: failed to fetch next episode after ${episode.name}")
        }
    }

    companion object {
        private const val FAILURE_CHANNEL_ID = "download_failures"
        private const val MAX_AUTO_RETRIES = 3
        /** Backoff delays: 30s, 2m, 10m. */
        private val RETRY_BACKOFF_MS = longArrayOf(30_000L, 120_000L, 600_000L)
    }
}
