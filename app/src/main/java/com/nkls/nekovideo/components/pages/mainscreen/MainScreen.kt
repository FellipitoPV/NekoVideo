package com.nkls.nekovideo.components.pages.mainscreen

import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nkls.nekovideo.MainActivity
import com.nkls.nekovideo.MediaPlaybackService
import com.nkls.nekovideo.R
import com.nkls.nekovideo.billing.BillingManager
import com.nkls.nekovideo.billing.PremiumManager
import com.nkls.nekovideo.components.CreateFolderDialog
import com.nkls.nekovideo.components.DeleteConfirmationDialog
import com.nkls.nekovideo.components.FixVideoMetadataDialog
import com.nkls.nekovideo.components.PasswordDialog
import com.nkls.nekovideo.components.ProcessingDialog
import com.nkls.nekovideo.components.LockedRenameDialog
import com.nkls.nekovideo.components.RenameDialog
import com.nkls.nekovideo.components.helpers.VideoRemuxer
import com.nkls.nekovideo.components.SortType
import com.nkls.nekovideo.components.FolderScreen
import com.nkls.nekovideo.components.helpers.FilesManager
import com.nkls.nekovideo.components.helpers.FolderLockManager
import com.nkls.nekovideo.components.helpers.LockedPlaybackSession
import com.nkls.nekovideo.components.helpers.PlaylistManager
import com.nkls.nekovideo.components.helpers.rememberFolderNavigationState
import com.nkls.nekovideo.components.layout.ActionFAB
import com.nkls.nekovideo.components.layout.ActionType
import com.nkls.nekovideo.components.layout.BannerAd
import com.nkls.nekovideo.components.layout.TopBar
import com.nkls.nekovideo.components.loadFolderContent
import com.nkls.nekovideo.components.player.MiniPlayerImproved
import com.nkls.nekovideo.components.player.VideoPlayerOverlay
import com.nkls.nekovideo.components.settings.AboutSettingsScreen
import com.nkls.nekovideo.components.settings.DisplaySettingsScreen
import com.nkls.nekovideo.components.settings.FilesSettingsScreen
import com.nkls.nekovideo.components.settings.InterfaceSettingsScreen
import com.nkls.nekovideo.components.settings.PlaybackSettingsScreen
import com.nkls.nekovideo.components.settings.SettingsScreen
import com.nkls.nekovideo.findActivity
import com.nkls.nekovideo.services.FolderVideoScanner
import com.nkls.nekovideo.theme.ThemeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun MainScreen(
    intent: Intent?,
    themeManager: ThemeManager,
    premiumManager: PremiumManager,
    billingManager: BillingManager,
    notificationReceived: Boolean = false,
    lastAction: String? = null,
    lastTime: Long = 0,
    externalVideoReceived: Boolean = false,
    autoOpenOverlay: Boolean = false

) {
    val navController = rememberNavController()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route?.substringBefore("/{folderPath}")?.substringBefore("/{playlist}")

    // Estado de navegação de pastas (gerencia pilha internamente)
    val folderNavState = rememberFolderNavigationState()
    val folderPath = folderNavState.currentPath
    val isAtRootLevel = folderNavState.isAtRoot
    val selectedItems = remember { mutableStateListOf<String>() }
    var showFabMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var renameTrigger by remember { mutableStateOf(0) }
    val coroutineScope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    var showPlayerOverlay by remember { mutableStateOf(false) }
    var deletedVideoPath by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    // Back press centralizado no BackHandler abaixo

    var isInPiPMode by remember { mutableStateOf(false) }

    var showPrivateFolders by remember {
        mutableStateOf(FilesManager.SecureFoldersVisibility.areSecureFoldersVisible(context))
    }

    var isMoveMode by remember { mutableStateOf(false) }
    var itemsToMove by remember { mutableStateOf<List<String>>(emptyList()) }
    var moveSourceLockedFolder by remember { mutableStateOf<String?>(null) }
    var showFolderActions by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var isLocking by remember { mutableStateOf(false) }
    var isUnlocking by remember { mutableStateOf(false) }
    var lockProgress by remember { mutableStateOf("") }
    // Senha da sessao atual (armazenada apos triple-tap)
    var sessionPassword by remember { mutableStateOf<String?>(null) }
    var showLockPasswordDialog by remember { mutableStateOf(false) }
    var pendingLockAction by remember { mutableStateOf<List<String>?>(null) }
    var pendingActionIsUnlock by remember { mutableStateOf(false) }

    // Estados para correção de metadados de vídeo
    var showFixMetadataDialog by remember { mutableStateOf(false) }
    var videoToFix by remember { mutableStateOf<String?>(null) }
    var isFixingVideo by remember { mutableStateOf(false) }
    var pendingVideoPlayback: (() -> Unit)? by remember { mutableStateOf(null) }

    val isPremium by premiumManager.isPremium.collectAsState()

    fun isSecureFolder(folderPath: String): Boolean {
        val secureFolderPath = FilesManager.SecureStorage.getSecureFolderPath(context)
        return folderPath.startsWith(secureFolderPath) ||
                folderPath.contains("/.private/") ||
                folderPath.contains("/secure/") ||
                folderPath.contains(".secure_videos") ||
                folderPath.endsWith(".secure_videos") ||
                File(folderPath, ".secure").exists() ||
                File(folderPath, ".nomedia").exists()
    }

    fun togglePrivateFolders() {
        val newState = FilesManager.SecureFoldersVisibility.toggleSecureFoldersVisibility(context)
        showPrivateFolders = newState
        renameTrigger++
    }


    // ✅ Função de refresh simplificada
    fun quickRefresh() {
        coroutineScope.launch {
            FolderVideoScanner.startScan(context, coroutineScope, forceRefresh = true)
        }
    }

    fun performRefresh() {
        coroutineScope.launch {
            try {
                FolderVideoScanner.startScan(context, coroutineScope)

                while (FolderVideoScanner.isScanning.value) {
                    delay(100)
                }

                delay(300)
            } catch (e: Exception) {
                Log.e("MainScreen", "Erro no refresh", e)
            }
        }
    }

    val currentTheme by themeManager.themeMode.collectAsState()
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current

    // ✅ DETECTOR DE MODO PIP
    LaunchedEffect(Unit) {
        val activity = context.findActivity() as? MainActivity
        if (activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Observar mudanças de configuração
            while (true) {
                isInPiPMode = activity.isInPiPMode
                delay(200)
            }
        }
    }

    // ✅ Ao clicar na notificação, NÃO abre o overlay automaticamente
    // O usuário pode usar o mini player e abrir o overlay se desejar
    // (Código removido para evitar problema de navegação com back press)

    LaunchedEffect(currentTheme, configuration.uiMode, showPlayerOverlay) {
        if (!showPlayerOverlay) {
            val activity = context.findActivity() as? ComponentActivity
            if (activity != null) {
                val isDarkTheme = when (currentTheme) {
                    "light" -> false
                    "dark" -> true
                    "system" -> {
                        val nightModeFlags = configuration.uiMode and
                                android.content.res.Configuration.UI_MODE_NIGHT_MASK
                        nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
                    }
                    else -> {
                        val nightModeFlags = configuration.uiMode and
                                android.content.res.Configuration.UI_MODE_NIGHT_MASK
                        nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
                    }
                }

                activity.enableEdgeToEdge(
                    statusBarStyle = if (isDarkTheme) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT
                        )
                    },
                    navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK)
                )
            }
        }
    }

    // BackHandlers movidos para DEPOIS do NavHost para ter prioridade (ordem LIFO)

    LaunchedEffect(showPlayerOverlay) {
        val activity = context.findActivity()
        if (activity is MainActivity) {
            activity.keepScreenOn(showPlayerOverlay)
        }
    }

    LaunchedEffect(externalVideoReceived) {
        if (externalVideoReceived) {

            // Aguardar um pouco para garantir que tudo carregou
            delay(500)

            // Abrir overlay diretamente
            showPlayerOverlay = true

            // Reset da flag
            val activity = context.findActivity() as? MainActivity
            activity?.resetExternalVideoFlag()
        }
    }

    LaunchedEffect(autoOpenOverlay) {
        if (autoOpenOverlay) {
            delay(100) // Pequeno delay para estabilizar
            showPlayerOverlay = true
        }
    }

    if (showRenameDialog) {
        val isInsideLockedForRename = FolderLockManager.isLocked(folderPath) &&
                LockedPlaybackSession.isActive &&
                LockedPlaybackSession.hasSessionForFolder(folderPath)

        if (isInsideLockedForRename && selectedItems.size == 1) {
            // Rename inside locked folder: only update the original name in manifest
            val obfuscatedPath = selectedItems.first()
            val obfuscatedName = File(obfuscatedPath).name
            val currentOriginalName = LockedPlaybackSession.getOriginalName(obfuscatedName) ?: obfuscatedName

            LockedRenameDialog(
                currentName = currentOriginalName,
                onDismiss = { showRenameDialog = false },
                onRename = { newName ->
                    coroutineScope.launch {
                        val pwd = sessionPassword ?: LockedPlaybackSession.sessionPassword
                        if (pwd != null) {
                            withContext(Dispatchers.IO) {
                                val updatedManifest = FolderLockManager.renameFileInManifest(
                                    folderPath, obfuscatedName, newName, pwd
                                )
                                if (updatedManifest != null) {
                                    LockedPlaybackSession.updateManifest(folderPath, updatedManifest)
                                }
                            }
                        }
                        showRenameDialog = false
                        selectedItems.clear()
                        renameTrigger++
                        quickRefresh()
                    }
                }
            )
        } else {
            RenameDialog(
                selectedItems = selectedItems.toList(),
                onDismiss = { showRenameDialog = false },
                onComplete = {
                    selectedItems.clear()
                    renameTrigger++
                    quickRefresh()
                },
                onRefresh = ::performRefresh
            )
        }
    }

    if (showPasswordDialog) {
        PasswordDialog(
            onDismiss = { showPasswordDialog = false },
            onPasswordVerified = { password ->
                sessionPassword = password
                val encodedFolderPath = currentBackStackEntry?.arguments?.getString("folderPath") ?: ""
                val isAtRoot = encodedFolderPath == "root" || isAtRootLevel

                if (showPrivateFolders) {
                    FilesManager.SecureFoldersVisibility.hideSecureFolders(context)
                    showPrivateFolders = false
                    sessionPassword = null
                    Toast.makeText(context, context.getString(R.string.secure_folders_hidden), Toast.LENGTH_SHORT).show()
                } else {
                    FilesManager.SecureFoldersVisibility.showSecureFolders(context)
                    showPrivateFolders = true
                    Toast.makeText(context, context.getString(R.string.secure_folders_shown), Toast.LENGTH_SHORT).show()
                }
                renameTrigger++
                showPasswordDialog = false
            }
        )
    }

    // Lock/Unlock password dialog (when sessionPassword is not available)
    if (showLockPasswordDialog && pendingLockAction != null) {
        PasswordDialog(
            onDismiss = {
                showLockPasswordDialog = false
                pendingLockAction = null
                pendingActionIsUnlock = false
            },
            onPasswordVerified = { password ->
                showLockPasswordDialog = false
                sessionPassword = password
                val folders = pendingLockAction ?: return@PasswordDialog
                val isUnlock = pendingActionIsUnlock
                pendingLockAction = null
                pendingActionIsUnlock = false

                coroutineScope.launch {
                    if (isUnlock) {
                        isUnlocking = true
                        withContext(Dispatchers.IO) {
                            folders.forEach { fp ->
                                FolderLockManager.unlockFolder(
                                    context = context,
                                    folderPath = fp,
                                    password = password,
                                    onProgress = { current, total, _ ->
                                        lockProgress = "$current/$total"
                                    },
                                    onError = { message ->
                                        launch(Dispatchers.Main) {
                                            Toast.makeText(context, "Erro: $message", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onSuccess = {
                                        launch(Dispatchers.Main) {
                                            Toast.makeText(context, context.getString(R.string.folder_unlocked_success), Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                            }
                        }
                        isUnlocking = false
                    } else {
                        isLocking = true
                        withContext(Dispatchers.IO) {
                            folders.forEach { fp ->
                                FolderLockManager.lockFolder(
                                    context = context,
                                    folderPath = fp,
                                    password = password,
                                    onProgress = { current, total, _ ->
                                        lockProgress = "$current/$total"
                                    },
                                    onError = { message ->
                                        launch(Dispatchers.Main) {
                                            Toast.makeText(context, "Erro: $message", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onSuccess = {
                                        launch(Dispatchers.Main) {
                                            Toast.makeText(context, context.getString(R.string.folder_locked_success), Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                            }
                        }
                        isLocking = false
                    }
                    lockProgress = ""
                    selectedItems.clear()
                    renameTrigger++
                    quickRefresh()
                }
            }
        )
    }


    // Lock progress dialog
    if (isLocking) {
        ProcessingDialog(
            title = context.getString(R.string.locking_folder),
            message = context.getString(R.string.locking_progress, lockProgress.substringBefore("/").toIntOrNull() ?: 0, lockProgress.substringAfter("/").toIntOrNull() ?: 0)
        )
    }

    // Unlock progress dialog
    if (isUnlocking) {
        ProcessingDialog(
            title = context.getString(R.string.unlocking_folder),
            message = context.getString(R.string.unlocking_progress, lockProgress.substringBefore("/").toIntOrNull() ?: 0, lockProgress.substringAfter("/").toIntOrNull() ?: 0)
        )
    }

    if (showCreateFolderDialog) {
        val isInsideLockedForCreate = FolderLockManager.isLocked(folderPath) &&
                LockedPlaybackSession.isActive &&
                LockedPlaybackSession.hasSessionForFolder(folderPath)

        CreateFolderDialog(
            currentPath = folderPath,
            isInsideLockedFolder = isInsideLockedForCreate,
            onDismiss = { showCreateFolderDialog = false },
            onFolderCreated = { folderName ->
                if (isInsideLockedForCreate) {
                    // Create locked subfolder using session password
                    val pwd = sessionPassword ?: LockedPlaybackSession.sessionPassword
                    if (pwd != null) {
                        coroutineScope.launch {
                            val subfolderPath = File(folderPath, folderName).absolutePath
                            val success = withContext(Dispatchers.IO) {
                                FolderLockManager.createEmptyLockedFolder(context, subfolderPath, pwd)
                            }
                            if (success) {
                                Toast.makeText(context, context.getString(R.string.folder_created), Toast.LENGTH_SHORT).show()
                            }
                            renameTrigger++
                            quickRefresh()
                        }
                    }
                } else {
                    renameTrigger++
                    Toast.makeText(context, context.getString(R.string.folder_created), Toast.LENGTH_SHORT).show()
                    quickRefresh()
                }
            },
            onRefresh = ::performRefresh
        )
    }


    if (showDeleteConfirmDialog) {
        DeleteConfirmationDialog(
            itemCount = selectedItems.size,
            onDismiss = { showDeleteConfirmDialog = false },
            onConfirm = {
                showDeleteConfirmDialog = false
                coroutineScope.launch {
                    val isInsideLocked = FolderLockManager.isLocked(folderPath) &&
                            LockedPlaybackSession.isActive &&
                            LockedPlaybackSession.hasSessionForFolder(folderPath)

                    if (isInsideLocked) {
                        // Delete files/subfolders from locked folder
                        val pwd = sessionPassword ?: LockedPlaybackSession.sessionPassword
                        if (pwd != null) {
                            withContext(Dispatchers.IO) {
                                selectedItems.toList().forEach { itemPath ->
                                    val file = File(itemPath)
                                    if (file.isDirectory) {
                                        // Deleting a locked subfolder: clean up session, registry, then delete recursively
                                        if (FolderLockManager.isLocked(itemPath)) {
                                            LockedPlaybackSession.removeSession(itemPath)
                                        }
                                        file.deleteRecursively()
                                    } else {
                                        val obfuscatedName = file.name
                                        file.delete()
                                        val updatedManifest = FolderLockManager.removeFileFromManifest(
                                            context, folderPath, obfuscatedName, pwd
                                        )
                                        if (updatedManifest != null) {
                                            LockedPlaybackSession.updateManifest(folderPath, updatedManifest)
                                        }
                                    }
                                }
                            }
                            launch(Dispatchers.Main) {
                                Toast.makeText(context, context.getString(R.string.items_deleted_locked), Toast.LENGTH_SHORT).show()
                            }
                        }
                        selectedItems.clear()
                        showFabMenu = false
                        renameTrigger++
                        quickRefresh()
                    } else if (currentRoute == "secure_folder") {
                        FilesManager.deleteSecureSelectedItems(
                            context = context,
                            selectedItems = selectedItems.toList(),
                            secureFolderPath = FilesManager.SecureStorage.getSecureFolderPath(context),
                            onError = { message ->
                                launch(Dispatchers.Main) {
                                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                }
                            },
                            onSuccess = { message ->
                                launch(Dispatchers.Main) {
                                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                }
                                selectedItems.clear()
                                showFabMenu = false
                                renameTrigger++
                                quickRefresh()
                            },
                            onRefresh = ::performRefresh
                        )
                    } else {
                        FilesManager.deleteSelectedItems(
                            context = context,
                            selectedItems = selectedItems.toList(),
                            onError = { message ->
                                launch(Dispatchers.Main) {
                                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                }
                            },
                            onSuccess = { message ->
                                launch(Dispatchers.Main) {
                                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                }
                                selectedItems.clear()
                                showFabMenu = false
                                renameTrigger++
                                quickRefresh()
                            },
                            onRefresh = ::performRefresh
                        )
                    }
                }
            }
        )
    }

    // Diálogo para corrigir metadados do vídeo
    if (showFixMetadataDialog && videoToFix != null) {
        FixVideoMetadataDialog(
            videoPath = videoToFix!!,
            onDismiss = {
                showFixMetadataDialog = false
                videoToFix = null
                pendingVideoPlayback = null
            },
            onConfirm = {
                showFixMetadataDialog = false
                isFixingVideo = true

                coroutineScope.launch {
                    val result = VideoRemuxer.remuxVideo(
                        context = context,
                        inputPath = videoToFix!!
                    )

                    isFixingVideo = false

                    when (result) {
                        is VideoRemuxer.RemuxResult.Success -> {
                            // Atualiza o cache do scanner após fix
                            FolderVideoScanner.startScan(context, coroutineScope, forceRefresh = true)

                            Toast.makeText(
                                context,
                                context.getString(R.string.fix_video_success),
                                Toast.LENGTH_SHORT
                            ).show()

                            // Reproduz o vídeo após corrigir
                            pendingVideoPlayback?.invoke()
                        }
                        is VideoRemuxer.RemuxResult.Error -> {
                            Toast.makeText(
                                context,
                                "${context.getString(R.string.fix_video_error)}: ${result.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }

                    videoToFix = null
                    pendingVideoPlayback = null
                }
            }
        )
    }

    // Diálogo de loading durante correção do vídeo
    if (isFixingVideo) {
        ProcessingDialog(
            title = context.getString(R.string.fix_video_metadata_title),
            message = context.getString(R.string.fixing_video)
        )
    }

    Scaffold(
        topBar = {
            if (currentRoute != "video_player" && !showPlayerOverlay) {
                TopBar(
                    currentRoute = currentRoute,
                    selectedItems = selectedItems.toList(),
                    folderPath = folderPath,
                    navController = navController,
                    onPasswordDialog = {
                        if (showPrivateFolders) {
                            togglePrivateFolders()
                            Toast.makeText(context, context.getString(R.string.secure_folders_hidden), Toast.LENGTH_SHORT).show()
                        } else {
                            showPasswordDialog = true
                        }
                    },
                    onSelectionClear = {
                        selectedItems.clear()
                        showFabMenu = false
                        showRenameDialog = false
                    },
                    onSelectAll = {
                        if (currentRoute == "folder") {
                            val isSecure = isSecureFolder(folderPath)
                            val isRoot = isAtRootLevel

                            val allItems = loadFolderContent(
                                context = context,
                                folderPath = folderPath,
                                sortType = SortType.NAME_ASC,
                                isSecureMode = isSecure,
                                isRootLevel = isRoot,
                                showPrivateFolders = showPrivateFolders
                            )
                            selectedItems.clear()
                            selectedItems.addAll(allItems.map { it.path })
                        }
                    },
                    premiumManager = premiumManager,
                    billingManager = billingManager,
                    isAtRootLevel = isAtRootLevel,
                    onNavigateToPath = { path ->
                        selectedItems.clear()
                        folderNavState.navigateToPath(path)
                    },
                    onNavigateBack = {
                        selectedItems.clear()
                        folderNavState.navigateBack()
                    }
                )
            }
        },
        floatingActionButton = {
            if (currentRoute != "video_player" && currentRoute?.startsWith("settings") != true && !showPlayerOverlay) {
                Box(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))) {
                ActionFAB(
                    hasSelectedItems = selectedItems.isNotEmpty(),
                    isMoveMode = isMoveMode,
                    isSecureMode = isSecureFolder(folderPath),
                    isRootDirectory = isAtRootLevel,
                    selectedItems = selectedItems.toList(),
                    itemsToMoveCount = itemsToMove.size,
                    isInsideLockedFolder = FolderLockManager.isLocked(folderPath) && LockedPlaybackSession.isActive && LockedPlaybackSession.hasSessionForFolder(folderPath),
                    onActionClick = { action ->
                        when (action) {
                            ActionType.SETTINGS -> {
                                navController.navigate("settings")
                            }
                            ActionType.UNLOCK -> { /* Removed - use MOVE instead */ }
                            ActionType.SECURE -> {
                                coroutineScope.launch {
                                    val securePath = FilesManager.SecureStorage.getSecureFolderPath(context)
                                    if (FilesManager.SecureStorage.ensureSecureFolderExists(context)) {
                                        val itemsToSecure = selectedItems.toList()
                                        // Track folders for auto-lock after move
                                        val foldersToAutoLock = itemsToSecure
                                            .filter { File(it).isDirectory }
                                            .map { File(securePath, File(it).name).absolutePath }

                                        FilesManager.moveSelectedItems(
                                            context = context,
                                            selectedItems = itemsToSecure,
                                            destinationPath = securePath,
                                            onError = { message ->
                                                launch(Dispatchers.Main) {
                                                    Toast.makeText(context, "Error: $message", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            onSuccess = { _ ->
                                                launch(Dispatchers.Main) {
                                                    Toast.makeText(context, context.getString(R.string.files_secured), Toast.LENGTH_SHORT).show()
                                                }

                                                // Auto-lock moved folders if session password available
                                                val pwd = sessionPassword
                                                if (pwd != null && foldersToAutoLock.isNotEmpty()) {
                                                    isLocking = true
                                                    foldersToAutoLock.forEach { movedPath ->
                                                        val movedFolder = File(movedPath)
                                                        if (movedFolder.exists() && movedFolder.isDirectory) {
                                                            FolderLockManager.lockFolder(
                                                                context = context,
                                                                folderPath = movedPath,
                                                                password = pwd,
                                                                onProgress = { current, total, _ ->
                                                                    lockProgress = "$current/$total"
                                                                },
                                                                onError = { msg ->
                                                                    launch(Dispatchers.Main) {
                                                                        Toast.makeText(context, "Erro ao trancar: $msg", Toast.LENGTH_SHORT).show()
                                                                    }
                                                                },
                                                                onSuccess = {
                                                                    launch(Dispatchers.Main) {
                                                                        Toast.makeText(context, context.getString(R.string.folder_locked_success), Toast.LENGTH_SHORT).show()
                                                                    }
                                                                }
                                                            )
                                                        }
                                                    }
                                                    isLocking = false
                                                    lockProgress = ""
                                                }

                                                selectedItems.clear()
                                                renameTrigger++
                                                quickRefresh()
                                            },
                                            onRefresh = ::performRefresh
                                        )
                                    }
                                }
                            }
                            ActionType.PRIVATIZE -> {
                                val foldersToLock = selectedItems.filter { path ->
                                    val file = File(path)
                                    file.isDirectory && !FolderLockManager.isLocked(path)
                                }
                                if (foldersToLock.isNotEmpty()) {
                                    val pwd = sessionPassword
                                    if (pwd != null) {
                                        coroutineScope.launch {
                                            isLocking = true
                                            withContext(Dispatchers.IO) {
                                                foldersToLock.forEach { fp ->
                                                    FolderLockManager.lockFolder(
                                                        context = context,
                                                        folderPath = fp,
                                                        password = pwd,
                                                        onProgress = { current, total, _ ->
                                                            lockProgress = "$current/$total"
                                                        },
                                                        onError = { message ->
                                                            launch(Dispatchers.Main) {
                                                                Toast.makeText(context, "Erro: $message", Toast.LENGTH_SHORT).show()
                                                            }
                                                        },
                                                        onSuccess = {
                                                            launch(Dispatchers.Main) {
                                                                Toast.makeText(context, context.getString(R.string.folder_locked_success), Toast.LENGTH_SHORT).show()
                                                            }
                                                        }
                                                    )
                                                }
                                            }
                                            isLocking = false
                                            lockProgress = ""
                                            selectedItems.clear()
                                            renameTrigger++
                                            quickRefresh()
                                        }
                                    } else {
                                        pendingLockAction = foldersToLock
                                        pendingActionIsUnlock = false
                                        showLockPasswordDialog = true
                                    }
                                }
                            }
                            ActionType.UNPRIVATIZE -> {
                                val foldersToUnlock = selectedItems.filter { path ->
                                    FolderLockManager.isLocked(path)
                                }
                                if (foldersToUnlock.isNotEmpty()) {
                                    val pwd = sessionPassword
                                    if (pwd != null) {
                                        coroutineScope.launch {
                                            isUnlocking = true
                                            withContext(Dispatchers.IO) {
                                                foldersToUnlock.forEach { fp ->
                                                    FolderLockManager.unlockFolder(
                                                        context = context,
                                                        folderPath = fp,
                                                        password = pwd,
                                                        onProgress = { current, total, _ ->
                                                            lockProgress = "$current/$total"
                                                        },
                                                        onError = { message ->
                                                            launch(Dispatchers.Main) {
                                                                Toast.makeText(context, "Erro: $message", Toast.LENGTH_SHORT).show()
                                                            }
                                                        },
                                                        onSuccess = {
                                                            launch(Dispatchers.Main) {
                                                                Toast.makeText(context, context.getString(R.string.folder_unlocked_success), Toast.LENGTH_SHORT).show()
                                                            }
                                                        }
                                                    )
                                                }
                                            }
                                            isUnlocking = false
                                            lockProgress = ""
                                            selectedItems.clear()
                                            renameTrigger++
                                            quickRefresh()
                                        }
                                    } else {
                                        pendingLockAction = foldersToUnlock
                                        pendingActionIsUnlock = true
                                        showLockPasswordDialog = true
                                    }
                                }
                            }
                            ActionType.DELETE -> showDeleteConfirmDialog = true
                            ActionType.RENAME -> showRenameDialog = true
                            ActionType.MOVE -> {
                                val isFromLocked = FolderLockManager.isLocked(folderPath) &&
                                        LockedPlaybackSession.isActive &&
                                        LockedPlaybackSession.hasSessionForFolder(folderPath)
                                moveSourceLockedFolder = if (isFromLocked) folderPath else null
                                itemsToMove = selectedItems.toList()
                                selectedItems.clear()
                                isMoveMode = true
                                Toast.makeText(context, context.getString(R.string.move_mode_activated), Toast.LENGTH_SHORT).show()
                            }
                            ActionType.CANCEL_MOVE -> {
                                isMoveMode = false
                                itemsToMove = emptyList()
                                moveSourceLockedFolder = null
                                Toast.makeText(context, context.getString(R.string.move_operation_cancelled), Toast.LENGTH_SHORT).show()
                            }
                            ActionType.SHUFFLE_PLAY -> {
                                coroutineScope.launch {
                                    val videos = FilesManager.getVideosRecursive(
                                        context = context,
                                        folderPath = folderPath,
                                        isSecureMode = isSecureFolder(folderPath),
                                        showPrivateFolders = showPrivateFolders,
                                        selectedItems = selectedItems.toList(),
                                        sessionPassword = sessionPassword
                                    )

                                    if (videos.isNotEmpty()) {
                                        // Register ALL locked folders for playback (supports multi-folder playlists)
                                        val lockedVideos = videos.filter { it.startsWith("locked://") }
                                        if (lockedVideos.isNotEmpty()) {
                                            val pwd = sessionPassword
                                            if (pwd != null) {
                                                val lockedFolderPaths = lockedVideos
                                                    .map { it.removePrefix("locked://") }
                                                    .mapNotNull { File(it).parent }
                                                    .distinct()

                                                for (lockedPath in lockedFolderPaths) {
                                                    if (!LockedPlaybackSession.hasSessionForFolder(lockedPath)) {
                                                        val salt = FolderLockManager.getSalt(lockedPath)
                                                        val manifest = FolderLockManager.readManifest(lockedPath, pwd)
                                                        if (salt != null && manifest != null) {
                                                            val xorKey = FolderLockManager.deriveXorKey(pwd, salt)
                                                            LockedPlaybackSession.start(xorKey, manifest, lockedPath, pwd)
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        PlaylistManager.setPlaylist(videos, startIndex = 0, shuffle = true)
                                        val window = PlaylistManager.getCurrentWindow()

                                        MediaPlaybackService.startWithPlaylist(context, window, 0)
                                        showPlayerOverlay = true
                                        selectedItems.clear()
                                    } else {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.no_videos_found),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                            ActionType.CREATE_FOLDER -> showCreateFolderDialog = true
                            ActionType.PASTE -> {
                                coroutineScope.launch {
                                    val movedItems = itemsToMove.toList()
                                    val destIsLocked = FolderLockManager.isLocked(folderPath)
                                    val sourceLockedFolder = moveSourceLockedFolder
                                    val pwd = sessionPassword ?: LockedPlaybackSession.sessionPassword

                                    // If items come from a locked folder, extract them first
                                    val actualItemsToMove = if (sourceLockedFolder != null && pwd != null) {
                                        withContext(Dispatchers.IO) {
                                            val obfuscatedNames = movedItems.map { File(it).name }
                                            val (restoredFiles, updatedManifest) = FolderLockManager.extractFilesFromLockedFolder(
                                                context, sourceLockedFolder, obfuscatedNames, pwd
                                            )
                                            if (updatedManifest != null) {
                                                LockedPlaybackSession.updateManifest(sourceLockedFolder, updatedManifest)
                                            }
                                            restoredFiles.map { it.absolutePath }
                                        }
                                    } else {
                                        movedItems
                                    }

                                    if (actualItemsToMove.isEmpty()) {
                                        launch(Dispatchers.Main) {
                                            Toast.makeText(context, "Error extracting files", Toast.LENGTH_SHORT).show()
                                        }
                                        itemsToMove = emptyList()
                                        isMoveMode = false
                                        moveSourceLockedFolder = null
                                        renameTrigger++
                                        quickRefresh()
                                        return@launch
                                    }

                                    FilesManager.moveSelectedItems(
                                        context = context,
                                        selectedItems = actualItemsToMove,
                                        destinationPath = folderPath,
                                        onError = { message ->
                                            launch(Dispatchers.Main) {
                                                Toast.makeText(context, "Error: $message", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        onSuccess = { _ ->
                                            launch(Dispatchers.Main) {
                                                Toast.makeText(context, context.getString(R.string.items_moved), Toast.LENGTH_SHORT).show()
                                            }

                                            // Auto-lock new files if pasted into a locked folder
                                            if (destIsLocked && pwd != null) {
                                                val newFiles = actualItemsToMove.map { File(folderPath, File(it).name) }
                                                isLocking = true
                                                FolderLockManager.addFilesToLockedFolder(
                                                    context = context,
                                                    folderPath = folderPath,
                                                    password = pwd,
                                                    newFiles = newFiles,
                                                    onProgress = { current, total, _ ->
                                                        lockProgress = "$current/$total"
                                                    },
                                                    onError = { msg ->
                                                        launch(Dispatchers.Main) {
                                                            Toast.makeText(context, "Erro ao trancar: $msg", Toast.LENGTH_SHORT).show()
                                                        }
                                                    },
                                                    onSuccess = {
                                                        launch(Dispatchers.Main) {
                                                            Toast.makeText(context, context.getString(R.string.folder_locked_success), Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                )
                                                isLocking = false
                                                lockProgress = ""

                                                // Update LockedPlaybackSession if active
                                                if (LockedPlaybackSession.isActive && LockedPlaybackSession.hasSessionForFolder(folderPath)) {
                                                    val updatedManifest = FolderLockManager.readManifest(folderPath, pwd)
                                                    val salt = FolderLockManager.getSalt(folderPath)
                                                    if (updatedManifest != null && salt != null) {
                                                        val xorKey = FolderLockManager.deriveXorKey(pwd, salt)
                                                        LockedPlaybackSession.start(xorKey, updatedManifest, folderPath, pwd)
                                                    }
                                                }
                                            }

                                            itemsToMove = emptyList()
                                            isMoveMode = false
                                            moveSourceLockedFolder = null
                                            renameTrigger++
                                            quickRefresh()
                                        },
                                        onRefresh = ::performRefresh
                                    )
                                }
                            }
                            ActionType.SET_AS_SECURE_FOLDER -> {
                                coroutineScope.launch {
                                    val folderPath = selectedItems.first()
                                    FilesManager.SecureStorage.setCustomSecureFolderPath(context, folderPath)

                                    launch(Dispatchers.Main) {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.secure_folder_set),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }

                                    selectedItems.clear()
                                    renameTrigger++
                                    quickRefresh()
                                }
                            }
                        }
                    }
                )
                } // Box navigationBarsPadding
            }
        },
        bottomBar = {
            if (currentRoute != "video_player" && currentRoute?.startsWith("settings") != true && !showPlayerOverlay) {
                Column(
                    modifier = Modifier.navigationBarsPadding()
                ) {
                    MiniPlayerImproved(
                        onOpenPlayer = { showPlayerOverlay = true }
                    )

                    BannerAd(isPremium = isPremium)
                }
            }
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            NavHost(
                navController = navController,
                startDestination = "folder",
                enterTransition = {
                    slideInHorizontally(
                        initialOffsetX = { fullWidth -> fullWidth },
                        animationSpec = tween(durationMillis = 50)
                    )
                },
                exitTransition = {
                    slideOutHorizontally(
                        targetOffsetX = { fullWidth -> -fullWidth },
                        animationSpec = tween(durationMillis = 50)
                    )
                },
                popEnterTransition = {
                    slideInHorizontally(
                        initialOffsetX = { fullWidth -> -fullWidth },
                        animationSpec = tween(durationMillis = 50)
                    )
                },
                popExitTransition = {
                    slideOutHorizontally(
                        targetOffsetX = { fullWidth -> fullWidth },
                        animationSpec = tween(durationMillis = 50)
                    )
                }
            ) {
                // Rota única para pastas - navegação gerenciada por FolderNavigationState
                composable("folder") {
                    val isSecure = isSecureFolder(folderPath)

                    FolderScreen(
                        folderPath = folderPath,
                        isSecureMode = isSecure,
                        isRootLevel = isAtRootLevel,
                        showPrivateFolders = showPrivateFolders,
                        isMoveMode = isMoveMode,
                        itemsToMove = itemsToMove,
                        onFolderClick = { itemPath ->
                            val items = loadFolderContent(
                                context = context,
                                folderPath = folderPath,
                                sortType = SortType.NAME_ASC,
                                isSecureMode = isSecure,
                                isRootLevel = isAtRootLevel,
                                showPrivateFolders = showPrivateFolders
                            )
                            val item = items.find { it.path == itemPath }
                            if (item?.isFolder == true) {
                                // Check if folder is locked - use sessionPassword
                                if (FolderLockManager.isLocked(itemPath)) {
                                    val pwd = sessionPassword ?: LockedPlaybackSession.sessionPassword
                                    if (pwd != null) {
                                        coroutineScope.launch {
                                            val manifest = withContext(Dispatchers.IO) {
                                                FolderLockManager.readManifest(itemPath, pwd)
                                            }
                                            if (manifest != null) {
                                                val salt = withContext(Dispatchers.IO) {
                                                    FolderLockManager.getSalt(itemPath)
                                                }
                                                if (salt != null) {
                                                    val xorKey = withContext(Dispatchers.IO) {
                                                        FolderLockManager.deriveXorKey(pwd, salt)
                                                    }
                                                    LockedPlaybackSession.start(xorKey, manifest, itemPath, pwd)
                                                    folderNavState.navigateTo(itemPath)
                                                }
                                            } else {
                                                Toast.makeText(context, context.getString(R.string.invalid_password_or_corrupted), Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                } else {
                                    folderNavState.navigateTo(itemPath)
                                }
                            } else {
                                // Função para reproduzir o vídeo
                                val playVideo = {
                                    // Check if we're in a locked folder session
                                    if (LockedPlaybackSession.isActive && LockedPlaybackSession.hasSessionForFolder(folderPath)) {
                                        // Playing from locked folder - use locked:// URI scheme
                                        val videos = items.filter { !it.isFolder }.map { "locked://${it.path}" }
                                        val clickedVideoIndex = videos.indexOf("locked://$itemPath")
                                        if (clickedVideoIndex >= 0) {
                                            PlaylistManager.setPlaylist(videos, startIndex = clickedVideoIndex, shuffle = false)
                                            val window = PlaylistManager.getCurrentWindow()
                                            val windowIndex = PlaylistManager.getCurrentIndexInWindow()
                                            MediaPlaybackService.startWithPlaylist(context, window, windowIndex)
                                            showPlayerOverlay = true
                                        }
                                    } else {
                                        val videos = items.filter { !it.isFolder }.map { "file://${it.path}" }
                                        val clickedVideoIndex = videos.indexOf("file://$itemPath")
                                        if (clickedVideoIndex >= 0) {
                                            PlaylistManager.setPlaylist(videos, startIndex = clickedVideoIndex, shuffle = false)
                                            val window = PlaylistManager.getCurrentWindow()
                                            val windowIndex = PlaylistManager.getCurrentIndexInWindow()
                                            MediaPlaybackService.startWithPlaylist(context, window, windowIndex)
                                            showPlayerOverlay = true
                                        } else {
                                            val videoUri = "file://$itemPath"
                                            PlaylistManager.setPlaylist(listOf(videoUri), startIndex = 0, shuffle = false)
                                            MediaPlaybackService.startWithPlaylist(context, listOf(videoUri), 0)
                                            showPlayerOverlay = true
                                        }
                                    }
                                }

                                playVideo()
                            }
                        },
                        selectedItems = selectedItems,
                        onSelectionChange = { newSelection ->
                            selectedItems.clear()
                            selectedItems.addAll(newSelection)
                            showFabMenu = false
                            showRenameDialog = false
                        },
                        renameTrigger = renameTrigger,
                        deletedVideoPath = deletedVideoPath
                    )
                }

                composable("settings") {
                    SettingsScreen(navController)
                }
                composable("settings/playback") {
                    PlaybackSettingsScreen()
                }
                composable("settings/interface") {
                    // MODIFICADO: Passar themeManager para InterfaceSettingsScreen
                    InterfaceSettingsScreen(themeManager)
                }
                composable("settings/about") {
                    AboutSettingsScreen()
                }
                composable("settings/display") {
                    DisplaySettingsScreen()
                }
                composable("settings/files") {
                    FilesSettingsScreen()
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════════
        // BackHandlers DEPOIS do NavHost para ter prioridade (ordem LIFO)
        // O último BackHandler registrado é o primeiro a ser verificado
        // ═══════════════════════════════════════════════════════════════════

        // BackHandler para navegação de pastas (volta para pasta anterior)
        BackHandler(enabled = !showPlayerOverlay && !isAtRootLevel && currentRoute == "folder") {
            Log.d("BackDebug", "🔙 BACK PRESSED - Handler: FOLDER NAVIGATION")
            Log.d("BackDebug", "   Ação: Voltando para pasta anterior")
            // Limpa seleção ao voltar
            if (selectedItems.isNotEmpty()) {
                selectedItems.clear()
            }
            // When navigating back from a locked subfolder, update currentFolderPath to parent
            val parentFolder = File(folderPath).parent
            if (parentFolder != null && FolderLockManager.isLocked(folderPath) &&
                LockedPlaybackSession.hasSessionForFolder(folderPath)) {
                if (LockedPlaybackSession.hasSessionForFolder(parentFolder)) {
                    LockedPlaybackSession.setCurrentFolder(parentFolder)
                }
            }
            folderNavState.navigateBack()
        }

        // BackHandler para ignorar voltar na root (não fecha o app)
        BackHandler(enabled = !showPlayerOverlay && isAtRootLevel && currentRoute == "folder") {
            // Não fazer nada - apenas consumir o evento para não fechar o app
        }

        // BackHandler para o overlay - PRIORIDADE MÁXIMA (registrado por último)
        BackHandler(enabled = showPlayerOverlay) {
            Log.d("BackDebug", "🔙 BACK PRESSED - Handler: OVERLAY (após NavHost)")
            Log.d("BackDebug", "   Ação: Fechando overlay")
            showPlayerOverlay = false
        }

        VideoPlayerOverlay(
            isVisible = showPlayerOverlay,
            canControlRotation = showPlayerOverlay,
            onDismiss = {
                showPlayerOverlay = false
                isInPiPMode = false
            },
            onVideoDeleted = { deletedPath ->
                deletedVideoPath = deletedPath
                CoroutineScope(Dispatchers.Main).launch {
                    delay(100)
                    deletedVideoPath = null
                }
            },
            premiumManager = premiumManager
        )

    }
}