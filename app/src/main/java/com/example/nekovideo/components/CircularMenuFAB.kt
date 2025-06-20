// Arquivo: CircularMenuFAB.kt
package com.example.nekovideo.components

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

enum class CircularMenuAction {
    UNLOCK, SECURE, DELETE, RENAME, MOVE, SHUFFLE_PLAY, CREATE_FOLDER
}

@Composable
fun CircularMenuFAB(
    isExpanded: Boolean,
    isMoveMode: Boolean,
    hasSelectedItems: Boolean,
    currentRoute: String,
    onMainClick: () -> Unit,
    onActionClick: (CircularMenuAction) -> Unit,
    onDismiss: () -> Unit
) {
    val menuItems = if (hasSelectedItems) {
        if (currentRoute == "secure_folder") {
            listOf(
                CircularMenuAction.UNLOCK to (Icons.Default.LockOpen to "Unlock"),
                CircularMenuAction.DELETE to (Icons.Default.Delete to "Delete"),
                CircularMenuAction.RENAME to (Icons.Default.Edit to "Rename"),
                CircularMenuAction.MOVE to (Icons.Default.DriveFileMove to "Move")
            )
        } else {
            listOf(
                CircularMenuAction.SECURE to (Icons.Default.Lock to "Secure"),
                CircularMenuAction.DELETE to (Icons.Default.Delete to "Delete"),
                CircularMenuAction.RENAME to (Icons.Default.Edit to "Rename"),
                CircularMenuAction.MOVE to (Icons.Default.DriveFileMove to "Move")
            )
        }
    } else {
        listOf(
            CircularMenuAction.SHUFFLE_PLAY to (Icons.Default.Shuffle to "Shuffle"),
            CircularMenuAction.CREATE_FOLDER to (Icons.Default.CreateNewFolder to "Create")
        )
    }

    // Animação de posição do FAB
    val fabOffsetX by animateFloatAsState(
        targetValue = if (isExpanded) 0f else 40f,
        animationSpec = tween(400)
    )
    val fabOffsetY by animateFloatAsState(
        targetValue = if (isExpanded) 0f else 40f,
        animationSpec = tween(400)
    )

    Box(
        modifier = Modifier.size(200.dp),
        contentAlignment = Alignment.Center
    ) {
        // Overlay para fechar o menu quando clicar fora
        if (isExpanded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        onClick = onDismiss,
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    )
            )
        }

        // Menu circular items
        menuItems.forEachIndexed { index, (action, iconData) ->
            val (icon, label) = iconData
            val angle = (360f / menuItems.size) * index

            CircularMenuItem(
                icon = icon,
                label = label,
                angle = angle,
                isVisible = isExpanded,
                radius = 80.dp,
                animationDelay = index * 50,
                onClick = {
                    onActionClick(action)
                    onDismiss()
                }
            )
        }

        // Main FAB
        val fabScale by animateFloatAsState(
            targetValue = if (isExpanded) 1.1f else 1.0f,
            animationSpec = tween(300)
        )
        val fabRotation by animateFloatAsState(
            targetValue = if (isExpanded) 45f else 0f,
            animationSpec = tween(300)
        )

        FloatingActionButton(
            onClick = onMainClick,
            modifier = Modifier
                .size(56.dp)
                .offset(x = fabOffsetX.dp, y = fabOffsetY.dp)
                .shadow(8.dp, RoundedCornerShape(28.dp))
                .graphicsLayer(
                    scaleX = fabScale,
                    scaleY = fabScale,
                    rotationZ = fabRotation
                ),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) {
            Icon(
                imageVector = when {
                    isMoveMode -> Icons.Default.ContentPaste
                    hasSelectedItems -> Icons.Default.FolderOpen
                    else -> Icons.Default.Settings
                },
                contentDescription = when {
                    isMoveMode -> "Paste Items"
                    hasSelectedItems -> "Actions"
                    else -> "Options"
                },
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun CircularMenuItem(
    icon: ImageVector,
    label: String,
    angle: Float,
    isVisible: Boolean,
    radius: Dp,
    animationDelay: Int,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = tween(
            durationMillis = 300,
            delayMillis = if (isVisible) animationDelay else 0,
            easing = FastOutSlowInEasing
        )
    )

    val alpha by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = tween(
            durationMillis = 200,
            delayMillis = if (isVisible) animationDelay else 0
        )
    )

    if (scale > 0f) {
        val angleRad = Math.toRadians(angle.toDouble())
        val x = (radius.value * cos(angleRad)).dp
        val y = (radius.value * sin(angleRad)).dp

        Box(
            modifier = Modifier
                .offset(x = x, y = -y)
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    alpha = alpha
                )
        ) {
            FloatingActionButton(
                onClick = onClick,
                modifier = Modifier.size(48.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}