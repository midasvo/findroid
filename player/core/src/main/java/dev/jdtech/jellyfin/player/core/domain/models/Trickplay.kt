package dev.jdtech.jellyfin.player.core.domain.models

import android.graphics.Bitmap

/**
 * Trickplay data for the current item.
 *
 * Two backing modes:
 *  - [images]: eagerly-decoded bitmaps, one per tile. Used by the existing default path,
 *    cheap to look up but heavy on memory for long items (a 2h movie at 10s intervals
 *    is ~720 tiles).
 *  - [loader]: lazy supplier that resolves a bitmap for a given position on demand
 *    (with internal LRU caching). Used by the experimental developer-toggle path so
 *    sprite-sheets only get decoded as the user scrubs near them.
 *
 * Exactly one is expected to be non-null; consumers prefer [loader] when present and
 * fall back to [images] otherwise.
 */
data class Trickplay(
    val interval: Int,
    val images: List<Bitmap> = emptyList(),
    val loader: TrickplayTileLoader? = null,
)

/**
 * Lazy supplier for trickplay tiles. Implementations decide their own caching strategy.
 * Implementations must be safe to call from background coroutines.
 */
interface TrickplayTileLoader {
    /**
     * Returns the bitmap for the given playback position (ms), or null if no tile is
     * available yet (still loading, network failed, position out of range).
     */
    suspend fun tileAt(positionMs: Long): Bitmap?

    /** Optional hook to release any cached resources. */
    fun release() {}
}
