package com.nkls.nekovideo.services

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.nkls.nekovideo.components.helpers.LockedPlaybackSession
import com.nkls.nekovideo.components.helpers.readFolderChildren
import com.nkls.nekovideo.components.helpers.FolderLockManager
import com.nkls.nekovideo.components.helpers.PortablePrivateFolders
import com.nkls.nekovideo.components.helpers.PortableVideoStore
import com.nkls.nekovideo.components.helpers.PrivateVideoMigration
import com.nkls.nekovideo.components.helpers.PrivateMigrationHistory
import com.nkls.nekovideo.components.helpers.LocalFolderUpdates
import com.nkls.nekovideo.BuildConfig
import com.nkls.nekovideo.components.helpers.supportedVideoExtensions
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class FolderInfo(
    val path: String,
    val hasVideos: Boolean = false,
    val videoCount: Int = 0,
    val isSecure: Boolean = false,
    val isLocked: Boolean = false,
    val lastModified: Long = 0L,
    val videos: List<VideoInfo> = emptyList()
)

data class VideoInfo(
    val path: String,
    val uri: Uri,
    val lastModified: Long,
    val sizeInBytes: Long
)

// ✅ NOVA - Versão serializável (sem Uri)
data class SerializableVideoInfo(
    val path: String,
    val uriString: String,
    val lastModified: Long,
    val sizeInBytes: Long
)

data class SerializableFolderInfo(
    val path: String,
    val hasVideos: Boolean = false,
    val videoCount: Int = 0,
    val isSecure: Boolean = false,
    val isLocked: Boolean = false,
    val lastModified: Long = 0L,
    val videos: List<SerializableVideoInfo> = emptyList()
)

// ✅ Conversores
fun VideoInfo.toSerializable() = SerializableVideoInfo(
    path = path,
    uriString = uri.toString(),
    lastModified = lastModified,
    sizeInBytes = sizeInBytes
)

fun SerializableVideoInfo.toVideoInfo() = VideoInfo(
    path = path,
    uri = Uri.parse(uriString),
    lastModified = lastModified,
    sizeInBytes = sizeInBytes
)

fun FolderInfo.toSerializable() = SerializableFolderInfo(
    path = path,
    hasVideos = hasVideos,
    videoCount = videoCount,
    isSecure = isSecure,
    isLocked = isLocked,
    lastModified = lastModified,
    videos = videos.map { it.toSerializable() }
)

fun SerializableFolderInfo.toFolderInfo() = FolderInfo(
    path = path,
    hasVideos = hasVideos,
    videoCount = videoCount,
    isSecure = isSecure,
    isLocked = isLocked,
    lastModified = lastModified,
    videos = videos.map { it.toVideoInfo() }
)

object FolderVideoScanner {
    private class ScanProfile(context: Context) {
        val nanos = linkedMapOf<String, Long>()
        val visited = HashSet<String>()
        val directoryStamps = linkedMapOf<String, Long>()
        val fastDiscovery = LockedPlaybackSession.sessionPassword == null
        val registry = FolderLockManager.getAllLockedFolders(context).associateBy { it.folderPath }
        val previousFolders = cache.value
        val privateRoots = listOf(
            com.nkls.nekovideo.components.helpers.FilesManager.SecureStorage.getNekoPrivateFolderPath(),
            com.nkls.nekovideo.components.helpers.FilesManager.SecureStorage.getSecureFolderPath(context)
        ).map { path -> runCatching { File(path).canonicalPath }.getOrDefault(File(path).absolutePath) }.toSet()
        var directories = 0L
        var uncachedPrivateCounts = 0L
        fun record(name: String, start: Long) {
            nanos[name] = (nanos[name] ?: 0L) + android.os.SystemClock.elapsedRealtimeNanos() - start
        }
    }
    internal var lastScanTimings: Map<String, Long> = emptyMap()
        private set
    private val _cache = MutableStateFlow<Map<String, FolderInfo>>(emptyMap())
    val cache: StateFlow<Map<String, FolderInfo>> = _cache.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()
    private val _fullScanVersion = MutableStateFlow(0L)
    internal val fullScanVersion: StateFlow<Long> = _fullScanVersion.asStateFlow()

    internal data class MigrationCheck(val credentialVersion: Long, val pathsOverride: List<String>?,
                                       val plan: PrivateVideoMigration.Plan)
    private val _migrationCheck = MutableStateFlow<MigrationCheck?>(null)
    internal val migrationCheck: StateFlow<MigrationCheck?> = _migrationCheck.asStateFlow()

    private var scanJob: Job? = null
    @Volatile private var scanGeneration = 0L
    private val externalRecoveryPaths = ConcurrentHashMap.newKeySet<String>()
    private val observedDirectoryStamps = ConcurrentHashMap<String, Long>()
    private val entryRefreshMutex = Mutex()
    private const val PREFS_NAME = "nekovideo_cache"
    private const val CACHE_KEY = "folder_cache"

    private val gson = Gson()

    // ✅ Carregar cache (CORRIGIDO)
    fun loadCacheFromDisk(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val cacheJson = prefs.getString(CACHE_KEY, null)

            if (cacheJson != null) {
                val type = object : TypeToken<Map<String, SerializableFolderInfo>>() {}.type
                val loadedCache: Map<String, SerializableFolderInfo> = gson.fromJson(cacheJson, type)

                // ✅ Converte de volta para FolderInfo com Uri reconstruído
                _cache.value = loadedCache.mapValues { it.value.toFolderInfo() }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            _cache.value = emptyMap() // ✅ Limpa se falhar
        }
    }



    // ✅ Salvar cache (CORRIGIDO)
    private fun saveCacheToDisk(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            // ✅ Converte para versão serializável
            val serializableCache = _cache.value.mapValues { it.value.toSerializable() }
            val cacheJson = gson.toJson(serializableCache)

            prefs.edit()
                .putString(CACHE_KEY, cacheJson)
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized fun startScan(context: Context, scope: CoroutineScope = GlobalScope, forceRefresh: Boolean = false,
                                dispatcher: CoroutineDispatcher = Dispatchers.IO) {
        // Se não for refresh forçado e já tem cache, não escaneia
        if (!forceRefresh && _cache.value.isNotEmpty()) {
            return
        }

        scanJob?.cancel()
        val generation = ++scanGeneration
        _isScanning.value = true
        _migrationCheck.value = null

        scanJob = scope.launch(dispatcher) {
            val timings = linkedMapOf<String, Long>()
            val profile = ScanProfile(context)
            val started = android.os.SystemClock.elapsedRealtime()
            var stage = started
            fun measured(name: String) {
                val now = android.os.SystemClock.elapsedRealtime()
                timings[name] = now - stage
                stage = now
            }
            try {
                val folderMap = ConcurrentHashMap<String, FolderInfo>()

                // Scan normal folders via MediaStore
                scanNormalFolders(context, folderMap)
                measured("mediaStore")

                // Scan secure folders (.nomedia/locked) and locked folders
                scanSecureFolders(context, folderMap, profile)
                measured("filesystem")

                // Scan direto das pastas principais para pegar arquivos não indexados
                scanDirectFolders(context, folderMap)
                measured("directFolders")

                restoreQueuedExternalArrivals(context, folderMap)
                prepareMigrationCheck(context, folderMap.keys, generation, fullScan = true)
                measured("recoveryAndMigration")
                if (generation != scanGeneration) return@launch
                _cache.value = folderMap.toMap()
                observedDirectoryStamps.clear()
                observedDirectoryStamps.putAll(profile.directoryStamps)
                com.nkls.nekovideo.components.FolderContentCache.clear()
                _fullScanVersion.value++

                // ✅ Salva o cache após o scan
                saveCacheToDisk(context)
                measured("publishAndSave")

            } catch (e: Exception) {
                if (e is CancellationException) throw e
                e.printStackTrace()
            } finally {
                profile.nanos.forEach { (name, nanos) -> timings[name] = nanos / 1_000_000L }
                timings["directoryCount"] = profile.directories
                timings["uncachedPrivateCount"] = profile.uncachedPrivateCounts
                timings["total"] = android.os.SystemClock.elapsedRealtime() - started
                lastScanTimings = timings
                android.util.Log.d("FolderVideoScanner", "Initial scan ms: $timings")
                finishScan(generation)
            }
        }
    }

    private var localRefreshJob: Job? = null

    /** Check directory metadata on entry/resume; read videos only below changed directories. */
    internal suspend fun reconcileFolderEntry(context: Context, path: String): Boolean = withContext(Dispatchers.IO) {
        entryRefreshMutex.withLock {
            scanJob?.join()
            localRefreshJob?.join()
            val generation = scanGeneration
            val root = File(path).absolutePath
            val known = observedDirectoryStamps.filterKeys { isSameOrDescendant(it, root) }
            val changed = known.filter { (directory, stamp) -> File(directory).lastModified() != stamp }.keys.toMutableSet()
            if (root !in known) changed += root
            if (changed.isEmpty()) return@withLock false
            val targets = changed.sortedBy { it.length }.fold(mutableListOf<String>()) { result, candidate ->
                if (result.none { isSameOrDescendant(candidate, it) }) result += candidate
                result
            }
            val updated = ConcurrentHashMap(_cache.value)
            val profile = ScanProfile(context)
            targets.forEach { target ->
                updated.keys.filter { isSameOrDescendant(it, target) }.forEach(updated::remove)
                val folder = File(target)
                if (folder.isDirectory) {
                    scanSecureFoldersRecursive(context, folder, updated, profile,
                        PortablePrivateFolders.hasPrivateAncestorForScan(context, folder))
                }
            }
            // Ancestors keep their unchanged direct videos; only visibility is propagated.
            targets.forEach { target ->
                var parent = File(target).parentFile
                while (parent != null) {
                    val parentPath = parent.path
                    val existing = updated[parentPath]
                    val hasVideos = (existing?.videoCount ?: 0) > 0 || updated.any { (child, info) ->
                        child.startsWith("$parentPath/") && info.hasVideos
                    }
                    if (existing != null) updated[parentPath] = existing.copy(hasVideos = hasVideos)
                    else if (hasVideos && parent.isDirectory) updated[parentPath] = FolderInfo(parentPath, hasVideos = true)
                    parent = parent.parentFile
                }
            }
            synchronized(this@FolderVideoScanner) {
                if (generation != scanGeneration) return@withLock false
                _cache.value = updated.toMap()
                targets.forEach { target ->
                    observedDirectoryStamps.keys.filter { isSameOrDescendant(it, target) }.forEach(observedDirectoryStamps::remove)
                }
                observedDirectoryStamps.putAll(profile.directoryStamps)
                com.nkls.nekovideo.components.FolderContentCache.invalidate(listOf(root))
                saveCacheToDisk(context)
            }
            true
        }
    }

    /** Known app edits reuse unchanged entries; only new subtrees need discovery. */
    @Synchronized internal fun refreshLocalPaths(context: Context, paths: Collection<String>): Job {
        val targets = paths.filter { it.isNotBlank() }.map { File(it).absolutePath }.toSet()
        val previousLocal = localRefreshJob
        val currentScan = scanJob
        return GlobalScope.launch(Dispatchers.IO) {
            try {
                previousLocal?.join()
                currentScan?.join()
                val updated = ConcurrentHashMap(_cache.value)
                val profile = ScanProfile(context)
                val touched = targets.toMutableSet()
                targets.sortedByDescending { it.length }.forEach { path ->
                    val folder = File(path)
                    if (!folder.isDirectory) {
                        com.nkls.nekovideo.components.FolderContentCache.invalidate(listOf(path))
                        updated.keys.filter { isSameOrDescendant(it, path) }.toList().forEach(updated::remove)
                        observedDirectoryStamps.keys.filter { isSameOrDescendant(it, path) }.forEach(observedDirectoryStamps::remove)
                        return@forEach
                    }
                    val children = folder.listFiles().orEmpty()
                    val names = children.mapTo(HashSet()) { it.name }
                    updated.keys.filter { File(it).parent == path && File(it).name !in names }.toList().forEach { missing ->
                        com.nkls.nekovideo.components.FolderContentCache.invalidate(listOf(missing))
                        updated.keys.filter { isSameOrDescendant(it, missing) }.toList().forEach(updated::remove)
                        observedDirectoryStamps.keys.filter { isSameOrDescendant(it, missing) }.forEach(observedDirectoryStamps::remove)
                    }
                    val locked = FolderLockManager.isLocked(path)
                    val loadedVideos = com.nkls.nekovideo.components.FolderContentCache.loadedItems(path).orEmpty()
                        .filterNot { it.isFolder }.map { VideoInfo(it.path, it.uri ?: Uri.fromFile(File(it.path)), it.lastModified, it.sizeInBytes) }
                    val knownVideos = (updated[path]?.videos.orEmpty() + loadedVideos)
                        .filter { File(it.path).name in names }.associateBy { it.path }
                    val videos = knownVideos.values.toMutableList()
                    val privateNames = if (locked) LockedPlaybackSession.getManifestForFolder(path)?.files
                        ?.mapTo(HashSet()) { it.obfuscatedName }.orEmpty() else emptySet()
                    children.forEach childLoop@ { child ->
                        if (child.name.startsWith(".neko") || child.name.endsWith(".part") || child.path in knownVideos ||
                            child.name in privateNames) return@childLoop
                        if (updated.containsKey(child.path)) return@childLoop
                        if (child.isDirectory) {
                            scanSecureFoldersRecursive(context, child, updated, profile,
                                PortablePrivateFolders.hasPrivateAncestorForScan(context, child))
                            touched += child.path
                        } else if (!locked && child.extension.lowercase() in supportedVideoExtensions &&
                            child.isFile && !PortableVideoStore.hasRecoveryRecord(child)) {
                            videos += VideoInfo(child.path, Uri.fromFile(child), child.lastModified(), child.length())
                        }
                    }
                    val count = if (locked) FolderLockManager.getLockedFileCount(context, path) else videos.size
                    updated[path] = FolderInfo(path, count > 0 || updated.any { (key, info) ->
                        key.startsWith("$path/") && info.hasVideos
                    }, count, locked || File(folder, ".nomedia").exists(), locked, folder.lastModified(), if (locked) emptyList() else videos)
                }
                touched.toList().forEach { path ->
                    var parent = File(path).parentFile
                    while (parent != null) {
                        val key = parent.path
                        updated[key]?.let { info ->
                            updated[key] = info.copy(hasVideos = info.videoCount > 0 || updated.any { (child, childInfo) ->
                                child.startsWith("$key/") && childInfo.hasVideos
                            })
                        }
                        parent = parent.parentFile
                    }
                }
                synchronized(this@FolderVideoScanner) {
                    _cache.value = updated.toMap()
                    targets.filter { File(it).isDirectory }.forEach { observedDirectoryStamps[it] = File(it).lastModified() }
                    observedDirectoryStamps.putAll(profile.directoryStamps)
                    saveCacheToDisk(context)
                }
                PrivateMigrationHistory(context).observe(touched, false)
                LocalFolderUpdates.mark(touched)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                android.util.Log.w("FolderVideoScanner", "Local update requires a verified folder read", failure)
                com.nkls.nekovideo.components.FolderContentCache.invalidate(targets)
                LocalFolderUpdates.mark(targets)
            }
        }.also { localRefreshJob = it }
    }

    @Synchronized fun refreshPaths(
        context: Context,
        paths: List<String>,
        scope: CoroutineScope = GlobalScope,
        showProgress: Boolean = true,
        cancelOngoing: Boolean = true
    ) {
        val targetPaths = paths
            .map { File(it).absolutePath }
            .distinct()
            .sortedBy { it.length }
            .filterIndexed { index, path ->
                targetPathsFilter(paths = paths, candidate = path, index = index)
            }

        if (targetPaths.isEmpty()) return

        if (scanJob?.isActive == true) {
            if (!cancelOngoing) return
            scanJob?.cancel()
        }

        val generation = ++scanGeneration

        if (showProgress) {
            _isScanning.value = true
        }

        scanJob = scope.launch(Dispatchers.IO) {
            try {
                val updatedCache = _cache.value.toMutableMap()
                val observedPaths = mutableSetOf<String>()
                val profile = ScanProfile(context)

                targetPaths.forEach { rootPath ->
                    observedPaths += rootPath
                    updatedCache.keys
                        .filter { cachedPath -> isSameOrDescendant(cachedPath, rootPath) }
                        .toList()
                        .forEach { observedPaths += it; updatedCache.remove(it) }

                    val rootDir = File(rootPath)
                    if (rootDir.exists() && rootDir.isDirectory) {
                        val subtreeMap = ConcurrentHashMap<String, FolderInfo>()
                        scanSecureFoldersRecursive(context, rootDir, subtreeMap, profile,
                            PortablePrivateFolders.hasPrivateAncestorForScan(context, rootDir))
                        observedPaths += subtreeMap.keys
                        updatedCache.putAll(subtreeMap)
                    }

                    recomputeAncestors(context, updatedCache, rootPath)
                }

                restoreQueuedExternalArrivals(context, updatedCache, targetPaths)
                prepareMigrationCheck(context, updatedCache.keys, generation, observedPaths = observedPaths)
                if (generation != scanGeneration) return@launch
                _cache.value = updatedCache.toMap()
                targetPaths.forEach { root ->
                    observedDirectoryStamps.keys.filter { isSameOrDescendant(it, root) }.forEach(observedDirectoryStamps::remove)
                }
                observedDirectoryStamps.putAll(profile.directoryStamps)
                com.nkls.nekovideo.components.FolderContentCache.invalidate(targetPaths)
                LocalFolderUpdates.mark(targetPaths)
                saveCacheToDisk(context)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                e.printStackTrace()
            } finally {
                finishScan(generation)
            }
        }
    }

    /** Also covers authentication after an already cached scan; counting remains inside the loading state. */
    @Synchronized internal fun requestPrivateMigrationCheck(context: Context, password: String,
                                                            pathsOverride: Collection<String>? = null) {
        if (!BuildConfig.PORTABLE_PRIVATE_WRITES || password != LockedPlaybackSession.sessionPassword) return
        if (scanJob?.isActive == true) {
            _isScanning.value = true
            return // Reuse the existing scan; authentication never starts another global scan.
        }
        if (pathsOverride == null && PrivateMigrationHistory(context).isComplete() && externalRecoveryPaths.isEmpty()) {
            _migrationCheck.value = MigrationCheck(LockedPlaybackSession.credentialVersion, null,
                PrivateVideoMigration.Plan(emptyList(), 0, 0))
            return
        }
        _isScanning.value = true
        val generation = ++scanGeneration
        _migrationCheck.value = null
        scanJob = GlobalScope.launch(Dispatchers.IO) {
            try {
                val updated = _cache.value.toMutableMap()
                if (restoreQueuedExternalArrivals(context, updated, pathsOverride) && generation == scanGeneration) {
                    _cache.value = updated.toMap()
                    saveCacheToDisk(context)
                }
                prepareMigrationCheck(context, _cache.value.keys, generation, pathsOverride?.toList())
            }
            finally { finishScan(generation) }
        }
    }

    private fun prepareMigrationCheck(context: Context, paths: Collection<String>, generation: Long,
                                       pathsOverride: List<String>? = null, fullScan: Boolean = false,
                                       observedPaths: Collection<String>? = null) {
        if (!BuildConfig.PORTABLE_PRIVATE_WRITES) return
        val history = PrivateMigrationHistory(context)
        val accessible = Environment.isExternalStorageManager()
        if (pathsOverride == null && accessible && generation == scanGeneration) {
            when {
                fullScan -> history.observe(paths + FolderLockManager.getAllLockedFolders(context).map { it.folderPath }, true)
                observedPaths != null -> history.observe(observedPaths, false)
                !history.hasDiscovery() -> history.observe(paths + FolderLockManager.getAllLockedFolders(context).map { it.folderPath }, false)
            }
        }
        val password = LockedPlaybackSession.sessionPassword ?: return
        val credential = LockedPlaybackSession.credentialVersion
        val plan = when {
            pathsOverride != null -> PrivateVideoMigration.plan(pathsOverride, password)
            accessible -> history.plan(password)
            else -> PrivateVideoMigration.Plan(emptyList(), 0, history.pendingPaths().size)
        }
        synchronized(this) {
            if (generation == scanGeneration && credential == LockedPlaybackSession.credentialVersion &&
                password == LockedPlaybackSession.sessionPassword) {
                _migrationCheck.value = MigrationCheck(credential, pathsOverride, plan)
            }
        }
    }

    @Synchronized private fun finishScan(generation: Long) {
        if (generation == scanGeneration) _isScanning.value = false
    }

    /** Recover only ordinary directories already found by the regular scan, without traversing the device again. */
    private fun restoreQueuedExternalArrivals(context: Context, cache: MutableMap<String, FolderInfo>,
                                              roots: Collection<String>? = null): Boolean {
        val password = LockedPlaybackSession.sessionPassword ?: return false
        var changed = false
        externalRecoveryPaths.toList().filter { path -> roots == null || roots.any { isSameOrDescendant(path, it) } }
            .forEach { path ->
                val restored = PortablePrivateFolders.restoreExternalArrivals(context, path, password)
                if (restored.isNotEmpty()) {
                    recomputeFolderInfo(context, cache, File(path))
                    recomputeAncestors(context, cache, path)
                    changed = true
                }
                if (!PortablePrivateFolders.hasExternalArrivals(context, path)) externalRecoveryPaths.remove(path)
            }
        return changed
    }

    private suspend fun scanDirectFolders(context: Context, folderMap: ConcurrentHashMap<String, FolderInfo>) {
        val directScanPaths = listOf(
            "/storage/emulated/0/Download",
            "/storage/emulated/0/Downloads",
            "/storage/emulated/0/Movies",
            "/storage/emulated/0/DCIM",
            "/storage/emulated/0/Pictures"
        )

        directScanPaths.forEach { path ->
            val dir = File(path)
            if (dir.exists() && dir.isDirectory) {
                scanDirectoryForVideos(context, dir, folderMap)
            }
        }
    }

    private suspend fun scanDirectoryForVideos(
        context: Context,
        directory: File,
        folderMap: ConcurrentHashMap<String, FolderInfo>
    ) {
        yield()

        if (!directory.exists() || !directory.isDirectory) return

        try {
            PortablePrivateFolders.restoreExternalArrivals(context, directory.absolutePath)
            val videoFiles = directory.listFiles()?.filter { file ->
                file.isFile && file.extension.lowercase() in supportedVideoExtensions && !PortableVideoStore.hasRecoveryRecord(file)
            } ?: emptyList()

            videoFiles.forEach { videoFile ->
                val videoInfo = VideoInfo(
                    path = videoFile.absolutePath,
                    uri = Uri.fromFile(videoFile),
                    lastModified = videoFile.lastModified(),
                    sizeInBytes = videoFile.length()
                )

                addVideoToFolder(folderMap, directory.absolutePath, videoInfo, false)
            }

            directory.listFiles()?.forEach { subDir ->
                if (subDir.isDirectory && !subDir.name.startsWith(".")) {
                    scanDirectoryForVideos(context, subDir, folderMap)
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun scanNormalFolders(context: Context, folderMap: ConcurrentHashMap<String, FolderInfo>) {
        val projection = arrayOf(
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.SIZE
        )

        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)

            while (cursor.moveToNext()) {
                yield()

                val videoPath = cursor.getString(dataColumn)
                if (PortableVideoStore.hasRecoveryRecord(File(videoPath))) {
                    File(videoPath).parent?.let { externalRecoveryPaths += it }
                    continue
                }
                val videoId = cursor.getLong(idColumn)
                val lastModified = cursor.getLong(dateColumn) * 1000L
                val size = cursor.getLong(sizeColumn)
                val parentDir = File(videoPath).parent ?: continue

                val videoUri = ContentUris.withAppendedId(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    videoId
                )

                val videoInfo = VideoInfo(
                    path = videoPath,
                    uri = videoUri,
                    lastModified = lastModified,
                    sizeInBytes = size
                )
                addVideoToFolder(folderMap, parentDir, videoInfo, false)
            }
        }
    }

    private suspend fun scanSecureFolders(context: Context, folderMap: ConcurrentHashMap<String, FolderInfo>, profile: ScanProfile) {
        val rootDirs = listOf(
            "/storage/emulated/0/",
            "/storage/emulated/0/Download/",
            "/storage/emulated/0/Movies/",
            "/storage/emulated/0/DCIM/",
            "/storage/emulated/0/Pictures/"
        )

        rootDirs.forEach { rootPath ->
            scanSecureFoldersRecursive(context, File(rootPath), folderMap, profile)
        }
    }

    private suspend fun scanSecureFoldersRecursive(
        context: Context,
        directory: File,
        folderMap: ConcurrentHashMap<String, FolderInfo>,
        profile: ScanProfile? = null,
        inheritedPrivateBoundary: Boolean = false
    ) {
        yield()

        if (!directory.exists() || !directory.isDirectory) return
        if (profile != null && !profile.visited.add(directory.absolutePath)) return

        profile?.let { it.directories++ }
        var stage = android.os.SystemClock.elapsedRealtimeNanos()
        var listedEntries = directory.listFiles()?.toList()
        val names = listedEntries.orEmpty().mapTo(HashSet()) { it.name }
        val fastDiscovery = profile?.fastDiscovery == true && LockedPlaybackSession.sessionPassword == null && listedEntries != null
        // Keep this listing read-only even if authentication happens halfway through the walk.
        // Queued arrivals are restored with fresh metadata after the discovery phase.
        val restored = if (fastDiscovery) emptyList() else PortablePrivateFolders.restoreExternalArrivals(context, directory.absolutePath)
        if (restored.isNotEmpty()) folderMap.remove(directory.absolutePath) // Replace stale MediaStore rows.
        if (restored.isNotEmpty()) listedEntries = directory.listFiles()?.toList()
        if (listedEntries != null) profile?.directoryStamps?.put(directory.absolutePath, directory.lastModified())
        val isSecure = if (fastDiscovery) ".nomedia" in names else File(directory, ".nomedia").exists()
        val isLocked = if (fastDiscovery) ".neko_locked" in names || PortablePrivateFolders.MARKER in names ||
            PortablePrivateFolders.INDEX in names || PortablePrivateFolders.PASSWORD_BACKUP in names
            else FolderLockManager.isLocked(directory.absolutePath)
        val privateBoundary = inheritedPrivateBoundary || directory.name.startsWith(".") || isSecure || isLocked ||
            names.any { it == ".secure" || it == ".neko_lock_in_progress" } ||
            profile?.privateRoots.orEmpty().any { isSameOrDescendant(directory.absolutePath, it) }
        profile?.record("fsMarkersMs", stage)
        stage = android.os.SystemClock.elapsedRealtimeNanos()
        val external = if (isLocked || isSecure) false else if (fastDiscovery)
            PortablePrivateFolders.hasExternalArrivalsForScan(directory, listedEntries.orEmpty(), privateBoundary)
            else PortablePrivateFolders.hasExternalArrivals(context, directory.absolutePath)
        if (external) {
            externalRecoveryPaths += directory.absolutePath
        }
        profile?.record("fsExternalDiscoveryMs", stage)
        stage = android.os.SystemClock.elapsedRealtimeNanos()

        val knownPrivateRecords = HashSet<String>()
        if (isLocked) {
            // Locked folder: get file count from registry, do NOT scan by extension
            val registryEntry = if (profile != null) profile.registry[directory.absolutePath]
                else FolderLockManager.getRegistryEntry(context, directory.absolutePath)
            if (registryEntry == null) profile?.let { it.uncachedPrivateCounts++ }
            val previous = profile?.previousFolders?.get(directory.absolutePath)?.takeIf {
                it.isLocked && it.lastModified == directory.lastModified()
            }
            val fileCount = registryEntry?.fileCount ?: previous?.videoCount ?: if (fastDiscovery && ".neko_locked" !in names) {
                PortablePrivateFolders.recoveryRecordsForScan(listedEntries.orEmpty()).also { records ->
                    records.mapTo(knownPrivateRecords) { it.absolutePath }
                }.size
            } else FolderLockManager.getLockedFileCount(context, directory.absolutePath)

            folderMap[directory.absolutePath] = FolderInfo(
                path = directory.absolutePath,
                hasVideos = fileCount > 0,
                videoCount = fileCount,
                isSecure = true,
                isLocked = true,
                lastModified = directory.lastModified(),
                videos = emptyList() // Locked videos are not scannable
            )
            propagateToParents(folderMap, directory.absolutePath, directory.lastModified())
        } else if (isSecure) {
            val videoFiles = listedEntries?.filter { file ->
                file.isFile && file.extension.lowercase() in supportedVideoExtensions && !PortableVideoStore.hasRecoveryRecord(file)
            } ?: emptyList()

            if (videoFiles.isNotEmpty()) {
                val videos = videoFiles.map { file ->
                    VideoInfo(
                        path = file.absolutePath,
                        uri = Uri.fromFile(file),
                        lastModified = file.lastModified(),
                        sizeInBytes = file.length()
                    )
                }

                folderMap[directory.absolutePath] = FolderInfo(
                    path = directory.absolutePath,
                    hasVideos = true,
                    videoCount = videos.size,
                    isSecure = true,
                    lastModified = directory.lastModified(),
                    videos = videos
                )
                propagateToParents(folderMap, directory.absolutePath, directory.lastModified())
            }
        } else if (!folderMap.containsKey(directory.absolutePath)) {
            // Pasta normal não coberta pelo MediaStore ainda (ex: recém destravada).
            // Só age se o MediaStore não a indexou — evita duplicatas.
            val videoFiles = listedEntries?.filter { file ->
                file.isFile && file.extension.lowercase() in supportedVideoExtensions && !PortableVideoStore.hasRecoveryRecord(file)
            } ?: emptyList()

            if (videoFiles.isNotEmpty()) {
                val videos = videoFiles.map { file ->
                    VideoInfo(
                        path = file.absolutePath,
                        uri = Uri.fromFile(file),
                        lastModified = file.lastModified(),
                        sizeInBytes = file.length()
                    )
                }
                folderMap[directory.absolutePath] = FolderInfo(
                    path = directory.absolutePath,
                    hasVideos = true,
                    videoCount = videos.size,
                    isSecure = false,
                    isLocked = false,
                    lastModified = directory.lastModified(),
                    videos = videos
                )
                propagateToParents(folderMap, directory.absolutePath, directory.lastModified())
            }
        }

        profile?.record("fsFolderContentMs", stage)
        stage = android.os.SystemClock.elapsedRealtimeNanos()
        val knownVideoPaths = if (isLocked) {
            LockedPlaybackSession.getManifestForFolder(directory.absolutePath)?.files
                ?.mapTo(HashSet()) { File(directory, it.obfuscatedName).absolutePath }.orEmpty()
        } else {
            folderMap[directory.absolutePath]?.videos?.mapTo(HashSet()) { it.path }.orEmpty()
        }
        val subfolders = listedEntries.orEmpty().filter { child ->
            child.absolutePath !in knownVideoPaths && child.absolutePath !in knownPrivateRecords && child.isDirectory
        }.filter { subDir ->
            subDir.name != ".neko_thumbs" && (!subDir.name.startsWith(".") || isSecure || isLocked ||
                File(subDir, ".nomedia").exists() || FolderLockManager.isLocked(subDir.absolutePath))
        }
        profile?.record("fsChildDirectoriesMs", stage)
        subfolders.forEach { scanSecureFoldersRecursive(context, it, folderMap, profile, privateBoundary) }
    }

    private fun addVideoToFolder(
        folderMap: ConcurrentHashMap<String, FolderInfo>,
        folderPath: String,
        videoInfo: VideoInfo,
        isSecure: Boolean
    ) {
        folderMap.compute(folderPath) { _, existing ->
            if (existing == null) {
                FolderInfo(
                    path = folderPath,
                    hasVideos = true,
                    videoCount = 1,
                    isSecure = isSecure,
                    lastModified = videoInfo.lastModified,
                    videos = listOf(videoInfo)
                )
            } else {
                val existingVideos = existing.videos
                val isDuplicate = existingVideos.any { it.path == videoInfo.path }

                if (!isDuplicate) {
                    existing.copy(
                        videoCount = existing.videoCount + 1,
                        lastModified = maxOf(videoInfo.lastModified, existing.lastModified),
                        videos = existingVideos + videoInfo
                    )
                } else {
                    existing
                }
            }
        }

        val parentPath = File(folderPath).parent
        if (parentPath != null && parentPath != folderPath) {
            folderMap.compute(parentPath) { _, existing ->
                if (existing == null) {
                    FolderInfo(
                        path = parentPath,
                        hasVideos = true,
                        videoCount = 0,
                        isSecure = false,
                        lastModified = videoInfo.lastModified,
                        videos = emptyList()
                    )
                } else {
                    existing.copy(
                        hasVideos = true,
                        lastModified = maxOf(videoInfo.lastModified, existing.lastModified)
                    )
                }
            }
            propagateToParents(folderMap, parentPath, videoInfo.lastModified)
        }
    }

    private fun propagateToParents(
        folderMap: ConcurrentHashMap<String, FolderInfo>,
        folderPath: String,
        lastModified: Long
    ) {
        val parentPath = File(folderPath).parent
        if (parentPath != null && parentPath != folderPath) {
            folderMap.compute(parentPath) { _, existing ->
                if (existing == null) {
                    FolderInfo(
                        path = parentPath,
                        hasVideos = true,
                        videoCount = 0,
                        isSecure = false,
                        lastModified = lastModified,
                        videos = emptyList()
                    )
                } else {
                    existing.copy(
                        hasVideos = true,
                        lastModified = maxOf(lastModified, existing.lastModified)
                    )
                }
            }
            propagateToParents(folderMap, parentPath, lastModified)
        }
    }

    private fun recomputeAncestors(
        context: Context,
        cacheMap: MutableMap<String, FolderInfo>,
        startPath: String
    ) {
        var current = File(startPath).parentFile
        while (current != null && current.absolutePath != current.parent) {
            recomputeFolderInfo(context, cacheMap, current)
            current = current.parentFile
        }
    }

    private fun recomputeFolderInfo(
        context: Context,
        cacheMap: MutableMap<String, FolderInfo>,
        directory: File
    ) {
        if (!directory.exists() || !directory.isDirectory) {
            cacheMap.remove(directory.absolutePath)
            return
        }

        PortablePrivateFolders.restoreExternalArrivals(context, directory.absolutePath)
        val isLocked = FolderLockManager.isLocked(directory.absolutePath)
        val isSecure = File(directory, ".nomedia").exists() || isLocked

        if (isLocked) {
            val registryEntry = FolderLockManager.getRegistryEntry(context, directory.absolutePath)
            val fileCount = registryEntry?.fileCount ?: FolderLockManager.getLockedFileCount(context, directory.absolutePath)
            cacheMap[directory.absolutePath] = FolderInfo(
                path = directory.absolutePath,
                hasVideos = fileCount > 0,
                videoCount = fileCount,
                isSecure = true,
                isLocked = true,
                lastModified = directory.lastModified(),
                videos = emptyList()
            )
            return
        }

        val directVideos = directory.listFiles()?.filter { file ->
            file.isFile && file.extension.lowercase() in supportedVideoExtensions && !PortableVideoStore.hasRecoveryRecord(file)
        } ?: emptyList()

        val videoInfos = directVideos.map { file ->
            VideoInfo(
                path = file.absolutePath,
                uri = Uri.fromFile(file),
                lastModified = file.lastModified(),
                sizeInBytes = file.length()
            )
        }

        val hasRelevantChildren = directory.listFiles()?.any { child ->
            child.isDirectory && cacheMap[child.absolutePath]?.let { it.hasVideos || it.isLocked || it.isSecure } == true
        } == true

        val shouldKeepEntry = videoInfos.isNotEmpty() || isSecure || hasRelevantChildren

        if (!shouldKeepEntry) {
            cacheMap.remove(directory.absolutePath)
            return
        }

        val maxChildModified = directory.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { cacheMap[it.absolutePath]?.lastModified }
            ?.maxOrNull()
            ?: 0L

        val maxVideoModified = videoInfos.maxOfOrNull { it.lastModified } ?: 0L

        cacheMap[directory.absolutePath] = FolderInfo(
            path = directory.absolutePath,
            hasVideos = videoInfos.isNotEmpty() || hasRelevantChildren,
            videoCount = videoInfos.size,
            isSecure = isSecure,
            isLocked = false,
            lastModified = maxOf(directory.lastModified(), maxVideoModified, maxChildModified),
            videos = videoInfos
        )
    }

    private fun isSameOrDescendant(path: String, rootPath: String): Boolean {
        return path == rootPath || path.startsWith("$rootPath${File.separator}")
    }

    private fun targetPathsFilter(paths: List<String>, candidate: String, index: Int): Boolean {
        return paths
            .map { File(it).absolutePath }
            .distinct()
            .sortedBy { it.length }
            .take(index)
            .none { isSameOrDescendant(candidate, it) }
    }

    fun hasFolderVideos(folderPath: String): Boolean {
        return _cache.value[folderPath]?.hasVideos ?: false
    }

    fun getFolderInfo(folderPath: String): FolderInfo? {
        return _cache.value[folderPath]
    }

    fun getFoldersWithVideos(): List<FolderInfo> {
        return _cache.value.values.filter { it.hasVideos }
    }

    fun getSecureFolders(): List<FolderInfo> {
        return _cache.value.values.filter { it.isSecure && it.hasVideos }
    }

    fun stopScan() {
        scanJob?.cancel()
        _isScanning.value = false
    }

    fun clearCache() {
        _cache.value = emptyMap()
    }

    // ✅ Limpar cache do disco também
    fun clearPersistentCache(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        clearCache()
    }
}
