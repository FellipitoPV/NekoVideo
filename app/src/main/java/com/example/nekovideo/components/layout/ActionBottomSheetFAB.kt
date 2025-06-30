// Arquivo: ActionBottomSheetFAB.kt
package com.example.nekovideo.components.layout

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

enum class ActionType {
    UNLOCK, SECURE, DELETE, RENAME, MOVE, SHUFFLE_PLAY, CREATE_FOLDER, SETTINGS, PASTE
}

data class ActionItem(
    val type: ActionType,
    val icon: ImageVector,
    val title: String,
    val subtitle: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionBottomSheetFAB(
    hasSelectedItems: Boolean,
    isMoveMode: Boolean,
    currentRoute: String,
    onActionClick: (ActionType) -> Unit
) {
    var showBottomSheet by remember { mutableStateOf(false) }
    val bottomSheetState = rememberModalBottomSheetState()

    // Define as ações baseado no contexto
    val actions = remember(hasSelectedItems, currentRoute) {
        if (hasSelectedItems) {
            if (currentRoute == "secure_folder") {
                listOf(
                    ActionItem(ActionType.UNLOCK, Icons.Default.LockOpen, "Desbloquear", "Remover da pasta segura"),
                    ActionItem(ActionType.DELETE, Icons.Default.Delete, "Excluir", "Apagar permanentemente"),
                    ActionItem(ActionType.RENAME, Icons.Default.Edit, "Renomear", "Alterar nome do arquivo"),
                    ActionItem(ActionType.MOVE, Icons.Default.DriveFileMove, "Mover", "Transferir para outra pasta")
                )
            } else {
                listOf(
                    ActionItem(ActionType.SECURE, Icons.Default.Lock, "Proteger", "Mover para pasta segura"),
                    ActionItem(ActionType.DELETE, Icons.Default.Delete, "Excluir", "Apagar permanentemente"),
                    ActionItem(ActionType.RENAME, Icons.Default.Edit, "Renomear", "Alterar nome do arquivo"),
                    ActionItem(ActionType.MOVE, Icons.Default.DriveFileMove, "Mover", "Transferir para outra pasta")
                )
            }
        } else {
            listOf(
                ActionItem(ActionType.SHUFFLE_PLAY, Icons.Default.Shuffle, "Reprodução Aleatória", "Reproduzir vídeos em ordem aleatória"),
                ActionItem(ActionType.CREATE_FOLDER, Icons.Default.CreateNewFolder, "Nova Pasta", "Criar uma nova pasta"),
                ActionItem(ActionType.SETTINGS, Icons.Default.Settings, "Configurações", "Abrir configurações do app")
            )
        }
    }

    // FAB Principal
    FloatingActionButton(
        onClick = {
            if (isMoveMode) {
                // Executar ação de colar diretamente
                onActionClick(ActionType.PASTE)
            } else {
                showBottomSheet = true
            }
        },
        modifier = Modifier.size(56.dp),
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary
    ) {
        Icon(
            imageVector = when {
                isMoveMode -> Icons.Default.ContentPaste
                hasSelectedItems -> Icons.Default.MoreVert
                else -> Icons.Default.Settings
            },
            contentDescription = when {
                isMoveMode -> "Colar itens"
                hasSelectedItems -> "Ações"
                else -> "Opções"
            },
            modifier = Modifier.size(24.dp)
        )
    }

    // Bottom Sheet Modal com animações
    if (showBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = { showBottomSheet = false },
            sheetState = bottomSheetState,
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
            AnimatedVisibility(
                visible = showBottomSheet,
                enter = fadeIn(animationSpec = tween(50)) + slideInVertically(
                    animationSpec = tween(50),
                    initialOffsetY = { it / 4 }
                ),
                exit = fadeOut(animationSpec = tween(50)) + slideOutVertically(
                    animationSpec = tween(50),
                    targetOffsetY = { it / 4 }
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    // Header
                    Text(
                        text = if (hasSelectedItems) "Ações dos Itens" else "Opções",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )

                    Divider(modifier = Modifier.padding(horizontal = 16.dp))

                    Spacer(modifier = Modifier.height(8.dp))

                    // Lista de ações
                    LazyColumn {
                        items(actions) { action ->
                            ActionListItem(
                                action = action,
                                onClick = {
                                    onActionClick(action.type)
                                    showBottomSheet = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionListItem(
    action: ActionItem,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                text = action.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
        },
        supportingContent = action.subtitle?.let {
            {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        leadingContent = {
            Icon(
                imageVector = action.icon,
                contentDescription = action.title,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 4.dp)
    )
}