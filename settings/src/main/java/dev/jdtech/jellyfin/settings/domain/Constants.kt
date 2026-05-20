package dev.jdtech.jellyfin.settings.domain

object Constants {
    // Player - Media Segments
    object PlayerMediaSegmentsAutoSkip {
        const val ALWAYS = "always"
        const val PIP = "pip"
    }

    // Player - Subtitle styling
    // Defaults match the platform "white-on-translucent-black" closed-caption look —
    // matches CaptionStyleCompat.DEFAULT but with an outlined edge for readability over
    // bright scenes. Player wiring parses the color strings with Color.parseColor and
    // maps edge / font keys to CaptionStyleCompat / Typeface constants.
    object SubtitleStyle {
        const val DEFAULT_FG_COLOR = "#FFFFFFFF"
        const val DEFAULT_BG_COLOR = "#80000000" // 50% black
        const val DEFAULT_EDGE_COLOR = "#FF000000"

        // Edge types (mapped 1:1 onto CaptionStyleCompat.EDGE_TYPE_*).
        const val EDGE_NONE = "none"
        const val EDGE_OUTLINE = "outline"
        const val EDGE_DROP_SHADOW = "drop_shadow"
        const val EDGE_DEPRESSED = "depressed"
        const val EDGE_RAISED = "raised"

        // Font families. Resolved via android.graphics.Typeface at the player.
        const val FONT_DEFAULT = "default"
        const val FONT_SERIF = "serif"
        const val FONT_SANS_SERIF = "sans_serif"
        const val FONT_MONOSPACE = "monospace"

        // Slider range (percent). Stored as Int so it fits the existing PreferenceIntInput.
        const val FONT_SCALE_MIN = 50
        const val FONT_SCALE_MAX = 200
        const val FONT_SCALE_DEFAULT = 100
    }

    // Network
    const val NETWORK_DEFAULT_REQUEST_TIMEOUT = 30_000L
    const val NETWORK_DEFAULT_CONNECT_TIMEOUT = 6_000L
    const val NETWORK_DEFAULT_SOCKET_TIMEOUT = 10_000L
}
