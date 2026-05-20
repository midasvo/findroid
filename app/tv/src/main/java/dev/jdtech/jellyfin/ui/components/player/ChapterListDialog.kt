package dev.jdtech.jellyfin.ui.components.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.player.core.domain.models.PlayerChapter
import dev.jdtech.jellyfin.player.core.domain.models.formatStartTimestamp
import dev.jdtech.jellyfin.player.local.R
import dev.jdtech.jellyfin.presentation.theme.spacings

/**
 * TV chapter list overlay. Mirrors the phone [dev.jdtech.jellyfin.presentation.player.ChapterListDialogFragment]
 * but composes natively so D-pad navigation falls out of [androidx.tv.material3.Card] focus
 * behaviour. The first item receives focus on open.
 */
@Composable
fun ChapterListDialog(
    chapters: List<PlayerChapter>,
    chapterImageUrl: (Int) -> String?,
    onChapterSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f),
            shape = RoundedCornerShape(12.dp),
            colors =
                androidx.tv.material3.SurfaceDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
        ) {
            Column(modifier = Modifier.padding(MaterialTheme.spacings.large)) {
                Text(
                    text = stringResource(id = R.string.select_chapter),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(MaterialTheme.spacings.medium))

                val listState = rememberLazyListState()
                val firstItemFocusRequester = remember { FocusRequester() }
                LaunchedEffect(Unit) {
                    if (chapters.isNotEmpty()) {
                        firstItemFocusRequester.requestFocus()
                    }
                }

                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                ) {
                    itemsIndexed(chapters) { index, chapter ->
                        ChapterRow(
                            index = index,
                            chapter = chapter,
                            imageUrl = chapterImageUrl(index),
                            modifier =
                                if (index == 0) Modifier.focusRequester(firstItemFocusRequester)
                                else Modifier,
                            onClick = {
                                onChapterSelected(index)
                                onDismiss()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterRow(
    index: Int,
    chapter: PlayerChapter,
    imageUrl: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedContainerColor = MaterialTheme.colorScheme.primary,
            ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(8.dp)),
    ) {
        Row(
            modifier = Modifier.padding(MaterialTheme.spacings.small).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier.width(160.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (imageUrl != null) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                    )
                } else {
                    // Placeholder block keeps row heights uniform regardless of whether the
                    // server extracted a chapter thumbnail.
                    Spacer(
                        modifier =
                            Modifier.fillMaxWidth().fillMaxHeight()
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.surface)
                    )
                }
            }
            Spacer(modifier = Modifier.width(MaterialTheme.spacings.medium))
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text =
                        chapter.name?.takeIf { it.isNotBlank() }
                            ?: stringResource(id = R.string.chapter_number, index + 1),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    maxLines = 2,
                )
                Text(
                    text = chapter.formatStartTimestamp(),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
        }
    }
}

