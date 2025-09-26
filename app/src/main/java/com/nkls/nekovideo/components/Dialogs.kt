package com.nkls.nekovideo.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nkls.nekovideo.R
import com.nkls.nekovideo.components.helpers.CastManager
import com.nkls.nekovideo.components.helpers.FilesManager
import com.nkls.nekovideo.language.LanguageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ✅ NOVO: Dialog para renomear um único item
@Composable
fun SingleRenameDialog(
    selectedItem: String,
    onDismiss: () -> Unit,
    onComplete: () -> Unit,
    onRefresh: (() -> Unit)? = null // ✅ NOVO: Callback de refresh
) {
    var newName by remember {
        val file = File(selectedItem)
        val nameWithoutExtension = if (file.isFile) {
            file.nameWithoutExtension
        } else {
            file.name.removePrefix(".")
        }
        mutableStateOf(nameWithoutExtension)
    }
    var isRenaming by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val context = LanguageManager.getLocalizedContext(LocalContext.current)

    Dialog(
        onDismissRequest = { if (!isRenaming) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !isRenaming,
            dismissOnClickOutside = !isRenaming
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Header
                Text(
                    text = if (isRenaming) stringResource(R.string.renaming_files) else stringResource(R.string.rename_item),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (isRenaming) {
                    // Progress Content
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(40.dp),
                            strokeWidth = 3.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.renaming_item),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    // Input Content
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            text = "Item: ${File(selectedItem).name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            label = { Text(stringResource(R.string.new_name)) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isRenaming,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                            )
                        )
                    }

                    // Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                        Button(
                            onClick = {
                                if (newName.trim().isNotEmpty()) {
                                    isRenaming = true
                                    coroutineScope.launch {
                                        val file = File(selectedItem)
                                        val extension = if (file.isFile) ".${file.extension}" else ""
                                        val isPrivateFolder = file.isDirectory && file.name.startsWith(".")
                                        val finalName = if (isPrivateFolder) ".$newName" else newName

                                        val newFile = File(file.parent, "$finalName$extension")
                                        val success = file.renameTo(newFile)

                                        if (success) {
                                            onRefresh?.invoke()
                                        }

                                        isRenaming = false
                                        onComplete()
                                        onDismiss()
                                    }
                                }
                            },
                            enabled = newName.trim().isNotEmpty(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(stringResource(R.string.rename))
                        }
                    }
                }
            }
        }
    }
}

// ✅ ATUALIZADO: Dialog para renomear múltiplos itens
@Composable
fun MultipleRenameDialog(
    selectedItems: List<String>,
    onDismiss: () -> Unit,
    onComplete: () -> Unit,
    onRefresh: (() -> Unit)? = null // ✅ NOVO: Callback de refresh
) {
    var baseName by remember { mutableStateOf("") }
    var startNumber by remember { mutableStateOf("1") }
    var isRenaming by remember { mutableStateOf(false) }
    var currentProgress by remember { mutableStateOf(0) }
    var totalItems by remember { mutableStateOf(selectedItems.size) }
    val coroutineScope = rememberCoroutineScope()
    val context = LanguageManager.getLocalizedContext(LocalContext.current)

    Dialog(
        onDismissRequest = { if (!isRenaming) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !isRenaming,
            dismissOnClickOutside = !isRenaming
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Header
                Text(
                    text = if (isRenaming) stringResource(R.string.renaming_files) else stringResource(R.string.rename_multiple_items),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (isRenaming) {
                    // Progress Content
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(40.dp),
                            strokeWidth = 3.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.renaming_progress, currentProgress, totalItems),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    // Input Content
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            text = stringResource(R.string.selected_items_count, selectedItems.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedTextField(
                            value = baseName,
                            onValueChange = { baseName = it },
                            label = { Text(stringResource(R.string.base_name)) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isRenaming,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                            )
                        )
                        OutlinedTextField(
                            value = startNumber,
                            onValueChange = { if (it.matches(Regex("\\d*"))) startNumber = it },
                            label = { Text(stringResource(R.string.start_number)) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isRenaming,
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                            )
                        )
                    }

                    // Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                        Button(
                            onClick = {
                                isRenaming = true
                                coroutineScope.launch {
                                    FilesManager.renameSelectedItems(
                                        context,
                                        selectedItems,
                                        baseName.trim(),
                                        startNumber.toIntOrNull() ?: 1,
                                        onProgress = { current, total ->
                                            currentProgress = current
                                            totalItems = total
                                        },
                                        onRefresh = onRefresh // ✅ NOVO: Passar callback
                                    )
                                    isRenaming = false
                                    onComplete()
                                    onDismiss()
                                }
                            },
                            enabled = baseName.trim().isNotEmpty() && startNumber.toIntOrNull() != null,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(stringResource(R.string.rename))
                        }
                    }
                }
            }
        }
    }
}

// ✅ MANTER: Dialog original como fallback (pode ser removido depois se não for usado)
@Composable
fun RenameDialog(
    selectedItems: List<String>,
    onDismiss: () -> Unit,
    onComplete: () -> Unit,
    onRefresh: (() -> Unit)? = null // ✅ NOVO: Callback de refresh
) {
    // Automaticamente escolher o dialog correto baseado na quantidade
    if (selectedItems.size == 1) {
        SingleRenameDialog(
            selectedItem = selectedItems.first(),
            onDismiss = onDismiss,
            onComplete = onComplete,
            onRefresh = onRefresh
        )
    } else {
        MultipleRenameDialog(
            selectedItems = selectedItems,
            onDismiss = onDismiss,
            onComplete = onComplete,
            onRefresh = onRefresh
        )
    }
}

@Composable
fun CreateFolderDialog(
    currentPath: String,
    onDismiss: () -> Unit,
    onFolderCreated: () -> Unit,
    onRefresh: (() -> Unit)? = null // ✅ NOVO: Callback de refresh
) {
    var folderName by remember { mutableStateOf("") }
    var isCreating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val context = LanguageManager.getLocalizedContext(LocalContext.current)

    Dialog(
        onDismissRequest = { if (!isCreating) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !isCreating,
            dismissOnClickOutside = !isCreating
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Header
                Text(
                    text = stringResource(R.string.create_new_folder),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (isCreating) {
                    // Progress Content
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 3.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.creating_folder),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    // Input Content
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = folderName,
                            onValueChange = {
                                folderName = it
                                errorMessage = null
                            },
                            label = { Text(stringResource(R.string.folder_name)) },
                            modifier = Modifier.fillMaxWidth(),
                            isError = errorMessage != null,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                errorBorderColor = MaterialTheme.colorScheme.error
                            )
                        )

                        errorMessage?.let { error ->
                            Text(
                                text = error,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }

                    // Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
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
                                                    folderName = folderName,
                                                    onRefresh = onRefresh // ✅ NOVO: Passar callback
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
                            enabled = folderName.isNotBlank() && !isCreating,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(stringResource(R.string.create))
                        }
                    }
                }
            }
        }
    }
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
    val context = LanguageManager.getLocalizedContext(LocalContext.current)
    val isFirstTime = !FilesManager.SecureStorage.hasPassword(context)

    Dialog(
        onDismissRequest = { if (!isProcessing) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !isProcessing,
            dismissOnClickOutside = !isProcessing
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Header
                Text(
                    text = if (isFirstTime) {
                        stringResource(R.string.set_secure_password)
                    } else {
                        stringResource(R.string.enter_password)
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (isProcessing) {
                    // Progress Content
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 3.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.processing),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    // Input Content
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        OutlinedTextField(
                            value = password,
                            onValueChange = {
                                password = it
                                errorMessage = null
                            },
                            label = { Text(stringResource(R.string.password)) },
                            modifier = Modifier.fillMaxWidth(),
                            isError = errorMessage != null,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            visualTransformation = PasswordVisualTransformation(),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                errorBorderColor = MaterialTheme.colorScheme.error
                            )
                        )

                        if (isFirstTime) {
                            OutlinedTextField(
                                value = confirmPassword,
                                onValueChange = {
                                    confirmPassword = it
                                    errorMessage = null
                                },
                                label = { Text(stringResource(R.string.confirm_password)) },
                                modifier = Modifier.fillMaxWidth(),
                                isError = errorMessage != null,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                visualTransformation = PasswordVisualTransformation(),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                    errorBorderColor = MaterialTheme.colorScheme.error
                                )
                            )
                        }

                        errorMessage?.let { error ->
                            Text(
                                text = error,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }

                    // Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
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
                                        }
                                        onPasswordVerified()
                                    } else {
                                        errorMessage = "Invalid password"
                                    }
                                }
                            },
                            enabled = password.isNotBlank() && (!isFirstTime || confirmPassword.isNotBlank()),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                if (isFirstTime) {
                                    stringResource(R.string.set_password)
                                } else {
                                    stringResource(R.string.verify)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DeleteConfirmationDialog(
    itemCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Header
                Text(
                    text = stringResource(R.string.confirm_deletion),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Content
                Text(
                    text = stringResource(R.string.delete_confirmation_message, itemCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                ) {
                    TextButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                    Button(
                        onClick = onConfirm,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(stringResource(R.string.delete))
                    }
                }
            }
        }
    }
}