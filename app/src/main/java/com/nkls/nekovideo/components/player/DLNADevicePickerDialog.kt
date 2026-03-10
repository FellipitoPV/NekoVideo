package com.nkls.nekovideo.components.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nkls.nekovideo.components.helpers.DLNACastManager

@Composable
fun DLNADevicePickerDialog(
    devices: List<DLNACastManager.DLNADevice>,
    isDiscovering: Boolean,
    onDeviceSelected: (DLNACastManager.DLNADevice) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Transmitir para...") },
        text = {
            Column {
                if (isDiscovering) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(bottom = 12.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Procurando dispositivos DLNA…", style = MaterialTheme.typography.bodySmall)
                    }
                }

                if (devices.isEmpty() && !isDiscovering) {
                    Text(
                        "Nenhum dispositivo DLNA encontrado na rede.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    LazyColumn {
                        items(devices) { device ->
                            ListItem(
                                headlineContent = { Text(device.name) },
                                leadingContent = {
                                    Icon(Icons.Default.Cast, contentDescription = null)
                                },
                                modifier = Modifier.clickable { onDeviceSelected(device) }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}
