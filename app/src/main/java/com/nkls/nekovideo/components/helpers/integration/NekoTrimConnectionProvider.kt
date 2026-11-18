package com.nkls.nekovideo.components.helpers

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle

/** Only connection status and revocation are exposed by this version of the protocol. */
class NekoTrimConnectionProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val context = checkNotNull(context)
        val client = callingPackage
        if (client !in NekoTrimConnection.clients ||
            !context.packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty().contains(client)) {
            throw SecurityException("Unsupported NekoTrim client")
        }
        val id = extras?.getString(NekoTrimConnection.CONNECTION_ID)
        val connected = id != null && NekoTrimConnection.authorized(context, checkNotNull(client), id)
        when (method) {
            "status" -> Unit
            "disconnect" -> if (connected) NekoTrimConnection.revoke(context, checkNotNull(client))
            else -> throw IllegalArgumentException("Unsupported connection request")
        }
        return Bundle().apply {
            putInt(NekoTrimConnection.PROTOCOL, NekoTrimConnection.PROTOCOL_VERSION)
            putBoolean(NekoTrimConnection.CONNECTED, connected && method != "disconnect")
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
        throw UnsupportedOperationException()
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
