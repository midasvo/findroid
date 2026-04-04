package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadProgress
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadStatus
import dev.jdtech.jellyfin.core.presentation.dummy.dummyEpisode
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings

@Composable
fun EpisodeCard(
    episode: FindroidEpisode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    downloadProgress: DownloadProgress? = null,
    onDownloadClick: (() -> Unit)? = null,
    onDownloadedClick: (() -> Unit)? = null,
) {
    val backgroundColor = MaterialTheme.colorScheme.background
    val effectiveStatus = downloadProgress?.status ?: DownloadStatus.NONE

    Row(
        modifier =
            modifier
                .height(84.dp)
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            ItemPoster(
                item = episode,
                direction = Direction.HORIZONTAL,
                modifier = Modifier.clip(MaterialTheme.shapes.small),
            )
            // Played badge only on thumbnail
            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(MaterialTheme.spacings.small),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
            ) {
                if (episode.played) PlayedBadge()
            }
            // Progress bar at bottom of thumbnail during download
            if (
                effectiveStatus == DownloadStatus.DOWNLOADING ||
                effectiveStatus == DownloadStatus.PENDING
            ) {
                val animatedProgress by
                    animateFloatAsState(
                        targetValue = downloadProgress?.progress ?: 0f,
                        animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
                    )
                if (effectiveStatus == DownloadStatus.PENDING) {
                    LinearProgressIndicator(
                        modifier =
                            Modifier.align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .height(3.dp),
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier =
                            Modifier.align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .height(3.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(MaterialTheme.spacings.default / 2))
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            Column {
                Text(
                    text =
                        stringResource(
                            id = CoreR.string.episode_name,
                            episode.indexNumber,
                            episode.name,
                        ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = episode.overview,
                    modifier = Modifier.alpha(0.7f),
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Canvas(
                modifier =
                    Modifier.align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(MaterialTheme.spacings.default)
            ) {
                drawRect(
                    brush =
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, backgroundColor),
                            startY = 0f,
                        )
                )
            }
        }
        // Download action button on the right
        EpisodeDownloadButton(
            status = effectiveStatus,
            progress = downloadProgress?.progress ?: 0f,
            onDownloadClick = onDownloadClick,
            onDownloadedClick = onDownloadedClick,
        )
    }
}

@Composable
private fun EpisodeDownloadButton(
    status: DownloadStatus,
    progress: Float,
    onDownloadClick: (() -> Unit)?,
    onDownloadedClick: (() -> Unit)?,
) {
    when (status) {
        DownloadStatus.COMPLETED -> {
            // Downloaded — checkmark icon, tappable to manage/delete
            IconButton(
                onClick = { onDownloadedClick?.invoke() },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_check),
                    contentDescription = stringResource(CoreR.string.downloaded_indicator),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        DownloadStatus.DOWNLOADING -> {
            // Downloading — circular progress around download icon
            Box(
                modifier = Modifier.size(40.dp),
                contentAlignment = Alignment.Center,
            ) {
                val animatedProgress by
                    animateFloatAsState(
                        targetValue = progress,
                        animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
                    )
                CircularProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.dp,
                    strokeCap = StrokeCap.Round,
                )
                Icon(
                    painter = painterResource(CoreR.drawable.ic_download),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DownloadStatus.QUEUED,
        DownloadStatus.PENDING -> {
            // Pending — indeterminate circular
            Box(
                modifier = Modifier.size(40.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.dp,
                    strokeCap = StrokeCap.Round,
                )
                Icon(
                    painter = painterResource(CoreR.drawable.ic_download),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DownloadStatus.FAILED -> {
            // Failed — error icon, tappable to retry
            IconButton(
                onClick = { onDownloadClick?.invoke() },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_alert_circle),
                    contentDescription = stringResource(CoreR.string.download_failed),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        DownloadStatus.NONE -> {
            // Not downloaded — download icon, tappable to start
            if (onDownloadClick != null) {
                IconButton(
                    onClick = onDownloadClick,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_download),
                        contentDescription = stringResource(CoreR.string.download_button_description),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun EpisodeCardPreview() {
    FindroidTheme {
        EpisodeCard(episode = dummyEpisode, onClick = {}, onDownloadClick = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun EpisodeCardDownloadingPreview() {
    FindroidTheme {
        EpisodeCard(
            episode = dummyEpisode,
            onClick = {},
            downloadProgress = DownloadProgress(status = DownloadStatus.DOWNLOADING, progress = 0.4f),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun EpisodeCardDownloadedPreview() {
    FindroidTheme {
        EpisodeCard(
            episode = dummyEpisode,
            onClick = {},
            downloadProgress = DownloadProgress(status = DownloadStatus.COMPLETED, progress = 1f),
            onDownloadedClick = {},
        )
    }
}
