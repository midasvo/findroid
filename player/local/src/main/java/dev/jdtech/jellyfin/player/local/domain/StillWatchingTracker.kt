package dev.jdtech.jellyfin.player.local.domain

/**
 * Decides when an "Are you still watching?" prompt should fire.
 *
 * Two independent triggers:
 * - [autoAdvanceThreshold]: N consecutive episodes auto-played without a user touch
 * - [inactivityThresholdMs]: M milliseconds of wall-clock time since the last user touch
 *
 * Either threshold trips the prompt. A value of [OFF] for either disables that axis.
 *
 * Pure: no Android, no media3, no coroutines. Drives off whatever wall-clock the caller
 * passes in so tests can advance it manually.
 */
class StillWatchingTracker(
    val autoAdvanceThreshold: Int,
    val inactivityThresholdMs: Long,
) {
    private var autoAdvanceCount: Int = 0
    private var lastInteractionAtMs: Long = 0L

    /** Reset internal counters. Call when a fresh playback session starts. */
    fun reset(nowMs: Long) {
        autoAdvanceCount = 0
        lastInteractionAtMs = nowMs
    }

    /** Reset counters to "the user is here" — call on every touch, seek, pause, etc. */
    fun onUserInteraction(nowMs: Long) {
        autoAdvanceCount = 0
        lastInteractionAtMs = nowMs
    }

    /**
     * Call when the player is about to auto-advance to the next item. Returns true if the
     * prompt should fire *instead* of advancing. The caller must hold the auto-advance off
     * until the user confirms.
     */
    fun onAutoAdvance(nowMs: Long): Boolean {
        autoAdvanceCount += 1
        return shouldPrompt(nowMs)
    }

    /** True if either threshold has been crossed. */
    fun shouldPrompt(nowMs: Long): Boolean {
        val autoAdvanceTripped =
            autoAdvanceThreshold != OFF && autoAdvanceCount >= autoAdvanceThreshold
        val inactivityTripped =
            inactivityThresholdMs != OFF_MS &&
                (nowMs - lastInteractionAtMs) >= inactivityThresholdMs
        return autoAdvanceTripped || inactivityTripped
    }

    val currentAutoAdvanceCount: Int
        get() = autoAdvanceCount

    companion object {
        /** Sentinel value for "off" on the auto-advance axis. */
        const val OFF: Int = 0

        /** Sentinel value for "off" on the inactivity axis. */
        const val OFF_MS: Long = 0L
    }
}
