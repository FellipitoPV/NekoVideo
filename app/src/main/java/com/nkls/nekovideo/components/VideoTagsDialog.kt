package com.nkls.nekovideo.components

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nkls.nekovideo.R
import com.nkls.nekovideo.components.helpers.TagEntity
import kotlinx.coroutines.launch

@Composable
fun VideoTagsDialog(
    selectedVideoCount: Int,
    tags: List<TagEntity>,
    initialSelectedTagIds: Set<Long>,
    onDismiss: () -> Unit,
    onCreateTag: suspend (String) -> Result<TagEntity>,
    onSave: suspend (Set<Long>) -> Result<Unit>
) {
    val coroutineScope = rememberCoroutineScope()
    val emptyNameMessage = stringResource(R.string.video_tags_name_empty)
    val duplicateNameMessage = stringResource(R.string.video_tags_name_exists)
    val dialogTags = remember(tags) { mutableStateListOf<TagEntity>().apply { addAll(tags) } }
    val selectedTagIds = remember(initialSelectedTagIds) { mutableStateListOf<Long>().apply { addAll(initialSelectedTagIds) } }
    var newTagName by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var isCreating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = { if (!isSaving && !isCreating) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !isSaving && !isCreating,
            dismissOnClickOutside = !isSaving && !isCreating
        )
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
                    text = stringResource(R.string.video_tags_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = pluralStringResource(R.plurals.video_tags_selected_count, selectedVideoCount, selectedVideoCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = stringResource(R.string.video_tags_choose),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newTagName,
                        onValueChange = {
                            newTagName = it
                            errorMessage = null
                        },
                        label = { Text(stringResource(R.string.video_tags_new_label)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        enabled = !isCreating && !isSaving,
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        )
                    )

                    Button(
                        onClick = {
                            val trimmedName = newTagName.trim()
                            if (trimmedName.isEmpty()) {
                                errorMessage = emptyNameMessage
                                return@Button
                            }

                            isCreating = true
                            errorMessage = null
                            coroutineScope.launch {
                                val result = onCreateTag(trimmedName)
                                result.onSuccess { createdTag ->
                                    if (dialogTags.none { it.id == createdTag.id }) {
                                        dialogTags.add(createdTag)
                                        val sortedTags = dialogTags.sortedBy { it.name.lowercase() }
                                        dialogTags.clear()
                                        dialogTags.addAll(sortedTags)
                                    }
                                    if (!selectedTagIds.contains(createdTag.id)) {
                                        selectedTagIds.add(createdTag.id)
                                    }
                                    newTagName = ""
                                }.onFailure { error ->
                                    errorMessage = when (error.message) {
                                        "empty" -> emptyNameMessage
                                        "exists" -> duplicateNameMessage
                                        else -> error.localizedMessage ?: duplicateNameMessage
                                    }
                                }
                                isCreating = false
                            }
                        },
                        enabled = !isCreating && !isSaving,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        if (isCreating) {
                            CircularProgressIndicator(modifier = Modifier.padding(horizontal = 2.dp), strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(R.string.video_tags_create))
                        }
                    }
                }

                errorMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (dialogTags.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.video_tags_empty),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.video_tags_empty_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        dialogTags.forEach { tag ->
                            val isSelected = selectedTagIds.contains(tag.id)
                            val containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)
                            val labelColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                            AssistChip(
                                onClick = {
                                    if (isSelected) {
                                        selectedTagIds.remove(tag.id)
                                    } else {
                                        selectedTagIds.add(tag.id)
                                    }
                                },
                                enabled = !isSaving && !isCreating,
                                label = { Text(tag.name) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = containerColor,
                                    labelColor = labelColor
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
                        enabled = !isSaving && !isCreating,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                    Button(
                        onClick = {
                            isSaving = true
                            errorMessage = null
                            coroutineScope.launch {
                                val result = onSave(selectedTagIds.toSet())
                                result.onSuccess {
                                    onDismiss()
                                }.onFailure { error ->
                                    errorMessage = error.localizedMessage
                                }
                                isSaving = false
                            }
                        },
                        enabled = !isSaving && !isCreating,
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
}
