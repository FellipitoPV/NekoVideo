package com.nkls.nekovideo.components.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.Tracks

private data class TrackOption(
    val groupIndex: Int,
    val trackIndex: Int,
    val title: String
)

@Composable
fun TrackSelectionDialog(
    availableSubtitles: List<Tracks.Group>,
    availableAudioTracks: List<Tracks.Group>,
    selectedSubtitleTrack: Int?,
    selectedAudioTrack: Int?,
    onSubtitleSelected: (groupIndex: Int, trackIndex: Int) -> Unit,
    onSubtitlesDisabled: () -> Unit,
    onAudioSelected: (groupIndex: Int, trackIndex: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    val subtitleOptions = buildList {
        availableSubtitles.forEachIndexed { groupIndex, group ->
            for (trackIndex in 0 until group.length) {
                add(
                    TrackOption(
                        groupIndex = groupIndex,
                        trackIndex = trackIndex,
                        title = getSubtitleDisplayName(group, trackIndex)
                    )
                )
            }
        }
    }

    val audioOptions = buildList {
        availableAudioTracks.forEachIndexed { groupIndex, group ->
            for (trackIndex in 0 until group.length) {
                val format = group.getTrackFormat(trackIndex)
                add(
                    TrackOption(
                        groupIndex = groupIndex,
                        trackIndex = trackIndex,
                        title = format.label ?: format.language ?: "Áudio ${trackIndex + 1}"
                    )
                )
            }
        }
    }

    val tabTitles = listOf("Legendas", "Áudio")

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.92f),
        title = {
            Text(
                text = "Legendas e Áudio",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 260.dp, max = 320.dp)
            ) {
                TabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    tabTitles.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = {
                                Text(
                                    text = title,
                                    fontSize = 12.sp,
                                    fontWeight = if (selectedTab == index) FontWeight.SemiBold else FontWeight.Medium
                                )
                            }
                        )
                    }
                }

                if (selectedTab == 0) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        item {
                            TrackOptionRow(
                                title = "Desativado",
                                selected = selectedSubtitleTrack == null,
                                onClick = {
                                    onSubtitlesDisabled()
                                    onDismiss()
                                }
                            )
                        }

                        items(subtitleOptions) { option ->
                            TrackOptionRow(
                                title = option.title,
                                selected = selectedSubtitleTrack == option.groupIndex,
                                onClick = {
                                    onSubtitleSelected(option.groupIndex, option.trackIndex)
                                    onDismiss()
                                }
                            )
                        }
                    }
                } else {
                    if (audioOptions.isEmpty()) {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Nenhuma faixa disponível",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(audioOptions) { option ->
                                TrackOptionRow(
                                    title = option.title,
                                    selected = selectedAudioTrack == option.groupIndex,
                                    onClick = {
                                        onAudioSelected(option.groupIndex, option.trackIndex)
                                        onDismiss()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Fechar")
            }
        }
    )
}

@Composable
private fun TrackOptionRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
        },
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 0.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )

            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(16.dp)
                )
            }
        }
    }
}

private fun getSubtitleDisplayName(group: Tracks.Group, index: Int): String {
    val format = group.getTrackFormat(index)

    val label = format.label?.takeIf { it.isNotBlank() }
    val language = format.language?.takeIf { it.isNotBlank() }

    return when {
        label != null && language != null -> "$label - [$language]"
        label != null -> label
        language != null -> {
            when (language.lowercase()) {
                "pt", "pt-br", "por" -> "Português"
                "en", "eng" -> "Inglês"
                "es", "spa" -> "Espanhol"
                "ja", "jpn" -> "Japonês"
                else -> language.uppercase()
            }
        }
        else -> "Legenda ${index + 1}"
    }
}
