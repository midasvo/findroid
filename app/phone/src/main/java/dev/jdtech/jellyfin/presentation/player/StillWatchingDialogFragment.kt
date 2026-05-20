package dev.jdtech.jellyfin.presentation.player

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.jdtech.jellyfin.player.local.R
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel
import java.lang.IllegalStateException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Are you still watching?" dialog. Uses [MaterialAlertDialogBuilder] for visual consistency
 * with the existing player dialogs (track/speed selection).
 *
 * The dialog renders a ticking countdown sourced from
 * [PlayerViewModel.UiState.stillWatchingTimeoutSeconds]. The actual pause-and-stop logic
 * is owned by the ViewModel — once its timer elapses it clears `showStillWatching` and
 * the activity dismisses us. The dialog is non-cancelable for the back button /
 * outside-touch so the only paths out are the two explicit buttons or that timeout.
 */
class StillWatchingDialogFragment(private val viewModel: PlayerViewModel) : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        isCancelable = false
        val activity = activity ?: throw IllegalStateException("Activity cannot be null")
        val initialSeconds = viewModel.uiState.value.stillWatchingTimeoutSeconds
        return MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.still_watching_title)
            .setMessage(getString(R.string.still_watching_message, initialSeconds))
            .setPositiveButton(R.string.still_watching_continue) { dialog, _ ->
                viewModel.acknowledgeStillWatching()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.still_watching_pause) { dialog, _ ->
                viewModel.dismissStillWatching()
                dialog.dismiss()
            }
            .create()
    }

    override fun onStart() {
        super.onStart()
        // Tick the countdown on the dialog's message. We re-read the timeout once on start
        // (it's set during ViewModel init and doesn't change while the prompt is up) and
        // count down locally; the ViewModel's own timer is what actually fires the pause.
        val alertDialog = dialog as? AlertDialog ?: return
        val totalSeconds = viewModel.uiState.value.stillWatchingTimeoutSeconds
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                for (remaining in totalSeconds downTo 1) {
                    alertDialog.setMessage(
                        getString(R.string.still_watching_message, remaining),
                    )
                    delay(1_000L)
                }
            }
        }
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
