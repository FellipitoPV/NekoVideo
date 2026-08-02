package com.nkls.nekovideo.components.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * Utilitários gerais do player
 */
object PlayerUtils {

    /**
     * Encontra a Activity a partir de um Context
     */
    fun Context.findActivity(): Activity? {
        var context = this
        while (context is ContextWrapper) {
            if (context is Activity) return context
            context = context.baseContext
        }
        return null
    }
}
