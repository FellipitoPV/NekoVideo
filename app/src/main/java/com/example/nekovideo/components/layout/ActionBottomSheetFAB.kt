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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class ActionType {
    UNLOCK, SECURE, DELETE, RENAME, MOVE, SHUFFLE_PLAY, CREATE_FOLDER, SETTINGS, PASTE,
    PRIVATIZE, UNPRIVATIZE // NOVAS AÇÕES
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
    isSecureMode: Boolean,
    selectedItems: List<String> = emptyList(), // NOVO: para verificar se tem pastas com "."
    onActionClick: (ActionType) -> Unit
) {
    var showBottomSheet by remember { mutableStateOf(false) }
    val bottomSheetState = rememberModalBottomSheetState()

    // Verifica se algum item selecionado é pasta privada (começa com ".")
    val hasPrivateFolders = remember(selectedItems) {
        selectedItems.any { path ->
            val file = java.io.File(path)
            file.isDirectory && file.name.startsWith(".")
        }
    }

    // Verifica se algum item selecionado é pasta normal (não começa com ".")
    val hasNormalFolders = remember(selectedItems) {
        selectedItems.any { path ->
            val file = java.io.File(path)
            file.isDirectory && !file.name.startsWith(".")
        }
    }

    // Define as ações baseado no contexto
    val actions = remember(hasSelectedItems, isSecureMode, hasPrivateFolders, hasNormalFolders) {
        if (hasSelectedItems) {
            val actionsList = mutableListOf<ActionItem>()

            if (isSecureMode) {
                // Está na área segura (.secure_videos)
                actionsList.add(ActionItem(ActionType.UNLOCK, Icons.Default.LockOpen, "Desbloquear"))
            } else {
                // Está em área normal
                actionsList.add(ActionItem(ActionType.SECURE, Icons.Default.Lock, "Proteger"))

                // Ações de privar/desprivar baseado no tipo de pasta
                if (hasPrivateFolders) {
                    actionsList.add(ActionItem(ActionType.UNPRIVATIZE, Icons.Default.VisibilityOff, "Desprivar"))
                }
                if (hasNormalFolders) {
                    actionsList.add(ActionItem(ActionType.PRIVATIZE, Icons.Default.Visibility, "Privar"))
                }
            }

            // Ações comuns
            actionsList.addAll(listOf(
                ActionItem(ActionType.DELETE, Icons.Default.Delete, "Excluir"),
                ActionItem(ActionType.RENAME, Icons.Default.Edit, "Renomear"),
                ActionItem(ActionType.MOVE, Icons.Default.DriveFileMove, "Mover")
            ))

            actionsList
        } else {
            listOf(
                ActionItem(ActionType.SHUFFLE_PLAY, Icons.Default.Shuffle, "Aleatório"),
                ActionItem(ActionType.CREATE_FOLDER, Icons.Default.CreateNewFolder, "Nova Pasta"),
                ActionItem(ActionType.SETTINGS, Icons.Default.Settings, "Configurações")
            )
        }
    }

    // FAB Principal
    FloatingActionButton(
        onClick = {
            if (isMoveMode) {
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

    // Bottom Sheet Modal com Grid
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

                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                    Spacer(modifier = Modifier.height(16.dp))

                    // Grid de ações (3 colunas)
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(actions) { action ->
                            ActionGridItem(
                                action = action,
                                onClick = {
                                    onActionClick(action.type)
                                    showBottomSheet = false
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun ActionGridItem(
    action: ActionItem,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp) // Altura fixa em vez de aspecto quadrado
            .clickable { onClick() },
        color = Color.Transparent,
        shape = RoundedCornerShape(12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = when (action.type) {
                        ActionType.DELETE -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                        ActionType.SECURE, ActionType.UNLOCK -> Color(0xFF4CAF50).copy(alpha = 0.15f)
                        ActionType.PRIVATIZE -> Color(0xFFFF9800).copy(alpha = 0.15f)
                        ActionType.UNPRIVATIZE -> Color(0xFF2196F3).copy(alpha = 0.15f)
                        else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                    }
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = action.icon,
                            contentDescription = action.title,
                            tint = when (action.type) {
                                ActionType.DELETE -> MaterialTheme.colorScheme.error
                                ActionType.SECURE, ActionType.UNLOCK -> Color(0xFF4CAF50)
                                ActionType.PRIVATIZE -> Color(0xFFFF9800)
                                ActionType.UNPRIVATIZE -> Color(0xFF2196F3)
                                else -> MaterialTheme.colorScheme.primary
                            },
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = action.title,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }
        }
    }
}