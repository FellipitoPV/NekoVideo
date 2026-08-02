package com.nkls.nekovideo.components.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.annotation.RequiresApi
import androidx.media3.session.MediaController

/**
 * Utilitários para Picture-in-Picture
 */
object PiPUtils {

    @RequiresApi(Build.VERSION_CODES.O)
    fun createPiPParams(
        context: Context,
        mediaController: MediaController?
    ): PictureInPictureParams {
        val actions = ArrayList<RemoteAction>()

        // Previous
        actions.add(
            createRemoteAction(
                context,
                android.R.drawable.ic_media_previous,
                "Previous",
                PiPConstants.REQUEST_CODE_PREVIOUS
            )
        )

        // Play/Pause
        val isPlaying = mediaController?.isPlaying ?: false
        actions.add(
            createRemoteAction(
                context,
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "Pause" else "Play",
                PiPConstants.REQUEST_CODE_PLAY_PAUSE
            )
        )

        // Next
        actions.add(
            createRemoteAction(
                context,
                android.R.drawable.ic_media_next,
                "Next",
                PiPConstants.REQUEST_CODE_NEXT
            )
        )

        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(actions)
            .build()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createRemoteAction(
        context: Context,
        iconResId: Int,
        title: String,
        requestCode: Int
    ): RemoteAction {
        val intent = Intent("PIP_CONTROL").apply {
            putExtra("action", requestCode)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val icon = Icon.createWithResource(context, iconResId)

        return RemoteAction(icon, title, title, pendingIntent)
    }
}
