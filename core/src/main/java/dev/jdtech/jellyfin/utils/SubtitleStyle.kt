package dev.jdtech.jellyfin.utils

import android.graphics.Typeface
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.Constants
import timber.log.Timber

/**
 * Default ARGB fallbacks. Kept here so the pure mapper does not need to know the platform
 * Color constants, which keeps it usable from unit tests that run without the Android framework.
 */
private const val DEFAULT_FOREGROUND_ARGB: Int = 0xFFFFFFFF.toInt()
private const val DEFAULT_BACKGROUND_ARGB: Int = 0x80000000.toInt() // 50% black
private const val DEFAULT_EDGE_ARGB: Int = 0xFF000000.toInt()
private const val TRANSPARENT_ARGB: Int = 0x00000000

/**
 * Builds a [CaptionStyleCompat] from the user's saved subtitle preferences.
 *
 * Reads the prefs and delegates to [buildSubtitleCaptionStyle], the pure mapper used by both
 * the player and the live preview in the styling settings screen.
 */
fun AppPreferences.buildCaptionStyle(): CaptionStyleCompat =
    buildSubtitleCaptionStyle(
        foregroundColorHex = getValue(subtitleForegroundColor),
        backgroundColorHex = getValue(subtitleBackgroundColor),
        edgeColorHex = getValue(subtitleEdgeColor),
        edgeType = getValue(subtitleEdgeType),
        fontFamily = getValue(subtitleFontFamily),
    )

/**
 * Pure mapper from primitive subtitle-style values to a [CaptionStyleCompat].
 *
 * Lives in [core] so both the live preview and the player wiring share the exact same
 * mapping logic. Takes primitives only — by contract it does not depend on [AppPreferences]
 * or any UI state — which keeps it testable from plain JVM unit tests.
 *
 * Colors are parsed defensively: a malformed value falls back to the library default for that
 * channel rather than crashing the player.
 */
fun buildSubtitleCaptionStyle(
    foregroundColorHex: String?,
    backgroundColorHex: String?,
    edgeColorHex: String?,
    edgeType: String?,
    fontFamily: String?,
): CaptionStyleCompat {
    val foreground = parseColorOr(foregroundColorHex, DEFAULT_FOREGROUND_ARGB)
    val background = parseColorOr(backgroundColorHex, DEFAULT_BACKGROUND_ARGB)
    val edge = parseColorOr(edgeColorHex, DEFAULT_EDGE_ARGB)

    return CaptionStyleCompat(
        /* foregroundColor = */ foreground,
        /* backgroundColor = */ background,
        /* windowColor = */ TRANSPARENT_ARGB,
        /* edgeType = */ subtitleEdgeTypeFor(edgeType),
        /* edgeColor = */ edge,
        /* typeface = */ subtitleTypefaceFor(fontFamily),
    )
}

/** Maps the stored edge-type string to a [CaptionStyleCompat] EDGE_TYPE constant. */
fun subtitleEdgeTypeFor(value: String?): Int =
    when (value) {
        Constants.SubtitleStyle.EDGE_NONE -> CaptionStyleCompat.EDGE_TYPE_NONE
        Constants.SubtitleStyle.EDGE_OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
        Constants.SubtitleStyle.EDGE_DROP_SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
        Constants.SubtitleStyle.EDGE_DEPRESSED -> CaptionStyleCompat.EDGE_TYPE_DEPRESSED
        Constants.SubtitleStyle.EDGE_RAISED -> CaptionStyleCompat.EDGE_TYPE_RAISED
        else -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
    }

/** Maps the stored font-family string to a [Typeface]. */
fun subtitleTypefaceFor(value: String?): Typeface =
    when (value) {
        Constants.SubtitleStyle.FONT_SERIF -> Typeface.SERIF
        Constants.SubtitleStyle.FONT_SANS_SERIF -> Typeface.SANS_SERIF
        Constants.SubtitleStyle.FONT_MONOSPACE -> Typeface.MONOSPACE
        Constants.SubtitleStyle.FONT_DEFAULT -> Typeface.DEFAULT
        else -> Typeface.DEFAULT
    }

/** Returns the user's font scale as a fraction (e.g. 1.25 for 125%). Clamped to [0.5, 2.0]. */
fun AppPreferences.subtitleFontScaleFraction(): Float {
    val percent = getValue(subtitleFontScale)
    return percent
        .coerceIn(Constants.SubtitleStyle.FONT_SCALE_MIN, Constants.SubtitleStyle.FONT_SCALE_MAX)
        .toFloat() / 100f
}

/** Applies the current subtitle preferences to [subtitleView]. */
fun SubtitleView.applySubtitleStyle(appPreferences: AppPreferences) {
    setStyle(appPreferences.buildCaptionStyle())
    setFractionalTextSize(
        SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * appPreferences.subtitleFontScaleFraction(),
    )
    // Honour user-applied insets only; we deliberately do not call setApplyEmbeddedStyles(false)
    // because that would also suppress SRT positioning (e.g. {\an1}..{\an9}) emitted by the
    // SubRip parser. ExoPlayer's SubRip extractor parses those tags and emits Cue positions —
    // SubtitleView respects the cue's anchored gravity by default.
    // TODO: expose an "Override embedded styles" toggle so users with ASS/SSA content that
    //  carries hardcoded colors can override them in favour of their saved style.
}

/**
 * Pure hex-color parser. Accepts `#RRGGBB` (alpha defaults to 0xFF) and `#AARRGGBB`. Returns
 * [fallback] for null / blank / malformed input.
 *
 * We do not call `android.graphics.Color.parseColor` here because we want the mapper to be
 * unit-testable on the JVM without Robolectric. The pref UI only ever stores `#RRGGBB` or
 * `#AARRGGBB` values (see `res/values/string_arrays.xml`), so the named-color support that
 * Android's parser offers is irrelevant.
 */
internal fun parseColorOr(value: String?, fallback: Int): Int {
    if (value.isNullOrBlank()) return fallback
    val trimmed = value.trim()
    if (!trimmed.startsWith('#')) {
        Timber.w("Invalid subtitle color value (missing '#'): %s", value)
        return fallback
    }
    val hex = trimmed.substring(1)
    if (hex.length != 6 && hex.length != 8) {
        Timber.w("Invalid subtitle color value (length %d): %s", hex.length, value)
        return fallback
    }
    val parsed = hex.toLongOrNull(16)
    if (parsed == null) {
        Timber.w("Invalid subtitle color value (not hex): %s", value)
        return fallback
    }
    // #RRGGBB → assume fully opaque.
    val argb = if (hex.length == 6) parsed or 0xFF000000L else parsed
    return argb.toInt()
}

