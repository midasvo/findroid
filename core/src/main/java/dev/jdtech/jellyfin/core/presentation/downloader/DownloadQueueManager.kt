package dev.jdtech.jellyfin.core.presentation.downloader

import dev.jdtech.jellyfin.models.FindroidItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared singleton that holds queued download items so they can be displayed
 * in the Downloads Queue tab. SeasonViewModel (or any download initiator)
 * writes queued items here; DownloadsViewModel reads them.
 */
@Singleton
class DownloadQueueManager @Inject constructor() {
    private val _queuedItems = MutableStateFlow<List<FindroidItem>>(emptyList())
    val queuedItems: StateFlow<List<FindroidItem>> = _queuedItems.asStateFlow()

    fun setQueuedItems(items: List<FindroidItem>) {
        _queuedItems.value = items
    }

    fun clear() {
        _queuedItems.value = emptyList()
    }
}
