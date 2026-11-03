package com.nkls.nekovideo.components.helpers

import android.content.Context
import android.util.Base64
import com.nkls.nekovideo.components.ShuffleTagFilter
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Collect candidates cheaply; prepare keys/records only after tag filtering and the size limit. */
internal object ShufflePlaylistBuilder {
    suspend fun build(context: Context, folderPath: String, isSecureMode: Boolean, showPrivateFolders: Boolean,
                      selectedItems: List<String> = emptyList(), password: String? = null,
                      tagScope: TagScope = TagScope.NORMAL, filter: ShuffleTagFilter? = null,
                      limit: Int = 1000, onProgress: (ShuffleProgress) -> Unit = {}): List<String> = withContext(Dispatchers.IO) {
        require(limit > 0)
        var last: ShuffleProgress? = null
        fun report(stage: ShuffleProgress.Stage, percent: Int, completed: Int = 0, total: Int = 0) {
            val next = ShuffleProgress(stage, percent.coerceIn(last?.percent ?: 0, 100), completed, total)
            if (next.stage != last?.stage || next.percent != last?.percent) { last = next; onProgress(next) }
        }
        val credential = LockedPlaybackSession.credentialVersion
        val visibility = FilesManager.SecureFoldersVisibility.visibilityVersion
        fun checkSession() {
            coroutineContext.ensureActive()
            if (credential != LockedPlaybackSession.credentialVersion ||
                visibility != FilesManager.SecureFoldersVisibility.visibilityVersion ||
                (password != null && LockedPlaybackSession.sessionPassword != password)) throw CancellationException("Shuffle session changed")
        }
        checkSession()
        report(ShuffleProgress.Stage.FILTERING, 0)
        var included: Set<String>? = null
        if (filter != null && filter.requiredTagIds.isNotEmpty()) {
            included = VideoTagStore.getVideoPathsForAllTagIds(context, filter.requiredTagIds, tagScope)
        }
        if (filter != null && filter.includeTagIds.isNotEmpty()) {
            val any = VideoTagStore.getVideoPathsForAnyTagIds(context, filter.includeTagIds, tagScope)
            included = included?.intersect(any) ?: any
        }
        val excluded = if (filter != null) VideoTagStore.getVideoPathsForAnyTagIds(context, filter.excludeTagIds, tagScope) else emptySet()
        included = included?.minus(excluded)
        if (included?.isEmpty() == true) {
            checkSession(); report(ShuffleProgress.Stage.BUILDING, 100); return@withContext emptyList()
        }
        report(ShuffleProgress.Stage.DISCOVERING, 5)
        val manifests = mutableMapOf<String, LockedFolderManifest>()
        val candidates = FilesManager.getVideosRecursive(context, folderPath, isSecureMode, showPrivateFolders,
            selectedItems, password, included, excluded, preparePlayback = false,
            onPrivateManifest = { path, manifest -> checkSession(); manifests[path] = manifest },
            onDiscovery = { checkSession() },
            onDiscoveryComplete = { folders, total ->
                checkSession()
                if (password != null) PortablePrivateFolders.prewarmShuffleKeys(folders, password, ::checkSession) { done, count ->
                    checkSession()
                    report(ShuffleProgress.Stage.PRIVATE_ACCESS, 10 + if (count == 0) 55 else 55 * done / count, done, count)
                }
                report(ShuffleProgress.Stage.COLLECTING, 65, 0, total)
            }, onFolderCollected = { done, total ->
                checkSession()
                report(ShuffleProgress.Stage.COLLECTING, 65 + if (total == 0) 0 else (15 * done / total).coerceAtMost(15), done, total)
            }).distinct()
        val selected = if (candidates.size > limit) candidates.shuffled().take(limit) else candidates
        val prepared = mutableSetOf<String>()
        var processed = selected.count { !it.startsWith("locked://") }
        fun preparing() {
            checkSession()
            report(ShuffleProgress.Stage.BUILDING, 80 + if (selected.isEmpty()) 19 else 19 * processed / selected.size,
                processed, selected.size)
        }
        preparing()
        selected.filter { it.startsWith("locked://") }.groupBy { File(it.removePrefix("locked://")).parent!! }
            .forEach { (path, videos) ->
                checkSession()
                val before = processed
                val names = videos.mapTo(HashSet()) { File(it.removePrefix("locked://")).name }
                val manifest = if (password == null) null else if (PortablePrivateFolders.isPortable(path)) {
                    PortablePrivateFolders.manifestForShufflePlayback(path, password, names) { processed++; preparing() }
                } else manifests[path]
                if (manifest != null && password != null) {
                    checkSession()
                    if (LockedPlaybackSession.getManifestForFolder(path) != manifest) {
                        // Portable entries have independent authenticated keys; no legacy fallback is used.
                        val key = if (manifest.files.all { it.portableMetadata != null }) ByteArray(32)
                            else FolderLockManager.deriveXorKey(password, Base64.decode(manifest.salt, Base64.NO_WRAP))
                        try {
                            checkSession()
                            LockedPlaybackSession.start(key, manifest, path, password)
                        } finally { key.fill(0) }
                    }
                    val available = manifest.files.mapTo(HashSet()) { it.obfuscatedName }
                    videos.filterTo(prepared) { File(it.removePrefix("locked://")).name in available }
                }
                processed = before + videos.size
                preparing()
            }
        checkSession()
        selected.filter { !it.startsWith("locked://") || it in prepared }.also { report(ShuffleProgress.Stage.BUILDING, 100) }
    }
}
