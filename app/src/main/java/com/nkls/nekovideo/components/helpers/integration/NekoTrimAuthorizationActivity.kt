package com.nkls.nekovideo.components.helpers

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nkls.nekovideo.R
import com.nkls.nekovideo.components.AppBottomSheet
import com.nkls.nekovideo.theme.NekoVideoTheme
import com.nkls.nekovideo.theme.ThemeManager

class NekoTrimAuthorizationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        val client = callingPackage
        if (client !in NekoTrimConnection.clients ||
            intent.getIntExtra(NekoTrimConnection.PROTOCOL, 0) != NekoTrimConnection.PROTOCOL_VERSION) {
            finish()
            return
        }
        val clientPackage = checkNotNull(client)
        setContent {
            NekoVideoTheme(themeManager = remember { ThemeManager(this) }) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppBottomSheet(
                        onDismissRequest = { finish() },
                        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                        title = stringResource(R.string.nekotrim_connection_authorize)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(stringResource(R.string.nekotrim_connection_request, NekoTrimConnection.label(clientPackage)))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { finish() }) { Text(stringResource(R.string.cancel)) }
                                Button(onClick = {
                                    val id = NekoTrimConnection.grant(this@NekoTrimAuthorizationActivity, clientPackage)
                                    setResult(Activity.RESULT_OK, Intent().apply {
                                        putExtra(NekoTrimConnection.CONNECTION_ID, id)
                                        putExtra(NekoTrimConnection.HOST_PACKAGE, packageName)
                                        putExtra(NekoTrimConnection.PROTOCOL, NekoTrimConnection.PROTOCOL_VERSION)
                                    })
                                    finish()
                                }) { Text(stringResource(R.string.nekotrim_connection_allow)) }
                            }
                        }
                    }
                }
            }
        }
    }
}
