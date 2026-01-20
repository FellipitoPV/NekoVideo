package com.nkls.nekovideo.components.player

/**
 * Modos de repetição do player
 */
enum class RepeatMode {
    NONE,
    REPEAT_ALL,
    REPEAT_ONE
}

/**
 * Modos de rotação da tela
 */
enum class RotationMode {
    AUTO,      // Adaptar ao vídeo
    PORTRAIT,  // Sempre vertical
    LANDSCAPE  // Sempre horizontal
}

/**
 * Constantes para controles de Picture-in-Picture
 */
object PiPConstants {
    const val REQUEST_CODE_PLAY_PAUSE = 1
    const val REQUEST_CODE_NEXT = 2
    const val REQUEST_CODE_PREVIOUS = 3
}
