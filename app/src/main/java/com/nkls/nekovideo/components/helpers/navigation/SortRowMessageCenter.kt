package com.nkls.nekovideo.components.helpers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SortRowMessageType {
    INFO,
    SUCCESS,
    ERROR
}

data class SortRowMessage(
    val type: SortRowMessageType,
    val text: String
)

object SortRowMessageCenter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _message = MutableStateFlow<SortRowMessage?>(null)
    val message: StateFlow<SortRowMessage?> = _message.asStateFlow()

    private var clearJob: Job? = null

    fun showInfo(text: String, durationMs: Long? = 3000L) {
        show(SortRowMessageType.INFO, text, durationMs)
    }

    fun showSuccess(text: String, durationMs: Long? = 3000L) {
        show(SortRowMessageType.SUCCESS, text, durationMs)
    }

    fun showError(text: String, durationMs: Long? = 4000L) {
        show(SortRowMessageType.ERROR, text, durationMs)
    }

    fun showPersistentInfo(text: String) {
        show(SortRowMessageType.INFO, text, null)
    }

    fun clear() {
        clearJob?.cancel()
        _message.value = null
    }

    private fun show(type: SortRowMessageType, text: String, durationMs: Long?) {
        clearJob?.cancel()
        _message.value = SortRowMessage(type = type, text = text)
        if (durationMs != null) {
            clearJob = scope.launch {
                delay(durationMs)
                _message.value = null
            }
        }
    }
}
