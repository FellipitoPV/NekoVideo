package com.nkls.nekovideo.components.layout

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.PendingActions
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nkls.nekovideo.R
import com.nkls.nekovideo.components.helpers.LocalNekoTrimVideoStatuses
import com.nkls.nekovideo.components.helpers.NekoTrimVideoAccess
import com.nkls.nekovideo.components.helpers.NekoTrimVideoState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun NekoTrimVideoBadge(path: String, modifier: Modifier = Modifier) {
    val statuses = LocalNekoTrimVideoStatuses.current
    if (statuses.isEmpty()) return
    val status by produceState<NekoTrimVideoState?>(null, path, statuses) {
        value = withContext(Dispatchers.IO) { runCatching { statuses[NekoTrimVideoAccess.videoKey(File(path))] }.getOrNull() }
    }
    val current = status ?: return
    val label = stringResource(when (current) {
        NekoTrimVideoState.QUEUED -> R.string.nekotrim_video_queued
        NekoTrimVideoState.PROCESSING -> R.string.nekotrim_video_processing
        NekoTrimVideoState.DONE -> R.string.nekotrim_video_done
        NekoTrimVideoState.FAILED -> R.string.nekotrim_video_failed
    })
    val icon = when (current) {
        NekoTrimVideoState.QUEUED -> Icons.Default.PendingActions
        NekoTrimVideoState.PROCESSING -> Icons.Default.HourglassTop
        NekoTrimVideoState.DONE -> Icons.Default.CheckCircle
        NekoTrimVideoState.FAILED -> Icons.Default.ErrorOutline
    }
    val color = when (current) {
        NekoTrimVideoState.DONE -> Color(0xFF86EFAC)
        NekoTrimVideoState.FAILED -> Color(0xFFFCA5A5)
        else -> Color.White
    }
    Row(modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.75f))
        .semantics(mergeDescendants = true) { contentDescription = "NekoTrim: $label" }
        .padding(horizontal = 4.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.ic_nekotrim), null, Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)))
        Icon(icon, null, tint = color, modifier = Modifier.size(13.dp))
    }
}
