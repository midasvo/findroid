package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme

@Composable
fun DownloadedBadge(modifier: Modifier = Modifier) {
    BaseBadge(modifier = modifier) {
        Icon(
            painter = painterResource(CoreR.drawable.ic_check),
            contentDescription = stringResource(CoreR.string.downloaded_indicator),
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(16.dp).align(Alignment.Center),
        )
    }
}

@Composable
fun DownloadFailedBadge(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .defaultMinSize(minWidth = 24.dp, minHeight = 24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.error),
    ) {
        Icon(
            painter = painterResource(CoreR.drawable.ic_alert_circle),
            contentDescription = stringResource(CoreR.string.download_failed_indicator),
            tint = MaterialTheme.colorScheme.onError,
            modifier = Modifier.size(16.dp).align(Alignment.Center),
        )
    }
}

@Composable
fun DownloadingBadge(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .defaultMinSize(minWidth = 24.dp, minHeight = 24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.tertiary),
    ) {
        Icon(
            painter = painterResource(CoreR.drawable.ic_download),
            contentDescription = stringResource(CoreR.string.downloading_indicator),
            tint = MaterialTheme.colorScheme.onTertiary,
            modifier = Modifier.size(16.dp).align(Alignment.Center),
        )
    }
}

@Composable
@Preview
private fun DownloadedBadgePreview() {
    FindroidTheme { DownloadedBadge() }
}

@Composable
@Preview
private fun DownloadFailedBadgePreview() {
    FindroidTheme { DownloadFailedBadge() }
}

@Composable
@Preview
private fun DownloadingBadgePreview() {
    FindroidTheme { DownloadingBadge() }
}
