package com.nkls.nekovideo.components.helpers

import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

internal enum class NekoTrimVideoState(val wire: String, val priority: Int) {
    DONE("done", 0), FAILED("failed", 1), QUEUED("queued", 2), PROCESSING("processing", 3)
}

internal val LocalNekoTrimVideoStatuses = staticCompositionLocalOf<Map<String, NekoTrimVideoState>> { emptyMap() }

/** One observer per connected app while the screen is visible; no polling or per-card requests. */
@Composable
internal fun rememberNekoTrimVideoStatuses(clients: List<String>): Map<String, NekoTrimVideoState> {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by produceState<Map<String, NekoTrimVideoState>>(emptyMap(), clients, lifecycle) {
        value = emptyMap()
        if (clients.isEmpty()) return@produceState
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            callbackFlow {
                val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) { trySend(Unit) }
                }
                clients.forEach { client ->
                    runCatching { context.contentResolver.registerContentObserver(Uri.parse("content://$client.nekovideo.status/status"), true, observer) }
                }
                trySend(Unit)
                awaitClose { context.contentResolver.unregisterContentObserver(observer) }
            }.conflate().collect {
                value = withContext(Dispatchers.IO) {
                    buildMap {
                        clients.forEach { client ->
                            try {
                                if (!NekoTrimConnection.authorized(context, client)) return@forEach
                                val id = context.getSharedPreferences(NekoTrimConnection.PREFS, 0).getString("$client.id", null)
                                    ?: return@forEach
                                context.contentResolver.query(Uri.parse("content://$client.nekovideo.status/status"),
                                    null, null, arrayOf(id), null)?.use { cursor ->
                                    while (cursor.moveToNext()) {
                                        val key = cursor.getString(0)
                                        val status = NekoTrimVideoState.entries.firstOrNull { it.wire == cursor.getString(1) } ?: continue
                                        if (status.priority >= (get(key)?.priority ?: -1)) put(key, status)
                                    }
                                }
                            } catch (error: CancellationException) { throw error }
                            catch (_: Exception) { /* Older or unavailable clients expose no status. */ }
                        }
                    }
                }
            }
        }
    }
    return state
}
