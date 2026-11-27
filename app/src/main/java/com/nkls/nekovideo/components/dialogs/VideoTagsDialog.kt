package com.nkls.nekovideo.components

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import com.nkls.nekovideo.R
import com.nkls.nekovideo.components.helpers.TagEntity
import kotlinx.coroutines.launch
import androidx.compose.runtime.CompositionLocalProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoTagsDialog(
    selectedVideoCount: Int,
    tags: List<TagEntity>,
    initialSelectedTagIds: Set<Long>,
    previewVideoTitle: String? = null,
    previewVideoUri: Uri? = null,
    onDismiss: () -> Unit,
    onManageTags: () -> Unit,
    onSave: suspend (Set<Long>) -> Result<Unit>
) {
    val coroutineScope = rememberCoroutineScope()
    val title = stringResource(R.string.video_tags_title)
    val selectedCountText = pluralStringResource(R.plurals.video_tags_selected_count, selectedVideoCount, selectedVideoCount)
    val manageTagsText = stringResource(R.string.settings_tags)
    val dialogTags = remember(tags) { mutableStateListOf<TagEntity>().apply { addAll(tags) } }
    var selectedTagIds by remember(initialSelectedTagIds) { mutableStateOf(initialSelectedTagIds) }
    var isSaving by remember { mutableStateOf(false) }
    var isPreviewVisible by remember(previewVideoUri) { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val canPreviewVideo = selectedVideoCount == 1 && previewVideoUri != null

    BottomAlignedResizableSheet(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = title,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
        ) {
            // Keep room for playback while allowing up to four complete tag rows.
            val tagsMaxHeight = if (isPreviewVisible) maxHeight * 0.30f else maxHeight
            val compactEmptyState = isPreviewVisible && maxHeight < 300.dp
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (!isPreviewVisible) {
                    Text(
                        text = selectedCountText,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                errorMessage?.let { message ->
                    Text(
                        text = message,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (canPreviewVideo && isPreviewVisible) {
                    BoxWithConstraints(
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                        contentAlignment = Alignment.Center
                    ) {
                        previewVideoUri?.let { uri ->
                            VideoTagsPreview(
                                modifier = Modifier.testTag("video_tags_preview"),
                                videoUri = uri,
                                maxCardWidth = maxWidth * 0.9f,
                                // The card adds 8.dp of padding on each edge of the video.
                                maxVideoHeight = (maxHeight - 16.dp).coerceAtLeast(0.dp),
                                onPreviewError = { isPreviewVisible = false }
                            )
                        }
                    }
                }

                if (canPreviewVideo) {
                    Row(
                        modifier = Modifier.fillMaxWidth().testTag("video_tags_preview_toggle"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = previewVideoTitle.orEmpty(),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        TextButton(
                            onClick = { isPreviewVisible = !isPreviewVisible },
                            enabled = !isSaving,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                imageVector = if (isPreviewVisible) Icons.Default.Close else Icons.Default.Visibility,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(if (isPreviewVisible) stringResource(R.string.close) else "Preview")
                        }
                    }
                }

                if (dialogTags.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("video_tags_empty")
                            .padding(vertical = if (compactEmptyState) 4.dp else 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.video_tags_empty),
                            style = if (compactEmptyState) MaterialTheme.typography.bodySmall
                                else MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (!compactEmptyState) {
                            Text(
                                text = stringResource(R.string.video_tags_empty_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    ScrollableVideoTags(
                        tags = dialogTags,
                        selectedTagIds = selectedTagIds,
                        enabled = !isSaving,
                        modifier = Modifier.heightIn(max = tagsMaxHeight),
                        onToggleTag = { tagId ->
                            selectedTagIds = if (tagId in selectedTagIds) selectedTagIds - tagId
                                else selectedTagIds + tagId
                        }
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = {
                    onDismiss()
                    onManageTags()
                },
                enabled = !isSaving,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(manageTagsText)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onDismiss,
                    enabled = !isSaving,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(stringResource(R.string.cancel))
                }

                Button(
                    onClick = {
                        isSaving = true
                        errorMessage = null
                        coroutineScope.launch {
                            val result = onSave(selectedTagIds)
                            result.onSuccess {
                                onDismiss()
                            }.onFailure { error ->
                                errorMessage = error.localizedMessage
                            }
                            isSaving = false
                        }
                    },
                    enabled = !isSaving,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(modifier = Modifier.padding(horizontal = 2.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.video_tags_save))
                    }
                }
            }
        }
    }
}

private enum class TagListSlot { RowSize, Content }

/** Measures the chip with the current font scale before choosing a whole number of visible rows. */
@Composable
private fun ScrollableVideoTags(
    tags: List<TagEntity>,
    selectedTagIds: Set<Long>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onToggleTag: (Long) -> Unit
) {
    val scrollState = rememberScrollState()
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        SubcomposeLayout(modifier.fillMaxWidth().testTag("video_tags_list")) { constraints ->
            val gap = 2.dp.roundToPx()
            val padding = 2.dp.roundToPx()
            val chip = subcompose(TagListSlot.RowSize) {
                // This measurement-only chip must not duplicate a tag in accessibility/testing.
                Box(Modifier.clearAndSetSemantics {}) {
                    VideoTagChip(tags.first(), false, enabled = false, onClick = {})
                }
            }.single().measure(constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity))
            val rows = ((constraints.maxHeight - padding + gap) / (chip.height + gap)).coerceIn(1, 4)
            val heightLimit = minOf(constraints.maxHeight, padding + chip.height * rows + gap * (rows - 1))
            val list = subcompose(TagListSlot.Content) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().verticalScroll(scrollState).padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    tags.forEach { tag ->
                        VideoTagChip(
                            tag, tag.id in selectedTagIds, enabled,
                            modifier = Modifier.testTag("video_tag_${tag.id}"),
                            onClick = { onToggleTag(tag.id) }
                        )
                    }
                }
            }.single().measure(constraints.copy(minHeight = 0, maxHeight = heightLimit))
            layout(list.width, list.height) { list.placeRelative(0, 0) }
        }
    }
}

@Composable
private fun VideoTagChip(
    tag: TagEntity,
    isSelected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    AssistChip(
        onClick = onClick,
        modifier = modifier.heightIn(min = 24.dp),
        enabled = enabled,
        label = { Text(tag.name, style = MaterialTheme.typography.labelSmall, maxLines = 1) },
        shape = RoundedCornerShape(7.dp),
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
            labelColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface
        ),
        border = null
    )
}
