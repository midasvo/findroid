package dev.jdtech.jellyfin.film.presentation.downloads

import dev.jdtech.jellyfin.core.presentation.downloader.DownloadProgress
import dev.jdtech.jellyfin.models.CollectionSection
import dev.jdtech.jellyfin.models.FindroidItem

data class ActiveDownload(
    val item: FindroidItem,
    val progress: DownloadProgress,
    val downloadId: Long?,
)

data class DownloadsState(
    val queueItems: List<ActiveDownload> = emptyList(),
    val hasCompleted: Boolean = false,
    val sections: List<CollectionSection> = emptyList(),
    val storageUsedBytes: Long = 0L,
    val storageFreeBytes: Long = 0L,
    val storageIsExternal: Boolean = false,
    val isLoading: Boolean = false,
    val error: Exception? = null,
)
