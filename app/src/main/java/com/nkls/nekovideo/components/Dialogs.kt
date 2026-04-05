package com.nkls.nekovideo.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Fingerprint
import android.content.ContextWrapper
import androidx.fragment.app.FragmentActivity
import com.nkls.nekovideo.components.helpers.BiometricHelper
import com.nkls.nekovideo.components.helpers.FolderLockManager
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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

@Composable
fun SingleRenameDialog(
    selectedItem: String,
    onDismiss: () -> Unit,
    onComplete: () -> Unit,
    onRefresh: (() -> Unit)? = null
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
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

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
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = if (isRenaming) stringResource(R.string.renaming_files) else stringResource(R.string.rename_item),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (isRenaming) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 3.dp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Text(
                            text = stringResource(R.string.renaming_item),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    fun performRename() {
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
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Item: ${File(selectedItem).name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            label = { Text(stringResource(R.string.new_name)) },
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                            enabled = !isRenaming,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { performRename() }),
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                            )
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                        Button(
                            onClick = { performRename() },
                            enabled = newName.trim().isNotEmpty(),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                                contentColor = MaterialTheme.colorScheme.onSurface
                            )
                        ) {
                            Text(stringResource(R.string.rename))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LockedRenameDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit
) {
    val nameWithoutExtension = currentName.substringBeforeLast(".")
    val extension = if (currentName.contains(".")) ".${currentName.substringAfterLast(".")}" else ""
    var newName by remember { mutableStateOf(nameWithoutExtension) }
    val context = LanguageManager.getLocalizedContext(LocalContext.current)
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.rename_item),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                fun performLockedRename() {
                    if (newName.trim().isNotEmpty()) {
                        onRename("${newName.trim()}$extension")
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Item: $currentName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text(stringResource(R.string.new_name)) },
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { performLockedRename() }),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        )
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    TextButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                    Button(
                        onClick = { performLockedRename() },
                        enabled = newName.trim().isNotEmpty(),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Text(stringResource(R.string.rename))
                    }
                }
            }
        }
    }
}

@Composable
fun MultipleRenameDialog(
    selectedItems: List<String>,
    onDismiss: () -> Unit,
    onComplete: () -> Unit,
    onRefresh: (() -> Unit)? = null
) {
    var baseName by remember { mutableStateOf("") }
    var startNumber by remember { mutableStateOf("1") }
    var isRenaming by remember { mutableStateOf(false) }
    var currentProgress by remember { mutableStateOf(0) }
    var totalItems by remember { mutableStateOf(selectedItems.size) }
    val coroutineScope = rememberCoroutineScope()
    val context = LanguageManager.getLocalizedContext(LocalContext.current)
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

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
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = if (isRenaming) stringResource(R.string.renaming_files) else stringResource(R.string.rename_multiple_items),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (isRenaming) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 3.dp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Text(
                            text = stringResource(R.string.renaming_progress, currentProgress, totalItems),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    fun performMultiRename() {
                        if (baseName.trim().isNotEmpty() && startNumber.toIntOrNull() != null) {
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
                                    onRefresh = onRefresh
                                )
                                isRenaming = false
                                onComplete()
                                onDismiss()
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = stringResource(R.string.selected_items_count, selectedItems.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedTextField(
                            value = baseName,
                            onValueChange = { baseName = it },
                            label = { Text(stringResource(R.string.base_name)) },
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                            enabled = !isRenaming,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                            )
                        )
                        OutlinedTextField(
                            value = startNumber,
                            onValueChange = { if (it.matches(Regex("\\d*"))) startNumber = it },
                            label = { Text(stringResource(R.string.start_number)) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isRenaming,
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { performMultiRename() }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                            )
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                        Button(
                            onClick = { performMultiRename() },
                            enabled = baseName.trim().isNotEmpty() && startNumber.toIntOrNull() != null,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                                contentColor = MaterialTheme.colorScheme.onSurface
                            )
                        ) {
                            Text(stringResource(R.string.rename))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RenameDialog(
    selectedItems: List<String>,
    onDismiss: () -> Unit,
    onComplete: () -> Unit,
    onRefresh: (() -> Unit)? = null
) {
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
    isInsideLockedFolder: Boolean = false,
    onDismiss: () -> Unit,
    onFolderCreated: (String) -> Unit,
    onRefresh: (() -> Unit)? = null
) {
    var folderName by remember { mutableStateOf("") }
    var isCreating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val context = LanguageManager.getLocalizedContext(LocalContext.current)
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

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
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.create_new_folder),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (isCreating) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Text(
                            text = stringResource(R.string.creating_folder),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    fun performCreateFolder() {
                        if (folderName.isNotBlank()) {
                            if (isInsideLockedFolder) {
                                onFolderCreated(folderName.trim())
                                onDismiss()
                            } else {
                                isCreating = true
                                errorMessage = null

                                coroutineScope.launch {
                                    val success = withContext(Dispatchers.IO) {
                                        try {
                                            FilesManager.createFolderWithMarker(
                                                context = context,
                                                path = currentPath,
                                                folderName = folderName,
                                                onRefresh = onRefresh
                                            )
                                        } catch (e: Exception) {
                                            errorMessage = e.message ?: "Failed to create folder"
                                            false
                                        }
                                    }

                                    isCreating = false

                                    if (success) {
                                        onFolderCreated(folderName.trim())
                                        onDismiss()
                                    }
                                }
                            }
                        } else {
                            errorMessage = "Folder name cannot be empty"
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = folderName,
                            onValueChange = {
                                folderName = it
                                errorMessage = null
                            },
                            label = { Text(stringResource(R.string.folder_name)) },
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                            isError = errorMessage != null,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { performCreateFolder() }),
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                        Button(
                            onClick = { performCreateFolder() },
                            enabled = folderName.isNotBlank() && !isCreating,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                                contentColor = MaterialTheme.colorScheme.onSurface
                            )
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
    onPasswordVerified: (String) -> Unit,
    onFirstTimePasswordCreated: ((String) -> Unit)? = null
) {
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val context = LanguageManager.getLocalizedContext(LocalContext.current)
    val view = LocalView.current
    val activity = remember(view) {
        var ctx: android.content.Context = view.context
        while (ctx is ContextWrapper && ctx !is FragmentActivity) ctx = ctx.baseContext
        ctx as FragmentActivity
    }
    val isFirstTime = !FilesManager.SecureStorage.hasPassword(context)
    val focusRequester = remember { FocusRequester() }

    val biometricEnabled = remember { BiometricHelper.isBiometricEnabled(context) }
    val showBiometricFirst = biometricEnabled && !isFirstTime
    var showManualEntry by remember { mutableStateOf(!showBiometricFirst) }

    LaunchedEffect(Unit) {
        if (showBiometricFirst) {
            BiometricHelper.authenticate(
                activity = activity,
                title = context.getString(R.string.biometric_unlock_title),
                negativeText = context.getString(R.string.biometric_use_password),
                onSuccess = { pwd -> onPasswordVerified(pwd) },
                onFallback = { showManualEntry = true },
                onError = { showManualEntry = true }
            )
        }
    }
    LaunchedEffect(showManualEntry) {
        if (showManualEntry) {
            try { focusRequester.requestFocus() } catch (_: Exception) {}
        }
    }

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
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = if (isFirstTime) {
                        stringResource(R.string.set_secure_password)
                    } else {
                        stringResource(R.string.enter_password)
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (isProcessing) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Text(
                            text = stringResource(R.string.processing),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (!showManualEntry) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fingerprint,
                            contentDescription = null,
                            modifier = Modifier.size(52.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.biometric_scanning),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        TextButton(onClick = { showManualEntry = true }) {
                            Text(stringResource(R.string.biometric_use_password))
                        }
                    }
                } else {
                    fun performPasswordSubmit() {
                        if (password.isBlank()) {
                            errorMessage = "Password cannot be empty"
                            return
                        }
                        if (isFirstTime && password != confirmPassword) {
                            errorMessage = "Passwords do not match"
                            return
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
                                    onFirstTimePasswordCreated?.invoke(password)
                                }
                                onPasswordVerified(password)
                            } else {
                                errorMessage = "Invalid password"
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = password,
                            onValueChange = {
                                password = it
                                errorMessage = null
                            },
                            label = { Text(stringResource(R.string.password)) },
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                            isError = errorMessage != null,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = if (isFirstTime) ImeAction.Next else ImeAction.Done
                            ),
                            keyboardActions = if (!isFirstTime) KeyboardActions(onDone = { performPasswordSubmit() }) else KeyboardActions.Default,
                            visualTransformation = PasswordVisualTransformation(),
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
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
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(onDone = { performPasswordSubmit() }),
                                visualTransformation = PasswordVisualTransformation(),
                                shape = RoundedCornerShape(10.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                        Button(
                            onClick = { performPasswordSubmit() },
                            enabled = password.isNotBlank() && (!isFirstTime || confirmPassword.isNotBlank()),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                                contentColor = MaterialTheme.colorScheme.onSurface
                            )
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
fun EnableBiometricDialog(
    password: String,
    onDismiss: () -> Unit,
    onEnabled: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = remember(view) {
        var ctx: android.content.Context = view.context
        while (ctx is ContextWrapper && ctx !is FragmentActivity) ctx = ctx.baseContext
        ctx as FragmentActivity
    }
    var isEnabling by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = { if (!isEnabling) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !isEnabling, dismissOnClickOutside = !isEnabling)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Fingerprint,
                    contentDescription = null,
                    modifier = Modifier.size(52.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.biometric_enable_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(R.string.biometric_enable_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    TextButton(onClick = onDismiss, enabled = !isEnabling) {
                        Text(stringResource(R.string.biometric_not_now))
                    }
                    Button(
                        onClick = {
                            isEnabling = true
                            BiometricHelper.enable(
                                activity = activity,
                                password = password,
                                title = context.getString(R.string.biometric_enable_prompt_title),
                                subtitle = context.getString(R.string.biometric_enable_prompt_subtitle),
                                negativeText = context.getString(R.string.biometric_enable_cancel),
                                onSuccess = { onEnabled() },
                                onError = { isEnabling = false; onDismiss() }
                            )
                        },
                        enabled = !isEnabling,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(stringResource(R.string.biometric_enable_confirm))
                    }
                }
            }
        }
    }
}

private enum class ChangePasswordStep { EnterOld, EnterNew, Processing }

@Composable
fun ChangePasswordDialog(
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = remember(view) {
        var ctx: android.content.Context = view.context
        while (ctx is ContextWrapper && ctx !is FragmentActivity) ctx = ctx.baseContext
        ctx as FragmentActivity
    }
    val coroutineScope = rememberCoroutineScope()
    var step by remember { mutableStateOf(ChangePasswordStep.EnterOld) }
    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var progressText by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(step) {
        when (step) {
            ChangePasswordStep.Processing -> {
                progressText = context.getString(R.string.change_password_processing)
                var failed = false
                withContext(Dispatchers.IO) {
                    FolderLockManager.reKeyAllFolders(
                        context = context,
                        oldPassword = oldPassword,
                        newPassword = newPassword,
                        onProgress = { foldersDone, foldersTotal, _, _ ->
                            coroutineScope.launch(Dispatchers.Main) {
                                progressText = "${context.getString(R.string.change_password_processing)} ($foldersDone/$foldersTotal)"
                            }
                        },
                        onError = { msg ->
                            failed = true
                            coroutineScope.launch(Dispatchers.Main) { errorMessage = msg }
                        }
                    )
                }
                if (failed) { step = ChangePasswordStep.EnterNew; return@LaunchedEffect }

                FilesManager.SecureStorage.savePassword(context, newPassword)

                if (BiometricHelper.isBiometricEnabled(context)) {
                    BiometricHelper.disable(context)
                    BiometricHelper.enable(
                        activity = activity,
                        password = newPassword,
                        title = context.getString(R.string.biometric_reactivate_prompt_title),
                        subtitle = context.getString(R.string.biometric_reactivate_prompt_subtitle),
                        negativeText = context.getString(R.string.biometric_enable_cancel),
                        onSuccess = {
                            android.widget.Toast.makeText(context, context.getString(R.string.biometric_reactivated), android.widget.Toast.LENGTH_SHORT).show()
                            onSuccess()
                        },
                        onError = {
                            android.widget.Toast.makeText(context, context.getString(R.string.biometric_deactivated_reactivate), android.widget.Toast.LENGTH_LONG).show()
                            onSuccess()
                        }
                    )
                } else {
                    onSuccess()
                }
            }
            else -> try { focusRequester.requestFocus() } catch (_: Exception) {}
        }
    }

    Dialog(
        onDismissRequest = { if (step != ChangePasswordStep.Processing) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = step != ChangePasswordStep.Processing,
            dismissOnClickOutside = step != ChangePasswordStep.Processing
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.change_password_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium
                )

                when (step) {
                    ChangePasswordStep.EnterOld -> {
                        OutlinedTextField(
                            value = oldPassword,
                            onValueChange = { oldPassword = it; errorMessage = null },
                            label = { Text(stringResource(R.string.change_password_step_old)) },
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                            singleLine = true,
                            isError = errorMessage != null,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = {
                                if (oldPassword.isNotBlank()) {
                                    val valid = FilesManager.SecureStorage.verifyPassword(context, oldPassword)
                                    if (valid) { step = ChangePasswordStep.EnterNew }
                                    else errorMessage = context.getString(R.string.invalid_password_error)
                                }
                            }),
                            visualTransformation = PasswordVisualTransformation(),
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                errorBorderColor = MaterialTheme.colorScheme.error
                            )
                        )
                        errorMessage?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                            Button(
                                onClick = {
                                    if (oldPassword.isBlank()) return@Button
                                    val valid = FilesManager.SecureStorage.verifyPassword(context, oldPassword)
                                    if (valid) step = ChangePasswordStep.EnterNew
                                    else errorMessage = context.getString(R.string.invalid_password_error)
                                },
                                enabled = oldPassword.isNotBlank(),
                                shape = RoundedCornerShape(8.dp)
                            ) { Text(stringResource(R.string.change_password_next)) }
                        }
                    }

                    ChangePasswordStep.EnterNew -> {
                        OutlinedTextField(
                            value = newPassword,
                            onValueChange = { newPassword = it; errorMessage = null },
                            label = { Text(stringResource(R.string.change_password_step_new)) },
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                            singleLine = true,
                            isError = errorMessage != null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                            visualTransformation = PasswordVisualTransformation(),
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                errorBorderColor = MaterialTheme.colorScheme.error
                            )
                        )
                        OutlinedTextField(
                            value = confirmPassword,
                            onValueChange = { confirmPassword = it; errorMessage = null },
                            label = { Text(stringResource(R.string.change_password_confirm_new)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            isError = errorMessage != null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                if (newPassword == confirmPassword && newPassword.isNotBlank()) {
                                    step = ChangePasswordStep.Processing
                                } else if (newPassword != confirmPassword) {
                                    errorMessage = context.getString(R.string.passwords_no_match_error)
                                }
                            }),
                            visualTransformation = PasswordVisualTransformation(),
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                errorBorderColor = MaterialTheme.colorScheme.error
                            )
                        )
                        errorMessage?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                            Button(
                                onClick = {
                                    if (newPassword != confirmPassword) {
                                        errorMessage = context.getString(R.string.passwords_no_match_error)
                                        return@Button
                                    }
                                    if (newPassword.isBlank()) return@Button
                                    step = ChangePasswordStep.Processing
                                },
                                enabled = newPassword.isNotBlank() && confirmPassword.isNotBlank(),
                                shape = RoundedCornerShape(8.dp)
                            ) { Text(stringResource(R.string.change_password_confirm_btn)) }
                        }
                    }

                    ChangePasswordStep.Processing -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(vertical = 8.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.5.dp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                            Text(
                                text = progressText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
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
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.confirm_deletion),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = stringResource(R.string.delete_confirmation_message, itemCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    TextButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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

/**
 * Diálogo de confirmação para deletar vídeo no player
 */
@Composable
fun DeleteVideoDialog(
    videoPath: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val fileName = File(videoPath).nameWithoutExtension

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.delete_video),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = stringResource(R.string.delete_video_confirmation, fileName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    TextButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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

/**
 * Diálogo de confirmação para corrigir metadados de vídeo
 */
@Composable
fun FixVideoMetadataDialog(
    videoPath: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onPlayAnyway: () -> Unit = {}
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.fix_video_metadata_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = stringResource(R.string.fix_video_metadata_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    ) {
                        Text(stringResource(R.string.cancel))
                    }

                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Text(stringResource(R.string.fix_video_button))
                    }
                }
            }
        }
    }
}

/**
 * Diálogo genérico de processamento com loading
 */
@Composable
fun ProcessingDialog(
    title: String,
    message: String
) {
    Dialog(
        onDismissRequest = { /* Não permite fechar durante processamento */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                CircularProgressIndicator(
                    modifier = Modifier.size(36.dp),
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )

                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
