package dev.jdtech.jellyfin.models

/**
 * What the player should do when playback enters a media segment of a given
 * [FindroidSegmentType].
 *
 * - [SKIP]   — auto-seek past the segment immediately.
 * - [ASK]    — show a dismissable skip button for a few seconds so the user can
 *              opt in.
 * - [IGNORE] — do nothing; play through the segment.
 *
 * The preference is stored as a string in [android.content.SharedPreferences] so
 * use [name] / [valueOf] for round-tripping. [fromPreferenceValue] tolerates
 * unknown / null inputs and falls back to a caller-supplied default.
 */
enum class MediaSegmentAction {
    SKIP,
    ASK,
    IGNORE,
    ;

    companion object {
        /**
         * Parse [value] into a [MediaSegmentAction], returning [default] when the
         * input is null/blank or does not match a known action. Tolerant on
         * purpose so a corrupted preference cannot crash the player.
         */
        fun fromPreferenceValue(value: String?, default: MediaSegmentAction): MediaSegmentAction {
            if (value.isNullOrBlank()) return default
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: default
        }
    }
}
