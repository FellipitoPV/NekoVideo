package com.nkls.nekovideo.components.helpers

import android.os.Environment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * Estado de navegação de pastas usando pilha reativa.
 * Em vez de criar novas telas a cada navegação, apenas atualizamos o caminho atual.
 */
@Stable
class FolderNavigationState(
    private val folderStack: SnapshotStateList<String>
) {
    companion object {
        val ROOT_PATH: String = Environment.getExternalStorageDirectory().absolutePath
    }

    /** Caminho da pasta atual (topo da pilha) */
    val currentPath: String
        get() = folderStack.lastOrNull() ?: ROOT_PATH

    /** Verifica se está no nível raiz */
    val isAtRoot: Boolean
        get() = folderStack.size <= 1 && currentPath == ROOT_PATH

    /** Tamanho da pilha (profundidade de navegação) */
    val depth: Int
        get() = folderStack.size

    /** Histórico de pastas (somente leitura) */
    val history: List<String>
        get() = folderStack.toList()

    /**
     * Navega para uma subpasta.
     * Adiciona o caminho à pilha.
     */
    fun navigateTo(folderPath: String) {
        if (folderPath != currentPath) {
            folderStack.add(folderPath)
        }
    }

    /**
     * Volta para a pasta anterior.
     * Remove o topo da pilha.
     * @return true se conseguiu voltar, false se já está na raiz
     */
    fun navigateBack(): Boolean {
        return if (folderStack.size > 1) {
            folderStack.removeAt(folderStack.lastIndex)
            true
        } else {
            false
        }
    }

    /**
     * Navega diretamente para a raiz.
     * Limpa a pilha e vai para o root.
     */
    fun navigateToRoot() {
        folderStack.clear()
        folderStack.add(ROOT_PATH)
    }

    /**
     * Navega para um índice específico do histórico.
     * Útil para breadcrumbs ou navegação rápida.
     */
    fun navigateToIndex(index: Int) {
        if (index in 0 until folderStack.size) {
            // Remove todos os itens após o índice
            while (folderStack.size > index + 1) {
                folderStack.removeAt(folderStack.lastIndex)
            }
        }
    }

    /**
     * Substitui o caminho atual sem adicionar à pilha.
     * Útil para refresh ou mudança de contexto.
     */
    fun replaceCurrent(folderPath: String) {
        if (folderStack.isNotEmpty()) {
            folderStack[folderStack.lastIndex] = folderPath
        } else {
            folderStack.add(folderPath)
        }
    }

    /**
     * Navega diretamente para um caminho específico.
     * Se o caminho já estiver na pilha, remove todos os níveis após ele.
     * Caso contrário, substitui a pilha para ir diretamente ao caminho.
     * Útil para navegação via breadcrumb.
     */
    fun navigateToPath(targetPath: String) {
        // Verifica se o caminho está na pilha atual
        val index = folderStack.indexOf(targetPath)

        if (index >= 0) {
            // Caminho está na pilha - remove todos os níveis após ele
            while (folderStack.size > index + 1) {
                folderStack.removeAt(folderStack.lastIndex)
            }
        } else {
            // Caminho não está na pilha - reconstruir baseado no path
            val rootPath = ROOT_PATH

            if (targetPath.startsWith(rootPath)) {
                // Limpa a pilha e reconstrói
                folderStack.clear()
                folderStack.add(rootPath)

                // Se não é o root, adiciona os níveis intermediários
                if (targetPath != rootPath) {
                    val relativePath = targetPath.removePrefix(rootPath).trim('/')
                    val segments = relativePath.split('/').filter { it.isNotEmpty() }

                    var currentPath = rootPath
                    for (segment in segments) {
                        currentPath = "$currentPath/$segment"
                        folderStack.add(currentPath)
                    }
                }
            } else {
                // Caminho absoluto diferente - apenas substitui
                folderStack.clear()
                folderStack.add(targetPath)
            }
        }
    }
}

/**
 * Cria e lembra um FolderNavigationState.
 */
@Composable
fun rememberFolderNavigationState(
    initialPath: String = FolderNavigationState.ROOT_PATH
): FolderNavigationState {
    val folderStack = remember { mutableStateListOf(initialPath) }
    return remember(folderStack) { FolderNavigationState(folderStack) }
}
