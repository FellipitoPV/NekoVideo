package com.nkls.nekovideo.components.helpers

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.security.MessageDigest

/** Immutable changes plus alternating complete checkpoints. Access is serialized by VideoTagStore. */
internal class TagBackupJournal(private val context: Context, private val directory: String) {
    private data class Envelope(
        @SerializedName("payload") val payload: String,
        @SerializedName("sha256") val sha256: String
    )
    private data class Record(
        @SerializedName("version") val version: Int = 1,
        @SerializedName("revision") val revision: Long,
        @SerializedName("full") val full: Boolean,
        @SerializedName("paths") val paths: Set<String>,
        @SerializedName("data") val data: VideoTagStore.TagBackupPayload
    )
    private data class Entry(val name: String, val uri: Uri)
    internal data class Snapshot(val revision: Long, val data: VideoTagStore.TagBackupPayload,
        val checkpointName: String, val checkpointRevision: Long, val changes: Int)
    private val gson = Gson()
    private val resolver get() = context.contentResolver
    private val collection get() = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private var cached: Snapshot? = null
    private var initialized = false

    fun current(): Snapshot? {
        if (!initialized) { cached = load(); initialized = true }
        return cached
    }

    /** Fresh readers also detect external corruption instead of using a cached successful result. */
    fun load(): Snapshot? {
        val entries = entries()
        if (entries.isEmpty()) return null
        val checkpoints = entries.filter { it.name.startsWith("checkpoint-") }
            .map { it to read(it.uri) }
        check(checkpoints.isNotEmpty()) { "tag_backup_checkpoint_missing" }
        val (entry, base) = checkpoints.maxBy { it.second.revision }
        check(base.full) { "tag_backup_invalid_checkpoint" }
        var snapshot = Snapshot(base.revision, base.data, entry.name, base.revision, 0)
        val changes = entries.filter { it.name.startsWith("change-") }.map { read(it.uri) }
            .filter { it.revision > base.revision }.sortedBy { it.revision }
        for (change in changes) {
            check(!change.full && change.revision == snapshot.revision + 1) { "tag_backup_change_missing" }
            snapshot = apply(snapshot, change)
        }
        validate(snapshot.data)
        return snapshot
    }

    fun saveFull(data: VideoTagStore.TagBackupPayload) {
        val previous = runCatching { current() }.getOrNull()
        val valid = if (previous == null) entries().mapNotNull { entry ->
            runCatching { read(entry.uri) }.getOrNull()?.let { entry to it }
        } else emptyList()
        val revision = (previous?.revision ?: valid.maxOfOrNull { it.second.revision } ?: 0L) + 1L
        val previousName = previous?.checkpointName ?: valid.filter { it.second.full }.maxByOrNull { it.second.revision }?.first?.name
        val name = if (previousName == "checkpoint-a.json") "checkpoint-b.json" else "checkpoint-a.json"
        val record = Record(revision = revision, full = true, paths = emptySet(), data = data)
        publish(name, record)
        cached = Snapshot(revision, data, name, revision, 0)
        initialized = true
        // Retain everything needed to replay the previous checkpoint as well.
        entries().filter { it.name != name }.forEach { entry ->
            val old = runCatching { read(entry.uri) }.getOrNull()
            if (old == null || (!old.full && old.revision <= (previous?.checkpointRevision ?: revision))) {
                runCatching { resolver.delete(entry.uri, null, null) }
            }
        }
    }

    fun savePaths(paths: Set<String>, data: VideoTagStore.TagBackupPayload) {
        val previous = checkNotNull(current())
        check(data.tags.toSet() == previous.data.tags.toSet()) { "tag_backup_definitions_changed" }
        val record = Record(revision = previous.revision + 1, full = false, paths = paths, data = data)
        check(data.refs.all { it.videoPath in paths })
        publish("change-${record.revision}.json", record)
        cached = apply(previous, record)
    }

    private fun apply(previous: Snapshot, record: Record): Snapshot = previous.copy(
        revision = record.revision,
        data = record.data.copy(refs = previous.data.refs.filterNot { it.videoPath in record.paths } + record.data.refs),
        changes = previous.changes + 1
    )

    private fun validate(data: VideoTagStore.TagBackupPayload) {
        check(data.version == 1 && data.exportedAt > 0)
        val tags = data.tags.mapTo(HashSet()) { it.scope to it.name }
        check(tags.size == data.tags.size && data.tags.all { it.name.isNotBlank() })
        check(data.refs.all { it.videoPath.isNotBlank() && (it.scope to it.tagName) in tags })
    }

    private fun entries(includePending: Boolean = false): List<Entry> = buildList {
        val selection = "relative_path = ?" + if (includePending) "" else " AND is_pending = 0"
        resolver.query(collection, arrayOf("_id", "_display_name"), selection, arrayOf(directory), null)!!.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1)
                if (name.matches(Regex("checkpoint-[ab]\\.json|change-[0-9]+\\.json"))) {
                    add(Entry(name, ContentUris.withAppendedId(collection, cursor.getLong(0))))
                }
            }
        }
    }

    private fun read(uri: Uri): Record {
        val envelope = resolver.openInputStream(uri)!!.bufferedReader().use { gson.fromJson(it, Envelope::class.java) }
        check(hash(envelope.payload) == envelope.sha256) { "tag_backup_checksum_failed" }
        return gson.fromJson(envelope.payload, Record::class.java).also {
            check(it.version == 1 && it.revision > 0)
            validate(it.data)
            check(it.full || it.data.refs.all { ref -> ref.videoPath in it.paths })
        }
    }

    private fun publish(name: String, record: Record) {
        validate(record.data)
        // Reuse only the inactive checkpoint; changes are never overwritten after publication.
        val existing = entries(includePending = true).firstOrNull { it.name == name }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.RELATIVE_PATH, directory)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = existing?.uri?.also { check(resolver.update(it, values, null, null) == 1) }
            ?: checkNotNull(resolver.insert(collection, values))
        try {
            val payload = gson.toJson(record)
            resolver.openOutputStream(uri, "wt")!!.bufferedWriter().use {
                gson.toJson(Envelope(payload, hash(payload)), it)
            }
            check(read(uri) == record) { "tag_backup_verification_failed" }
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
        } catch (failure: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw failure
        }
    }

    private fun hash(payload: String) = MessageDigest.getInstance("SHA-256")
        .digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
