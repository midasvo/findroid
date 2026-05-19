package dev.jdtech.jellyfin.models

import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.io.File
import java.util.UUID
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo

data class FindroidSource(
    val id: String,
    val name: String,
    val type: FindroidSourceType,
    val path: String,
    val size: Long,
    val mediaStreams: List<FindroidMediaStream>,
    val downloadId: Long? = null,
    /**
     * True when [path] points at a server-side transcode (an HLS manifest) rather
     * than the original file. Only the playback path ever produces this — downloads
     * always fetch the original.
     */
    val transcoded: Boolean = false,
)

suspend fun MediaSourceInfo.toFindroidSource(
    jellyfinRepository: JellyfinRepository,
    itemId: UUID,
    includePath: Boolean = false,
): FindroidSource {
    // When the server decided this source needs transcoding it returns a (relative)
    // transcodingUrl — an HLS manifest. Honor it over the original file. This only
    // happens on the playback path; downloads request a profile with no transcoding
    // profiles, so transcodingUrl is always null there.
    val transcodeUrl = transcodingUrl
    val transcoded = !transcodeUrl.isNullOrBlank()
    val path =
        when {
            transcoded -> jellyfinRepository.getBaseUrl() + transcodeUrl
            protocol == MediaProtocol.FILE -> {
                try {
                    if (includePath) jellyfinRepository.getStreamUrl(itemId, id.orEmpty()) else ""
                } catch (e: Exception) {
                    ""
                }
            }
            protocol == MediaProtocol.HTTP -> this.path.orEmpty()
            else -> ""
        }
    return FindroidSource(
        id = id.orEmpty(),
        name = name.orEmpty(),
        type = FindroidSourceType.REMOTE,
        path = path,
        size = size ?: 0,
        mediaStreams =
            mediaStreams?.map { it.toFindroidMediaStream(jellyfinRepository) } ?: emptyList(),
        transcoded = transcoded,
    )
}

fun FindroidSourceDto.toFindroidSource(serverDatabaseDao: ServerDatabaseDao): FindroidSource {
    return FindroidSource(
        id = id,
        name = name,
        type = type,
        path = path,
        size = File(path).length(),
        mediaStreams =
            serverDatabaseDao.getMediaStreamsBySourceId(id).map { it.toFindroidMediaStream() },
        downloadId = downloadId,
    )
}

enum class FindroidSourceType {
    REMOTE,
    LOCAL,
}
