package com.nkls.nekovideo.components.helpers

internal data class VideoTagCountCache(
    val counts: Map<String, Int> = emptyMap(),
    private val changeVersion: Long? = null,
    private val folderRevision: Int? = null
) {
    suspend fun refresh(
        videoPaths: Set<String>,
        change: VideoTagChange,
        folderRevision: Int,
        loadCounts: suspend (List<String>) -> Map<String, Int>
    ): VideoTagCountCache {
        val missingPaths = videoPaths - counts.keys
        val pathsToLoad = when {
            changeVersion == null || this.folderRevision != folderRevision -> videoPaths
            change.version == changeVersion -> missingPaths
            change.version == changeVersion + 1L && change.affectedVideoPaths != null ->
                missingPaths + change.affectedVideoPaths.filter { it in videoPaths }
            // A lifecycle pause or conflated updates may skip events. Refresh tags only, not files.
            else -> videoPaths
        }
        val refreshedCounts = if (pathsToLoad.isEmpty()) emptyMap() else loadCounts(pathsToLoad.toList())
        return VideoTagCountCache(
            counts = counts.filterKeys { it in videoPaths } + refreshedCounts,
            changeVersion = change.version,
            folderRevision = folderRevision
        )
    }
}
