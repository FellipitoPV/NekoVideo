package com.nkls.nekovideo.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nkls.nekovideo.R
import com.nkls.nekovideo.components.helpers.TagEntity

data class ShuffleTagFilter(
    val includeTagIds: Set<Long>,
    val excludeTagIds: Set<Long>
)

private enum class TagFilterMode {
    NEUTRAL,
    INCLUDE,
    EXCLUDE
}

@Composable
fun ShuffleTagsDialog(
    tags: List<TagEntity>,
    onDismiss: () -> Unit,
    onConfirm: (ShuffleTagFilter) -> Unit
) {
    val includeLabel = stringResource(R.string.shuffle_tags_mode_include)
    val excludeLabel = stringResource(R.string.shuffle_tags_mode_exclude)
    val title = stringResource(R.string.shuffle_tags_title)
    val description = stringResource(R.string.shuffle_tags_description)
    val emptyText = stringResource(R.string.shuffle_tags_empty)
    val cancelText = stringResource(R.string.cancel)
    val confirmText = stringResource(R.string.shuffle_tags_confirm)
    val bottomSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val configuration = LocalConfiguration.current
    val maxContentHeight = (configuration.screenHeightDp * 0.52f).dp
    val tagModes = remember(tags) {
        mutableStateMapOf<Long, TagFilterMode>().apply {
            tags.forEach { put(it.id, TagFilterMode.NEUTRAL) }
        }
    }

    fun cycleMode(tagId: Long) {
        tagModes[tagId] = when (tagModes[tagId] ?: TagFilterMode.NEUTRAL) {
            TagFilterMode.NEUTRAL -> TagFilterMode.INCLUDE
            TagFilterMode.INCLUDE -> TagFilterMode.EXCLUDE
            TagFilterMode.EXCLUDE -> TagFilterMode.NEUTRAL
        }
    }

    val hasActiveFilters = tagModes.values.any { it != TagFilterMode.NEUTRAL }
    val includeCount = tagModes.count { it.value == TagFilterMode.INCLUDE }
    val excludeCount = tagModes.count { it.value == TagFilterMode.EXCLUDE }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = bottomSheetState,
        dragHandle = {
            Surface(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                shape = MaterialTheme.shapes.extraSmall
            ) {
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (hasActiveFilters) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Text(
                            text = "+$includeCount / -$excludeCount",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            HorizontalDivider()

            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (tags.isEmpty()) {
                Text(
                    text = emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 20.dp)
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxContentHeight)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        tags.forEach { tag ->
                            val mode = tagModes[tag.id] ?: TagFilterMode.NEUTRAL
                            val (bgColor, textColor) = when (mode) {
                                TagFilterMode.NEUTRAL -> Pair(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                                    MaterialTheme.colorScheme.onSurface
                                )
                                TagFilterMode.INCLUDE -> Pair(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                TagFilterMode.EXCLUDE -> Pair(
                                    MaterialTheme.colorScheme.errorContainer,
                                    MaterialTheme.colorScheme.onErrorContainer
                                )
                            }

                            AssistChip(
                                onClick = { cycleMode(tag.id) },
                                label = {
                                    Text(
                                        text = when (mode) {
                                            TagFilterMode.NEUTRAL -> tag.name
                                            TagFilterMode.INCLUDE -> "+ ${tag.name}"
                                            TagFilterMode.EXCLUDE -> "- ${tag.name}"
                                        },
                                        fontWeight = if (mode == TagFilterMode.NEUTRAL) FontWeight.Normal else FontWeight.Medium
                                    )
                                },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = bgColor,
                                    labelColor = textColor
                                )
                            )
                        }
                    }
                }
            }

            if (tags.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = includeLabel,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Text(
                            text = excludeLabel,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                TextButton(onClick = onDismiss) {
                    Text(cancelText)
                }
                Button(
                    onClick = {
                        val includeTagIds = tagModes.filterValues { it == TagFilterMode.INCLUDE }.keys
                        val excludeTagIds = tagModes.filterValues { it == TagFilterMode.EXCLUDE }.keys
                        onConfirm(
                            ShuffleTagFilter(
                                includeTagIds = includeTagIds,
                                excludeTagIds = excludeTagIds
                            )
                        )
                    },
                    enabled = tags.isNotEmpty() && hasActiveFilters,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Text(confirmText)
                }
            }
        }
    }
}
