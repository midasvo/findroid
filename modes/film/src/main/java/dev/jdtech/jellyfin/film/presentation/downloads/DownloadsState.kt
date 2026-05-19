package dev.jdtech.jellyfin.film.presentation.downloads

import dev.jdtech.jellyfin.core.presentation.downloader.DownloadProgress
import java.util.UUID
import dev.jdtech.jellyfin.models.CollectionSection
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.UiText

data class ActiveDownload(
    val item: FindroidItem,
    val progress: DownloadProgress,
    val downloadId: Long?,
    /** Bytes downloaded so far, or -1 if unknown. */
    val bytesDownloaded: Long = -1L,
    /** Total bytes for the download, or -1 if unknown. */
    val totalBytes: Long = -1L,
    /** True when [totalBytes] is an estimate (a transcode of unknown length). */
    val totalBytesEstimated: Boolean = false,
    /** True when the server is transcoding this download (e.g. Dolby Vision). */
    val isTranscode: Boolean = false,
    /** Transfer rate in bytes/sec, or 0 if not yet computed. */
    val bytesPerSecond: Long = 0L,
    /** User-facing failure reason, only set when progress.status == FAILED. */
    val errorText: UiText? = null,
)

enum class DownloadSortOrder { NAME, DATE, SIZE }

data class DownloadsState(
    val queueItems: List<ActiveDownload> = emptyList(),
    val hasCompleted: Boolean = false,
    val sortOrder: DownloadSortOrder = DownloadSortOrder.DATE,
    val sections: List<CollectionSection> = emptyList(),
    val itemSizes: Map<UUID, Long> = emptyMap(),
    val storageUsedBytes: Long = 0L,
    val storageFreeBytes: Long = 0L,
    val storageIsExternal: Boolean = false,
    val isLoading: Boolean = false,
    val error: Exception? = null,
)
