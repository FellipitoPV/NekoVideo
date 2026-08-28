package com.nkls.nekovideo.components.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.nkls.nekovideo.R
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Tracks
import kotlin.math.roundToInt
import java.util.Locale

private data class TrackOption(
    val groupIndex: Int,
    val trackIndex: Int,
    val title: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackSelectionDialog(
    availableSubtitles: List<Tracks.Group>,
    availableAudioTracks: List<Tracks.Group>,
    selectedSubtitleTrack: Int?,
    selectedAudioTrack: Int?,
    selectedExternalSubtitleName: String?,
    isExternalSubtitleSelected: Boolean,
    subtitleSizeLevel: Int,
    onSubtitleSelected: (groupIndex: Int, trackIndex: Int) -> Unit,
    onExternalSubtitleClick: () -> Unit,
    onSubtitleSizeLevelChanged: (Int) -> Unit,
    onSubtitlesDisabled: () -> Unit,
    onAudioSelected: (groupIndex: Int, trackIndex: Int) -> Unit,
    onDismiss: () -> Unit,
    onOpen: () -> Unit = {},
    onClose: () -> Unit = {}
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    val bottomSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(Unit) { onOpen() }

    val dismissAndClose = { onDismiss(); onClose() }

    val subtitleOptions = buildList {
        availableSubtitles.forEachIndexed { groupIndex, group ->
            for (trackIndex in 0 until group.length) {
                add(
                    TrackOption(
                        groupIndex = groupIndex,
                        trackIndex = trackIndex,
                        title = getSubtitleDisplayName(context, group, trackIndex)
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
                        title = format.label ?: format.language ?: context.getString(R.string.player_audio_track_number, trackIndex + 1)
                    )
                )
            }
        }
    }

    val tabTitles = listOf(stringResource(R.string.tab_subtitles), stringResource(R.string.tab_audio))

    ModalBottomSheet(
        onDismissRequest = dismissAndClose,
        sheetState = bottomSheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        dragHandle = {
            Surface(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(2.dp)
            ) {
                Box(modifier = Modifier.size(width = 32.dp, height = 4.dp))
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = stringResource(R.string.tracks_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp, max = 360.dp)
            ) {
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    divider = {},
                    modifier = Modifier.fillMaxWidth()
                ) {
                    tabTitles.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            selectedContentColor = MaterialTheme.colorScheme.onSurface,
                            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            text = {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (selectedTab == index) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        )
                    }
                }

                if (selectedTab == 0) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        item {
                            AddSubtitleFileRow(
                                title = selectedExternalSubtitleName
                                    ?: stringResource(R.string.subtitle_file_select),
                                selected = isExternalSubtitleSelected,
                                onClick = {
                                    onExternalSubtitleClick()
                                    dismissAndClose()
                                }
                            )
                        }

                        item {
                            SubtitleSizeControl(
                                subtitleSizeLevel = subtitleSizeLevel,
                                onSubtitleSizeLevelChanged = onSubtitleSizeLevelChanged
                            )
                        }

                        item {
                            Spacer(modifier = Modifier.size(2.dp))
                        }

                        item {
                            SectionLabel(title = stringResource(R.string.subtitle_file_video_section))
                        }

                        item {
                            TrackOptionRow(
                                title = stringResource(R.string.subtitles_off),
                                selected = selectedSubtitleTrack == null && !isExternalSubtitleSelected,
                                onClick = {
                                    onSubtitlesDisabled()
                                    dismissAndClose()
                                }
                            )
                        }

                        items(subtitleOptions) { option ->
                            TrackOptionRow(
                                title = option.title,
                                selected = selectedSubtitleTrack == option.groupIndex,
                                onClick = {
                                    onSubtitleSelected(option.groupIndex, option.trackIndex)
                                    dismissAndClose()
                                }
                            )
                        }
                    }
                } else {
                    if (audioOptions.isEmpty()) {
                        Text(
                            text = stringResource(R.string.no_tracks_available),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 24.dp, start = 4.dp)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(audioOptions) { option ->
                                TrackOptionRow(
                                    title = option.title,
                                    selected = selectedAudioTrack == option.groupIndex,
                                    onClick = {
                                        onAudioSelected(option.groupIndex, option.trackIndex)
                                        dismissAndClose()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddSubtitleFileRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    val accentColor = MaterialTheme.colorScheme.primary
    val rowBgSelected = MaterialTheme.colorScheme.primaryContainer
    val rowBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)
    val rowBgUnselected = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.16f)
    val outlineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
    val textPrimary = MaterialTheme.colorScheme.onSurface
    val textSelected = MaterialTheme.colorScheme.onPrimaryContainer

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .drawBehind {
                drawRoundRect(
                    color = if (selected) accentColor else outlineColor,
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f)
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx(), 10.dp.toPx())
                )
            }
            .background(if (selected) rowBgSelected else rowBgUnselected)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(accentColor.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = if (selected) textSelected else accentColor,
                modifier = Modifier.size(16.dp)
            )
        }

        Text(
            text = title,
            color = if (selected) textSelected else textPrimary,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.weight(1f))

        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun SubtitleSizeControl(
    subtitleSizeLevel: Int,
    onSubtitleSizeLevelChanged: (Int) -> Unit
) {
    val rowBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)
    val accentColor = MaterialTheme.colorScheme.primary
    val textPrimary = MaterialTheme.colorScheme.onSurface
    val textMuted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(rowBg)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        val labels = listOf(
            stringResource(R.string.subtitle_size_small),
            stringResource(R.string.subtitle_size_medium),
            stringResource(R.string.subtitle_size_large)
        )

        Text(
            text = stringResource(R.string.subtitle_size_title),
            color = textPrimary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 1.dp)
        )

        Slider(
            modifier = Modifier.heightIn(min = 20.dp),
            value = subtitleSizeLevel.toFloat(),
            onValueChange = { onSubtitleSizeLevelChanged(it.roundToInt().coerceIn(0, 2)) },
            valueRange = 0f..2f,
            steps = 1,
            colors = SliderDefaults.colors(
                thumbColor = accentColor,
                activeTrackColor = accentColor,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            thumb = {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(accentColor)
                )
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(2.dp),
                    colors = SliderDefaults.colors(
                        activeTrackColor = accentColor,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    thumbTrackGapSize = 0.dp,
                    drawStopIndicator = null
                )
            }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            labels.forEachIndexed { index, label ->
                Text(
                    text = label,
                    color = if (subtitleSizeLevel == index) textPrimary else textMuted,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(title: String) {
    Text(
        text = title,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp)
    )
}

@Composable
private fun TrackOptionRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val rowBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)
    val rowBgSelected = MaterialTheme.colorScheme.primaryContainer
    val textPrimary = MaterialTheme.colorScheme.onSurface
    val textSelected = MaterialTheme.colorScheme.onPrimaryContainer
    val textMuted = MaterialTheme.colorScheme.onSurfaceVariant
    val accentColor = MaterialTheme.colorScheme.primary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) rowBgSelected else rowBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) textSelected else textPrimary,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
        )

        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(18.dp)
            )
        } else {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = Color.Transparent,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

private fun getSubtitleDisplayName(context: android.content.Context, group: Tracks.Group, index: Int): String {
    val format = group.getTrackFormat(index)

    val label = format.label?.takeIf { it.isNotBlank() }
    val language = format.language?.takeIf { it.isNotBlank() }

    return when {
        label != null && language != null -> "$label - [$language]"
        label != null -> label
        language != null -> {
            languageCodeToTag(language)?.let { tag ->
                Locale.forLanguageTag(tag).getDisplayLanguage(Locale.getDefault())
                    .takeIf { it.isNotBlank() }
            } ?: language.uppercase()
        }
        else -> context.getString(R.string.player_track_number, index + 1)
    }
}

private fun languageCodeToTag(language: String): String? {
    return when (language.lowercase()) {
        "pt", "pt-br", "por" -> "pt-BR"
        "en", "eng" -> "en"
        "es", "spa" -> "es"
        "ja", "jpn" -> "ja"
        else -> null
    }
}
