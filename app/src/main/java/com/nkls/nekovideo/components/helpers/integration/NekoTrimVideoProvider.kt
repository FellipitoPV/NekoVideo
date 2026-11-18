package com.nkls.nekovideo.components.helpers

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.OpenableColumns
import java.io.File

class NekoTrimVideoProvider : ContentProvider() {
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

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
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
        if (mode != "r") throw SecurityException("Videos are read-only")
        val source = source(uri)
        val file = File(source.path)
        if (FolderLockManager.isLocked(checkNotNull(file.parent))) throw SecurityException("Private videos are not supported")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw SecurityException("Videos are read-only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException("Videos are read-only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException("Videos are read-only")
}
