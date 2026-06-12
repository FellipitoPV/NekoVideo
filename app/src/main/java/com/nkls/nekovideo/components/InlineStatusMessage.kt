package com.nkls.nekovideo.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nkls.nekovideo.components.helpers.SortRowMessage
import com.nkls.nekovideo.components.helpers.SortRowMessageType

private val SuccessBannerColor = Color(0xFF2E7D32)
private val SuccessBannerContentColor = Color(0xFFFFFFFF)

@Composable
fun InlineStatusMessage(
    message: SortRowMessage?,
    modifier: Modifier = Modifier
) {
    var displayedMessage by remember { mutableStateOf<SortRowMessage?>(null) }
    val visibleState = remember { MutableTransitionState(false) }

    LaunchedEffect(message) {
        if (message != null) {
            displayedMessage = message
        }
        visibleState.targetState = message != null
    }

    AnimatedVisibility(
        visibleState = visibleState,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier
    ) {
        displayedMessage?.let { currentMessage ->
            val (containerColor, contentColor, icon) = when (currentMessage.type) {
                SortRowMessageType.INFO -> Triple(
                    MaterialTheme.colorScheme.primary,
                    MaterialTheme.colorScheme.onPrimary,
                    Icons.Default.Info
                )
                SortRowMessageType.SUCCESS -> Triple(
                    SuccessBannerColor,
                    SuccessBannerContentColor,
                    Icons.Default.CheckCircle
                )
                SortRowMessageType.ERROR -> Triple(
                    MaterialTheme.colorScheme.errorContainer,
                    MaterialTheme.colorScheme.onErrorContainer,
                    Icons.Default.Error
                )
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = containerColor,
                tonalElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = currentMessage.text,
                        color = contentColor,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
