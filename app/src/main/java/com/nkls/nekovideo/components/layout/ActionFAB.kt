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
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nkls.nekovideo.R

enum class ActionType {
    UNLOCK, SECURE, DELETE, RENAME, MOVE, SHUFFLE_PLAY, CREATE_FOLDER, SETTINGS, PASTE,
    PRIVATIZE, UNPRIVATIZE, CANCEL_MOVE, SET_AS_SECURE_FOLDER
}

data class ActionItem(
    val type: ActionType,
    val icon: ImageVector,
    val title: String,
    val subtitle: String? = null,
    val isEnabled: Boolean = true
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionFAB(
    hasSelectedItems: Boolean,
    isMoveMode: Boolean,
    isSecureMode: Boolean,
    isRootDirectory: Boolean = false,
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
    val secureFolderSet = stringResource(R.string.action_set_secure_folder)
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

    val isSingleNomediaFolder = selectedItems.size == 1 &&
            java.io.File(selectedItems.first()).let { file ->
                file.isDirectory && file.name.startsWith(".")
            }

    val actions = remember(hasSelectedItems, isSecureMode, hasPrivateFolders, hasNormalFolders, isMoveMode, moveItemsText, isRootDirectory, selectedItems) {
        when {
            isMoveMode -> {
                listOf(
                    ActionItem(ActionType.PASTE, Icons.Default.ContentPaste, pasteHereText, moveItemsText),
                    ActionItem(ActionType.CANCEL_MOVE, Icons.Default.Cancel, cancelText, cancelOperationText)
                )
            }
            hasSelectedItems -> {
                val actionsList = mutableListOf<ActionItem>()

                val isSingleFolder = selectedItems.size == 1 &&
                        java.io.File(selectedItems.first()).isDirectory

                // ✅ ADICIONAR O SHUFFLE_PLAY AQUI NO INÍCIO
                actionsList.add(
                    ActionItem(
                        ActionType.SHUFFLE_PLAY,
                        Icons.Default.Shuffle,
                        shufflePlayText
                    )
                )

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
                    ActionItem(ActionType.MOVE, Icons.AutoMirrored.Filled.DriveFileMove, moveText)
                ))

                if (isSingleNomediaFolder) {
                    actionsList.add(
                        ActionItem(
                            ActionType.SET_AS_SECURE_FOLDER,
                            Icons.Default.FolderSpecial,
                            secureFolderSet
                        )
                    )
                }

                actionsList
            }
            else -> {
                buildList {
                    add(ActionItem(
                        ActionType.SHUFFLE_PLAY,
                        Icons.Default.Shuffle,
                        shufflePlayText,
                        isEnabled = !isRootDirectory
                    ))
                    add(ActionItem(ActionType.CREATE_FOLDER, Icons.Default.CreateNewFolder, createFolderText))
                    add(ActionItem(ActionType.SETTINGS, Icons.Default.Settings, settingsText))
                }
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
            val density = LocalDensity.current
            var isWide by remember { mutableStateOf(false) }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { size ->
                        isWide = with(density) { size.width.toDp() } > 600.dp
                    }
                    .padding(bottom = if (isWide) 8.dp else 16.dp)
            ) {
                // Header com contexto
                Column(modifier = Modifier.padding(
                    horizontal = 16.dp,
                    vertical = if (isWide) 4.dp else 8.dp
                )) {
                    Text(
                        text = when {
                            isMoveMode -> modeMoveFiles
                            hasSelectedItems -> itemActions
                            else -> options
                        },
                        style = if (isWide) MaterialTheme.typography.titleMedium
                               else MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )

                    if (isMoveMode) {
                        Text(
                            text = navigateToDestination,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                Spacer(modifier = Modifier.height(if (isWide) 8.dp else 16.dp))

                // Grid de ações - mais colunas em landscape
                val gridCols = when {
                    isMoveMode -> 2
                    isWide -> 5
                    else -> 3
                }

                LazyVerticalGrid(
                    columns = GridCells.Fixed(gridCols),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (isWide) 8.dp else 12.dp),
                    verticalArrangement = Arrangement.spacedBy(if (isWide) 8.dp else 12.dp)
                ) {
                    items(actions) { action ->
                        ActionGridItem(
                            action = action,
                            isMoveMode = isMoveMode,
                            isCompact = isWide,
                            onClick = {
                                onActionClick(action.type)
                                showBottomSheet = false
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(if (isWide) 8.dp else 16.dp))
            }
        }
    }
}

@Composable
private fun ActionGridItem(
    action: ActionItem,
    isMoveMode: Boolean = false,
    isCompact: Boolean = false,
    onClick: () -> Unit
) {
    val itemHeight = when {
        isCompact -> 76.dp
        isMoveMode -> 110.dp
        else -> 100.dp
    }
    val iconSurfaceSize = if (isCompact) 36.dp else 40.dp
    val iconSize = if (isCompact) 18.dp else 20.dp

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(itemHeight)
            .clickable(enabled = action.isEnabled) {
                if (action.isEnabled) onClick()
            },
        color = Color.Transparent,
        shape = RoundedCornerShape(12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (isCompact) 4.dp else 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    modifier = Modifier.size(iconSurfaceSize),
                    shape = RoundedCornerShape(10.dp),
                    color = when (action.type) {
                        ActionType.DELETE -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                        ActionType.CANCEL_MOVE -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                        ActionType.PASTE -> Color(0xFF4CAF50).copy(alpha = 0.15f)
                        ActionType.SECURE, ActionType.UNLOCK -> Color(0xFF4CAF50).copy(alpha = 0.15f)
                        ActionType.PRIVATIZE -> Color(0xFFFF9800).copy(alpha = 0.15f)
                        ActionType.UNPRIVATIZE -> Color(0xFF2196F3).copy(alpha = 0.15f)
                        else -> if (action.isEnabled) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                        }
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
                                else -> if (action.isEnabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                }
                            },
                            modifier = Modifier.size(iconSize)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(if (isCompact) 4.dp else 8.dp))

                Text(
                    text = action.title,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = if (isCompact) 10.sp else 11.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = if (isCompact) 12.sp else 13.sp
                    ),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp), // ✅ ADICIONE
                    color = if (action.isEnabled) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    }
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