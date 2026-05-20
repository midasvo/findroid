package dev.jdtech.jellyfin.player.local.domain

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import dev.jdtech.jellyfin.player.core.domain.models.TrickplayTileLoader
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.util.UUID
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Max number of per-sheet load mutexes to keep around. See [TrickplayLoader.loadMutexes]. */
private const val MUTEX_CACHE_SIZE = 8

/**
 * Lazily decodes trickplay sprite-sheets on demand and crops out the right cell for the
 * requested playback position. Sprite-sheets are decoded once and held in an LRU cache,
 * keyed by sheet index, so seeks within a sheet are essentially free and seeks across
 * sheets only re-decode once.
 *
 * This is the experimental, developer-toggle path. The default trickplay path eagerly
 * decodes every tile in [dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel],
 * which is fine for short items but wasteful for long ones.
 *
 * Cache size is intentionally small (a handful of sheets): a single sheet at 320x180 with
 * 10x10 tiles is ~1.7MB decoded, and the user rarely scrubs across more than a few sheets
 * in quick succession.
 */
class TrickplayLoader(
    private val repository: JellyfinRepository,
    private val itemId: UUID,
    private val width: Int,
    private val height: Int,
    private val tileWidth: Int,
    private val tileHeight: Int,
    private val thumbnailCount: Int,
    private val interval: Int,
    /** Max number of decoded sprite-sheets to keep in memory. */
    cacheSize: Int = DEFAULT_CACHE_SIZE,
) : TrickplayTileLoader {

    private val tilesPerSheet = max(1, tileWidth * tileHeight)
    private val maxSheetIndex =
        if (thumbnailCount <= 0) 0 else (thumbnailCount - 1) / tilesPerSheet

    // We deliberately do not call recycle() in entryRemoved. tileAt holds a reference to
    // the evicted bitmap and reads from it on Dispatchers.Default after the LruCache lookup;
    // recycling on the main thread mid-read would crash with
    // "Canvas: trying to use a recycled bitmap". The cache is small (a handful of sheets,
    // a few MB) and Bitmap pixels are GC-managed, so we let the GC reclaim evicted entries.
    private val sheetCache: LruCache<Int, Bitmap> = LruCache(cacheSize)

    // Per-sheet load mutex prevents two concurrent scrub frames from kicking off two
    // identical network fetches for the same sprite-sheet. Keyed by sheet index.
    //
    // Bounded by [MUTEX_CACHE_SIZE] so rapid scrubbing across a long movie can't grow this
    // map without bound. LruCache is already thread-safe so no extra synchronisation is
    // needed; the per-mutex critical section in [loadSheet] handles the dedupe.
    private val loadMutexes: LruCache<Int, Mutex> = LruCache(MUTEX_CACHE_SIZE)

    override suspend fun tileAt(positionMs: Long): Bitmap? {
        if (thumbnailCount <= 0 || interval <= 0) return null

        val globalTileIndex = (positionMs / interval).toInt().coerceIn(0, thumbnailCount - 1)
        val sheetIndex = globalTileIndex / tilesPerSheet
        val localIndex = globalTileIndex % tilesPerSheet
        val tileX = localIndex % tileWidth
        val tileY = localIndex / tileWidth

        val sheet = loadSheet(sheetIndex) ?: return null

        // Defensive bounds check — a corrupt/short sheet shouldn't crash the player.
        val offsetX = tileX * width
        val offsetY = tileY * height
        if (offsetX + width > sheet.width || offsetY + height > sheet.height) {
            Timber.w(
                "Trickplay tile out of bounds: sheet=$sheetIndex tile=$localIndex " +
                    "offset=($offsetX,$offsetY) sheet=${sheet.width}x${sheet.height}",
            )
            return null
        }

        return withContext(Dispatchers.Default) {
            // createBitmap copies the pixels into a fresh bitmap. The cropped tile is
            // independent of the cached sheet, so it survives sheet eviction.
            Bitmap.createBitmap(sheet, offsetX, offsetY, width, height)
        }
    }

    private suspend fun loadSheet(sheetIndex: Int): Bitmap? {
        sheetCache.get(sheetIndex)?.takeIf { !it.isRecycled }?.let { return it }
        if (sheetIndex < 0 || sheetIndex > maxSheetIndex) return null

        // LruCache.get/put are synchronized internally; the "check then insert" race
        // here at worst creates an extra unused Mutex that the LruCache will evict — the
        // per-mutex critical section below still dedupes the actual network fetch.
        val mutex =
            loadMutexes.get(sheetIndex) ?: Mutex().also { loadMutexes.put(sheetIndex, it) }

        return mutex.withLock {
            // Double-check after acquiring the per-sheet mutex: another coroutine may
            // have populated the cache while we were waiting.
            sheetCache.get(sheetIndex)?.takeIf { !it.isRecycled }?.let {
                return@withLock it
            }

            val bitmap =
                withContext(Dispatchers.IO) {
                    try {
                        val bytes = repository.getTrickplayData(itemId, width, sheetIndex)
                        if (bytes == null) {
                            null
                        } else {
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to load trickplay sheet $sheetIndex")
                        null
                    }
                }

            if (bitmap != null) {
                sheetCache.put(sheetIndex, bitmap)
            }
            bitmap
        }
    }

    override fun release() {
        sheetCache.evictAll()
    }

    companion object {
        const val DEFAULT_CACHE_SIZE = 3
    }
}
