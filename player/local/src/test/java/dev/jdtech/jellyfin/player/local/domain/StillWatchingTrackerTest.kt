package dev.jdtech.jellyfin.player.local.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [StillWatchingTracker] — the "Are you still watching?" threshold logic.
 *
 * Drives the wall clock manually so the test is deterministic.
 */
class StillWatchingTrackerTest {

    @Test
    fun `three auto-advances in a row trip the prompt`() {
        val tracker = StillWatchingTracker(autoAdvanceThreshold = 3, inactivityThresholdMs = OFF)
        tracker.reset(nowMs = 0L)

        // First two auto-advances don't trip — we want to be sure the threshold isn't off by one.
        assertFalse(tracker.onAutoAdvance(nowMs = 60_000L))
        assertFalse(tracker.onAutoAdvance(nowMs = 120_000L))
        // Third trips.
        assertTrue(tracker.onAutoAdvance(nowMs = 180_000L))
        assertEquals(3, tracker.currentAutoAdvanceCount)
    }

    @Test
    fun `a user interaction in the middle of a binge resets the counter`() {
        val tracker = StillWatchingTracker(autoAdvanceThreshold = 3, inactivityThresholdMs = OFF)
        tracker.reset(nowMs = 0L)

        tracker.onAutoAdvance(nowMs = 60_000L)
        tracker.onAutoAdvance(nowMs = 120_000L)
        // Viewer pressed pause / hit a button — they're still here.
        tracker.onUserInteraction(nowMs = 130_000L)
        // Next two auto-advances must not trip yet (counter restarted at zero).
        assertFalse(tracker.onAutoAdvance(nowMs = 190_000L))
        assertFalse(tracker.onAutoAdvance(nowMs = 250_000L))
        assertTrue(tracker.onAutoAdvance(nowMs = 310_000L))
    }

    @Test
    fun `inactivity past the time threshold trips even without auto-advance`() {
        val tracker =
            StillWatchingTracker(
                autoAdvanceThreshold = StillWatchingTracker.OFF,
                inactivityThresholdMs = 90 * 60_000L, // 90 min
            )
        tracker.reset(nowMs = 0L)

        // 89 min in, viewer is technically still on the hook.
        assertFalse(tracker.shouldPrompt(nowMs = 89 * 60_000L))
        // 90 min on the nose — trip.
        assertTrue(tracker.shouldPrompt(nowMs = 90 * 60_000L))
        // And anything beyond.
        assertTrue(tracker.shouldPrompt(nowMs = 120 * 60_000L))
    }

    @Test
    fun `interaction during a long binge defers the inactivity trip`() {
        val tracker =
            StillWatchingTracker(
                autoAdvanceThreshold = StillWatchingTracker.OFF,
                inactivityThresholdMs = 90 * 60_000L,
            )
        tracker.reset(nowMs = 0L)

        // 80 minutes in, viewer pauses.
        tracker.onUserInteraction(nowMs = 80 * 60_000L)
        // 80 + 89 = 169 still safe.
        assertFalse(tracker.shouldPrompt(nowMs = 169 * 60_000L))
        // 80 + 90 = 170 trips.
        assertTrue(tracker.shouldPrompt(nowMs = 170 * 60_000L))
    }

    @Test
    fun `both axes off means the prompt never fires`() {
        val tracker =
            StillWatchingTracker(
                autoAdvanceThreshold = StillWatchingTracker.OFF,
                inactivityThresholdMs = StillWatchingTracker.OFF_MS,
            )
        tracker.reset(nowMs = 0L)

        repeat(50) { tracker.onAutoAdvance(nowMs = it * 60_000L) }
        assertFalse(tracker.shouldPrompt(nowMs = Long.MAX_VALUE / 2))
    }

    @Test
    fun `only the auto-advance axis is enabled — wall time doesn't matter`() {
        val tracker = StillWatchingTracker(autoAdvanceThreshold = 2, inactivityThresholdMs = OFF)
        tracker.reset(nowMs = 0L)

        // A long wall-clock gap with no auto-advance never trips.
        assertFalse(tracker.shouldPrompt(nowMs = Long.MAX_VALUE / 2))

        assertFalse(tracker.onAutoAdvance(nowMs = 60_000L))
        assertTrue(tracker.onAutoAdvance(nowMs = 120_000L))
    }

    @Test
    fun `reset wipes counters from a prior session`() {
        val tracker = StillWatchingTracker(autoAdvanceThreshold = 2, inactivityThresholdMs = OFF)
        tracker.reset(nowMs = 0L)
        tracker.onAutoAdvance(nowMs = 60_000L)
        tracker.onAutoAdvance(nowMs = 120_000L)
        assertTrue(tracker.shouldPrompt(nowMs = 120_000L))

        tracker.reset(nowMs = 200_000L)
        assertEquals(0, tracker.currentAutoAdvanceCount)
        assertFalse(tracker.shouldPrompt(nowMs = 200_000L))
    }

    private companion object {
        private const val OFF: Long = StillWatchingTracker.OFF_MS
    }
}
