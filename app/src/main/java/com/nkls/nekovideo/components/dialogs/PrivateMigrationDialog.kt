package com.nkls.nekovideo.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nkls.nekovideo.BuildConfig
import com.nkls.nekovideo.R
import com.nkls.nekovideo.components.helpers.LockedPlaybackSession
import com.nkls.nekovideo.components.helpers.PrivateVideoMigration
import com.nkls.nekovideo.components.helpers.PrivateMigrationHistory
import com.nkls.nekovideo.components.helpers.SortRowMessageCenter
import com.nkls.nekovideo.services.FolderVideoScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun PrivateMigrationPrompt(videoCount: Int, folderCount: Int, onLater: () -> Unit, onUpdate: () -> Unit) {
    val resources = LocalContext.current.resources
    val bottomSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    AppBottomSheet(
        onDismissRequest = onLater,
        sheetState = bottomSheetState,
        title = stringResource(R.string.private_migration_title)
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = resources.getQuantityString(R.plurals.private_migration_offer, videoCount, videoCount, folderCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(
                    onClick = onLater,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                ) { Text(stringResource(R.string.private_migration_later)) }
                Button(
                    onClick = onUpdate,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) { Text(stringResource(R.string.private_migration_update)) }
            }
        }
    }
}

/** Observes the existing scan; it cannot convert anything until the user confirms after authentication. */
@Composable
internal fun PrivateMigrationCoordinator(password: String?, privateVisible: Boolean, busy: Boolean,
                                         candidatePaths: Collection<String>? = null, onCompleted: (List<String>) -> Unit) {
    if (!BuildConfig.PORTABLE_PRIVATE_WRITES) return
    val context = LocalContext.current
    val scanning by FolderVideoScanner.isScanning.collectAsState()
    val checked by FolderVideoScanner.migrationCheck.collectAsState()
    val scope = rememberCoroutineScope()
    var offered by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PrivateVideoMigration.Plan?>(null) }
    var running by remember { mutableStateOf(false) }
    var completed by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }

    LaunchedEffect(privateVisible) {
        if (!privateVisible) { pending = null; offered = false }
    }

    LaunchedEffect(password, privateVisible, checked, scanning, busy, running, candidatePaths) {
        if (password == null || !privateVisible || scanning || FolderVideoScanner.isScanning.value || busy || running || offered) return@LaunchedEffect
        if (password != LockedPlaybackSession.sessionPassword) return@LaunchedEffect
        val ready = FolderVideoScanner.migrationCheck.value?.takeIf {
            it.credentialVersion == LockedPlaybackSession.credentialVersion && it.pathsOverride == candidatePaths?.toList()
        }
        if (ready == null) {
            FolderVideoScanner.requestPrivateMigrationCheck(context, password, candidatePaths)
            return@LaunchedEffect
        }
        val plan = ready.plan
        if (plan.folders.isNotEmpty()) {
            offered = true
            pending = plan
        } else if (plan.unavailableFolders > 0) {
            offered = true
            SortRowMessageCenter.showError(context.getString(R.string.private_migration_unavailable, plan.unavailableFolders))
        }
    }

    if (pending != null && privateVisible && !scanning && !busy && password != null) {
        val plan = checkNotNull(pending)
        PrivateMigrationPrompt(plan.videoCount, plan.folders.size,
            onLater = { pending = null },
            onUpdate = {
                pending = null
                running = true
                completed = 0
                total = plan.videoCount
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            val jobContext = currentCoroutineContext()
                            PrivateVideoMigration.migrate(context, plan, password,
                                progress = { done, count -> completed = done; total = count },
                                checkpoint = { _, _ -> jobContext.ensureActive() })
                        }
                        if (result.failedFolders > 0) {
                            SortRowMessageCenter.showError(context.getString(R.string.private_migration_partial,
                                result.updatedVideos, result.failedFolders))
                        } else {
                            SortRowMessageCenter.showSuccess(context.getString(R.string.private_migration_complete, result.updatedVideos))
                        }
                        if (candidatePaths == null) {
                            withContext(Dispatchers.IO) { PrivateMigrationHistory(context).observe(plan.folders, false) }
                        }
                        onCompleted(plan.folders)
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        SortRowMessageCenter.showError(context.getString(R.string.private_migration_retry))
                    } finally { running = false }
                }
            })
    }
    if (running) ProcessingDialog(stringResource(R.string.private_migration_title),
        stringResource(R.string.private_migration_progress, completed, total))
}
