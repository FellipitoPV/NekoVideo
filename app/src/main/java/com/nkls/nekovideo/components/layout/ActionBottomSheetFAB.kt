package com.nkls.nekovideo.components.layout

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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nkls.nekovideo.R

enum class ActionType {
    UNLOCK, SECURE, DELETE, RENAME, MOVE, SHUFFLE_PLAY, CREATE_FOLDER, SETTINGS, PASTE,
    PRIVATIZE, UNPRIVATIZE, CANCEL_MOVE // NOVA AÇÃO
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
    selectedItems: List<String> = emptyList(),
    itemsToMoveCount: Int = 0, // NOVO: contador de itens para mover
    onActionClick: (ActionType) -> Unit
) {
    var showBottomSheet by remember { mutableStateOf(false) }
    val bottomSheetState = rememberModalBottomSheetState()

    val pasteHereText = stringResource(R.string.action_paste_here)
    val cancelText = stringResource(R.string.action_cancel)
    val cancelOperationText = stringResource(R.string.action_cancel_operation)
    val unlockText = stringResource(R.string.action_unlock)
    val protectText = stringResource(R.string.action_protect)
    val unprivatizeText = stringResource(R.string.action_unprivatize)
    val privatizeText = stringResource(R.string.action_privatize)
    val deleteText = stringResource(R.string.action_delete)
    val renameText = stringResource(R.string.action_rename)
    val moveText = stringResource(R.string.action_move)
    val shufflePlayText = stringResource(R.string.action_shuffle_play)
    val createFolderText = stringResource(R.string.action_create_folder)
    val settingsText = stringResource(R.string.action_settings)
    val moveItemsText = pluralStringResource(R.plurals.move_items_count, itemsToMoveCount, itemsToMoveCount)

    // NOVOS: strings que estavam hardcoded
    val movingItemsText = pluralStringResource(R.plurals.moving_items_status, itemsToMoveCount, itemsToMoveCount)
    val cancelDescription = stringResource(R.string.cancel_description)
    val pasteHereDescription = stringResource(R.string.paste_here_description)
    val actionsDescription = stringResource(R.string.actions_description)
    val optionsDescription = stringResource(R.string.options_description)
    val modeMoveFiles = stringResource(R.string.mode_move_files)
    val itemActions = stringResource(R.string.item_actions)
    val options = stringResource(R.string.options)
    val navigateToDestination = stringResource(R.string.navigate_to_destination)


    // Verifica se algum item selecionado é pasta privada
    val hasPrivateFolders = remember(selectedItems) {
        selectedItems.any { path ->
            val file = java.io.File(path)
            file.isDirectory && file.name.startsWith(".")
        }
    }

    // Verifica se algum item selecionado é pasta normal
    val hasNormalFolders = remember(selectedItems) {
        selectedItems.any { path ->
            val file = java.io.File(path)
            file.isDirectory && !file.name.startsWith(".")
        }
    }

    // Define as ações baseado no contexto
    val actions = remember(hasSelectedItems, isSecureMode, hasPrivateFolders, hasNormalFolders, isMoveMode, moveItemsText) {
        when {
            isMoveMode -> {
                listOf(
                    ActionItem(ActionType.PASTE, Icons.Default.ContentPaste, pasteHereText, moveItemsText),
                    ActionItem(ActionType.CANCEL_MOVE, Icons.Default.Cancel, cancelText, cancelOperationText)
                )
            }
            hasSelectedItems -> {
                val actionsList = mutableListOf<ActionItem>()

                if (isSecureMode) {
                    actionsList.add(ActionItem(ActionType.UNLOCK, Icons.Default.LockOpen, unlockText))
                } else {
                    actionsList.add(ActionItem(ActionType.SECURE, Icons.Default.Lock, protectText))

                    if (hasPrivateFolders) {
                        actionsList.add(ActionItem(ActionType.UNPRIVATIZE, Icons.Default.VisibilityOff, unprivatizeText))
                    }
                    if (hasNormalFolders) {
                        actionsList.add(ActionItem(ActionType.PRIVATIZE, Icons.Default.Visibility, privatizeText))
                    }
                }

                actionsList.addAll(listOf(
                    ActionItem(ActionType.DELETE, Icons.Default.Delete, deleteText),
                    ActionItem(ActionType.RENAME, Icons.Default.Edit, renameText),
                    ActionItem(ActionType.MOVE, Icons.Default.DriveFileMove, moveText)
                ))

                actionsList
            }
            else -> {
                listOf(
                    ActionItem(ActionType.SHUFFLE_PLAY, Icons.Default.Shuffle, shufflePlayText),
                    ActionItem(ActionType.CREATE_FOLDER, Icons.Default.CreateNewFolder, createFolderText),
                    ActionItem(ActionType.SETTINGS, Icons.Default.Settings, settingsText)
                )
            }
        }
    }

    // NOVO: Layout para modo Move - FAB duplo
    if (isMoveMode) {
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Indicador de status do Move
            Surface(
                modifier = Modifier.padding(end = 4.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(20.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DriveFileMove,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = movingItemsText, // ✅ CORRIGIDO
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontSize = 11.sp
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Botão Cancelar
                FloatingActionButton(
                    onClick = { onActionClick(ActionType.CANCEL_MOVE) },
                    modifier = Modifier.size(48.dp),
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = cancelDescription, // ✅ CORRIGIDO
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Botão Colar (principal)
                FloatingActionButton(
                    onClick = { onActionClick(ActionType.PASTE) },
                    modifier = Modifier.size(56.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentPaste,
                        contentDescription = pasteHereDescription, // ✅ CORRIGIDO
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    } else {
        // FAB normal (sem modo Move)
        FloatingActionButton(
            onClick = { showBottomSheet = true },
            modifier = Modifier.size(56.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) {
            Icon(
                imageVector = if (hasSelectedItems) Icons.Default.MoreVert else Icons.Default.Settings,
                contentDescription = if (hasSelectedItems) actionsDescription else optionsDescription, // ✅ CORRIGIDO
                modifier = Modifier.size(24.dp)
            )
        }
    }

    // Bottom Sheet Modal
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
                    // Header com contexto
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            text = when {
                                isMoveMode -> modeMoveFiles // ✅ CORRIGIDO
                                hasSelectedItems -> itemActions // ✅ CORRIGIDO
                                else -> options // ✅ CORRIGIDO
                            },
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )

                        if (isMoveMode) {
                            Text(
                                text = navigateToDestination, // ✅ CORRIGIDO
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                    Spacer(modifier = Modifier.height(16.dp))

                    // Grid de ações
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(if (isMoveMode) 2 else 3),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(actions) { action ->
                            ActionGridItem(
                                action = action,
                                isMoveMode = isMoveMode,
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
    isMoveMode: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (isMoveMode) 100.dp else 80.dp) // Maior altura para modo Move
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
                        ActionType.CANCEL_MOVE -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                        ActionType.PASTE -> Color(0xFF4CAF50).copy(alpha = 0.15f)
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
                                ActionType.DELETE, ActionType.CANCEL_MOVE -> MaterialTheme.colorScheme.error
                                ActionType.PASTE, ActionType.SECURE, ActionType.UNLOCK -> Color(0xFF4CAF50)
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

                // Subtitle para modo Move
                action.subtitle?.let { subtitle ->
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 10.sp
                        ),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}