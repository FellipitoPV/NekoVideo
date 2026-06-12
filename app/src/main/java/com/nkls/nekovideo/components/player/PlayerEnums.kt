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
 * Velocidades de reprodução suportadas (0.25 em 0.25 entre 0.25 e 2.0)
 */
enum class PlaybackSpeed(val value: Float) {
    SPEED_0_25(0.25f),
    SPEED_0_50(0.50f),
    SPEED_0_75(0.75f),
    SPEED_1_00(1.00f),
    SPEED_1_25(1.25f),
    SPEED_1_50(1.50f),
    SPEED_1_75(1.75f),
    SPEED_2_00(2.00f)
}

/**
 * Constantes para controles de Picture-in-Picture
 */
object PiPConstants {
    const val REQUEST_CODE_PLAY_PAUSE = 1
    const val REQUEST_CODE_NEXT = 2
    const val REQUEST_CODE_PREVIOUS = 3
}
