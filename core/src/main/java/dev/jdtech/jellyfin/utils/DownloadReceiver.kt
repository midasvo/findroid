package dev.jdtech.jellyfin.utils

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidSourceDto
import dev.jdtech.jellyfin.models.toFindroidEpisode
import dev.jdtech.jellyfin.models.toFindroidMovie
import dev.jdtech.jellyfin.models.toFindroidSource
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

@AndroidEntryPoint
class DownloadReceiver : BroadcastReceiver() {

    @Inject lateinit var database: ServerDatabaseDao

    @Inject lateinit var downloader: Downloader

    @Inject lateinit var repository: JellyfinRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.intent.action.DOWNLOAD_COMPLETE") return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        if (id == -1L) return

        // BroadcastReceiver.onReceive runs on the main thread. Both the Room
        // DAO calls and the file rename below are blocking I/O, so move the
        // whole handler off the main thread via goAsync().
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                handleDownloadComplete(context, id)
            } catch (e: Exception) {
                Timber.e(e, "DownloadReceiver handler failed for id=$id")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleDownloadComplete(context: Context, id: Long) {
        val downloadManager = context.getSystemService(DownloadManager::class.java)
        val query = DownloadManager.Query().setFilterById(id)
        var downloadStatus = DownloadManager.STATUS_FAILED
        var downloadReason = -1
        downloadManager.query(query).use { cursor ->
            if (cursor.moveToFirst()) {
                downloadStatus =
                    cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                downloadReason =
                    cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            }
        }

        val source = database.getSourceByDownloadId(id)
        if (source != null) {
            if (downloadStatus == DownloadManager.STATUS_SUCCESSFUL) {
                val path = source.path.removeSuffix(".download")
                if (renameFile(source.path, path)) {
                    database.setSourcePath(source.id, path)
                    Timber.d("Download complete, file at: $path")
                } else {
                    Timber.e("Failed to rename download, deleting item. path=${source.path}")
                    deleteItem(source)
                }
            } else {
                Timber.e(
                    "Download failed status=$downloadStatus reason=$downloadReason path=${source.path}",
                )
                deleteItem(source)
            }
            return
        }

        val mediaStream = database.getMediaStreamByDownloadId(id)
        if (mediaStream != null) {
            if (downloadStatus == DownloadManager.STATUS_SUCCESSFUL) {
                val path = mediaStream.path.removeSuffix(".download")
                if (renameFile(mediaStream.path, path)) {
                    database.setMediaStreamPath(mediaStream.id, path)
                } else {
                    Timber.e(
                        "Failed to rename media stream download, deleting. path=${mediaStream.path}",
                    )
                    database.deleteMediaStream(mediaStream.id)
                }
            } else {
                Timber.e(
                    "Media stream download failed status=$downloadStatus reason=$downloadReason",
                )
                database.deleteMediaStream(mediaStream.id)
            }
        }
    }

    private fun renameFile(fromPath: String, toPath: String): Boolean {
        val src = File(fromPath)
        val dst = File(toPath)
        if (src.renameTo(dst)) return true
        // renameTo can fail on some external storage filesystems (e.g. FAT32/exFAT on SD cards).
        // Fall back to copy + delete.
        return try {
            src.copyTo(dst, overwrite = true)
            src.delete()
            true
        } catch (e: Exception) {
            Timber.e(e, "copyTo fallback also failed: $fromPath -> $toPath")
            false
        }
    }

    private suspend fun deleteItem(sourceDto: FindroidSourceDto) {
        val source = sourceDto.toFindroidSource(database)
        val userId = repository.getUserId()
        val item: FindroidItem? =
            try {
                database.getMovie(sourceDto.itemId).toFindroidMovie(database, userId)
            } catch (_: Exception) {
                try {
                    database.getEpisode(sourceDto.itemId).toFindroidEpisode(database, userId)
                } catch (_: Exception) {
                    null
                }
            }
        if (item == null) return
        downloader.deleteItem(item, source)
    }
}
