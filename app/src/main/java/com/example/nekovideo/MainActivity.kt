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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallTopAppBar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.nekovideo.components.CreateFolderDialog
import com.example.nekovideo.components.CustomTopAppBar
import com.example.nekovideo.components.DeleteConfirmationDialog
import com.example.nekovideo.components.PasswordDialog
import com.example.nekovideo.components.RenameDialog
import com.example.nekovideo.components.RootFolderScreen
import com.example.nekovideo.components.SecureFolderScreen
import com.example.nekovideo.components.SubFolderScreen
import com.example.nekovideo.components.getSecureFolderContents
import com.example.nekovideo.components.getVideosAndSubfolders
import com.example.nekovideo.components.helpers.FilesManager
import com.example.nekovideo.components.layout.ActionBottomSheetFAB
import com.example.nekovideo.components.layout.ActionType
import com.example.nekovideo.components.player.MiniPlayerImproved
import com.example.nekovideo.components.player.VideoPlayerOverlay
import com.example.nekovideo.components.settings.AboutSettingsScreen
import com.example.nekovideo.components.settings.InterfaceSettingsScreen
import com.example.nekovideo.components.settings.PlaybackSettingsScreen
import com.example.nekovideo.components.settings.SettingsScreen
import com.example.nekovideo.ui.theme.NekoVideoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // Update the intent
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

    var isMoveMode by remember { mutableStateOf(false) }
    var itemsToMove by remember { mutableStateOf<List<String>>(emptyList()) }
    var showFolderActions by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }

    var tapCount by remember { mutableStateOf(0) }
    val maxTapInterval = 500L // 500ms interval for triple tap

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

    // Substitua o LaunchedEffect que controla o back press por este código corrigido
    LaunchedEffect(currentRoute, folderPath, selectedItems, isMoveMode, showPlayerOverlay) { // Adicionar showPlayerOverlay
        val activity = context.findActivity()
        if (activity != null) {
            activity.onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // PRIMEIRA PRIORIDADE: Overlay visível
                    if (showPlayerOverlay) {
                        showPlayerOverlay = false
                        return
                    }

                    // SEGUNDA PRIORIDADE: Move mode ativo
                    if (isMoveMode) {
                        isMoveMode = false
                        itemsToMove = emptyList()
                        return
                    }

                    // TERCEIRA PRIORIDADE: Ações de pasta abertas
                    if (showFolderActions) {
                        showFolderActions = false
                        return
                    }

                    // QUARTA PRIORIDADE: Itens selecionados
                    if (selectedItems.isNotEmpty()) {
                        selectedItems.clear()
                        showFabMenu = false
                        showRenameDialog = false
                        return
                    }

                    // NAVEGAÇÃO NORMAL: Apenas se nada acima estiver ativo
                    when {
                        currentRoute == "video_player" -> {
                            navController.popBackStack()
                        }

                        currentRoute == "settings" || currentRoute?.startsWith("settings/") == true -> {
                            navController.popBackStack()
                        }

                        currentRoute == "video_list" || currentRoute == "secure_folder" -> {
                            val rootPath = android.os.Environment.getExternalStorageDirectory().absolutePath
                            val secureFolderPath = FilesManager.SecureStorage.getSecureFolderPath(context)

                            val isAtRoot = (currentRoute == "video_list" && folderPath == rootPath) ||
                                    (currentRoute == "secure_folder" && folderPath == secureFolderPath)

                            if (isAtRoot) {
                                navController.navigate("video_folders") {
                                    popUpTo("video_folders") { inclusive = true }
                                    launchSingleTop = true
                                }
                            } else {
                                val basePath = if (currentRoute == "secure_folder") secureFolderPath else rootPath
                                val relativePath = folderPath.removePrefix(basePath).trim('/')
                                val pathSegments = relativePath.split('/').filter { it.isNotEmpty() }

                                if (pathSegments.isNotEmpty()) {
                                    val parentPath = if (currentRoute == "secure_folder") {
                                        if (pathSegments.dropLast(1).isEmpty()) secureFolderPath
                                        else "$secureFolderPath/${pathSegments.dropLast(1).joinToString("/")}"
                                    } else {
                                        if (pathSegments.dropLast(1).isEmpty()) rootPath
                                        else "$rootPath/${pathSegments.dropLast(1).joinToString("/")}"
                                    }

                                    val isReturningToRootFromNormal = currentRoute == "video_list" &&
                                            (parentPath == rootPath || parentPath == "$rootPath/")

                                    if (isReturningToRootFromNormal) {
                                        navController.navigate("video_folders") {
                                            popUpTo("video_folders") { inclusive = true }
                                            launchSingleTop = true
                                        }
                                    } else {
                                        val encodedParentPath = Uri.encode(parentPath)
                                        val targetRoute = if (currentRoute == "secure_folder") "secure_folder" else "video_list"

                                        navController.navigate("$targetRoute/$encodedParentPath") {
                                            popUpTo("$targetRoute/{folderPath}") { inclusive = false }
                                            launchSingleTop = true
                                        }
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
                val securePath = FilesManager.SecureStorage.getSecureFolderPath(context)
                val encodedPath = Uri.encode(securePath)
                navController.navigate("secure_folder/$encodedPath") {
                    popUpTo("video_folders") { inclusive = false }
                    launchSingleTop = true
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
                    onPasswordDialog = { showPasswordDialog = true },
                    onSelectionClear = {
                        selectedItems.clear()
                        showFabMenu = false
                        showRenameDialog = false
                    },
                    onSelectAll = {
                        if (currentRoute == "video_list") {
                            val allItems = getVideosAndSubfolders(context, folderPath)
                            selectedItems.clear()
                            selectedItems.addAll(allItems.map { it.path })
                        } else if (currentRoute == "secure_folder") {
                            val allItems = getSecureFolderContents(context, folderPath)
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
                    currentRoute = currentRoute ?: "",
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
                            ActionType.DELETE -> showDeleteConfirmDialog = true
                            ActionType.RENAME -> showRenameDialog = true
                            ActionType.MOVE -> {
                                itemsToMove = selectedItems.toList()
                                selectedItems.clear()
                                isMoveMode = true
                                if (currentRoute == "secure_folder") {
                                    val securePath = FilesManager.SecureStorage.getSecureFolderPath(context)
                                    val encodedPath = Uri.encode(securePath)
                                    navController.navigate("secure_folder/$encodedPath") {
                                        popUpTo("secure_folder/{folderPath}") { inclusive = false }
                                        launchSingleTop = true
                                    }
                                } else {
                                    navController.navigate("video_folders")
                                }
                            }
                            ActionType.SHUFFLE_PLAY -> {
                                coroutineScope.launch {
                                    val videos = withContext(Dispatchers.IO) {
                                        if (currentRoute == "secure_folder") {
                                            FilesManager.SecureStorage.getSecureVideosRecursively(context, folderPath)
                                                .map { "file://$it" }
                                                .shuffled()
                                        } else {
                                            getVideosAndSubfolders(context, folderPath, recursive = true)
                                                .filter { !it.isFolder }
                                                .map { "file://${it.path}" }
                                                .shuffled()
                                        }
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
                startDestination = "video_folders",
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
                composable("video_folders") {
                    RootFolderScreen(
                        onFolderClick = { folderPath ->
                            val encodedPath = Uri.encode(folderPath)
                            navController.navigate("video_list/$encodedPath")
                        },
                        selectedItems = selectedItems,
                        onSelectionChange = { newSelection ->
                            selectedItems.clear()
                            selectedItems.addAll(newSelection)
                            showFabMenu = false
                            showRenameDialog = false
                        }
                    )
                }
                composable("video_list/{folderPath}") { backStackEntry ->
                    val folderPath = backStackEntry.arguments?.getString("folderPath")?.let { Uri.decode(it) } ?: ""
                    SubFolderScreen(
                        folderPath = folderPath,
                        onFolderClick = { itemPath ->
                            val items = getVideosAndSubfolders(context, folderPath)
                            val item = items.find { it.path == itemPath }
                            if (item?.isFolder == true) {
                                val encodedSubPath = Uri.encode(itemPath)
                                navController.navigate("video_list/$encodedSubPath")
                            } else {
                                // Ao invés de navegar, iniciar o serviço e mostrar overlay
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
                        deletedVideoPath = deletedVideoPath // Adicionar este parâmetro
                    )
                }
                composable("secure_folder/{folderPath}") { backStackEntry ->
                    val folderPath = backStackEntry.arguments?.getString("folderPath")?.let { Uri.decode(it) } ?: ""
                    SecureFolderScreen(
                        folderPath = folderPath,
                        onFolderClick = { itemPath ->
                            val items = getSecureFolderContents(context, folderPath)
                            val item = items.find { it.path == itemPath }
                            if (item?.isFolder == true) {
                                val encodedSubPath = Uri.encode(itemPath)
                                navController.navigate("secure_folder/$encodedSubPath")
                            } else {
                                // Ao invés de navegar, iniciar o serviço e mostrar overlay
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
                        deletedVideoPath = deletedVideoPath // Adicionar este parâmetro também
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