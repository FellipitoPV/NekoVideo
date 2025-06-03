package com.example.nekovideo.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.example.nekovideo.components.helpers.FilesManager
import kotlinx.coroutines.launch

@Composable
fun RenameDialog(
    selectedItems: List<String>,
    onDismiss: () -> Unit,
    onComplete: () -> Unit
) {
    var baseName by remember { mutableStateOf("") }
    var startNumber by remember { mutableStateOf("1") }
    var isRenaming by remember { mutableStateOf(false) }
    var currentProgress by remember { mutableStateOf(0) }
    var totalItems by remember { mutableStateOf(selectedItems.size) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = { if (!isRenaming) onDismiss() },
        title = { Text(if (isRenaming) "Renaming Files" else "Rename Files") },
        text = {
            if (isRenaming) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator()
                    Text("Renaming file $currentProgress of $totalItems")
                }
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = baseName,
                        onValueChange = { baseName = it },
                        label = { Text("Base Name") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isRenaming
                    )
                    OutlinedTextField(
                        value = startNumber,
                        onValueChange = { if (it.matches(Regex("\\d*"))) startNumber = it },
                        label = { Text("Start Number") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isRenaming
                    )
                }
            }
        },
        confirmButton = {
            if (!isRenaming) {
                Button(
                    onClick = {
                        isRenaming = true
                        coroutineScope.launch {
                            FilesManager.renameSelectedItems(
                                context,
                                selectedItems,
                                baseName.trim(),
                                startNumber.toIntOrNull() ?: 1
                            ) { current, total ->
                                currentProgress = current
                                totalItems = total
                            }
                            isRenaming = false
                            onComplete()
                            onDismiss()
                        }
                    },
                    enabled = baseName.trim().isNotEmpty() && startNumber.toIntOrNull() != null
                ) {
                    Text("Rename")
                }
            }
        },
        dismissButton = {
            if (!isRenaming) {
                Button(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = !isRenaming,
            dismissOnClickOutside = !isRenaming
        )
    )
}