package dev.jdtech.jellyfin.presentation.settings

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.recalculateWindowInsets
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.text.Cue
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.presentation.settings.components.SettingsGroupCard
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.utils.plus
import dev.jdtech.jellyfin.settings.R as SettingsR
import dev.jdtech.jellyfin.settings.domain.Constants
import dev.jdtech.jellyfin.settings.presentation.settings.SettingsAction
import dev.jdtech.jellyfin.settings.presentation.subtitle.SubtitleStyleAction
import dev.jdtech.jellyfin.settings.presentation.subtitle.SubtitleStyleEvent
import dev.jdtech.jellyfin.settings.presentation.subtitle.SubtitleStyleState
import dev.jdtech.jellyfin.settings.presentation.subtitle.SubtitleStyleViewModel
import dev.jdtech.jellyfin.utils.ObserveAsEvents
import timber.log.Timber

@Composable
fun SubtitleStyleScreen(
    navigateBack: () -> Unit,
    viewModel: SubtitleStyleViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is SubtitleStyleEvent.LaunchIntent -> {
                try {
                    context.startActivity(event.intent)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to launch system caption settings")
                }
            }
        }
    }

    SubtitleStyleScreenLayout(
        state = state,
        onAction = { action ->
            when (action) {
                is SubtitleStyleAction.OnBackClick -> navigateBack()
                else -> viewModel.onAction(action)
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubtitleStyleScreenLayout(
    state: SubtitleStyleState,
    onAction: (SubtitleStyleAction) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val contentPadding = PaddingValues(all = MaterialTheme.spacings.default)

    Scaffold(
        modifier =
            Modifier.fillMaxSize()
                .recalculateWindowInsets()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(SettingsR.string.subtitle_style)) },
                navigationIcon = {
                    IconButton(onClick = { onAction(SubtitleStyleAction.OnBackClick) }) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_arrow_left),
                            contentDescription = null,
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = contentPadding + innerPadding,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                SubtitleStylePreview(
                    state = state,
                    modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp),
                )
            }

            items(state.preferenceGroups) { group ->
                SettingsGroupCard(
                    group = group,
                    onAction = { action ->
                        if (action is SettingsAction.OnUpdate) {
                            onAction(SubtitleStyleAction.OnUpdate(action.preference))
                        }
                    },
                    modifier = Modifier.widthIn(max = 640.dp),
                )
            }

            item {
                TextButton(
                    onClick = { onAction(SubtitleStyleAction.OnOpenSystemCaptionSettings) },
                    modifier = Modifier.widthIn(max = 640.dp),
                ) {
                    Text(stringResource(SettingsR.string.subtitle_system_caption_settings))
                }
            }
        }
    }
}

@Composable
private fun SubtitleStylePreview(
    state: SubtitleStyleState,
    modifier: Modifier = Modifier,
) {
    val sampleText = stringResource(SettingsR.string.subtitle_style_preview_text)

    Box(
        modifier =
            modifier
                .aspectRatio(16f / 5f)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    // Dark gradient that mimics a bright-then-dark frame, so the user can judge
                    // contrast against both extremes.
                    Brush.horizontalGradient(
                        listOf(
                            Color(0xFF1E1E1E),
                            Color(0xFF5C4A2E),
                            Color(0xFFE2C792),
                        )
                    )
                ),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
            factory = { context -> SubtitleView(context) },
            update = { view ->
                view.setStyle(state.toCaptionStyle())
                view.setFractionalTextSize(
                    SubtitleView.DEFAULT_TEXT_SIZE_FRACTION *
                        (state.fontScale.coerceIn(
                            Constants.SubtitleStyle.FONT_SCALE_MIN,
                            Constants.SubtitleStyle.FONT_SCALE_MAX,
                        ) / 100f),
                )
                view.setCues(listOf(Cue.Builder().setText(sampleText).build()))
            },
        )
    }
}

private fun SubtitleStyleState.toCaptionStyle(): CaptionStyleCompat {
    val foreground = parseColorSafe(foregroundColor, AndroidColor.WHITE)
    val background = parseColorSafe(backgroundColor, AndroidColor.argb(128, 0, 0, 0))
    val edge = parseColorSafe(edgeColor, AndroidColor.BLACK)
    val edgeTypeConst =
        when (edgeType) {
            Constants.SubtitleStyle.EDGE_NONE -> CaptionStyleCompat.EDGE_TYPE_NONE
            Constants.SubtitleStyle.EDGE_OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
            Constants.SubtitleStyle.EDGE_DROP_SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
            Constants.SubtitleStyle.EDGE_DEPRESSED -> CaptionStyleCompat.EDGE_TYPE_DEPRESSED
            Constants.SubtitleStyle.EDGE_RAISED -> CaptionStyleCompat.EDGE_TYPE_RAISED
            else -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
        }
    val typeface =
        when (fontFamily) {
            Constants.SubtitleStyle.FONT_SERIF -> android.graphics.Typeface.SERIF
            Constants.SubtitleStyle.FONT_SANS_SERIF -> android.graphics.Typeface.SANS_SERIF
            Constants.SubtitleStyle.FONT_MONOSPACE -> android.graphics.Typeface.MONOSPACE
            else -> android.graphics.Typeface.DEFAULT
        }
    return CaptionStyleCompat(
        foreground,
        background,
        AndroidColor.TRANSPARENT,
        edgeTypeConst,
        edge,
        typeface,
    )
}

private fun parseColorSafe(value: String?, fallback: Int): Int {
    if (value.isNullOrBlank()) return fallback
    return try {
        AndroidColor.parseColor(value)
    } catch (e: IllegalArgumentException) {
        fallback
    }
}
