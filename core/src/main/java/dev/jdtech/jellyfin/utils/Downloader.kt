package dev.jdtech.jellyfin.utils

import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.UiText

interface Downloader {
    suspend fun downloadItem(
        item: FindroidItem,
        sourceId: String,
        storageIndex: Int = 0,
    ): Pair<Long, UiText?>

    /**
     * Resolves the first available media source for the item and starts the download.
     * Returns (downloadId, null) on success or (-1L, errorText) on failure.
     */
    suspend fun downloadItem(
        item: FindroidItem,
        storageIndex: Int = 0,
    ): Pair<Long, UiText?>

    suspend fun cancelDownload(item: FindroidItem, downloadId: Long)

    suspend fun deleteItem(item: FindroidItem, source: FindroidSource)

    /** Snapshot from the Android DownloadManager for a single download id. */
    data class Progress(
        /** DownloadManager.STATUS_* */
        val status: Int,
        /** Progress percentage 0..100, or -1 if unknown. */
        val progress: Int,
        /** Bytes downloaded so far, or -1 if unknown. */
        val bytesDownloaded: Long,
        /** Total bytes for the download, or -1 if unknown. */
        val totalBytes: Long,
    )

    suspend fun getProgress(downloadId: Long?): Progress

    /**
     * Batched progress lookup — one DownloadManager query covers every id.
     * Missing ids are returned with a FAILED status (the DM entry disappeared).
     */
    suspend fun getProgress(downloadIds: List<Long>): Map<Long, Progress>

    /**
     * Returns every in-flight download known to the DB as (item, downloadId) pairs.
     * Used on app startup to re-attach the queue to Android DownloadManager
     * jobs that survived process death.
     */
    suspend fun getActiveDownloads(): List<Pair<FindroidItem, Long>>

    /** Persists a pending queue entry so it can be re-queued after process death. */
    suspend fun savePendingDownload(item: FindroidItem)

    /** Removes a persisted pending entry (on start/remove/retry). */
    suspend fun removePendingDownload(itemId: java.util.UUID)

    /**
     * Returns previously queued but not-yet-started items paired with the timestamp
     * they were originally added (ms since epoch). Items that can no longer be resolved
     * (server unreachable, item deleted) are logged and skipped.
     */
    suspend fun getPendingDownloads(): List<Pair<FindroidItem, Long>>

    /**
     * Drops DB rows and on-disk files whose state no longer matches reality:
     *   - completed sources whose file has been deleted externally
     *   - active .download sources whose DownloadManager job is gone and file is missing
     *   - orphan .download files not referenced by any DB source row
     *
     * Run on app startup before restoring active downloads.
     */
    suspend fun sweepOrphans()

    /**
     * Promotes a finished download from `<path>.download` to `<path>` and updates
     * the DB source path so the Library tab can find it. Idempotent — safe to call
     * from both the pump and the broadcast receiver, in either order. Returns true
     * if the source is in a finalized state after the call (already finalized, or
     * just finalized successfully).
     */
    suspend fun finalizeDownload(downloadId: Long): Boolean
}
