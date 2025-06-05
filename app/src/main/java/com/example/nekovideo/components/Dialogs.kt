package com.example.nekovideo.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.example.nekovideo.components.helpers.FilesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

@Composable
fun CreateFolderDialog(
    currentPath: String,
    onDismiss: () -> Unit,
    onFolderCreated: () -> Unit
) {
    var folderName by remember { mutableStateOf("") }
    var isCreating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = { if (!isCreating) onDismiss() },
        title = { Text("Create New Folder") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isCreating) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Text("Creating folder...")
                    }
                } else {
                    OutlinedTextField(
                        value = folderName,
                        onValueChange = {
                            folderName = it
                            errorMessage = null
                        },
                        label = { Text("Folder Name") },
                        modifier = Modifier.fillMaxWidth(),
                        isError = errorMessage != null
                    )

                    errorMessage?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!isCreating) {
                Button(
                    onClick = {
                        if (folderName.isNotBlank()) {
                            isCreating = true
                            errorMessage = null

                            coroutineScope.launch {
                                val success = withContext(Dispatchers.IO) {
                                    try {
                                        FilesManager.createFolderWithMarker(
                                            context = context,
                                            path = currentPath,
                                            folderName = folderName
                                        )
                                    } catch (e: Exception) {
                                        errorMessage = e.message ?: "Failed to create folder"
                                        false
                                    }
                                }

                                isCreating = false

                                if (success) {
                                    onFolderCreated()
                                    onDismiss()
                                }
                            }
                        } else {
                            errorMessage = "Folder name cannot be empty"
                        }
                    },
                    enabled = folderName.isNotBlank() && !isCreating
                ) {
                    Text("Create")
                }
            }
        },
        dismissButton = {
            if (!isCreating) {
                Button(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = !isCreating,
            dismissOnClickOutside = !isCreating
        )
    )
}

@Composable
fun PasswordDialog(
    onDismiss: () -> Unit,
    onPasswordVerified: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val isFirstTime = !FilesManager.SecureStorage.hasPassword(context)

    AlertDialog(
        onDismissRequest = { if (!isProcessing) onDismiss() },
        title = { Text(if (isFirstTime) "Set Secure Folder Password" else "Enter Password") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isProcessing) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Text("Processing...")
                    }
                } else {
                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            errorMessage = null
                        },
                        label = { Text("Password") },
                        modifier = Modifier.fillMaxWidth(),
                        isError = errorMessage != null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        visualTransformation = PasswordVisualTransformation() // Se quiser manter como senha
                    )
                    if (isFirstTime) {
                        OutlinedTextField(
                            value = confirmPassword,
                            onValueChange = {
                                confirmPassword = it
                                errorMessage = null
                            },
                            label = { Text("Confirm Password") },
                            modifier = Modifier.fillMaxWidth(),
                            isError = errorMessage != null,
                            singleLine = true
                        )
                    }
                    errorMessage?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!isProcessing) {
                Button(
                    onClick = {
                        if (password.isBlank()) {
                            errorMessage = "Password cannot be empty"
                            return@Button
                        }
                        if (isFirstTime && password != confirmPassword) {
                            errorMessage = "Passwords do not match"
                            return@Button
                        }
                        isProcessing = true
                        coroutineScope.launch {
                            val success = withContext(Dispatchers.IO) {
                                if (isFirstTime) {
                                    FilesManager.SecureStorage.savePassword(context, password)
                                } else {
                                    FilesManager.SecureStorage.verifyPassword(context, password)
                                }
                            }
                            isProcessing = false
                            if (success) {
                                if (isFirstTime) {
                                    FilesManager.SecureStorage.ensureSecureFolderExists(context)
                                    //Toast.makeText(context, "Password set successfully", Toast.LENGTH_SHORT).show()
                                }
                                onPasswordVerified()
                            } else {
                                errorMessage = "Invalid password"
                            }
                        }
                    },
                    enabled = password.isNotBlank() && (!isFirstTime || confirmPassword.isNotBlank())
                ) {
                    Text(if (isFirstTime) "Set Password" else "Verify")
                }
            }
        },
        dismissButton = {
            if (!isProcessing) {
                Button(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = !isProcessing,
            dismissOnClickOutside = !isProcessing
        )
    )
}