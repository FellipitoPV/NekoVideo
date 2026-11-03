package com.nkls.nekovideo.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.nkls.nekovideo.R
import com.nkls.nekovideo.components.helpers.ShuffleProgress
import kotlinx.coroutines.flow.StateFlow

/** Only the sheet observes progress; the library does not recompose on every percentage. */
@Composable
internal fun ShuffleProgressDialog(progress: StateFlow<ShuffleProgress>) {
    val state by progress.collectAsState()
    ProcessingDialog(
        title = stringResource(R.string.action_shuffle_play),
        message = stringResource(when (state.stage) {
            ShuffleProgress.Stage.FILTERING -> R.string.shuffle_progress_filtering
            ShuffleProgress.Stage.DISCOVERING -> R.string.shuffle_progress_discovering
            ShuffleProgress.Stage.PRIVATE_ACCESS -> R.string.shuffle_progress_private
            ShuffleProgress.Stage.COLLECTING -> R.string.shuffle_progress_collecting
            ShuffleProgress.Stage.BUILDING -> R.string.shuffle_progress_building
        }), progress = state.percent / 100f
    )
}
