package dev.jdtech.jellyfin.utils

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Defensive backstop for [Downloader.finalizeDownload]. The pump in
 * `DownloadQueue` is the primary completion path; this receiver only matters
 * when the pump misses a transition (process death between download finish and
 * the next pump tick). Both call sites delegate to the same idempotent
 * [Downloader.finalizeDownload] so they cannot disagree.
 */
@AndroidEntryPoint
class DownloadReceiver : BroadcastReceiver() {

    @Inject lateinit var downloader: Downloader

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.intent.action.DOWNLOAD_COMPLETE") return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        if (id == -1L) return

        // BroadcastReceiver.onReceive runs on the main thread. The DB DAO
        // calls and file rename in finalizeDownload are blocking I/O, so move
        // the work off the main thread via goAsync().
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                downloader.finalizeDownload(id)
            } catch (e: Exception) {
                Timber.e(e, "DownloadReceiver finalize failed for id=$id")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
