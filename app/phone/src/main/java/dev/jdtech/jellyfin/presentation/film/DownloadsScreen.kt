package dev.jdtech.jellyfin.presentation.film

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.recalculateWindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadProgress
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadStatus
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovies
import dev.jdtech.jellyfin.film.presentation.downloads.ActiveDownload
import dev.jdtech.jellyfin.film.presentation.downloads.DownloadsState
import dev.jdtech.jellyfin.film.presentation.downloads.DownloadsViewModel
import dev.jdtech.jellyfin.models.CollectionSection
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.presentation.film.components.ActiveDownloadCard
import dev.jdtech.jellyfin.presentation.film.components.Direction
import dev.jdtech.jellyfin.presentation.film.components.ItemCard
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings

private enum class DownloadsTab { LIBRARY, QUEUE }

@Composable
fun DownloadsScreen(
    onItemClick: (item: FindroidItem) -> Unit,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Reload completed downloads each time the screen becomes visible, so edits
    // made on show/season/movie screens (deletes) are reflected when we return.
    val lifecycleOwner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.loadItems()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DownloadsScreenLayout(
        state = state,
        onItemClick = onItemClick,
        onCancelDownload = { viewModel.cancelDownload(it) },
        onDismissDownload = { viewModel.dismissCompletedDownload(it) },
        onRetryDownload = { viewModel.retryDownload(it) },
        onClearCompleted = { viewModel.clearCompleted() },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadsScreenLayout(
    state: DownloadsState,
    onItemClick: (FindroidItem) -> Unit,
    onCancelDownload: (ActiveDownload) -> Unit,
    onDismissDownload: (ActiveDownload) -> Unit,
    onRetryDownload: (ActiveDownload) -> Unit,
    onClearCompleted: () -> Unit,
) {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var selectedTab by rememberSaveable { mutableIntStateOf(DownloadsTab.LIBRARY.ordinal) }

    Scaffold(
        modifier =
            Modifier.fillMaxSize()
                .recalculateWindowInsets()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(CoreR.string.title_download)) },
                windowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
                scrollBehavior = scrollBehavior,
            )
        },
        contentWindowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            // Segmented button row — show the active/pending count on the
            // Queue tab so the user can see at a glance that downloads are
            // running without switching tabs.
            val activeCount =
                state.queueItems.count {
                    it.progress.status == DownloadStatus.QUEUED ||
                        it.progress.status == DownloadStatus.PENDING ||
                        it.progress.status == DownloadStatus.DOWNLOADING ||
                        it.progress.status == DownloadStatus.PAUSED
                }
            val queueLabel = stringResource(CoreR.string.download_queue).let { base ->
                if (activeCount > 0) "$base ($activeCount)" else base
            }
            val tabLabels = listOf(
                stringResource(CoreR.string.downloads_tab_library),
                queueLabel,
            )
            SingleChoiceSegmentedButtonRow(
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(
                            horizontal = MaterialTheme.spacings.default,
                            vertical = MaterialTheme.spacings.small,
                        ),
            ) {
                tabLabels.forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = index == selectedTab,
                        onClick = { selectedTab = index },
                        shape =
                            SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = tabLabels.size,
                            ),
                        colors =
                            SegmentedButtonDefaults.colors(
                                inactiveContainerColor = Color.Transparent,
                            ),
                        label = { Text(label) },
                    )
                }
            }

            when (DownloadsTab.entries.getOrNull(selectedTab) ?: DownloadsTab.LIBRARY) {
                DownloadsTab.LIBRARY -> DownloadsTabContent(
                    state = state,
                    onItemClick = onItemClick,
                    context = context,
                )
                DownloadsTab.QUEUE -> QueueTabContent(
                    state = state,
                    onCancelDownload = onCancelDownload,
                    onDismissDownload = onDismissDownload,
                    onRetryDownload = onRetryDownload,
                    onClearCompleted = onClearCompleted,
                )
            }
        }
    }
}

@Composable
private fun DownloadsTabContent(
    state: DownloadsState,
    onItemClick: (FindroidItem) -> Unit,
    context: android.content.Context,
) {
    val hasContent = state.sections.isNotEmpty()

    if (!hasContent && !state.isLoading) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(CoreR.string.no_downloads),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(MaterialTheme.spacings.small))
                Text(
                    text = stringResource(CoreR.string.no_downloads_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (hasContent) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(all = MaterialTheme.spacings.default),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.default),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.default),
        ) {
            for (section in state.sections) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = section.name.asString(),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                gridItems(items = section.items, key = { it.id }) { item ->
                    Column {
                        ItemCard(
                            item = item,
                            direction =
                                if (item is FindroidEpisode) Direction.HORIZONTAL
                                else Direction.VERTICAL,
                            onClick = { onItemClick(item) },
                        )
                        state.itemSizes[item.id]?.let { sizeBytes ->
                            Text(
                                text = Formatter.formatShortFileSize(context, sizeBytes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // Storage info footer
            if (state.storageUsedBytes > 0 || state.storageFreeBytes > 0) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Spacer(Modifier.height(MaterialTheme.spacings.small))
                    val locationName =
                        if (state.storageIsExternal) {
                            stringResource(CoreR.string.external)
                        } else {
                            stringResource(CoreR.string.internal)
                        }
                    Text(
                        text =
                            stringResource(
                                CoreR.string.storage_usage_location,
                                locationName,
                                Formatter.formatFileSize(context, state.storageUsedBytes),
                                Formatter.formatFileSize(context, state.storageFreeBytes),
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueTabContent(
    state: DownloadsState,
    onCancelDownload: (ActiveDownload) -> Unit,
    onDismissDownload: (ActiveDownload) -> Unit,
    onRetryDownload: (ActiveDownload) -> Unit,
    onClearCompleted: () -> Unit,
) {
    val allItems = state.queueItems

    if (allItems.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(CoreR.string.no_queue_items),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(MaterialTheme.spacings.small))
                Text(
                    text = stringResource(CoreR.string.no_queue_items_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(all = MaterialTheme.spacings.default),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.default),
        ) {
            if (state.hasCompleted) {
                item {
                    androidx.compose.material3.TextButton(
                        onClick = onClearCompleted,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(CoreR.string.clear_completed))
                    }
                }
            }
            items(
                items = allItems,
                key = { it.item.id },
            ) { activeDownload ->
                ActiveDownloadCard(
                    activeDownload = activeDownload,
                    onCancelClick = { onCancelDownload(activeDownload) },
                    onDismissClick = { onDismissDownload(activeDownload) },
                    onRetryClick = { onRetryDownload(activeDownload) },
                )
            }
        }
    }
}

@PreviewScreenSizes
@Composable
private fun DownloadsScreenLayoutPreview() {
    FindroidTheme {
        DownloadsScreenLayout(
            state =
                DownloadsState(
                    sections =
                        listOf(
                            CollectionSection(
                                id = 0,
                                name = UiText.StringResource(CoreR.string.movies_label),
                                items = dummyMovies,
                            )
                        ),
                    storageUsedBytes = 1_500_000_000L,
                    storageFreeBytes = 10_000_000_000L,
                    storageIsExternal = true,
                ),
            onItemClick = {},
            onCancelDownload = {},
            onDismissDownload = {},
            onRetryDownload = {},
            onClearCompleted = {},
        )
    }
}

@PreviewScreenSizes
@Composable
private fun DownloadsScreenLayoutEmptyPreview() {
    FindroidTheme {
        DownloadsScreenLayout(
            state = DownloadsState(),
            onItemClick = {},
            onCancelDownload = {},
            onDismissDownload = {},
            onRetryDownload = {},
            onClearCompleted = {},
        )
    }
}
