package dev.jdtech.jellyfin.utils

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.media3.common.Player
import androidx.media3.ui.TimeBar
import coil3.load
import coil3.request.crossfade
import coil3.request.transformations
import coil3.transform.RoundedCornersTransformation
import dev.jdtech.jellyfin.player.core.domain.models.Trickplay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

class PreviewScrubListener(
    private val scrubbingPreview: ImageView,
    private val timeBarView: View,
    private val player: Player,
) : TimeBar.OnScrubListener {
    var currentTrickplay: Trickplay? = null
    private val roundedCorners = RoundedCornersTransformation(10f)
    private var currentBitMap: Bitmap? = null

    // Owns async tile fetches when the loader path is active. Cancel-on-stop keeps
    // a slow network from posting a stale tile after the user lifts their finger.
    private val scope: CoroutineScope = MainScope()
    private var loaderJob: Job? = null
    private var lastLoadedTileKey: Int = Int.MIN_VALUE

    override fun onScrubStart(timeBar: TimeBar, position: Long) {
        Timber.d("Scrubbing started at $position")

        if (currentTrickplay == null) {
            return
        }

        scrubbingPreview.visibility = View.VISIBLE
        onScrubMove(timeBar, position)
    }

    override fun onScrubMove(timeBar: TimeBar, position: Long) {
        Timber.d("Scrubbing to $position")

        try {
            val trickplay = currentTrickplay ?: return
            positionPreview(position, trickplay)

            // Prefer the lazy loader when it's wired up (developer toggle path).
            val loader = trickplay.loader
            if (loader != null) {
                val tileKey = (position / trickplay.interval).toInt()
                if (tileKey == lastLoadedTileKey) {
                    return
                }
                lastLoadedTileKey = tileKey
                loaderJob?.cancel()
                loaderJob = scope.launch {
                    val image =
                        try {
                            loader.tileAt(position)
                        } catch (e: Exception) {
                            Timber.e(e, "Trickplay tile load failed")
                            null
                        }

                    if (image == null) {
                        // Keep the previous tile on screen; don't flicker to empty.
                        return@launch
                    }
                    if (currentBitMap != image) {
                        scrubbingPreview.load(image) {
                            coroutineContext(Dispatchers.Main.immediate)
                            crossfade(false)
                            transformations(roundedCorners)
                        }
                        currentBitMap = image
                    }
                }
                return
            }

            // Legacy eager path: indexed list of pre-decoded bitmaps.
            val image = trickplay.images[position.div(trickplay.interval).toInt()]
            if (currentBitMap != image) {
                scrubbingPreview.load(image) {
                    coroutineContext(Dispatchers.Main.immediate)
                    crossfade(false)
                    transformations(roundedCorners)
                }
                currentBitMap = image
            }
        } catch (e: Exception) {
            scrubbingPreview.visibility = View.GONE
            Timber.e(e)
        }
    }

    private fun positionPreview(position: Long, trickplay: Trickplay) {
        val parent = scrubbingPreview.parent as ViewGroup

        val offset = if (player.duration > 0) position.toFloat() / player.duration else 0f
        val minX = scrubbingPreview.left
        val maxX = parent.width - parent.paddingRight

        val startX =
            timeBarView.left + (timeBarView.right - timeBarView.left) * offset -
                scrubbingPreview.width / 2
        val endX = startX + scrubbingPreview.width

        val layoutX =
            when {
                startX >= minX && endX <= maxX -> startX
                startX < minX -> minX
                else -> maxX - scrubbingPreview.width
            }.toFloat()

        scrubbingPreview.x = layoutX
    }

    override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
        Timber.d("Scrubbing stopped at $position")

        loaderJob?.cancel()
        lastLoadedTileKey = Int.MIN_VALUE
        scrubbingPreview.visibility = View.GONE
    }

    fun dispose() {
        scope.cancel()
    }
}
