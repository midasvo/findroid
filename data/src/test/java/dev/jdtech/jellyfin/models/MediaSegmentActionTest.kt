package dev.jdtech.jellyfin.models

import org.junit.Assert.assertEquals
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
}
