package dev.jdtech.jellyfin.player.core.domain.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class PlayerChapter(
    /** The start position. */
    val startPosition: Long,
    /** The name. */
    val name: String? = null,
    /** The Jellyfin image tag for the chapter thumbnail, if one was extracted server-side. */
    val imageTag: String? = null,
) : Parcelable

/**
 * Format the chapter's start position as a human-readable `H:MM:SS` (or `M:SS` when under an hour)
 * timestamp suitable for display next to the chapter title.
 */
fun PlayerChapter.formatStartTimestamp(): String {
    val totalSeconds = startPosition / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
