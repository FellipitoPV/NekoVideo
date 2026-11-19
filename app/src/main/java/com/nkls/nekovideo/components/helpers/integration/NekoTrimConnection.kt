package com.nkls.nekovideo.components.helpers

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.edit
import java.security.MessageDigest
import java.util.UUID

internal object NekoTrimConnection {
    const val PROTOCOL_VERSION = 1
    const val CONNECTION_ID = "connection_id"
    const val HOST_PACKAGE = "host_package"
    const val PROTOCOL = "protocol_version"
    const val CONNECTED = "connected"
    const val PREFS = "nekotrim_connection"
    val clients = listOf("com.nkls.nekotrim", "com.nkls.nekotrim.debug")

    private fun preferences(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun uri(context: Context): Uri = Uri.parse("content://${context.packageName}.nekotrim/connection")

    // A reinstall or replacement signing certificate requires another authorization.
    private fun identity(context: Context, packageName: String): String? = runCatching {
        val info = context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val certificates = checkNotNull(info.signingInfo).apkContentsSigners.map {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
        }.sorted().joinToString(":")
        "${info.firstInstallTime}:$certificates"
    }.getOrNull()

    fun grant(context: Context, client: String): String {
        require(client in clients)
        val identity = checkNotNull(identity(context, client))
        val id = UUID.randomUUID().toString()
        check(preferences(context).edit().putString("$client.identity", identity).putString("$client.id", id).commit())
        context.contentResolver.notifyChange(uri(context), null)
        return id
    }

    fun authorized(context: Context, client: String, connectionId: String? = null): Boolean {
        if (client !in clients) return false
        val prefs = preferences(context)
        val storedIdentity = prefs.getString("$client.identity", null) ?: return false
        val storedId = prefs.getString("$client.id", null) ?: return false
        return storedIdentity == identity(context, client) && (connectionId == null || connectionId == storedId)
    }

    fun revoke(context: Context, client: String) {
        NekoTrimOutputAccess.revoke(context, client)
        preferences(context).edit { remove("$client.identity"); remove("$client.id") }
        NekoTrimVideoAccess.revoke(context, client)
        context.contentResolver.notifyChange(uri(context), null)
    }

    fun label(client: String): String = if (client.endsWith(".debug")) "NekoTrim Debug" else "NekoTrim"
}
