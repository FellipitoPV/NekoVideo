package com.example.nekovideo.components.helpers

import android.content.Context
import android.widget.Toast
import java.io.File

object FilesManager {
    fun renameSelectedItems(
        context: Context,
        selectedItems: List<String>,
        baseName: String,
        startNumber: Int,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ) {
        if (selectedItems.isEmpty()) {
            Toast.makeText(context, "No items selected", Toast.LENGTH_SHORT).show()
            return
        }

        val parentDir = File(selectedItems.first()).parentFile ?: return
        val existingFiles = parentDir.listFiles()?.map { it.name }?.toMutableSet() ?: mutableSetOf()
        var currentNumber = startNumber
        var renamedCount = 0
        val totalItems = selectedItems.size

        selectedItems.forEachIndexed { index, path ->
            val file = File(path)
            if (!file.exists()) return@forEachIndexed

            val extension = if (file.isFile) ".${file.extension}" else ""
            var newName: String

            // Procurar o próximo número sem conflito
            while (true) {
                newName = if (currentNumber == 0) baseName else "$baseName $currentNumber"
                val fullNewName = "$newName$extension"
                if (fullNewName !in existingFiles && !File(parentDir, fullNewName).exists()) {
                    break
                }
                currentNumber++
            }

            // Renomear o arquivo
            val newFile = File(parentDir, "$newName$extension")
            if (file.renameTo(newFile)) {
                //Toast.makeText(context, "Renamed ${file.name} to $newName$extension", Toast.LENGTH_SHORT).show()
                existingFiles.add("$newName$extension")
                renamedCount++
            } else {
                Toast.makeText(context, "Failed to rename ${file.name}", Toast.LENGTH_SHORT).show()
            }
            currentNumber++
            onProgress(index + 1, totalItems) // Atualizar progresso (1-based index)
        }

        if (renamedCount == 0) {
            Toast.makeText(context, "No files were renamed", Toast.LENGTH_SHORT).show()
        }
    }
}