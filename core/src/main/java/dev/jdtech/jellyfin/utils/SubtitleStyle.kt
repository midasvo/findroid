package dev.jdtech.jellyfin.utils

import android.graphics.Color
import android.graphics.Typeface
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.Constants
import timber.log.Timber

/**
 * Builds a [CaptionStyleCompat] from the user's saved subtitle preferences.
 *
 * Colors are parsed defensively — a malformed pref value falls back to the
 * library default for that channel rather than crashing the player.
 */
fun AppPreferences.buildCaptionStyle(): CaptionStyleCompat {
    val foreground =
        parseColorOr(getValue(subtitleForegroundColor), Color.WHITE)
    val background =
        parseColorOr(
            getValue(subtitleBackgroundColor),
            Color.argb(128, 0, 0, 0), // 50% black
        )
    val edge = parseColorOr(getValue(subtitleEdgeColor), Color.BLACK)
    val edgeType =
        when (getValue(subtitleEdgeType)) {
            Constants.SubtitleStyle.EDGE_NONE -> CaptionStyleCompat.EDGE_TYPE_NONE
            Constants.SubtitleStyle.EDGE_OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
            Constants.SubtitleStyle.EDGE_DROP_SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
            Constants.SubtitleStyle.EDGE_DEPRESSED -> CaptionStyleCompat.EDGE_TYPE_DEPRESSED
            Constants.SubtitleStyle.EDGE_RAISED -> CaptionStyleCompat.EDGE_TYPE_RAISED
            else -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
        }
    val typeface =
        when (getValue(subtitleFontFamily)) {
            Constants.SubtitleStyle.FONT_SERIF -> Typeface.SERIF
            Constants.SubtitleStyle.FONT_SANS_SERIF -> Typeface.SANS_SERIF
            Constants.SubtitleStyle.FONT_MONOSPACE -> Typeface.MONOSPACE
            Constants.SubtitleStyle.FONT_DEFAULT -> Typeface.DEFAULT
            else -> Typeface.DEFAULT
        }

    return CaptionStyleCompat(
        /* foregroundColor = */ foreground,
        /* backgroundColor = */ background,
        /* windowColor = */ Color.TRANSPARENT,
        /* edgeType = */ edgeType,
        /* edgeColor = */ edge,
        /* typeface = */ typeface,
    )
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
}

private fun parseColorOr(value: String?, fallback: Int): Int {
    if (value.isNullOrBlank()) return fallback
    return try {
        Color.parseColor(value)
    } catch (e: IllegalArgumentException) {
        Timber.w(e, "Invalid subtitle color value: %s", value)
        fallback
    }
}
