package com.nkls.nekovideo.components.helpers

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.Handler
import android.os.HandlerThread
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.os.Bundle
import android.system.ErrnoException
import android.system.OsConstants
import android.provider.OpenableColumns
import java.io.File

class NekoTrimVideoProvider : ContentProvider() {
    private val reads by lazy { Handler(HandlerThread("NekoTrimPrivateRead").apply { start() }.looper) }
    override fun onCreate() = true

    private fun source(uri: Uri): NekoTrimVideoAccess.Source {
        val context = checkNotNull(context)
        val source = NekoTrimVideoAccess.source(context, uri)
        if (Binder.getCallingUid() != Process.myUid()) {
            val caller = callingPackage
            if (caller != source.client ||
                !context.packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty().contains(caller) ||
                !NekoTrimConnection.authorized(context, source.client, source.connectionId)) {
                throw SecurityException("Video access requires the authorized NekoTrim connection")
            }
        }
        return source
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val client = checkNotNull(callingPackage)
        val uid = Binder.getCallingUid()
        val identity = Binder.clearCallingIdentity()
        return try { NekoTrimOutputAccess.call(checkNotNull(context), client, uid, method, arg, extras) }
        finally { Binder.restoreCallingIdentity(identity) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        if (uri.pathSegments.firstOrNull() == "output") return NekoTrimOutputAccess.query(checkNotNull(context), checkNotNull(callingPackage), Binder.getCallingUid(), uri, projection)
        val source = source(uri)
        val columns = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE || it == NekoTrimVideoAccess.SOURCE_KEY }?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map {
                when (it) {
                    OpenableColumns.DISPLAY_NAME -> source.name
                    OpenableColumns.SIZE -> source.size
                    else -> NekoTrimVideoAccess.videoKey(File(source.path))
                }
            })
        }
    }

    override fun getType(uri: Uri) = "video/*"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (uri.pathSegments.firstOrNull() == "output") return NekoTrimOutputAccess.open(checkNotNull(context), checkNotNull(callingPackage), Binder.getCallingUid(), uri, mode)
        if (mode != "r") throw SecurityException("Videos are read-only")
        val source = source(uri)
        val file = File(source.path)
        if (!source.locked) {
            check(!FolderLockManager.isLocked(checkNotNull(file.parent)))
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        val key = checkNotNull(LockedPlaybackSession.getXorKeyForFile(file.path)) { "Unlock the source folder first" }
        val input = XorMediaDataSource(file, key)
        val callback = object : ProxyFileDescriptorCallback() {
            override fun onGetSize() = input.getSize()
            override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                val prefs = checkNotNull(context).getSharedPreferences(NekoTrimConnection.PREFS, 0)
                if (prefs.getString("${source.client}.id", null) != source.connectionId || LockedPlaybackSession.getXorKeyForFile(file.path) == null)
                    throw ErrnoException("read", OsConstants.EACCES)
                return try { input.readAt(offset, data, 0, size).coerceAtLeast(0) }
                catch (error: Exception) { throw ErrnoException("read", OsConstants.EIO, error) }
            }
            override fun onRelease() { input.close() }
        }
        val identity = Binder.clearCallingIdentity()
        return try { checkNotNull(context).getSystemService(StorageManager::class.java)
            .openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, reads) }
        catch (error: Exception) { input.close(); throw error }
        finally { Binder.restoreCallingIdentity(identity) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw SecurityException("Videos are read-only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException("Videos are read-only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException("Videos are read-only")
}
