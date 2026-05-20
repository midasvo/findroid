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
 * unknown / null inputs and falls back to a caller-supplied default,
 * [fromPreferenceValueOrNull] tolerates them by returning null instead.
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

        /**
         * Like [fromPreferenceValue] but returns null when the input is absent
         * or unrecognised. Used by the per-type preferences so a missing value
         * can fall through to the legacy global toggles instead of being
         * silently coerced to a new default — which would otherwise reset
         * upgrading users' behaviour.
         */
        fun fromPreferenceValueOrNull(value: String?): MediaSegmentAction? {
            if (value.isNullOrBlank()) return null
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
        }

        /**
         * Resolve the configured [MediaSegmentAction] for [segmentType] using
         * the following priority chain:
         *
         *  1. An explicit per-type preference in [perTypeActions] (non-null entry).
         *  2. The legacy global auto-skip toggle when [legacyAutoSkipEnabled] is
         *     true and [legacyAutoSkipTypes] contains the type — yields [SKIP].
         *  3. The legacy global skip-button toggle when [legacySkipButtonEnabled]
         *     is true and [legacySkipButtonTypes] contains the type — yields
         *     [ASK].
         *  4. [IGNORE] as the safe default.
         *
         * Centralised so the resolution can be unit-tested independently of the
         * `PlayerViewModel` (which carries Android / ExoPlayer dependencies).
         */
        fun resolve(
            segmentType: FindroidSegmentType,
            perTypeActions: Map<FindroidSegmentType, MediaSegmentAction?>,
            legacyAutoSkipEnabled: Boolean,
            legacyAutoSkipTypes: Set<String>,
            legacySkipButtonEnabled: Boolean,
            legacySkipButtonTypes: Set<String>,
        ): MediaSegmentAction {
            perTypeActions[segmentType]?.let { return it }

            val typeKey = segmentType.toString()
            return when {
                legacyAutoSkipEnabled && legacyAutoSkipTypes.contains(typeKey) -> SKIP
                legacySkipButtonEnabled && legacySkipButtonTypes.contains(typeKey) -> ASK
                else -> IGNORE
            }
        }
    }
}
