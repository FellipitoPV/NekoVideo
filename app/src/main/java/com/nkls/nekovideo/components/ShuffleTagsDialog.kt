package com.nkls.nekovideo.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
    val neutralLabel = stringResource(R.string.shuffle_tags_mode_neutral)
    val includeLabel = stringResource(R.string.shuffle_tags_mode_include)
    val excludeLabel = stringResource(R.string.shuffle_tags_mode_exclude)
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

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.shuffle_tags_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = stringResource(R.string.shuffle_tags_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (tags.isEmpty()) {
                    Text(
                        text = stringResource(R.string.shuffle_tags_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 20.dp)
                    )
                } else {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        tags.forEach { tag ->
                            val mode = tagModes[tag.id] ?: TagFilterMode.NEUTRAL
                            val (label, bgColor, textColor) = when (mode) {
                                TagFilterMode.NEUTRAL -> Triple(
                                    neutralLabel,
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                                    MaterialTheme.colorScheme.onSurface
                                )
                                TagFilterMode.INCLUDE -> Triple(
                                    includeLabel,
                                    MaterialTheme.colorScheme.primaryContainer,
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                TagFilterMode.EXCLUDE -> Triple(
                                    excludeLabel,
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    TextButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(stringResource(R.string.cancel))
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
                        enabled = tags.isNotEmpty() && tagModes.values.any { it != TagFilterMode.NEUTRAL },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Text(stringResource(R.string.shuffle_tags_confirm))
                    }
                }
            }
        }
    }
}
