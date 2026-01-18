package com.nkls.nekovideo.components.pages.mainscreen

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
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
import com.nkls.nekovideo.components.PasswordDialog
import com.nkls.nekovideo.components.RenameDialog
import com.nkls.nekovideo.components.SortType
import com.nkls.nekovideo.components.FolderScreen
import com.nkls.nekovideo.components.helpers.FilesManager
import com.nkls.nekovideo.components.helpers.FolderNavigationState
import com.nkls.nekovideo.components.helpers.PlaylistManager
import com.nkls.nekovideo.components.helpers.rememberFolderNavigationState
import com.nkls.nekovideo.components.layout.ActionFAB
import com.nkls.nekovideo.components.layout.ActionType
import com.nkls.nekovideo.components.layout.BannerAd
import com.nkls.nekovideo.components.layout.TopBar
import com.nkls.nekovideo.components.loadFolderContent
import com.nkls.nekovideo.components.loadFolderContentRecursive
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
    var showFolderActions by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }

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

    LaunchedEffect(currentTheme, configuration.uiMode, showPlayerOverlay) { // Adicione showPlayerOverlay
        // SÓ EXECUTE SE O PLAYER NÃO ESTIVER ABERTO
        if (!showPlayerOverlay) {
            val activity = context.findActivity()
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

                // Status bar: acompanha o tema
                val statusBarColor = if (isDarkTheme) Color(0xFF121212) else Color(0xFFF5F5F5)
                activity.window.statusBarColor = statusBarColor.toArgb()

                // Navigation bar: SEMPRE preta com ícones brancos
                activity.window.navigationBarColor = Color(0xFF000000).toArgb()

                val insetsController = WindowCompat.getInsetsController(activity.window, activity.window.decorView)

                // Status bar: ícones acompanham o tema
                insetsController.isAppearanceLightStatusBars = !isDarkTheme

                // Navigation bar: SEMPRE ícones brancos
                insetsController.isAppearanceLightNavigationBars = false
            }
        }
    }

    LaunchedEffect(Unit) {
        // Pequeno delay após recreate para garantir que tudo foi carregado
        delay(200)

        // Força atualização do tema
        themeManager.forceStatusBarUpdate()
    }

    // Log do estado atual a cada recomposição relevante
    LaunchedEffect(showPlayerOverlay, currentRoute, folderPath, showFolderActions, selectedItems.size) {
        Log.d("BackDebug", "═══════════════════════════════════════")
        Log.d("BackDebug", "📊 Estado atual:")
        Log.d("BackDebug", "   showPlayerOverlay: $showPlayerOverlay")
        Log.d("BackDebug", "   currentRoute: $currentRoute")
        Log.d("BackDebug", "   folderPath: $folderPath")
        Log.d("BackDebug", "   selectedItems: ${selectedItems.size}")
        Log.d("BackDebug", "   showFolderActions: $showFolderActions")
        Log.d("BackDebug", "───────────────────────────────────────")
        Log.d("BackDebug", "🎯 BackHandler OVERLAY (após NavHost): enabled=$showPlayerOverlay")
        Log.d("BackDebug", "═══════════════════════════════════════")
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
        RenameDialog(
            selectedItems = selectedItems.toList(),
            onDismiss = { showRenameDialog = false },
            onComplete = {
                selectedItems.clear()
                renameTrigger++
                quickRefresh()
            },
            onRefresh = ::performRefresh  // ✅ ADICIONAR
        )
    }

    if (showPasswordDialog) {
        PasswordDialog(
            onDismiss = { showPasswordDialog = false },
            onPasswordVerified = {
                val encodedFolderPath = currentBackStackEntry?.arguments?.getString("folderPath") ?: ""
                val isAtRoot = encodedFolderPath == "root" || isAtRootLevel

                if (isAtRoot) {
                    // ✅ MODIFICADO: Usar as funções do FilesManager
                    if (showPrivateFolders) {
                        // Se já estão visíveis, esconder
                        FilesManager.SecureFoldersVisibility.hideSecureFolders(context)
                        showPrivateFolders = false
                        Toast.makeText(context, context.getString(R.string.secure_folders_hidden), Toast.LENGTH_SHORT).show()

                    } else {
                        // Se não estão visíveis, mostrar
                        FilesManager.SecureFoldersVisibility.showSecureFolders(context)
                        showPrivateFolders = true
                        Toast.makeText(context, context.getString(R.string.secure_folders_shown), Toast.LENGTH_SHORT).show()
                    }
                    renameTrigger++
                } else {
                    val securePath = FilesManager.SecureStorage.getSecureFolderPath(context)
                    folderNavState.navigateTo(securePath)
                }
                showPasswordDialog = false
            }
        )
    }

    if (showCreateFolderDialog) {
        CreateFolderDialog(
            currentPath = folderPath,
            onDismiss = { showCreateFolderDialog = false },
            onFolderCreated = {
                renameTrigger++
                Toast.makeText(context, context.getString(R.string.folder_created), Toast.LENGTH_SHORT).show()
                quickRefresh()
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
                    if (currentRoute == "secure_folder") {
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

    Scaffold(
        topBar = {
            if (currentRoute != "video_player" && !showPlayerOverlay) {
                TopBar(
                    currentRoute = currentRoute,
                    selectedItems = selectedItems.toList(),
                    folderPath = folderPath,
                    context = context,
                    navController = navController,
                    showPrivateFolders = showPrivateFolders,
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
                ActionFAB(
                    hasSelectedItems = selectedItems.isNotEmpty(),
                    isMoveMode = isMoveMode,
                    isSecureMode = isSecureFolder(folderPath),
                    isRootDirectory = isAtRootLevel,
                    selectedItems = selectedItems.toList(),
                    itemsToMoveCount = itemsToMove.size,
                    onActionClick = { action ->
                        when (action) {
                            ActionType.SETTINGS -> {
                                navController.navigate("settings")
                            }
                            ActionType.UNLOCK -> {
                                coroutineScope.launch {
                                    val unlockedPath = "/storage/emulated/0/DCIM/Unlocked"
                                    if (FilesManager.ensureUnlockedFolderExists()) {
                                        FilesManager.moveSelectedItems(
                                            context = context,
                                            selectedItems = selectedItems.toList(),
                                            destinationPath = unlockedPath,
                                            onError = { message ->
                                                launch(Dispatchers.Main) {
                                                    Toast.makeText(context, "Error: $message", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            onSuccess = { message ->
                                                launch(Dispatchers.Main) {
                                                    Toast.makeText(context, context.getString(R.string.secure_folders_shown), Toast.LENGTH_SHORT).show()
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
                            ActionType.SECURE -> {
                                coroutineScope.launch {
                                    val securePath = FilesManager.SecureStorage.getSecureFolderPath(context)
                                    if (FilesManager.SecureStorage.ensureSecureFolderExists(context)) {
                                        FilesManager.moveSelectedItems(
                                            context = context,
                                            selectedItems = selectedItems.toList(),
                                            destinationPath = securePath,
                                            onError = { message ->
                                                launch(Dispatchers.Main) {
                                                    Toast.makeText(context, "Error: $message", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            onSuccess = { message ->
                                                launch(Dispatchers.Main) {
                                                    Toast.makeText(context, context.getString(R.string.files_secured), Toast.LENGTH_SHORT).show()
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
                                coroutineScope.launch {
                                    val foldersToPrivatize = selectedItems.filter { path ->
                                        FilesManager.canFolderBePrivatized(path)
                                    }

                                    if (foldersToPrivatize.isNotEmpty()) {
                                        withContext(Dispatchers.IO) {
                                            FilesManager.privatizeFolders(
                                                context = context,
                                                selectedFolders = foldersToPrivatize,
                                                onProgress = { current, total -> },
                                                onError = { message ->
                                                    launch(Dispatchers.Main) {
                                                        Toast.makeText(context, "Erro: $message", Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                                onSuccess = { message ->
                                                    launch(Dispatchers.Main) {
                                                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                                    }
                                                    selectedItems.clear()
                                                    renameTrigger++
                                                    quickRefresh()
                                                },
                                                onRefresh = ::performRefresh
                                            )
                                        }
                                    } else {
                                        launch(Dispatchers.Main) {
                                            Toast.makeText(context, context.getString(R.string.select_valid_folders_to_privatize), Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                            ActionType.UNPRIVATIZE -> {
                                coroutineScope.launch {
                                    val foldersToUnprivatize = selectedItems.filter { path ->
                                        FilesManager.isFolderPrivate(path)
                                    }

                                    if (foldersToUnprivatize.isNotEmpty()) {
                                        withContext(Dispatchers.IO) {
                                            FilesManager.unprivatizeFolders(
                                                context = context,
                                                selectedFolders = foldersToUnprivatize,
                                                onProgress = { current, total -> },
                                                onError = { message ->
                                                    launch(Dispatchers.Main) {
                                                        Toast.makeText(context, "Erro: $message", Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                                onSuccess = { message ->
                                                    launch(Dispatchers.Main) {
                                                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                                    }
                                                    selectedItems.clear()
                                                    renameTrigger++
                                                    quickRefresh()
                                                },
                                                onRefresh = ::performRefresh
                                            )
                                        }
                                    } else {
                                        launch(Dispatchers.Main) {
                                            Toast.makeText(context, context.getString(R.string.select_private_folders_to_unprivatize), Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                            ActionType.DELETE -> showDeleteConfirmDialog = true
                            ActionType.RENAME -> showRenameDialog = true
                            ActionType.MOVE -> {
                                itemsToMove = selectedItems.toList()
                                selectedItems.clear()
                                isMoveMode = true
                                Toast.makeText(context, context.getString(R.string.move_mode_activated), Toast.LENGTH_SHORT).show()
                            }
                            ActionType.CANCEL_MOVE -> {
                                isMoveMode = false
                                itemsToMove = emptyList()
                                Toast.makeText(context, context.getString(R.string.move_operation_cancelled), Toast.LENGTH_SHORT).show()
                            }
                            ActionType.SHUFFLE_PLAY -> {
                                coroutineScope.launch {
                                    // ✅ LIMPO: Só coordena
                                    val videos = FilesManager.getVideosRecursive(
                                        context = context,
                                        folderPath = folderPath,
                                        isSecureMode = isSecureFolder(folderPath),
                                        showPrivateFolders = showPrivateFolders,
                                        selectedItems = selectedItems.toList()
                                    )

                                    if (videos.isNotEmpty()) {
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
                                    FilesManager.moveSelectedItems(
                                        context = context,
                                        selectedItems = itemsToMove,
                                        destinationPath = folderPath,
                                        onError = { message ->
                                            launch(Dispatchers.Main) {
                                                Toast.makeText(context, "Error: $message", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        onSuccess = { message ->
                                            launch(Dispatchers.Main) {
                                                Toast.makeText(context, context.getString(R.string.items_moved), Toast.LENGTH_SHORT).show()
                                            }
                                            itemsToMove = emptyList()
                                            isMoveMode = false
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
                                            context.getString(R.string.secure_folder_set), // ou "Pasta segura definida!"
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
                                // Navega para subpasta usando estado (sem criar nova tela)
                                folderNavState.navigateTo(itemPath)
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
            canControlRotation = showPlayerOverlay, // Só pode controlar rotação quando está visível
            onDismiss = {
                showPlayerOverlay = false
                isInPiPMode = false // ✅ ADICIONAR
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