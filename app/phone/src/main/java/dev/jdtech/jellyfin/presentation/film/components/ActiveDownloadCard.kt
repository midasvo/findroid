package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadProgress
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadStatus
import dev.jdtech.jellyfin.core.presentation.dummy.dummyEpisode
import dev.jdtech.jellyfin.film.presentation.downloads.ActiveDownload
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import kotlin.math.roundToInt

@Composable
fun ActiveDownloadCard(
    activeDownload: ActiveDownload,
    onCancelClick: () -> Unit,
    onDismissClick: () -> Unit,
    onRetryClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val progress = activeDownload.progress
    val isCompleted = progress.status == DownloadStatus.COMPLETED
    val animatedProgress by
        animateFloatAsState(
            targetValue = if (isCompleted) 1f else progress.progress,
            animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
        )

    val statusText =
        when (progress.status) {
            DownloadStatus.QUEUED -> stringResource(CoreR.string.download_queued)
            DownloadStatus.PENDING -> stringResource(CoreR.string.download_pending)
            DownloadStatus.DOWNLOADING -> stringResource(CoreR.string.download_downloading)
            DownloadStatus.FAILED -> stringResource(CoreR.string.download_failed)
            DownloadStatus.COMPLETED -> stringResource(CoreR.string.download_completed)
            else -> ""
        }

    val progressColor =
        when (progress.status) {
            DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
            DownloadStatus.COMPLETED -> MaterialTheme.colorScheme.tertiary
            else -> ProgressIndicatorDefaults.linearColor
        }

    val item = activeDownload.item
    val itemName =
        when (item) {
            is FindroidEpisode -> {
                val episodeLabel =
                    stringResource(
                        CoreR.string.episode_name_extended,
                        item.parentIndexNumber,
                        item.indexNumber,
                        item.name,
                    )
                if (item.seriesName.isNotEmpty()) "${item.seriesName} - $episodeLabel"
                else episodeLabel
            }
            else -> item.name
        }

    OutlinedCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacings.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = itemName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (progress.status == DownloadStatus.DOWNLOADING) {
                        Text(
                            text = "${animatedProgress.times(100).roundToInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                when (progress.status) {
                    DownloadStatus.QUEUED -> {
                        LinearProgressIndicator(
                            progress = { 0f },
                            modifier = Modifier.fillMaxWidth().height(3.dp),
                        )
                    }
                    DownloadStatus.PENDING -> {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(3.dp),
                        )
                    }
                    else -> {
                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier.fillMaxWidth().height(3.dp),
                            color = progressColor,
                        )
                    }
                }
            }
            Spacer(Modifier.width(MaterialTheme.spacings.small))
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                when (progress.status) {
                    DownloadStatus.COMPLETED -> {
                        FilledTonalIconButton(onClick = onDismissClick) {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_check),
                                contentDescription = null,
                            )
                        }
                    }
                    DownloadStatus.FAILED -> {
                        FilledTonalIconButton(onClick = onRetryClick) {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_download),
                                contentDescription = stringResource(CoreR.string.retry_download),
                            )
                        }
                    }
                    else -> {
                        FilledTonalIconButton(onClick = onCancelClick) {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_x),
                                contentDescription = null,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun ActiveDownloadCardPreview() {
    FindroidTheme {
        ActiveDownloadCard(
            activeDownload =
                ActiveDownload(
                    item = dummyEpisode,
                    progress = DownloadProgress(status = DownloadStatus.DOWNLOADING, progress = 0.45f),
                    downloadId = 1L,
                ),
            onCancelClick = {},
            onDismissClick = {},
        )
    }
}
