package dev.jdtech.jellyfin.models

import kotlinx.serialization.Serializable
import org.jellyfin.sdk.model.api.BaseItemDto

@Serializable
data class FindroidChapter(
    /** The start position. */
    val startPosition: Long,
    /** The name. */
    val name: String? = null,
    /** The Jellyfin image tag for the chapter thumbnail, if one was extracted server-side. */
    val imageTag: String? = null,
)

fun BaseItemDto.toFindroidChapters(): List<FindroidChapter> {
    return chapters?.map { chapter ->
        FindroidChapter(
            startPosition = chapter.startPositionTicks / 10000,
            name = chapter.name,
            imageTag = chapter.imageTag,
        )
    } ?: emptyList()
}
