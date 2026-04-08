package com.nkls.nekovideo.components.layout

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.nkls.nekovideo.R
import com.nkls.nekovideo.components.helpers.DLNACastManager
import com.nkls.nekovideo.components.helpers.FilesManager
import com.nkls.nekovideo.components.helpers.LockedPlaybackSession
import com.nkls.nekovideo.components.player.DLNADevicePickerDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun TopBar(
    currentRoute: String?,
    selectedItems: List<String>,
    folderPath: String,
    navController: NavController,
    onPasswordDialog: () -> Unit,
    onSelectionClear: () -> Unit,
    onSelectAll: () -> Unit,
    isAtRootLevel: Boolean = false,
    onNavigateToPath: (String) -> Unit = {},
    onNavigateBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val maxTapInterval = 500L
    var tapCount by remember { mutableIntStateOf(0) }

    val density = LocalDensity.current
    var isCompact by remember { mutableStateOf(false) }

    // Cast state
    val castManager = remember { DLNACastManager.getInstance(context) }
    var isCasting by remember { mutableStateOf(castManager.isConnected) }
    var showDevicePicker by remember { mutableStateOf(false) }
    var showDisconnectDialog by remember { mutableStateOf(false) }
    var discoveredDevices by remember { mutableStateOf<List<DLNACastManager.DLNADevice>>(emptyList()) }
    var isDiscovering by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            isCasting = castManager.isConnected
            delay(1000)
        }
    }

    if (showDevicePicker) {
        DLNADevicePickerDialog(
            devices = discoveredDevices,
            isDiscovering = isDiscovering,
            onDeviceSelected = { device ->
                showDevicePicker = false
                castManager.connectToDevice(device)
                isCasting = true
            },
            onDismiss = { showDevicePicker = false }
        )
    }

    if (showDisconnectDialog) {
        AlertDialog(
            onDismissRequest = { showDisconnectDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.CastConnected,
                    contentDescription = null,
                    tint = Color(0xFF4CAF50)
                )
            },
            title = { Text(stringResource(R.string.cast_disconnect_title)) },
            text = { Text(stringResource(R.string.cast_disconnect_message, castManager.connectedDeviceName)) },
            confirmButton = {
                TextButton(onClick = {
                    showDisconnectDialog = false
                    castManager.stopCasting()
                    isCasting = false
                }) {
                    Text(stringResource(R.string.cast_disconnect_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    TopAppBar(
        modifier = Modifier.onSizeChanged { size ->
            isCompact = with(density) { size.width.toDp() } > 600.dp
        },
        expandedHeight = if (isCompact) 48.dp else 64.dp,
        title = {
            when {
                selectedItems.isNotEmpty() -> {
                    Text(
                        text = pluralStringResource(R.plurals.items_selected, selectedItems.size, selectedItems.size),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
                currentRoute == "settings" -> {
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
                currentRoute == "settings/playback" -> {
                    Text(
                        text = stringResource(R.string.playback_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
                currentRoute == "settings/interface" -> {
                    Text(
                        text = stringResource(R.string.interface_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
                currentRoute == "settings/display" -> {
                    Text(
                        text = stringResource(R.string.settings_display),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
                currentRoute == "settings/performance" -> {
                    Text(
                        text = stringResource(R.string.settings_performance),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
                currentRoute == "settings/about" -> {
                    Text(
                        text = stringResource(R.string.about_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
                currentRoute == "settings/security" -> {
                    Text(
                        text = stringResource(R.string.security_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
                currentRoute == "folder" && isAtRootLevel -> {
                    // Está na raiz: mostra ícone e nome do app com triple tap
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .pointerInput(Unit) {
                                detectTapGestures { _ ->
                                    tapCount++
                                    if (tapCount == 1) {
                                        coroutineScope.launch {
                                            delay(maxTapInterval)
                                            if (tapCount < 3) tapCount = 0
                                        }
                                    }
                                    if (tapCount >= 3) {
                                        tapCount = 0
                                        onPasswordDialog()
                                    }
                                }
                            }
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_app_icon),
                            contentDescription = null,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "NekoVideo",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                currentRoute == "folder" -> {
                    // Breadcrumb interativo para navegação entre pastas
                    val rootPath = android.os.Environment.getExternalStorageDirectory().absolutePath
                    val relativePath = folderPath.removePrefix(rootPath).trim('/')
                    val pathSegments = relativePath.split('/').filter { it.isNotEmpty() }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // App icon with triple-tap for private folders toggle
                        Image(
                            painter = painterResource(id = R.drawable.ic_app_icon),
                            contentDescription = null,
                            modifier = Modifier
                                .size(28.dp)
                                .pointerInput(Unit) {
                                    detectTapGestures { _ ->
                                        tapCount++
                                        if (tapCount == 1) {
                                            coroutineScope.launch {
                                                delay(maxTapInterval)
                                                if (tapCount < 3) {
                                                    tapCount = 0
                                                }
                                            }
                                        }
                                        if (tapCount >= 3) {
                                            tapCount = 0
                                            onPasswordDialog()
                                        }
                                    }
                                }
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "/",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.padding(horizontal = 2.dp)
                        )

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            itemsIndexed(pathSegments) { index, segment ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // Resolve real name if segment is an obfuscated subfolder
                                    val parentFullPath = rootPath + if (index > 0) "/" + pathSegments.take(index).joinToString("/") else ""
                                    val segmentFullPath = "$rootPath/${pathSegments.take(index + 1).joinToString("/")}"
                                    val realName = LockedPlaybackSession.getManifestForFolder(parentFullPath)
                                        ?.subfolders?.find { it.obfuscatedName == segment }
                                        ?.originalName
                                    val isHidden = segment.startsWith(".")
                                    val displayName = when {
                                        segmentFullPath == FilesManager.SecureStorage.getNekoPrivateFolderPath() ->
                                            stringResource(R.string.neko_private_folder_name)
                                        realName != null -> realName
                                        isHidden -> segment.drop(1)
                                        else -> segment
                                    }
                                    val isCurrentFolder = index == pathSegments.size - 1

                                    Text(
                                        text = displayName,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = if (isCurrentFolder) FontWeight.Medium else FontWeight.Normal,
                                        color = when {
                                            isHidden -> MaterialTheme.colorScheme.tertiary
                                            isCurrentFolder -> MaterialTheme.colorScheme.onSurface
                                            else -> MaterialTheme.colorScheme.primary
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .clickable(enabled = !isCurrentFolder) {
                                                val targetPath = rootPath + "/" + pathSegments
                                                    .take(index + 1)
                                                    .joinToString("/")
                                                onNavigateToPath(targetPath)
                                            }
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    )

                                    if (index < pathSegments.size - 1) {
                                        Text(
                                            text = "/",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                            modifier = Modifier.padding(horizontal = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                else -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable(enabled = true, onClick = { })
                            .pointerInput(Unit) {
                                detectTapGestures { _ ->
                                    tapCount++
                                    if (tapCount == 1) {
                                        coroutineScope.launch {
                                            delay(maxTapInterval)
                                            if (tapCount < 3) {
                                                tapCount = 0
                                            }
                                        }
                                    }
                                    if (tapCount >= 3) {
                                        tapCount = 0
                                        onPasswordDialog()
                                    }
                                }
                            }
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_app_icon),
                            contentDescription = null,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "NekoVideo",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        },
        navigationIcon = {
            when {
                selectedItems.isNotEmpty() -> {
                    IconButton(onClick = onSelectionClear) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.cancel_selection),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                currentRoute?.startsWith("settings") == true -> {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                currentRoute == "folder" && !isAtRootLevel -> {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        },
        actions = {
            // Botão Cast — visível na tela de pasta, fora do modo seleção
            if (currentRoute == "folder" && selectedItems.isEmpty()) {
                IconButton(onClick = {
                    if (isCasting) {
                        showDisconnectDialog = true
                    } else {
                        discoveredDevices = emptyList()
                        isDiscovering = true
                        showDevicePicker = true
                        castManager.onDevicesFound = { devices ->
                            discoveredDevices = devices
                            isDiscovering = false
                        }
                        castManager.discoverDevices()
                    }
                }) {
                    Icon(
                        imageVector = if (isCasting) Icons.Default.CastConnected else Icons.Default.Cast,
                        contentDescription = "Cast",
                        tint = if (isCasting) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            // Botão SelectAll
            if (selectedItems.isNotEmpty() && currentRoute == "folder") {
                IconButton(onClick = onSelectAll) {
                    Icon(
                        imageVector = Icons.Default.SelectAll,
                        contentDescription = stringResource(R.string.select_all),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurface
        )
    )
}