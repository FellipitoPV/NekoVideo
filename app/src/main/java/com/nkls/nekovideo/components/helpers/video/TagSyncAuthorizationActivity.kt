package com.nkls.nekovideo.components.helpers

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nkls.nekovideo.R
import com.nkls.nekovideo.components.AppBottomSheet
import com.nkls.nekovideo.theme.NekoVideoTheme
import com.nkls.nekovideo.theme.ThemeManager
import kotlinx.coroutines.launch

class TagSyncAuthorizationActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (callingPackage != TagSyncBridge.CLIENT) { setResult(Activity.RESULT_CANCELED); finish(); return }
        setContent { NekoVideoTheme(themeManager = remember { ThemeManager(this) }) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                AppBottomSheet(onDismissRequest = { finish() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    title = stringResource(R.string.tag_sync_authorize_title)) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.tag_sync_authorize_description))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { finish() }) { Text(stringResource(R.string.action_cancel)) }
                            Button(onClick = {
                                TagSyncBridge.authorize(this@TagSyncAuthorizationActivity)
                                setResult(Activity.RESULT_OK); finish()
                            }) { Text(stringResource(R.string.tag_sync_allow)) }
                        }
                    }
                }
            }
        } }
    }
}

@Composable
internal fun TagSyncSettingsItem() {
    val context = LocalContext.current
    val revision by TagSyncBridge.revision.collectAsState()
    val client = TagSyncBridge.isClient(context)
    val enabled = remember(revision) { TagSyncBridge.enabled(context) }
    val authorized = remember(revision) { TagSyncBridge.authorized(context) }
    if (!client && !authorized) return
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) scope.launch {
            runCatching { TagSyncBridge.connect(context) }.onFailure {
                SortRowMessageCenter.showError(context.getString(R.string.tag_sync_unavailable))
            }
        }
    }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.tag_sync_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(if (enabled || !client) R.string.tag_sync_connected_description else R.string.tag_sync_description))
            TextButton(onClick = {
                when {
                    !client -> TagSyncBridge.revoke(context)
                    enabled -> TagSyncBridge.disconnect(context)
                    else -> runCatching { launcher.launch(TagSyncBridge.authorizationIntent()) }.onFailure {
                        SortRowMessageCenter.showError(context.getString(R.string.tag_sync_unavailable))
                    }
                }
            }) { Text(stringResource(when { !client -> R.string.tag_sync_revoke; enabled -> R.string.tag_sync_disconnect; else -> R.string.tag_sync_connect })) }
        }
    }
}
