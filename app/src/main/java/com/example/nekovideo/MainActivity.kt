package com.example.nekovideo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.nekovideo.components.CreateFolderDialog
import com.example.nekovideo.components.DeleteConfirmationDialog
import com.example.nekovideo.components.OptimizedThumbnailManager
import com.example.nekovideo.components.PasswordDialog
import com.example.nekovideo.components.RenameDialog
import com.example.nekovideo.components.SortType
import com.example.nekovideo.components.SubFolderScreen
import com.example.nekovideo.components.helpers.FilesManager
import com.example.nekovideo.components.layout.ActionBottomSheetFAB
import com.example.nekovideo.components.layout.ActionType
import com.example.nekovideo.components.layout.CustomTopAppBar
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
import com.example.nekovideo.language.LanguageManager
import com.example.nekovideo.ui.theme.NekoVideoTheme
import com.example.nekovideo.ui.theme.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    // NOVO: Adicionar ThemeManager
    private lateinit var themeManager: ThemeManager

    // NOVO: Variável para controlar intent da notificação
    private var notificationIntentReceived = false
    private var lastIntentAction: String? = null
    private var lastIntentTime: Long = 0


    private fun handleNotificationIntent(intent: Intent?) {
        val currentTime = System.currentTimeMillis()
        lastIntentTime = currentTime
        lastIntentAction = intent?.action

        Log.d("MainActivity", "==========================================")
        Log.d("MainActivity", "🔍 HANDLE NOTIFICATION INTENT CHAMADO")
        Log.d("MainActivity", "Timestamp: $currentTime")
        Log.d("MainActivity", "Intent: $intent")
        Log.d("MainActivity", "Action: ${intent?.action}")
        Log.d("MainActivity", "==========================================")

        // Log detalhado de todas as extras
        intent?.extras?.let { extras ->
            Log.d("MainActivity", "📦 EXTRAS DO INTENT:")
            for (key in extras.keySet()) {
                val value = extras.get(key)
                Log.d("MainActivity", "   $key = $value (${value?.javaClass?.simpleName})")
            }
        } ?: Log.d("MainActivity", "❌ Nenhuma extra encontrada")

        // Processar action
        when (intent?.action) {
            "OPEN_PLAYER" -> {
                Log.d("MainActivity", "🎵 ACTION OPEN_PLAYER DETECTADO!")
                notificationIntentReceived = true

                val playlist = intent.getStringArrayListExtra("PLAYLIST")
                val initialIndex = intent.getIntExtra("INITIAL_INDEX", 0)
                val autoOpen = intent.getBooleanExtra("AUTO_OPEN_PLAYER", false)

                Log.d("MainActivity", "📋 Dados da notificação:")
                Log.d("MainActivity", "   Playlist size: ${playlist?.size}")
                Log.d("MainActivity", "   Initial index: $initialIndex")
                Log.d("MainActivity", "   Auto open: $autoOpen")

                // Log cada item da playlist
                playlist?.forEachIndexed { index, item ->
                    Log.d("MainActivity", "   [$index] $item")
                }
            }
            null -> {
                Log.d("MainActivity", "⚠️  Intent com action NULL")
            }
            else -> {
                Log.d("MainActivity", "❓ Action desconhecida: ${intent.action}")
            }
        }

        Log.d("MainActivity", "==========================================")
    }

    companion object {
        var shouldOpenPlayerGlobal = false
    }

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase?.let { context ->
            val languageCode = LanguageManager.getCurrentLanguage(context)
            LanguageManager.setLocale(context, languageCode)
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        //LanguageManager.initialize(this)

        val currentLanguage = LanguageManager.getCurrentLanguage(this)
        if (currentLanguage != "system") {
            LanguageManager.setLocale(this, currentLanguage)
        }

        enableEdgeToEdge()

        // PROCESSAR intent inicial
        handleNotificationIntent(intent)

        themeManager = ThemeManager(this)

        setContent {
            val currentLanguage by LanguageManager.currentLanguage.collectAsState()

            // Criar contexto localizado
            val localizedContext = remember(currentLanguage) {
                LanguageManager.getLocalizedContext(this@MainActivity, currentLanguage)
            }

                NekoVideoTheme(themeManager = themeManager) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        // USAR SUA FUNÇÃO MAINSCREEN ORIGINAL, só adicionando parâmetros de debug
                        MainScreen(
                            intent = intent,
                            themeManager = themeManager,
                            // Debug info
                            notificationReceived = notificationIntentReceived,
                            lastAction = lastIntentAction,
                            lastTime = lastIntentTime
                        )
                    }
                }
        }

        OptimizedThumbnailManager.startPeriodicCleanup()
        Log.d("MainActivity", "✅ onCreate FINALIZADO")
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
        OptimizedThumbnailManager.cancelLoading("")
    }

    override fun onDestroy() {
        super.onDestroy()
        MediaControllerManager.disconnect()
        OptimizedThumbnailManager.stopPeriodicCleanup()
        OptimizedThumbnailManager.clearCache()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d("MainActivity", "🔄 ==> onNewIntent CHAMADO <==")
        Log.d("MainActivity", "Nova intent: ${intent}")
        Log.d("MainActivity", "Nova action: ${intent?.action}")

        setIntent(intent)

        // PROCESSAR nova intent
        handleNotificationIntent(intent)

        // IMPORTANTE: Recompor sem recreate para não perder estado
        Log.d("MainActivity", "🔄 Recompondo interface...")

        setContent {
            NekoVideoTheme(themeManager = themeManager) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        intent = intent,
                        themeManager = themeManager,
                        // Debug info atualizado
                        notificationReceived = notificationIntentReceived,
                        lastAction = lastIntentAction,
                        lastTime = lastIntentTime
                    )
                }
            }
        }

        Log.d("MainActivity", "✅ onNewIntent FINALIZADO")
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


@Composable
fun MainScreen(
    intent: Intent?,
    themeManager: ThemeManager,
    // NOVOS parâmetros de debug (opcionais)
    notificationReceived: Boolean = false,
    lastAction: String? = null,
    lastTime: Long = 0
) {
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

    val currentTheme by themeManager.themeMode.collectAsState()
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current


    LaunchedEffect(intent, notificationReceived, lastAction, lastTime) {

        // Lógica original (para abrir player pela primeira vez)
        if (intent?.action == "OPEN_PLAYER" && !notificationReceived) {
            val playlist = intent.getStringArrayListExtra("PLAYLIST") ?: emptyList()
            if (playlist.isNotEmpty()) {
                Log.d("MainScreen", "✅ Abrindo player - playlist: ${playlist.size} itens")
                showPlayerOverlay = true
            }
            intent.action = null
        }

        // NOVA lógica para notificação - APENAS abre overlay
        if (notificationReceived && lastAction == "OPEN_PLAYER") {

            val playlist = intent?.getStringArrayListExtra("PLAYLIST") ?: emptyList()

            if (playlist.isNotEmpty()) {

                // ✅ APENAS abre overlay, NÃO chama startWithPlaylist!
                showPlayerOverlay = true

            } else {
                Log.w("MainScreen", "❌ Playlist vazia na notificação")
            }
        }
    }

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

                            if (encodedFolderPath == "root") {
                                return
                            }

                            if (folderPath == secureFolderPath) {
                                navController.navigate("folder/root") {
                                    popUpTo("folder/root") { inclusive = true }
                                    launchSingleTop = true
                                }
                                return
                            }

                            val isInSecureStructure = folderPath.startsWith(secureFolderPath)
                            val isPrivateFolder = isSecureFolder(folderPath) && !isInSecureStructure

                            val parentPath = if (isInSecureStructure) {
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

                            val isParentRoot = parentPath == rootPath || parentPath == secureFolderPath

                            if (isParentRoot) {
                                if (parentPath == secureFolderPath) {
                                    val encodedSecurePath = Uri.encode(secureFolderPath)
                                    navController.navigate("folder/$encodedSecurePath") {
                                        popUpTo("folder/{folderPath}") { inclusive = false }
                                        launchSingleTop = true
                                    }
                                } else {
                                    navController.navigate("folder/root") {
                                        popUpTo("folder/root") { inclusive = true }
                                        launchSingleTop = true
                                    }
                                }
                            } else {
                                val encodedParentPath = Uri.encode(parentPath)
                                navController.navigate("folder/$encodedParentPath") {
                                    popUpTo("folder/{folderPath}") { inclusive = false }
                                    launchSingleTop = true
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

    LaunchedEffect(showPlayerOverlay) {
        val activity = context.findActivity()
        if (activity is MainActivity) {
            activity.keepScreenOn(showPlayerOverlay)
        }
    }

    // Todos os diálogos permanecem iguais...
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
                val encodedFolderPath = currentBackStackEntry?.arguments?.getString("folderPath") ?: ""
                val isAtRoot = encodedFolderPath == "root" || isAtRootLevel(folderPath)

                if (isAtRoot) {
                    showPrivateFolders = !showPrivateFolders
                    renameTrigger++
                } else {
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
                    showPrivateFolders = showPrivateFolders,
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
                                sortType = SortType.NAME_ASC,
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
                                itemsToMove = selectedItems.toList()
                                selectedItems.clear()
                                isMoveMode = true
                                Toast.makeText(context, "Modo mover ativado. Navegue até o destino e cole.", Toast.LENGTH_SHORT).show()
                            }
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
                startDestination = "folder/root",
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
                        showPrivateFolders = showPrivateFolders,
                        isMoveMode = isMoveMode,
                        itemsToMove = itemsToMove,
                        onFolderClick = { itemPath ->
                            val items = loadFolderContent(
                                context = context,
                                folderPath = folderPath,
                                sortType = SortType.NAME_ASC,
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
                composable("settings/performance") {
                    PerformanceSettingsScreen()
                }
            }
        }
        VideoPlayerOverlay(
            isVisible = showPlayerOverlay,
            onDismiss = { showPlayerOverlay = false },
            onVideoDeleted = { deletedPath ->
                deletedVideoPath = deletedPath
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    kotlinx.coroutines.delay(100)
                    deletedVideoPath = null
                }
            }
        )
    }
}