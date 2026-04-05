package dev.jdtech.jellyfin.utils

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.text.format.Formatter
import androidx.core.net.toUri
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.FindroidSources
import dev.jdtech.jellyfin.models.FindroidTrickplayInfo
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.models.toFindroidEpisode
import dev.jdtech.jellyfin.models.toFindroidEpisodeDto
import dev.jdtech.jellyfin.models.toFindroidMediaStreamDto
import dev.jdtech.jellyfin.models.toFindroidMovie
import dev.jdtech.jellyfin.models.toFindroidMovieDto
import dev.jdtech.jellyfin.models.toFindroidSeasonDto
import dev.jdtech.jellyfin.models.toFindroidSegmentsDto
import dev.jdtech.jellyfin.models.toFindroidShowDto
import dev.jdtech.jellyfin.models.toFindroidSource
import dev.jdtech.jellyfin.models.toFindroidSourceDto
import dev.jdtech.jellyfin.models.toFindroidTrickplayInfoDto
import dev.jdtech.jellyfin.models.toFindroidUserDataDto
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.work.ImagesDownloaderWorker
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import kotlin.Exception
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import timber.log.Timber

class DownloaderImpl(
    private val context: Context,
    private val database: ServerDatabaseDao,
    private val jellyfinRepository: JellyfinRepository,
    private val appPreferences: AppPreferences,
    private val workManager: WorkManager,
) : Downloader {
    private val downloadManager = context.getSystemService(DownloadManager::class.java)

    // TODO: We should probably move most (if not all) code to a worker.
    //  At this moment it is possible that some things are not downloaded due to the user leaving
    //  the current screen
    override suspend fun downloadItem(
        item: FindroidItem,
        sourceId: String,
        storageIndex: Int,
    ): Pair<Long, UiText?> = coroutineScope {
        try {
            val source =
                jellyfinRepository.getMediaSources(item.id, true).first { it.id == sourceId }
            val segments = jellyfinRepository.getSegments(item.id)
            val trickplayInfo =
                if (item is FindroidSources) {
                    item.trickplayInfo?.get(sourceId)
                } else {
                    null
                }
            val dirs = context.getExternalFilesDirs(null)
            val storageLocation = run {
                // Try requested index first; silently fall back to index 0 if unavailable.
                val requested = dirs.getOrNull(storageIndex)
                if (
                    requested != null &&
                    Environment.getExternalStorageState(requested) == Environment.MEDIA_MOUNTED
                ) {
                    requested
                } else {
                    dirs.getOrNull(0)?.takeIf {
                        Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED
                    }
                }
            } ?: return@coroutineScope Pair(
                -1,
                UiText.StringResource(CoreR.string.storage_unavailable),
            )
            val destFile = File(storageLocation, "downloads/${item.id}.${source.id}.download")
            destFile.parentFile?.mkdirs()
            val path = Uri.fromFile(destFile)
            val stats = StatFs(storageLocation.path)
            if (stats.availableBytes < source.size) {
                return@coroutineScope Pair(
                    -1,
                    UiText.StringResource(
                        CoreR.string.not_enough_storage,
                        Formatter.formatFileSize(context, source.size),
                        Formatter.formatFileSize(context, stats.availableBytes),
                    ),
                )
            }
            val request =
                DownloadManager.Request(source.path.toUri())
                    .setTitle(item.name)
                    .setAllowedOverMetered(
                        appPreferences.getValue(appPreferences.downloadOverMobileData)
                    )
                    .setAllowedOverRoaming(
                        appPreferences.getValue(appPreferences.downloadWhenRoaming)
                    )
                    .setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    )
                    .setDestinationUri(path)
            val downloadId = downloadManager.enqueue(request)

            when (item) {
                is FindroidMovie -> {
                    database.insertMovie(
                        item.toFindroidMovieDto(
                            appPreferences.getValue(appPreferences.currentServer)
                        )
                    )
                }
                is FindroidEpisode -> {
                    val show = jellyfinRepository.getShow(item.seriesId)
                    database.insertShow(
                        show.toFindroidShowDto(
                            appPreferences.getValue(appPreferences.currentServer)
                        )
                    )
                    val season = jellyfinRepository.getSeason(item.seasonId)
                    database.insertSeason(season.toFindroidSeasonDto())
                    database.insertEpisode(
                        item.toFindroidEpisodeDto(
                            appPreferences.getValue(appPreferences.currentServer)
                        )
                    )

                    startImagesDownloader(show)
                    startImagesDownloader(season)
                }
            }

            val sourceDto = source.toFindroidSourceDto(item.id, path.path.orEmpty())

            database.insertSource(sourceDto.copy(downloadId = downloadId))
            database.insertUserData(item.toFindroidUserDataDto(jellyfinRepository.getUserId()))

            val resolvedStorageIndex = dirs.indexOf(storageLocation)
            downloadExternalMediaStreams(item, source, resolvedStorageIndex)

            segments.forEach { database.insertSegment(it.toFindroidSegmentsDto(item.id)) }

            if (trickplayInfo != null) {
                downloadTrickplayData(item.id, sourceId, trickplayInfo)
            }

            startImagesDownloader(item)
            return@coroutineScope Pair(downloadId, null)
        } catch (e: Exception) {
            try {
                val source = jellyfinRepository.getMediaSources(item.id).first { it.id == sourceId }
                deleteItem(item, source)
            } catch (e: Exception) { Timber.e(e, "Failed to clean up failed download") }
            Timber.e(e)
            return@coroutineScope Pair(-1, mapDownloadError(e))
        }
    }

    override suspend fun downloadItem(
        item: FindroidItem,
        storageIndex: Int,
    ): Pair<Long, UiText?> {
        val sources = try {
            jellyfinRepository.getMediaSources(item.id, true)
        } catch (e: Exception) {
            Timber.e(e, "Failed to resolve media sources for ${item.name}")
            return Pair(-1, mapDownloadError(e))
        }
        val sourceId = sources.firstOrNull()?.id
            ?: return Pair(-1, UiText.StringResource(CoreR.string.download_error_no_sources))
        return downloadItem(item = item, sourceId = sourceId, storageIndex = storageIndex)
    }

    override suspend fun cancelDownload(item: FindroidItem, downloadId: Long) {
        val source =
            database.getSourceByDownloadId(downloadId)?.toFindroidSource(database) ?: return
        source.downloadId?.let { downloadManager.remove(it) }
        deleteItem(item, source)
    }

    override suspend fun deleteItem(item: FindroidItem, source: FindroidSource) {
        when (item) {
            is FindroidMovie -> {
                database.deleteMovie(item.id)
            }
            is FindroidEpisode -> {
                database.deleteEpisode(item.id)
                val remainingEpisodes = database.getEpisodesBySeasonId(item.seasonId)
                if (remainingEpisodes.isEmpty()) {
                    database.deleteSeason(item.seasonId)
                    database.deleteUserData(item.seasonId)
                    File(context.filesDir, "trickplay/${item.seasonId}").deleteRecursively()
                    File(context.filesDir, "images/${item.seasonId}").deleteRecursively()
                    val remainingSeasons = database.getSeasonsByShowId(item.seriesId)
                    if (remainingSeasons.isEmpty()) {
                        database.deleteShow(item.seriesId)
                        database.deleteUserData(item.seriesId)
                        File(context.filesDir, "trickplay/${item.seriesId}").deleteRecursively()
                        File(context.filesDir, "images/${item.seriesId}").deleteRecursively()
                    }
                }
            }
        }

        database.deleteSource(source.id)
        File(source.path).delete()

        val mediaStreams = database.getMediaStreamsBySourceId(source.id)
        for (mediaStream in mediaStreams) {
            // Cancel any still-running DM job for this external stream before
            // removing the DB row, otherwise we leak in-flight downloads.
            mediaStream.downloadId?.let { downloadManager.remove(it) }
            File(mediaStream.path).delete()
        }
        database.deleteMediaStreamsBySourceId(source.id)

        database.deleteUserData(item.id)

        File(context.filesDir, "trickplay/${item.id}").deleteRecursively()
        File(context.filesDir, "images/${item.id}").deleteRecursively()
    }

    override suspend fun getProgress(downloadId: Long?): Downloader.Progress {
        if (downloadId == null) {
            return Downloader.Progress(DownloadManager.STATUS_FAILED, 0, -1L, -1L)
        }
        var downloadStatus = DownloadManager.STATUS_FAILED
        var progress = -1
        var bytesDownloaded = -1L
        var totalBytes = -1L
        val query = DownloadManager.Query().setFilterById(downloadId)
        downloadManager.query(query).use { cursor ->
            if (cursor.moveToFirst()) {
                downloadStatus =
                    cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                totalBytes =
                    cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                    )
                bytesDownloaded =
                    cursor.getLong(
                        cursor.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR,
                        ),
                    )
                progress =
                    when (downloadStatus) {
                        DownloadManager.STATUS_SUCCESSFUL -> 100
                        DownloadManager.STATUS_RUNNING ->
                            if (totalBytes > 0) {
                                bytesDownloaded.times(100).div(totalBytes).toInt()
                            } else {
                                -1
                            }
                        else -> -1
                    }
            }
        }
        return Downloader.Progress(downloadStatus, progress, bytesDownloaded, totalBytes)
    }

    override suspend fun getProgress(downloadIds: List<Long>): Map<Long, Downloader.Progress> {
        if (downloadIds.isEmpty()) return emptyMap()
        val result = mutableMapOf<Long, Downloader.Progress>()
        val query = DownloadManager.Query().setFilterById(*downloadIds.toLongArray())
        downloadManager.query(query).use { cursor ->
            val statusIdx = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            val totalIdx = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val doneIdx =
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val idIdx = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIdx)
                val status = cursor.getInt(statusIdx)
                val total = cursor.getLong(totalIdx)
                val done = cursor.getLong(doneIdx)
                val progress =
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> 100
                        DownloadManager.STATUS_RUNNING ->
                            if (total > 0) done.times(100).div(total).toInt() else -1
                        else -> -1
                    }
                result[id] = Downloader.Progress(status, progress, done, total)
            }
        }
        // Any id not returned by DM is gone; surface as failed.
        for (id in downloadIds) {
            if (id !in result) {
                result[id] = Downloader.Progress(DownloadManager.STATUS_FAILED, 0, -1L, -1L)
            }
        }
        return result
    }

    private fun downloadExternalMediaStreams(
        item: FindroidItem,
        source: FindroidSource,
        storageIndex: Int = 0,
    ) {
        val dirs = context.getExternalFilesDirs(null)
        val storageLocation = dirs.getOrNull(storageIndex)
            ?.takeIf { Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }
            ?: dirs.getOrNull(0)?.takeIf { Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }
            ?: return
        val downloadsDir = File(storageLocation, "downloads")
        downloadsDir.mkdirs()
        for (mediaStream in source.mediaStreams.filter { it.isExternal }) {
            val id = UUID.randomUUID()
            val streamPath =
                Uri.fromFile(
                    File(downloadsDir, "${item.id}.${source.id}.$id.download")
                )
            try {
                database.insertMediaStream(
                    mediaStream.toFindroidMediaStreamDto(id, source.id, streamPath.path.orEmpty())
                )
                val mediaStreamPath = mediaStream.path ?: continue
                val request =
                    DownloadManager.Request(mediaStreamPath.toUri())
                        .setTitle(mediaStream.title)
                        .setAllowedOverMetered(
                            appPreferences.getValue(appPreferences.downloadOverMobileData)
                        )
                        .setAllowedOverRoaming(
                            appPreferences.getValue(appPreferences.downloadWhenRoaming)
                        )
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
                        .setDestinationUri(streamPath)
                val downloadId = downloadManager.enqueue(request)
                database.setMediaStreamDownloadId(id, downloadId)
            } catch (e: Exception) {
                // One bad external stream (malformed URL, DM rejects request)
                // shouldn't kill the whole download. Drop the orphan DB row and
                // keep going with the remaining streams.
                Timber.e(
                    e,
                    "Failed to enqueue external stream ${mediaStream.title} for ${item.name}",
                )
                try {
                    database.deleteMediaStream(id)
                } catch (_: Exception) {
                    // swallow — nothing more we can do
                }
            }
        }
    }

    private suspend fun downloadTrickplayData(
        itemId: UUID,
        sourceId: String,
        trickplayInfo: FindroidTrickplayInfo,
    ) {
        val maxIndex =
            ceil(
                    trickplayInfo.thumbnailCount
                        .toDouble()
                        .div(trickplayInfo.tileWidth * trickplayInfo.tileHeight)
                )
                .toInt()
        val byteArrays = mutableListOf<ByteArray>()
        for (i in 0..maxIndex) {
            jellyfinRepository.getTrickplayData(itemId, trickplayInfo.width, i)?.let { byteArray ->
                byteArrays.add(byteArray)
            }
        }
        saveTrickplayData(itemId, sourceId, trickplayInfo, byteArrays)
    }

    private fun saveTrickplayData(
        itemId: UUID,
        sourceId: String,
        trickplayInfo: FindroidTrickplayInfo,
        byteArrays: List<ByteArray>,
    ) {
        val basePath = "trickplay/$itemId/$sourceId"
        database.insertTrickplayInfo(trickplayInfo.toFindroidTrickplayInfoDto(sourceId))
        File(context.filesDir, basePath).mkdirs()
        for ((i, byteArray) in byteArrays.withIndex()) {
            val file = File(context.filesDir, "$basePath/$i")
            file.writeBytes(byteArray)
        }
    }

    override suspend fun savePendingDownload(item: FindroidItem) = withContext(Dispatchers.IO) {
        val kind =
            when (item) {
                is FindroidMovie -> "MOVIE"
                is FindroidEpisode -> "EPISODE"
                else -> return@withContext
            }
        database.insertPendingDownload(
            dev.jdtech.jellyfin.models.PendingDownloadDto(
                itemId = item.id,
                itemKind = kind,
                addedAt = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun removePendingDownload(itemId: UUID) = withContext(Dispatchers.IO) {
        database.deletePendingDownload(itemId)
    }

    override suspend fun getPendingDownloads(): List<Pair<FindroidItem, Long>> = withContext(Dispatchers.IO) {
        val pending = database.getPendingDownloads()
        val result = mutableListOf<Pair<FindroidItem, Long>>()
        for (row in pending) {
            val resolved: FindroidItem? =
                try {
                    when (row.itemKind) {
                        "MOVIE" -> jellyfinRepository.getMovie(row.itemId)
                        "EPISODE" -> jellyfinRepository.getEpisode(row.itemId)
                        else -> null
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to resolve pending download ${row.itemId}; dropping")
                    null
                }
            if (resolved != null) {
                result.add(resolved to row.addedAt)
            } else {
                // Drop the row so we don't keep retrying a dead reference.
                database.deletePendingDownload(row.itemId)
            }
        }
        result
    }

    override suspend fun getActiveDownloads(): List<Pair<FindroidItem, Long>> = withContext(Dispatchers.IO) {
        val userId = jellyfinRepository.getUserId()
        val sources = database.getActiveDownloadSources()
        val result = mutableListOf<Pair<FindroidItem, Long>>()
        for (source in sources) {
            val downloadId = source.downloadId ?: continue
            val item: FindroidItem? =
                try {
                    database.getMovie(source.itemId).toFindroidMovie(database, userId)
                } catch (_: Exception) {
                    try {
                        database.getEpisode(source.itemId).toFindroidEpisode(database, userId)
                    } catch (_: Exception) {
                        null
                    }
                }
            if (item != null) result.add(item to downloadId)
        }
        result
    }

    // Maps exceptions to user-facing text. Raw exception messages may contain URLs,
    // hostnames, or stack fragments that shouldn't surface in the UI — log the full
    // exception via Timber and show a generic localized message instead.
    private fun mapDownloadError(e: Throwable): UiText =
        when (e) {
            is UnknownHostException, is ConnectException ->
                UiText.StringResource(CoreR.string.download_error_server_unreachable)
            is SocketTimeoutException ->
                UiText.StringResource(CoreR.string.download_error_timeout)
            is IOException ->
                UiText.StringResource(CoreR.string.download_error_network)
            else -> UiText.StringResource(CoreR.string.unknown_error)
        }

    override suspend fun sweepOrphans() = withContext(Dispatchers.IO) {
        val userId = jellyfinRepository.getUserId()

        // Collect known in-flight downloadIds from DM so we know what's still valid.
        val liveDmIds = mutableSetOf<Long>()
        try {
            val query = DownloadManager.Query()
                .setFilterByStatus(
                    DownloadManager.STATUS_PENDING or
                        DownloadManager.STATUS_RUNNING or
                        DownloadManager.STATUS_PAUSED or
                        DownloadManager.STATUS_SUCCESSFUL,
                )
            downloadManager.query(query).use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                while (cursor.moveToNext()) liveDmIds.add(cursor.getLong(idIdx))
            }
        } catch (e: Exception) {
            Timber.w(e, "Could not enumerate DownloadManager; skipping orphan sweep")
            return@withContext
        }

        val knownPaths = mutableSetOf<String>()

        // Completed sources: file must still exist on disk.
        for (source in database.getCompletedDownloadSources()) {
            knownPaths.add(source.path)
            if (File(source.path).exists()) continue
            Timber.i("Sweeping completed source with missing file: ${source.path}")
            cleanupOrphanSource(source.itemId, userId)
        }

        // Active (.download) sources: file missing AND DM job gone → orphan.
        for (source in database.getActiveDownloadSources()) {
            knownPaths.add(source.path)
            val dlId = source.downloadId
            val dmAlive = dlId != null && dlId in liveDmIds
            val fileExists = File(source.path).exists()
            if (!dmAlive && !fileExists) {
                Timber.i("Sweeping dead active source: ${source.path}")
                cleanupOrphanSource(source.itemId, userId)
            }
        }

        // Orphan .download files on disk: not tracked by any DB source row.
        for (storageDir in context.getExternalFilesDirs(null)) {
            if (storageDir == null) continue
            if (Environment.getExternalStorageState(storageDir) != Environment.MEDIA_MOUNTED) continue
            val downloadsDir = File(storageDir, "downloads")
            val files = downloadsDir.listFiles() ?: continue
            for (file in files) {
                if (!file.isFile) continue
                if (file.absolutePath in knownPaths) continue
                Timber.i("Deleting orphan download file: ${file.absolutePath}")
                try {
                    file.delete()
                } catch (e: Exception) {
                    Timber.w(e, "Failed to delete orphan ${file.absolutePath}")
                }
            }
        }
    }

    private suspend fun cleanupOrphanSource(itemId: UUID, userId: UUID) {
        val item: FindroidItem? =
            try {
                database.getMovieOrNull(itemId)?.toFindroidMovie(database, userId)
            } catch (_: Exception) {
                null
            } ?: try {
                database.getEpisodeOrNull(itemId)?.toFindroidEpisode(database, userId)
            } catch (_: Exception) {
                null
            }
        if (item == null) {
            // Item record is already gone — drop lingering source rows directly.
            for (source in database.getSources(itemId)) {
                database.deleteMediaStreamsBySourceId(source.id)
                database.deleteSource(source.id)
            }
            return
        }
        for (source in database.getSources(itemId).map { it.toFindroidSource(database) }) {
            try {
                deleteItem(item, source)
            } catch (e: Exception) {
                Timber.w(e, "Failed to delete orphan item ${item.name}")
            }
        }
    }

    private fun startImagesDownloader(item: FindroidItem) {
        val downloadImagesRequest =
            OneTimeWorkRequestBuilder<ImagesDownloaderWorker>()
                .setInputData(workDataOf(ImagesDownloaderWorker.KEY_ITEM_ID to item.id.toString()))
                .build()

        // Episode downloads trigger image fetches for episode + season + show,
        // and a whole season download would otherwise enqueue the same show worker
        // N times. Dedupe on itemId so we only hit the image endpoint once per item.
        workManager.enqueueUniqueWork(
            "image-download-${item.id}",
            ExistingWorkPolicy.KEEP,
            downloadImagesRequest,
        )
    }
}
