package com.example.nekovideo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.nekovideo.components.CreateFolderDialog
import com.example.nekovideo.components.layout.CustomTopAppBar
import com.example.nekovideo.components.DeleteConfirmationDialog
import com.example.nekovideo.components.OptimizedThumbnailManager
import com.example.nekovideo.components.PasswordDialog
import com.example.nekovideo.components.RenameDialog
import com.example.nekovideo.components.SortType
import com.example.nekovideo.components.SubFolderScreen
import com.example.nekovideo.components.helpers.FilesManager
import com.example.nekovideo.components.layout.ActionBottomSheetFAB
import com.example.nekovideo.components.layout.ActionType
import com.example.nekovideo.components.loadFolderContent
import com.example.nekovideo.components.loadFolderContentRecursive
import com.example.nekovideo.components.player.MediaControllerManager
import com.example.nekovideo.components.player.MiniPlayerImproved
import com.example.nekovideo.components.player.VideoPlayerOverlay
import com.example.nekovideo.components.settings.AboutSettingsScreen
import com.example.nekovideo.components.settings.DisplaySettingsScreen
import com.example.nekovideo.components.settings.FilesSettingsScreen
import com.example.nekovideo.components.settings.InterfaceSettingsScreen
import com.example.nekovideo.components.settings.PerformanceSettingsScreen
import com.example.nekovideo.components.settings.PlaybackSettingsScreen
import com.example.nekovideo.components.settings.SettingsScreen
import com.example.nekovideo.ui.theme.NekoVideoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        enableEdgeToEdge()
        setContent {
            NekoVideoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(intent)
                }
            }
        }

        // ✅ ADICIONE AQUI - Inicia limpeza automática do cache
        OptimizedThumbnailManager.startPeriodicCleanup()
    }

    fun keepScreenOn(keep: Boolean) {
        if (keep) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private val playbackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "PLAYBACK_STATE_CHANGED") {
                val isPlaying = intent.getBooleanExtra("IS_PLAYING", false)
                keepScreenOn(isPlaying)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this,
            playbackReceiver,
            IntentFilter("PLAYBACK_STATE_CHANGED"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(playbackReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver já foi removido
        }
        keepScreenOn(false)

        // ✅ ADICIONE AQUI - Limpeza leve quando app vai para background
        OptimizedThumbnailManager.cancelLoading("")
    }

    // ✅ ADICIONE ESTE MÉTODO INTEIRO:
    override fun onDestroy() {
        super.onDestroy()

        MediaControllerManager.disconnect()

        // Para limpeza automática e limpa todos os caches
        OptimizedThumbnailManager.stopPeriodicCleanup()
        OptimizedThumbnailManager.clearCache()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        setContent {
            NekoVideoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(intent)
                }
            }
        }
    }
}

fun Context.findActivity(): ComponentActivity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is ComponentActivity) return context
        context = context.baseContext
    }
    return null
}

private fun cancelMoveMode(
    isMoveMode: MutableState<Boolean>,
    itemsToMove: MutableState<List<String>>
) {
    isMoveMode.value = false
    itemsToMove.value = emptyList()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(intent: Intent?) {
    val navController = rememberNavController()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route?.substringBefore("/{folderPath}")?.substringBefore("/{playlist}")
    val folderPath = currentBackStackEntry?.arguments?.getString("folderPath")?.let { Uri.decode(it) } ?: ""
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

    var showPrivateFolders by remember { mutableStateOf(false) }

    var isMoveMode by remember { mutableStateOf(false) }
    var itemsToMove by remember { mutableStateOf<List<String>>(emptyList()) }
    var showFolderActions by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }

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

    fun isAtRootLevel(path: String): Boolean {
        val rootPath = android.os.Environment.getExternalStorageDirectory().absolutePath
        val encodedFolderPath = currentBackStackEntry?.arguments?.getString("folderPath") ?: ""
        return encodedFolderPath == "root" || path == rootPath
    }

    // Modificar o LaunchedEffect do intent para mostrar overlay
    LaunchedEffect(intent) {
        if (intent?.action == "OPEN_PLAYER") {
            val playlist = intent.getStringArrayListExtra("PLAYLIST") ?: emptyList()
            val initialIndex = intent.getIntExtra("INITIAL_INDEX", 0)
            if (playlist.isNotEmpty()) {
                // Ao invés de navegar, apenas mostrar o overlay
                showPlayerOverlay = true
            }
            // Limpar a ação para evitar repetição
            intent.action = null
        }
    }

    LaunchedEffect(currentRoute, folderPath, selectedItems, isMoveMode, showPlayerOverlay) {
        val activity = context.findActivity()
        if (activity != null) {
            activity.onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (showPlayerOverlay) {
                        showPlayerOverlay = false
                        return
                    }

                    if (showFolderActions) {
                        showFolderActions = false
                        return
                    }

                    if (selectedItems.isNotEmpty()) {
                        selectedItems.clear()
                        showFabMenu = false
                        showRenameDialog = false
                        return
                    }

                    when {
                        currentRoute == "settings" || currentRoute?.startsWith("settings/") == true -> {
                            navController.popBackStack()
                        }

                        currentRoute == "folder" -> {
                            val rootPath = android.os.Environment.getExternalStorageDirectory().absolutePath
                            val secureFolderPath = FilesManager.SecureStorage.getSecureFolderPath(context)
                            val encodedFolderPath = currentBackStackEntry?.arguments?.getString("folderPath") ?: ""

                            // CORREÇÃO: Distinguir entre pasta dentro da estrutura segura vs pasta privada
                            val isInSecureStructure = folderPath.startsWith(secureFolderPath)
                            val isPrivateFolder = isSecureFolder(folderPath) && !isInSecureStructure

                            // Determinar se está na raiz do contexto atual
                            val isAtContextRoot = when {
                                isInSecureStructure -> folderPath == secureFolderPath
                                else -> folderPath == rootPath || encodedFolderPath == "root"
                            }

                            if (isAtContextRoot) {
                                navController.navigate("folder/root") {
                                    popUpTo("folder/root") { inclusive = true }
                                    launchSingleTop = true
                                }
                            } else {
                                // Calcular caminho pai baseado na estrutura real
                                val parentPath = if (isInSecureStructure) {
                                    // Para pastas dentro da estrutura segura
                                    val withoutSecurePrefix = folderPath.removePrefix(secureFolderPath).trimStart('/')

                                    val pathSegments = withoutSecurePrefix.split('/').filter { it.isNotEmpty() }

                                    if (pathSegments.size <= 1) {
                                        secureFolderPath
                                    } else {
                                        val parentSegments = pathSegments.dropLast(1)
                                        val result = "$secureFolderPath/${parentSegments.joinToString("/")}"
                                        result
                                    }
                                } else {
                                    // Para pastas normais/privadas, usar navegação normal
                                    val withoutRootPrefix = folderPath.removePrefix(rootPath).trimStart('/')

                                    val pathSegments = withoutRootPrefix.split('/').filter { it.isNotEmpty() }

                                    if (pathSegments.size <= 1) {
                                        rootPath
                                    } else {
                                        val parentSegments = pathSegments.dropLast(1)
                                        val result = "$rootPath/${parentSegments.joinToString("/")}"
                                        result
                                    }
                                }


                                // Verificar se o parentPath é uma raiz conhecida
                                val isParentRoot = parentPath == rootPath || parentPath == secureFolderPath

                                if (isParentRoot) {
                                    navController.navigate("folder/root") {
                                        popUpTo("folder/root") { inclusive = true }
                                        launchSingleTop = true
                                    }
                                } else {
                                    val encodedParentPath = Uri.encode(parentPath)
                                    navController.navigate("folder/$encodedParentPath") {
                                        popUpTo("folder/{folderPath}") { inclusive = false }
                                        launchSingleTop = true
                                    }
                                }
                            }
                        }

                        else -> {
                            activity.finish()
                        }
                    }
                }
            })
        }
    }

    // Adicionar este LaunchedEffect após os existentes no MainScreen
    LaunchedEffect(showPlayerOverlay) {
        val activity = context.findActivity()
        if (activity is MainActivity) {
            activity.keepScreenOn(showPlayerOverlay)
        }
    }

    // DIALOGS
    if (showRenameDialog) {
        RenameDialog(
            selectedItems = selectedItems.toList(),
            onDismiss = { showRenameDialog = false },
            onComplete = {
                selectedItems.clear()
                showFabMenu = false
                showRenameDialog = false
                renameTrigger++
            }
        )
    }

    if (showPasswordDialog) {
        PasswordDialog(
            onDismiss = { showPasswordDialog = false },
            onPasswordVerified = {
                // CORREÇÃO: Usar detecção mais robusta do root level
                val encodedFolderPath = currentBackStackEntry?.arguments?.getString("folderPath") ?: ""
                val isAtRoot = encodedFolderPath == "root" || isAtRootLevel(folderPath)

                if (isAtRoot) {
                    // MUDANÇA: Toggle das pastas privadas apenas quando está na raiz
                    showPrivateFolders = !showPrivateFolders
                    renameTrigger++ // Refresh da lista
                } else {
                    // Se não estiver na raiz, navegar para pasta segura (comportamento original)
                    val securePath = FilesManager.SecureStorage.getSecureFolderPath(context)
                    val encodedPath = Uri.encode(securePath)
                    navController.navigate("folder/$encodedPath") {
                        popUpTo("folder/root") { inclusive = false }
                        launchSingleTop = true
                    }
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
                Toast.makeText(context, "Folder created", Toast.LENGTH_SHORT).show()
            }
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
                            }
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
                            }
                        )
                    }
                }
            }
        )
    }

    Scaffold(
        topBar = {
            if (currentRoute != "video_player" && !showPlayerOverlay) {
                CustomTopAppBar(
                    currentRoute = currentRoute,
                    selectedItems = selectedItems.toList(),
                    folderPath = folderPath,
                    context = context,
                    navController = navController,
                    showPrivateFolders = showPrivateFolders, // CORRIGIDO: Adicionar parâmetro
                    onPasswordDialog = { showPasswordDialog = true },
                    onSelectionClear = {
                        selectedItems.clear()
                        showFabMenu = false
                        showRenameDialog = false
                    },
                    onSelectAll = {
                        if (currentRoute == "folder") {
                            val isSecure = isSecureFolder(folderPath)
                            val isRoot = isAtRootLevel(folderPath)

                            val allItems = loadFolderContent(
                                context = context,
                                folderPath = folderPath,
                                sortType = SortType.NAME_ASC, // Usar ordenação padrão
                                isSecureMode = isSecure,
                                isRootLevel = isRoot,
                                showPrivateFolders = showPrivateFolders
                            )
                            selectedItems.clear()
                            selectedItems.addAll(allItems.map { it.path })
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (currentRoute != "video_player" && currentRoute?.startsWith("settings") != true && !showPlayerOverlay) {
                ActionBottomSheetFAB(
                    hasSelectedItems = selectedItems.isNotEmpty(),
                    isMoveMode = isMoveMode,
                    isSecureMode = isSecureFolder(folderPath),
                    selectedItems = selectedItems.toList(), // Já existia
                    itemsToMoveCount = itemsToMove.size, // NOVO: passa quantidade de itens para mover
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
                                                    Toast.makeText(context, "Files unlocked!", Toast.LENGTH_SHORT).show()
                                                }
                                                selectedItems.clear()
                                                renameTrigger++
                                            }
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
                                                    Toast.makeText(context, "Files secured!", Toast.LENGTH_SHORT).show()
                                                }
                                                selectedItems.clear()
                                                renameTrigger++
                                            }
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
                                                }
                                            )
                                        }
                                    } else {
                                        launch(Dispatchers.Main) {
                                            Toast.makeText(context, "Selecione pastas válidas para privar", Toast.LENGTH_SHORT).show()
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
                                                }
                                            )
                                        }
                                    } else {
                                        launch(Dispatchers.Main) {
                                            Toast.makeText(context, "Selecione pastas privadas para desprivar", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                            ActionType.DELETE -> showDeleteConfirmDialog = true
                            ActionType.RENAME -> showRenameDialog = true
                            ActionType.MOVE -> {
                                // MODIFICADO: Não navega mais para a raiz
                                itemsToMove = selectedItems.toList()
                                selectedItems.clear()
                                isMoveMode = true
                                Toast.makeText(context, "Modo mover ativado. Navegue até o destino e cole.", Toast.LENGTH_SHORT).show()
                            }
                            // NOVA AÇÃO: Cancelar modo Move
                            ActionType.CANCEL_MOVE -> {
                                isMoveMode = false
                                itemsToMove = emptyList()
                                Toast.makeText(context, "Operação de mover cancelada", Toast.LENGTH_SHORT).show()
                            }
                            ActionType.SHUFFLE_PLAY -> {
                                coroutineScope.launch {
                                    val videos = withContext(Dispatchers.IO) {
                                        val isSecure = isSecureFolder(folderPath)
                                        loadFolderContentRecursive(
                                            context = context,
                                            folderPath = folderPath,
                                            isSecureMode = isSecure,
                                            showPrivateFolders = showPrivateFolders
                                        ).filter { !it.isFolder }
                                            .map { "file://${it.path}" }
                                            .shuffled()
                                    }
                                    if (videos.isNotEmpty()) {
                                        MediaPlaybackService.startWithPlaylist(context, videos, 0)
                                        showPlayerOverlay = true
                                    } else {
                                        Toast.makeText(context, "No videos found", Toast.LENGTH_SHORT).show()
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
                                                Toast.makeText(context, "Items moved!", Toast.LENGTH_SHORT).show()
                                            }
                                            itemsToMove = emptyList()
                                            isMoveMode = false
                                            renameTrigger++
                                        }
                                    )
                                }
                            }
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (currentRoute != "video_player" && currentRoute?.startsWith("settings") != true && !showPlayerOverlay) {
                Box(
                    modifier = Modifier.safeDrawingPadding()
                ) {
                    MiniPlayerImproved(
                        onOpenPlayer = { showPlayerOverlay = true }
                    )
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
                startDestination = "folder/root", // MUDANÇA: começar na raiz unificada
                enterTransition = {
                    slideInHorizontally(
                        initialOffsetX = { fullWidth -> fullWidth },
                        animationSpec = tween(durationMillis = 300)
                    )
                },
                exitTransition = {
                    slideOutHorizontally(
                        targetOffsetX = { fullWidth -> -fullWidth },
                        animationSpec = tween(durationMillis = 300)
                    )
                },
                popEnterTransition = {
                    slideInHorizontally(
                        initialOffsetX = { fullWidth -> -fullWidth },
                        animationSpec = tween(durationMillis = 300)
                    )
                },
                popExitTransition = {
                    slideOutHorizontally(
                        targetOffsetX = { fullWidth -> fullWidth },
                        animationSpec = tween(durationMillis = 300)
                    )
                }
            ) {
                // REMOVIDO: A rota "video_folders" não existe mais

                // ROTA UNIFICADA para todas as pastas (raiz e subpastas)
                composable("folder/{folderPath}") { backStackEntry ->
                    val encodedFolderPath = backStackEntry.arguments?.getString("folderPath") ?: "root"

                    val folderPath = if (encodedFolderPath == "root") {
                        android.os.Environment.getExternalStorageDirectory().absolutePath
                    } else {
                        Uri.decode(encodedFolderPath)
                    }

                    val isSecure = isSecureFolder(folderPath)
                    val isRootLevel = encodedFolderPath == "root"

                    SubFolderScreen(
                        folderPath = folderPath,
                        isSecureMode = isSecure,
                        isRootLevel = isRootLevel,
                        showPrivateFolders = showPrivateFolders, // NOVO: passa o estado
                        onFolderClick = { itemPath ->
                            val items = loadFolderContent(
                                context = context,
                                folderPath = folderPath,
                                sortType = SortType.NAME_ASC, // Usar ordenação padrão
                                isSecureMode = isSecure,
                                isRootLevel = isRootLevel,
                                showPrivateFolders = showPrivateFolders
                            )
                            val item = items.find { it.path == itemPath }
                            if (item?.isFolder == true) {
                                val encodedSubPath = Uri.encode(itemPath)
                                navController.navigate("folder/$encodedSubPath")
                            } else {
                                val videos = items.filter { !it.isFolder }.map { "file://${it.path}" }
                                val clickedVideoIndex = videos.indexOf("file://$itemPath")
                                if (clickedVideoIndex >= 0) {
                                    val orderedPlaylist = videos.subList(clickedVideoIndex, videos.size) +
                                            videos.subList(0, clickedVideoIndex)
                                    MediaPlaybackService.startWithPlaylist(context, orderedPlaylist, 0)
                                    showPlayerOverlay = true
                                } else {
                                    val videoUri = "file://$itemPath"
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
                    InterfaceSettingsScreen()
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
                composable("settings/performance") {
                    PerformanceSettingsScreen()
                }
                // Removido duplicata: composable("settings/about")
            }
        }
        VideoPlayerOverlay(
            isVisible = showPlayerOverlay,
            onDismiss = { showPlayerOverlay = false },
            onVideoDeleted = { deletedPath ->
                deletedVideoPath = deletedPath
                // Reset após um frame para trigger o LaunchedEffect
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    kotlinx.coroutines.delay(100)
                    deletedVideoPath = null
                }
            }
        )
    }
}