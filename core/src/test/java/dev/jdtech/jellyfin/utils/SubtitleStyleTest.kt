package dev.jdtech.jellyfin.utils

import androidx.media3.ui.CaptionStyleCompat
import dev.jdtech.jellyfin.settings.domain.Constants
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the pure-Kotlin pieces of [SubtitleStyle].
 *
 * The mapper deliberately avoids depending on `android.graphics.Color` (which needs the
 * Android framework) so these run as a plain JVM test without Robolectric.
 */
class SubtitleStyleTest {

    // --- parseColorOr -------------------------------------------------------

    @Test
    fun `parseColorOr accepts a six digit hex and assumes full opacity`() {
        // #RRGGBB → 0xFFRRGGBB
        assertEquals(0xFFFFFFFF.toInt(), parseColorOr("#FFFFFF", fallback = 0))
        assertEquals(0xFF000000.toInt(), parseColorOr("#000000", fallback = 0))
        assertEquals(0xFFFF0000.toInt(), parseColorOr("#FF0000", fallback = 0))
        assertEquals(0xFF00FF00.toInt(), parseColorOr("#00FF00", fallback = 0))
        assertEquals(0xFF0000FF.toInt(), parseColorOr("#0000FF", fallback = 0))
    }

    @Test
    fun `parseColorOr accepts an eight digit hex with explicit alpha`() {
        // #AARRGGBB stored verbatim
        assertEquals(0xFFFFFFFF.toInt(), parseColorOr("#FFFFFFFF", fallback = 0))
        assertEquals(0x80000000.toInt(), parseColorOr("#80000000", fallback = 0)) // 50% black
        assertEquals(0x00000000, parseColorOr("#00000000", fallback = 0xDEAD))
        assertEquals(0x40FF8800, parseColorOr("#40FF8800", fallback = 0))
    }

    @Test
    fun `parseColorOr is case insensitive for hex digits`() {
        assertEquals(parseColorOr("#abcdef", 0), parseColorOr("#ABCDEF", 0))
        assertEquals(parseColorOr("#80aabbcc", 0), parseColorOr("#80AABBCC", 0))
    }

    @Test
    fun `parseColorOr returns fallback for null and blank input`() {
        val fallback = 0xCAFEBABE.toInt()
        assertEquals(fallback, parseColorOr(null, fallback))
        assertEquals(fallback, parseColorOr("", fallback))
        assertEquals(fallback, parseColorOr("   ", fallback))
    }

    @Test
    fun `parseColorOr returns fallback when the hash prefix is missing`() {
        val fallback = 0x12345678
        assertEquals(fallback, parseColorOr("FFFFFF", fallback))
        assertEquals(fallback, parseColorOr("FFFFFFFF", fallback))
    }

    @Test
    fun `parseColorOr returns fallback for wrong length`() {
        val fallback = 0x12345678
        assertEquals(fallback, parseColorOr("#", fallback))
        assertEquals(fallback, parseColorOr("#FFF", fallback)) // shorthand not supported
        assertEquals(fallback, parseColorOr("#FFFFF", fallback))
        assertEquals(fallback, parseColorOr("#FFFFFFF", fallback))
        assertEquals(fallback, parseColorOr("#FFFFFFFFF", fallback))
    }

    @Test
    fun `parseColorOr returns fallback for non-hex characters`() {
        val fallback = 0x12345678
        assertEquals(fallback, parseColorOr("#GGGGGG", fallback))
        assertEquals(fallback, parseColorOr("#ZZZZZZZZ", fallback))
        assertEquals(fallback, parseColorOr("#  FFFF", fallback))
    }

    @Test
    fun `parseColorOr trims surrounding whitespace`() {
        assertEquals(0xFFFFFFFF.toInt(), parseColorOr("  #FFFFFF  ", fallback = 0))
        assertEquals(0x80000000.toInt(), parseColorOr("\t#80000000\n", fallback = 0))
    }

    // --- subtitleEdgeTypeFor -------------------------------------------------

    @Test
    fun `subtitleEdgeTypeFor round-trips every documented edge value`() {
        assertEquals(
            CaptionStyleCompat.EDGE_TYPE_NONE,
            subtitleEdgeTypeFor(Constants.SubtitleStyle.EDGE_NONE),
        )
        assertEquals(
            CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            subtitleEdgeTypeFor(Constants.SubtitleStyle.EDGE_OUTLINE),
        )
        assertEquals(
            CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
            subtitleEdgeTypeFor(Constants.SubtitleStyle.EDGE_DROP_SHADOW),
        )
        assertEquals(
            CaptionStyleCompat.EDGE_TYPE_DEPRESSED,
            subtitleEdgeTypeFor(Constants.SubtitleStyle.EDGE_DEPRESSED),
        )
        assertEquals(
            CaptionStyleCompat.EDGE_TYPE_RAISED,
            subtitleEdgeTypeFor(Constants.SubtitleStyle.EDGE_RAISED),
        )
    }

    @Test
    fun `subtitleEdgeTypeFor falls back to outline for unknown or null input`() {
        assertEquals(CaptionStyleCompat.EDGE_TYPE_OUTLINE, subtitleEdgeTypeFor(null))
        assertEquals(CaptionStyleCompat.EDGE_TYPE_OUTLINE, subtitleEdgeTypeFor(""))
        assertEquals(CaptionStyleCompat.EDGE_TYPE_OUTLINE, subtitleEdgeTypeFor("nope"))
        // String values are case-sensitive; an unexpected casing falls back.
        assertEquals(CaptionStyleCompat.EDGE_TYPE_OUTLINE, subtitleEdgeTypeFor("OUTLINE"))
    }
}
