package com.example.nekovideo

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallTopAppBar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import com.example.nekovideo.components.RenameDialog
import com.example.nekovideo.components.VideoFolderScreen
import com.example.nekovideo.components.VideoListScreen
import com.example.nekovideo.components.VideoPlayerScreen
import com.example.nekovideo.components.getVideosAndSubfolders
import com.example.nekovideo.ui.theme.NekoVideoTheme
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.nekovideo.components.CreateFolderDialog
import com.example.nekovideo.components.PasswordDialog
import com.example.nekovideo.components.SecureFolderScreen
import com.example.nekovideo.components.getSecureFolderContents
import com.example.nekovideo.components.helpers.FilesManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
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
                    MainScreen()
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
fun MainScreen() {
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

    var isMoveMode by remember { mutableStateOf(false) }
    var itemsToMove by remember { mutableStateOf<List<String>>(emptyList()) }
    var showFolderActions by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }

    var tapCount by remember { mutableStateOf(0) }
    val maxTapInterval = 500L // 500ms interval for triple tap

    LaunchedEffect(currentRoute, folderPath, selectedItems, isMoveMode) {
        val activity = context.findActivity()
        if (activity != null) {
            println("\n=== Back Callback Registered ===")
            println("Current Route: $currentRoute")
            println("Current Folder Path: $folderPath")

            activity.onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    println("\n=== Back Pressed Handler ===")
                    println("Current State Before Handling:")
                    println("- Current Route: $currentRoute")
                    println("- Current Folder: $folderPath")
                    println("- Selected Items: ${selectedItems.size}")
                    println("- Is Move Mode: $isMoveMode")
                    println("- Show Folder Actions: $showFolderActions")

                    if (isMoveMode) {
                        println("\nAction: Canceling Move Mode")
                        isMoveMode = false
                        itemsToMove = emptyList()
                        return
                    }

                    if (showFolderActions) {
                        println("\nAction: Hiding Folder Actions")
                        showFolderActions = false
                        return
                    }

                    if (selectedItems.isNotEmpty()) {
                        println("\nAction: Clearing Selection (${selectedItems.size} items)")
                        selectedItems.clear()
                        showFabMenu = false
                        showRenameDialog = false
                        return
                    }

                    when {
                        currentRoute == "video_player" -> {
                            println("\nAction: Back from Video Player")
                            println("Navigation: Popping back stack")
                            navController.popBackStack()
                        }

                        currentRoute == "video_list" || currentRoute == "secure_folder" -> {
                            val rootPath = android.os.Environment.getExternalStorageDirectory().absolutePath
                            val secureFolderPath = FilesManager.SecureStorage.getSecureFolderPath(context)

                            println("\nNavigation Details:")
                            println("- Root Path: $rootPath")
                            println("- Secure Path: $secureFolderPath")
                            println("- Current Path: $folderPath")

                            // Verifica se está na raiz (normal ou segura)
                            val isAtRoot = (currentRoute == "video_list" && folderPath == rootPath) ||
                                    (currentRoute == "secure_folder" && folderPath == secureFolderPath)

                            if (isAtRoot) {
                                println("\nAction: Back from Root Folder")
                                println("Reason: Already at root, navigating to video_folders")
                                println("Navigation: video_folders (clearing back stack)")
                                navController.navigate("video_folders") {
                                    popUpTo("video_folders") { inclusive = true }
                                    launchSingleTop = true
                                }
                            } else {
                                val basePath = if (currentRoute == "secure_folder") secureFolderPath else rootPath
                                val relativePath = folderPath.removePrefix(basePath).trim('/')
                                val pathSegments = relativePath.split('/').filter { it.isNotEmpty() }

                                println("\nPath Analysis:")
                                println("- Base Path: $basePath")
                                println("- Relative Path: $relativePath")
                                println("- Path Segments: $pathSegments")

                                if (pathSegments.isNotEmpty()) {
                                    val parentPath = if (currentRoute == "secure_folder") {
                                        if (pathSegments.dropLast(1).isEmpty()) secureFolderPath
                                        else "$secureFolderPath/${pathSegments.dropLast(1).joinToString("/")}"
                                    } else {
                                        if (pathSegments.dropLast(1).isEmpty()) rootPath
                                        else "$rootPath/${pathSegments.dropLast(1).joinToString("/")}"
                                    }

                                    // Verificação explícita para retorno à raiz
                                    val isReturningToRootFromNormal = currentRoute == "video_list" &&
                                            (parentPath == rootPath || parentPath == "$rootPath/")

                                    if (isReturningToRootFromNormal) {
                                        println("\nAction: Direct root navigation from normal folder")
                                        println("Navigation: video_folders (clearing back stack)")
                                        navController.navigate("video_folders") {
                                            popUpTo("video_folders") { inclusive = true }
                                            launchSingleTop = true
                                        }
                                    } else {
                                        val encodedParentPath = Uri.encode(parentPath)
                                        val targetRoute = if (currentRoute == "secure_folder") "secure_folder" else "video_list"

                                        println("\nAction: Back to Parent Folder")
                                        println("Navigation: $targetRoute/$encodedParentPath")
                                        navController.navigate("$targetRoute/$encodedParentPath") {
                                            popUpTo("$targetRoute/{folderPath}") { inclusive = false }
                                            launchSingleTop = true
                                        }
                                    }
                                }
                            }
                        }

                        else -> {
                            println("\nAction: Finishing Activity")
                            println("Reason: No special back handling, default behavior")
                            activity.finish()
                        }
                    }
                }
            })
        } else {
            println("\n=== ERROR ===")
            println("Failed to find ComponentActivity")
        }
    }

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

    fun getSecureVideosRecursively(context: Context, folderPath: String): List<String> {
        val videoPaths = mutableListOf<String>()
        val folder = File(folderPath)

        if (!folder.exists()) return emptyList()

        folder.listFiles()?.forEach { file ->
            if (file.isDirectory && file.name != ".nomedia") {
                videoPaths.addAll(getSecureVideosRecursively(context, file.absolutePath))
            } else if (file.isFile && file.extension.lowercase() in listOf("mp4", "mkv", "avi", "mov", "wmv")) {
                videoPaths.add(file.absolutePath)
            }
        }

        return videoPaths
    }

    Scaffold(
        topBar = {
            if (currentRoute != "video_player") {
                SmallTopAppBar(
                    title = {
                        if (selectedItems.isNotEmpty()) {
                            Text(
                                text = "${selectedItems.size} item(s) selected",
                                style = MaterialTheme.typography.titleSmall
                            )
                        } else if (currentRoute == "video_folders") {
                            Text(
                                text = "NekoVideo",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier
                                    .clickable(
                                        enabled = true,
                                        onClick = { /* Handled by pointerInput */ }
                                    )
                                    .pointerInput(Unit) {
                                        detectTapGestures { _ ->
                                            tapCount++
                                            if (tapCount == 1) {
                                                coroutineScope.launch {
                                                    delay(maxTapInterval)
                                                    if (tapCount < 3) {
                                                        tapCount = 0 // Reset if not triple tap
                                                    }
                                                }
                                            }
                                            if (tapCount >= 3) {
                                                tapCount = 0
                                                showPasswordDialog = true
                                            }
                                        }
                                    }
                            )
                        } else {
                            val secureFolderPath = FilesManager.SecureStorage.getSecureFolderPath(context)
                            val rootPath = android.os.Environment.getExternalStorageDirectory().absolutePath
                            val basePath = if (currentRoute == "secure_folder") secureFolderPath else rootPath
                            val relativePath = folderPath.removePrefix(basePath).trim('/')
                            val pathSegments = relativePath.split('/').filter { it.isNotEmpty() }
                            val displayPath = if (currentRoute == "secure_folder" && relativePath.isEmpty()) listOf("secure_video") else {
                                if (currentRoute == "secure_folder") listOf("secure_video") + pathSegments else pathSegments
                            }
                            Row {
                                displayPath.forEachIndexed { index, segment ->
                                    Text(
                                        text = segment,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        textDecoration = TextDecoration.Underline,
                                        modifier = Modifier
                                            .clickable {
                                                val targetPath = if (currentRoute == "secure_folder" && index == 0) {
                                                    secureFolderPath
                                                } else {
                                                    val segmentsUpToIndex = displayPath.take(index + 1)
                                                    val prefix = if (currentRoute == "secure_folder") secureFolderPath else rootPath
                                                    if (currentRoute == "secure_folder" && segmentsUpToIndex[0] == "secure_video") {
                                                        "$secureFolderPath/${segmentsUpToIndex.drop(1).joinToString("/")}"
                                                    } else {
                                                        "$prefix/${segmentsUpToIndex.joinToString("/")}"
                                                    }
                                                }
                                                val encodedPath = Uri.encode(targetPath)
                                                val targetRoute = if (currentRoute == "secure_folder") "secure_folder" else "video_list"
                                                println("Navigating to: $targetRoute/$encodedPath, targetPath: $targetPath")
                                                try {
                                                    navController.navigate("$targetRoute/$encodedPath") {
                                                        popUpTo("video_folders") { inclusive = false }
                                                        launchSingleTop = true
                                                    }
                                                } catch (e: Exception) {
                                                    println("Navigation error: ${e.message}")
                                                }
                                            }
                                            .padding(end = 4.dp)
                                    )
                                    if (index < displayPath.size - 1) {
                                        Text(
                                            text = "/",
                                            style = MaterialTheme.typography.titleSmall,
                                            modifier = Modifier.padding(end = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        if (selectedItems.isNotEmpty()) {
                            IconButton(onClick = {
                                println("Cancel selection clicked")
                                selectedItems.clear()
                                showFabMenu = false
                                showRenameDialog = false
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Cancel,
                                    contentDescription = "Cancel Selection"
                                )
                            }
                        } else if (currentRoute == "video_list" || currentRoute == "secure_folder") {
                            val rootPath = android.os.Environment.getExternalStorageDirectory().absolutePath
                            val secureFolderPath = FilesManager.SecureStorage.getSecureFolderPath(context)
                            if (currentRoute == "video_list" && folderPath == rootPath) {
                                IconButton(onClick = {
                                    println("Navigating to video_folders from root path")
                                    navController.navigate("video_folders") {
                                        popUpTo("video_folders") { inclusive = true }
                                        launchSingleTop = true
                                    }
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowBack,
                                        contentDescription = "Back"
                                    )
                                }
                            } else if (currentRoute == "secure_folder" && folderPath == secureFolderPath) {
                                IconButton(onClick = {
                                    println("Navigating to video_folders from secure folder root")
                                    navController.navigate("video_folders") {
                                        popUpTo("video_folders") { inclusive = true }
                                        launchSingleTop = true
                                    }
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowBack,
                                        contentDescription = "Back"
                                    )
                                }
                            } else {
                                IconButton(onClick = {
                                    println("Back button clicked")
                                    navController.popBackStack()
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowBack,
                                        contentDescription = "Back"
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        if (selectedItems.isNotEmpty() && currentRoute == "video_list") {
                            IconButton(onClick = {
                                println("Select all clicked")
                                val allItems = getVideosAndSubfolders(context, folderPath)
                                selectedItems.clear()
                                selectedItems.addAll(allItems.map { it.path })
                            }) {
                                Icon(
                                    imageVector = Icons.Default.SelectAll,
                                    contentDescription = "Select All"
                                )
                            }
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (currentRoute != "video_player") {
                Box {
                    FloatingActionButton(
                        onClick = {
                            when {
                                isMoveMode -> {
                                    coroutineScope.launch {
                                        FilesManager.moveSelectedItems(
                                            context = context,
                                            selectedItems = itemsToMove,
                                            destinationPath = folderPath,
                                            onError = { message ->
                                                launch(Dispatchers.Main) {
                                                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            onSuccess = { message ->
                                                launch(Dispatchers.Main) {
                                                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        )
                                        itemsToMove = emptyList()
                                        isMoveMode = false
                                        renameTrigger++
                                    }
                                }
                                selectedItems.isNotEmpty() -> showFabMenu = !showFabMenu
                                else -> showFolderActions = !showFolderActions
                            }
                        },
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Icon(
                            imageVector = when {
                                isMoveMode -> Icons.Default.ContentPaste
                                selectedItems.isNotEmpty() -> Icons.Default.FolderOpen
                                else -> Icons.Default.Settings
                            },
                            contentDescription = when {
                                isMoveMode -> "Paste Items"
                                selectedItems.isNotEmpty() -> "Folder Actions"
                                else -> "Folder Options"
                            }
                        )
                    }

                    if (showFabMenu && selectedItems.isNotEmpty() && !isMoveMode) {
                        Column(
                            horizontalAlignment = Alignment.End,
                            modifier = Modifier.offset(y = (-112).dp)
                        ) {
                            FloatingActionButton(
                                onClick = {
                                    coroutineScope.launch {
                                        val securePath = FilesManager.SecureStorage.getSecureFolderPath(context)
                                        if (FilesManager.SecureStorage.ensureSecureFolderExists(context)) {
                                            FilesManager.moveSelectedItems(
                                                context = context,
                                                selectedItems = selectedItems.toList(),
                                                destinationPath = securePath,
                                                onError = { message ->
                                                    Toast.makeText(context, "Error: $message", Toast.LENGTH_SHORT).show()
                                                },
                                                onSuccess = { message ->
                                                    Toast.makeText(context, "Files secured!", Toast.LENGTH_SHORT).show()
                                                    selectedItems.clear()
                                                    showFabMenu = false
                                                    renameTrigger++
                                                }
                                            )
                                        } else {
                                            Toast.makeText(context, "Failed to access secure storage", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                modifier = Modifier.size(40.dp).clip(CircleShape),
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Secure Files",
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }

                            FloatingActionButton(
                                onClick = {
                                    showRenameDialog = true
                                    showFabMenu = false
                                },
                                modifier = Modifier.size(40.dp).clip(CircleShape),
                                containerColor = MaterialTheme.colorScheme.secondary
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Rename",
                                    tint = MaterialTheme.colorScheme.onSecondary
                                )
                            }


                            FloatingActionButton(
                                onClick = {
                                    itemsToMove = selectedItems.toList()
                                    selectedItems.clear()
                                    isMoveMode = true
                                    showFabMenu = false
                                    if (currentRoute == "secure_folder") {
                                        val securePath = FilesManager.SecureStorage.getSecureFolderPath(context)
                                        val encodedPath = Uri.encode(securePath)
                                        println("Navigating to secure_folder/$encodedPath for move operation")
                                        navController.navigate("secure_folder/$encodedPath") {
                                            popUpTo("secure_folder/{folderPath}") { inclusive = false }
                                            launchSingleTop = true
                                        }
                                    } else {
                                        println("Navigating to video_folders for move operation")
                                        navController.navigate("video_folders")
                                    }
                                },
                                modifier = Modifier.size(40.dp).clip(CircleShape),
                                containerColor = MaterialTheme.colorScheme.tertiary
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DriveFileMove,
                                    contentDescription = "Move",
                                    tint = MaterialTheme.colorScheme.onTertiary
                                )
                            }

                        }
                    }

                    if (showFolderActions && selectedItems.isEmpty() && !isMoveMode) {
                        Column(
                            horizontalAlignment = Alignment.End,
                            modifier = Modifier.offset(y = (-112).dp)
                        ) {
                            FloatingActionButton(
                                onClick = {
                                    showFolderActions = false
                                    coroutineScope.launch {
                                        val videos = withContext(Dispatchers.IO) {
                                            if (currentRoute == "secure_folder") {
                                                // Busca vídeos na pasta segura
                                                getSecureVideosRecursively(context, folderPath)
                                                    .map { "file://$it" }
                                                    .shuffled()
                                            } else {
                                                // Busca vídeos normais (já existente)
                                                getVideosAndSubfolders(context, folderPath, recursive = true)
                                                    .filter { !it.isFolder }
                                                    .map { "file://${it.path}" }
                                                    .shuffled()
                                            }
                                        }

                                        if (videos.isNotEmpty()) {
                                            val encodedPlaylist = Uri.encode(videos.joinToString(","))
                                            navController.navigate("video_player/$encodedPlaylist")
                                        } else {
                                            Toast.makeText(context, "No videos found", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                modifier = Modifier.size(40.dp).clip(CircleShape),
                                containerColor = MaterialTheme.colorScheme.secondary
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Shuffle,
                                    contentDescription = "Shuffle Play",
                                    tint = MaterialTheme.colorScheme.onSecondary
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            FloatingActionButton(
                                onClick = {
                                    showFolderActions = false
                                    showCreateFolderDialog = true
                                },
                                modifier = Modifier.size(40.dp).clip(CircleShape),
                                containerColor = MaterialTheme.colorScheme.tertiary
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CreateNewFolder,
                                    contentDescription = "Create Folder",
                                    tint = MaterialTheme.colorScheme.onTertiary
                                )
                            }
                        }
                    }
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
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = if (currentRoute == "video_player") 0.dp else max(0.dp, paddingValues.calculateTopPadding() - 16.dp),
                    bottom = paddingValues.calculateBottomPadding(),
                    start = paddingValues.calculateStartPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                    end = paddingValues.calculateEndPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)
                )
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
                    VideoFolderScreen(
                        onFolderClick = { folderPath ->
                            val encodedPath = Uri.encode(folderPath)
                            println("Navigating to folder: video_list/$encodedPath")
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
                    println("Current folder: $folderPath")
                    VideoListScreen(
                        folderPath = folderPath,
                        onFolderClick = { itemPath ->
                            val item = getVideosAndSubfolders(context, folderPath).find { it.path == itemPath }
                            if (item?.isFolder == true) {
                                val encodedSubPath = Uri.encode(itemPath)
                                println("Navigating to subfolder: video_list/$encodedSubPath")
                                navController.navigate("video_list/$encodedSubPath")
                            } else {
                                val videoUri = "file://$itemPath"
                                val encodedPlaylist = Uri.encode(videoUri)
                                println("Navigating to video player: video_player/$encodedPlaylist")
                                navController.navigate("video_player/$encodedPlaylist")
                            }
                        },
                        selectedItems = selectedItems,
                        onSelectionChange = { newSelection ->
                            selectedItems.clear()
                            selectedItems.addAll(newSelection)
                            showFabMenu = false
                            showRenameDialog = false
                        },
                        renameTrigger = renameTrigger
                    )
                }
                composable("video_player/{playlist}") { backStackEntry ->
                    val playlist = backStackEntry.arguments?.getString("playlist")?.let { Uri.decode(it) }
                        ?.split(",") ?: emptyList()
                    VideoPlayerScreen(playlist = playlist)
                }
                composable("secure_folder/{folderPath}") { backStackEntry ->
                    val folderPath = backStackEntry.arguments?.getString("folderPath")?.let { Uri.decode(it) } ?: ""
                    println("Current secure folder: $folderPath")
                    SecureFolderScreen(
                        folderPath = folderPath,
                        onFolderClick = { itemPath ->
                            val item = getSecureFolderContents(context, folderPath).find { it.path == itemPath }
                            if (item?.isFolder == true) {
                                val encodedSubPath = Uri.encode(itemPath)
                                println("Navigating to secure subfolder: secure_folder/$encodedSubPath")
                                navController.navigate("secure_folder/$encodedSubPath")
                            } else {
                                val videoUri = "file://$itemPath"
                                val encodedPlaylist = Uri.encode(videoUri)
                                println("Navigating to video player: video_player/$encodedPlaylist")
                                navController.navigate("video_player/$encodedPlaylist")
                            }
                        },
                        selectedItems = selectedItems,
                        onSelectionChange = { newSelection ->
                            selectedItems.clear()
                            selectedItems.addAll(newSelection)
                            showFabMenu = false
                            showRenameDialog = false
                        },
                        renameTrigger = renameTrigger
                    )
                }
            }
        }
    }
}