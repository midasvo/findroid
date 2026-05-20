package dev.jdtech.jellyfin.presentation.player

import android.app.Dialog
import android.os.Bundle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.jdtech.jellyfin.player.local.R
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel
import java.lang.IllegalStateException

/**
 * "Are you still watching?" dialog. Uses [MaterialAlertDialogBuilder] for visual consistency
 * with the existing player dialogs (track/speed selection).
 *
 * The timeout itself lives in the ViewModel — when this fragment is dismissed because the
 * ViewModel cleared `showStillWatching`, [dismissWasInternal] tells us not to fire a second
 * user-cancel signal. The dialog is non-cancelable for the back button / outside-touch so
 * the only paths out are the two explicit buttons or the ViewModel-driven timeout.
 */
class StillWatchingDialogFragment(private val viewModel: PlayerViewModel) : DialogFragment() {

    /** Set to true when the ViewModel dismisses us; suppresses the onCancel path. */
    var dismissWasInternal: Boolean = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        isCancelable = false
        return activity?.let { activity ->
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.still_watching_title)
                .setMessage(R.string.still_watching_message)
                .setPositiveButton(R.string.still_watching_continue) { dialog, _ ->
                    viewModel.acknowledgeStillWatching()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.still_watching_pause) { dialog, _ ->
                    viewModel.dismissStillWatching()
                    dialog.dismiss()
                }
                .create()
        } ?: throw IllegalStateException("Activity cannot be null")
    }

    override fun onDestroy() {
        super.onDestroy()
        // Fix for hiding the system bars on API < 30
        activity?.window?.let {
            WindowCompat.getInsetsController(it, it.decorView).apply {
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}
