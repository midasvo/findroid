package dev.jdtech.jellyfin.core.presentation.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [nextDownloadSpeed] — the fix for the queue speed/ETA flashing.
 */
class DownloadSpeedTest {

    @Test
    fun `advancing bytes compute a real speed`() {
        // 2 MB more, 1000 ms later -> 2 MB/s
        val result =
            nextDownloadSpeed(
                prevSample = 0L to 1_000L,
                currentBytes = 2_000_000L,
                nowMs = 2_000L,
                previousSpeed = 0L,
            )
        assertEquals(2_000_000L, result.bytesPerSecond)
        assertTrue(result.advanced)
    }

    @Test
    fun `a flat poll keeps the previous speed and does not advance`() {
        // THE flashing case: byte count unchanged since the last sample must NOT report 0.
        val result =
            nextDownloadSpeed(
                prevSample = 5_000_000L to 1_000L,
                currentBytes = 5_000_000L,
                nowMs = 2_000L,
                previousSpeed = 8_000_000L,
            )
        assertEquals(8_000_000L, result.bytesPerSecond)
        assertFalse(result.advanced)
    }

    @Test
    fun `the first sample reports no speed yet but starts the clock`() {
        val result =
            nextDownloadSpeed(
                prevSample = null,
                currentBytes = 1_000L,
                nowMs = 1_000L,
                previousSpeed = 0L,
            )
        assertEquals(0L, result.bytesPerSecond)
        assertTrue(result.advanced)
    }

    @Test
    fun `an unknown byte count keeps the previous speed and does not advance`() {
        val result =
            nextDownloadSpeed(
                prevSample = 100L to 1_000L,
                currentBytes = -1L,
                nowMs = 2_000L,
                previousSpeed = 4_000_000L,
            )
        assertEquals(4_000_000L, result.bytesPerSecond)
        assertFalse(result.advanced)
    }
}
