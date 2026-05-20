package dev.jdtech.jellyfin.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaSegmentActionTest {
    @Test
    fun `valid enum names parse round-trip`() {
        MediaSegmentAction.entries.forEach { action ->
            assertEquals(
                action,
                MediaSegmentAction.fromPreferenceValue(
                    value = action.name,
                    default = MediaSegmentAction.IGNORE,
                ),
            )
        }
    }

    @Test
    fun `lowercase preference values still parse`() {
        assertEquals(
            MediaSegmentAction.ASK,
            MediaSegmentAction.fromPreferenceValue("ask", default = MediaSegmentAction.IGNORE),
        )
    }

    @Test
    fun `null falls back to default`() {
        assertEquals(
            MediaSegmentAction.SKIP,
            MediaSegmentAction.fromPreferenceValue(null, default = MediaSegmentAction.SKIP),
        )
    }

    @Test
    fun `blank string falls back to default`() {
        assertEquals(
            MediaSegmentAction.ASK,
            MediaSegmentAction.fromPreferenceValue("   ", default = MediaSegmentAction.ASK),
        )
    }

    @Test
    fun `unknown value falls back to default`() {
        assertEquals(
            MediaSegmentAction.IGNORE,
            MediaSegmentAction.fromPreferenceValue("MAYBE", default = MediaSegmentAction.IGNORE),
        )
    }

    // --- fromPreferenceValueOrNull -------------------------------------------------

    @Test
    fun `nullable parser returns null for absent value`() {
        assertNull(MediaSegmentAction.fromPreferenceValueOrNull(null))
        assertNull(MediaSegmentAction.fromPreferenceValueOrNull(""))
        assertNull(MediaSegmentAction.fromPreferenceValueOrNull("   "))
    }

    @Test
    fun `nullable parser returns null for unknown value`() {
        assertNull(MediaSegmentAction.fromPreferenceValueOrNull("MAYBE"))
    }

    @Test
    fun `nullable parser round-trips valid enum names`() {
        MediaSegmentAction.entries.forEach { action ->
            assertEquals(action, MediaSegmentAction.fromPreferenceValueOrNull(action.name))
        }
    }

    // --- resolve() ----------------------------------------------------------------

    @Test
    fun `resolve prefers explicit per-type action over legacy toggles`() {
        val resolved = MediaSegmentAction.resolve(
            segmentType = FindroidSegmentType.INTRO,
            perTypeActions = mapOf(FindroidSegmentType.INTRO to MediaSegmentAction.IGNORE),
            // Legacy says SKIP — should not win because the user explicitly chose IGNORE.
            legacyAutoSkipEnabled = true,
            legacyAutoSkipTypes = setOf("INTRO"),
            legacySkipButtonEnabled = true,
            legacySkipButtonTypes = setOf("INTRO"),
        )
        assertEquals(MediaSegmentAction.IGNORE, resolved)
    }

    /**
     * Regression test for PR #20 review: a user who set
     * `playerMediaSegmentsAutoSkip = true` before this PR existed should still
     * get [MediaSegmentAction.SKIP] behaviour after upgrading, even though
     * they have never touched the new per-type preferences.
     *
     * The per-type map carries null entries for the types the user has not
     * configured, and resolve() must fall through to the legacy auto-skip
     * toggle instead of stopping at the per-type lookup.
     */
    @Test
    fun `resolve falls through to legacy auto-skip when per-type pref is null`() {
        val resolved = MediaSegmentAction.resolve(
            segmentType = FindroidSegmentType.INTRO,
            perTypeActions = mapOf(
                FindroidSegmentType.INTRO to null,
                FindroidSegmentType.OUTRO to null,
            ),
            legacyAutoSkipEnabled = true,
            legacyAutoSkipTypes = setOf("INTRO", "OUTRO"),
            legacySkipButtonEnabled = false,
            legacySkipButtonTypes = emptySet(),
        )
        assertEquals(MediaSegmentAction.SKIP, resolved)
    }

    @Test
    fun `resolve falls through to legacy skip-button when per-type pref is null`() {
        val resolved = MediaSegmentAction.resolve(
            segmentType = FindroidSegmentType.OUTRO,
            perTypeActions = mapOf(FindroidSegmentType.OUTRO to null),
            legacyAutoSkipEnabled = false,
            legacyAutoSkipTypes = emptySet(),
            legacySkipButtonEnabled = true,
            legacySkipButtonTypes = setOf("INTRO", "OUTRO"),
        )
        assertEquals(MediaSegmentAction.ASK, resolved)
    }

    @Test
    fun `resolve prefers legacy auto-skip over legacy skip-button when both apply`() {
        val resolved = MediaSegmentAction.resolve(
            segmentType = FindroidSegmentType.INTRO,
            perTypeActions = emptyMap(),
            legacyAutoSkipEnabled = true,
            legacyAutoSkipTypes = setOf("INTRO"),
            legacySkipButtonEnabled = true,
            legacySkipButtonTypes = setOf("INTRO"),
        )
        assertEquals(MediaSegmentAction.SKIP, resolved)
    }

    @Test
    fun `resolve returns IGNORE when nothing is configured`() {
        val resolved = MediaSegmentAction.resolve(
            segmentType = FindroidSegmentType.RECAP,
            perTypeActions = emptyMap(),
            legacyAutoSkipEnabled = false,
            legacyAutoSkipTypes = emptySet(),
            legacySkipButtonEnabled = false,
            legacySkipButtonTypes = emptySet(),
        )
        assertEquals(MediaSegmentAction.IGNORE, resolved)
    }

    @Test
    fun `resolve ignores legacy when toggle is disabled even with matching type`() {
        val resolved = MediaSegmentAction.resolve(
            segmentType = FindroidSegmentType.INTRO,
            perTypeActions = emptyMap(),
            // Type set contains INTRO but the master toggle is off — legacy is inert.
            legacyAutoSkipEnabled = false,
            legacyAutoSkipTypes = setOf("INTRO"),
            legacySkipButtonEnabled = false,
            legacySkipButtonTypes = setOf("INTRO"),
        )
        assertEquals(MediaSegmentAction.IGNORE, resolved)
    }

    @Test
    fun `resolve ignores legacy when type set does not match segment`() {
        val resolved = MediaSegmentAction.resolve(
            segmentType = FindroidSegmentType.PREVIEW,
            perTypeActions = emptyMap(),
            legacyAutoSkipEnabled = true,
            legacyAutoSkipTypes = setOf("INTRO", "OUTRO"),
            legacySkipButtonEnabled = true,
            legacySkipButtonTypes = setOf("INTRO", "OUTRO"),
        )
        assertEquals(MediaSegmentAction.IGNORE, resolved)
    }
}
