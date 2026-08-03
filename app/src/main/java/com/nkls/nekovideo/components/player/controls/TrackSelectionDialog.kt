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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.nkls.nekovideo.R
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Tracks
import kotlin.math.roundToInt

private val DialogBg = Color(0xFF1A1A2E)
private val AccentBlue = Color(0xFF6C8CFF)
private val TextWhite = Color.White
private val TextMuted = Color.White.copy(alpha = 0.5f)
private val RowBg = Color.White.copy(alpha = 0.08f)
private val RowBgSelected = Color.White.copy(alpha = 0.15f)

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
    var selectedTab by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) { onOpen() }

    val dismissAndClose = { onDismiss(); onClose() }

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
                        title = format.label ?: format.language ?: "Audio ${trackIndex + 1}"
                    )
                )
            }
        }
    }

    val tabTitles = listOf(stringResource(R.string.tab_subtitles), stringResource(R.string.tab_audio))

    AlertDialog(
        onDismissRequest = dismissAndClose,
        containerColor = DialogBg,
        titleContentColor = TextWhite,
        textContentColor = TextMuted,
        modifier = Modifier.fillMaxWidth(1f),
        title = {
                Text(
                    text = stringResource(R.string.tracks_title),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp
                )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp, max = 360.dp)
            ) {
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.Transparent,
                    contentColor = TextWhite,
                    divider = {},
                    modifier = Modifier.fillMaxWidth()
                ) {
                    tabTitles.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            selectedContentColor = TextWhite,
                            unselectedContentColor = TextMuted,
                            text = {
                                Text(
                                    text = title,
                                    fontSize = 13.sp,
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
                            fontSize = 13.sp,
                            color = TextMuted,
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
        },
        confirmButton = {}
    )
}

@Composable
private fun AddSubtitleFileRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .drawBehind {
                drawRoundRect(
                    color = if (selected) AccentBlue else Color.White.copy(alpha = 0.28f),
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f)
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx(), 10.dp.toPx())
                )
            }
            .background(if (selected) RowBgSelected else Color.White.copy(alpha = 0.03f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(AccentBlue.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = if (selected) TextWhite else AccentBlue,
                modifier = Modifier.size(16.dp)
            )
        }

        Text(
            text = title,
            color = if (selected) TextWhite else TextWhite,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.weight(1f))

        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = AccentBlue,
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(RowBg)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        val labels = listOf(
            stringResource(R.string.subtitle_size_small),
            stringResource(R.string.subtitle_size_medium),
            stringResource(R.string.subtitle_size_large)
        )

        Text(
            text = stringResource(R.string.subtitle_size_title),
            color = TextWhite,
            fontSize = 12.sp,
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
                thumbColor = AccentBlue,
                activeTrackColor = AccentBlue,
                inactiveTrackColor = Color.White.copy(alpha = 0.2f)
            ),
            thumb = {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(AccentBlue)
                )
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(2.dp),
                    colors = SliderDefaults.colors(
                        activeTrackColor = AccentBlue,
                        inactiveTrackColor = Color.White.copy(alpha = 0.2f)
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
                    color = if (subtitleSizeLevel == index) TextWhite else TextMuted,
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(title: String) {
    Text(
        text = title,
        color = TextMuted,
        fontSize = 12.sp,
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) RowBgSelected else RowBg)
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
            fontSize = 13.sp,
            color = if (selected) TextWhite else TextMuted,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
        )

        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = AccentBlue,
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

private fun getSubtitleDisplayName(group: Tracks.Group, index: Int): String {
    val format = group.getTrackFormat(index)

    val label = format.label?.takeIf { it.isNotBlank() }
    val language = format.language?.takeIf { it.isNotBlank() }

    return when {
        label != null && language != null -> "$label - [$language]"
        label != null -> label
        language != null -> {
            when (language.lowercase()) {
                "pt", "pt-br", "por" -> "Portugu\u00eas"
                "en", "eng" -> "English"
                "es", "spa" -> "Espa\u00f1ol"
                "ja", "jpn" -> "\u65e5\u672c\u8a9e"
                else -> language.uppercase()
            }
        }
        else -> "Track ${index + 1}"
    }
}
