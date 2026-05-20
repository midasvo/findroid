package dev.jdtech.jellyfin.player.local.presentation

/**
 * User intents for the [PlayerViewModel]. Following the rest of the app's MVI pattern, screens
 * (phone and TV) call [PlayerViewModel.onAction] instead of poking at the player directly.
 */
sealed interface PlayerAction {
    /**
     * Seek the player to the start of the chapter at [index] in the current item's chapter list.
     * No-op if [index] is out of bounds.
     */
    data class JumpToChapter(val index: Int) : PlayerAction
}
