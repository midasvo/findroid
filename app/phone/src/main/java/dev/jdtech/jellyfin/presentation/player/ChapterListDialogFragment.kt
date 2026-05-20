package dev.jdtech.jellyfin.presentation.player

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade
import coil3.request.transformations
import coil3.transform.RoundedCornersTransformation
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.jdtech.jellyfin.R
import dev.jdtech.jellyfin.player.core.domain.models.PlayerChapter
import dev.jdtech.jellyfin.player.core.domain.models.formatStartTimestamp
import dev.jdtech.jellyfin.player.local.R as PlayerR
import dev.jdtech.jellyfin.player.local.presentation.PlayerAction
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel

/**
 * Phone chapter list overlay. Shows each chapter with its server-extracted thumbnail (when
 * available), title, and timestamp. Tapping a row seeks to the chapter via [PlayerAction.JumpToChapter].
 *
 * Implemented as a [DialogFragment] (rather than a Compose bottom sheet) for consistency with the
 * existing [TrackSelectionDialogFragment] and [SpeedSelectionDialogFragment] in this module, which
 * are wired through the same `supportFragmentManager` mechanism.
 */
class ChapterListDialogFragment(
    private val viewModel: PlayerViewModel,
) : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val activity = requireActivity()
        val chapters = viewModel.uiState.value.currentChapters

        val recycler =
            RecyclerView(activity).apply {
                layoutManager = LinearLayoutManager(activity)
                adapter =
                    ChapterAdapter(chapters) { index ->
                        viewModel.onAction(PlayerAction.JumpToChapter(index))
                        dismiss()
                    }
            }

        return MaterialAlertDialogBuilder(activity)
            .setTitle(getString(PlayerR.string.select_chapter))
            .setView(recycler)
            .setNegativeButton(android.R.string.cancel) { dialog, _ -> dialog.dismiss() }
            .create()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Fix for hiding the system bars on API < 30 — same dance as TrackSelectionDialogFragment.
        activity?.window?.let {
            WindowCompat.getInsetsController(it, it.decorView).apply {
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    private inner class ChapterAdapter(
        private val chapters: List<PlayerChapter>,
        private val onClick: (Int) -> Unit,
    ) : RecyclerView.Adapter<ChapterViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChapterViewHolder {
            val view =
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_chapter, parent, false)
            return ChapterViewHolder(view)
        }

        override fun onBindViewHolder(holder: ChapterViewHolder, position: Int) {
            val chapter = chapters[position]
            holder.bind(
                index = position,
                title =
                    chapter.name?.takeIf { it.isNotBlank() }
                        ?: holder.itemView.context.getString(
                            PlayerR.string.chapter_number,
                            position + 1,
                        ),
                timestamp = chapter.formatStartTimestamp(),
                imageUrl = viewModel.chapterImageUrl(position),
                onClick = onClick,
            )
        }

        override fun getItemCount(): Int = chapters.size
    }

    private class ChapterViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbnail: ImageView = itemView.findViewById(R.id.chapter_thumbnail)
        private val title: TextView = itemView.findViewById(R.id.chapter_title)
        private val timestamp: TextView = itemView.findViewById(R.id.chapter_timestamp)
        private val rounded = RoundedCornersTransformation(8f)

        fun bind(
            index: Int,
            title: String,
            timestamp: String,
            imageUrl: String?,
            onClick: (Int) -> Unit,
        ) {
            this.title.text = title
            this.timestamp.text = timestamp
            if (imageUrl != null) {
                thumbnail.visibility = View.VISIBLE
                thumbnail.load(imageUrl) {
                    crossfade(true)
                    transformations(rounded)
                }
            } else {
                // Hide rather than placeholder so rows without thumbnails align flush left.
                thumbnail.visibility = View.GONE
            }
            itemView.setOnClickListener { onClick(index) }
        }
    }
}
