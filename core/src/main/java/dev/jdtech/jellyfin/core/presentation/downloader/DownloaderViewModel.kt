package dev.jdtech.jellyfin.core.presentation.downloader

import android.app.DownloadManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.models.isDownloading
import dev.jdtech.jellyfin.utils.Downloader
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

@HiltViewModel
class DownloaderViewModel
@Inject
constructor(
    private val downloader: Downloader,
    private val downloadQueue: DownloadQueue,
) : ViewModel() {
    private val _state = MutableStateFlow(DownloaderState())
    val state = _state.asStateFlow()

    private val eventsChannel = Channel<DownloaderEvent>()
    val events = eventsChannel.receiveAsFlow()

    private var trackedItemId: UUID? = null
    private var observerJob: Job? = null
    private var wasCompleted = false

    fun update(item: FindroidItem) {
        if (trackedItemId == item.id && observerJob?.isActive == true) return
        trackedItemId = item.id
        wasCompleted = false

        // Restore if in-flight from a previous session
        if (item.isDownloading()) {
            val localSource = item.sources.firstOrNull { it.type == FindroidSourceType.LOCAL }
            val dlId = localSource?.downloadId
            if (dlId != null) {
                viewModelScope.launch { downloadQueue.restore(item, dlId) }
            }
        }

        startObserving(item.id)
    }

    private fun startObserving(itemId: UUID) {
        observerJob?.cancel()
        observerJob =
            viewModelScope.launch {
                downloadQueue.entries.collect { entries ->
                    val entry = entries.firstOrNull { it.id == itemId }
                    val newState =
                        when (val s = entry?.state) {
                            is DownloadQueue.EntryState.Pending ->
                                DownloaderState(status = DownloadManager.STATUS_PENDING)
                            is DownloadQueue.EntryState.Downloading ->
                                DownloaderState(
                                    status = DownloadManager.STATUS_RUNNING,
                                    progress = entry.progress / 100f,
                                )
                            is DownloadQueue.EntryState.Completed ->
                                DownloaderState(
                                    status = DownloadManager.STATUS_SUCCESSFUL,
                                    progress = 1f,
                                )
                            is DownloadQueue.EntryState.Failed ->
                                DownloaderState(
                                    status = DownloadManager.STATUS_FAILED,
                                    errorText = s.error,
                                )
                            null -> DownloaderState()
                        }
                    if (
                        newState.status == DownloadManager.STATUS_SUCCESSFUL && !wasCompleted
                    ) {
                        wasCompleted = true
                        eventsChannel.trySend(DownloaderEvent.Successful)
                    }
                    _state.emit(newState)
                }
            }
    }

    private fun download(item: FindroidItem) {
        trackedItemId = item.id
        wasCompleted = false
        viewModelScope.launch { downloadQueue.enqueue(item) }
        startObserving(item.id)
    }

    private fun cancelDownload(item: FindroidItem) {
        downloadQueue.remove(item.id)
    }

    private fun deleteDownload(item: FindroidItem) {
        viewModelScope.launch {
            // Ensure it's not tracked as an active download anymore
            downloadQueue.remove(item.id)
            val source =
                item.sources.firstOrNull { it.type == FindroidSourceType.LOCAL } ?: return@launch
            downloader.deleteItem(item = item, source = source)
            eventsChannel.send(DownloaderEvent.Deleted)
        }
    }

    fun onAction(action: DownloaderAction) {
        when (action) {
            is DownloaderAction.Download -> download(action.item)
            is DownloaderAction.DeleteDownload -> deleteDownload(action.item)
            is DownloaderAction.CancelDownload -> cancelDownload(action.item)
        }
    }

    override fun onCleared() {
        super.onCleared()
        observerJob?.cancel()
        eventsChannel.close()
    }
}
