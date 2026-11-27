package com.nkls.nekovideo.components

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nkls.nekovideo.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * For content whose height changes asynchronously (such as a video's decoded aspect ratio).
 * Bottom alignment is resolved during the same layout pass as the content size, without a
 * separately animated sheet anchor that can still refer to the previous height.
 */
@Composable
internal fun BottomAlignedResizableSheet(
    onDismissRequest: () -> Unit,
    title: String,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(12.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val scope = rememberCoroutineScope()
    val latestDismiss by rememberUpdatedState(onDismissRequest)
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var returnJob by remember { mutableStateOf<Job?>(null) }
    val dismissLabel = stringResource(R.string.action_cancel)

    fun returnToRest() {
        returnJob?.cancel()
        returnJob = scope.launch {
            animate(dragOffset, 0f, animationSpec = tween(160)) { value, _ -> dragOffset = value }
        }
    }

    fun requestDismiss() {
        latestDismiss()
        // A caller may decline dismissal while saving.
        returnToRest()
    }

    Dialog(
        onDismissRequest = { requestDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        ConfigureBottomSheetDialogWindow()
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.matchParentSize().background(BottomSheetDefaults.ScrimColor)
                    .semantics {
                        contentDescription = dismissLabel
                        onClick { requestDismiss(); true }
                    }
                    .pointerInput(Unit) { detectTapGestures { requestDismiss() } }
            )
            Box(
                Modifier.fillMaxSize().windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                ).imePadding(),
                contentAlignment = Alignment.BottomCenter
            ) {
                Surface(
                    modifier = Modifier.widthIn(max = BottomSheetDefaults.SheetMaxWidth).fillMaxWidth()
                        .offset { IntOffset(0, dragOffset.roundToInt()) },
                    shape = BottomSheetDefaults.ExpandedShape,
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    Column {
                        Box(
                            Modifier.align(Alignment.CenterHorizontally).width(64.dp).height(48.dp)
                                .testTag("bottom_sheet_handle")
                                .semantics { dismiss(dismissLabel) { requestDismiss(); true } }
                                .pointerInput(Unit) {
                                    val dismissDistance = 48.dp.toPx()
                                    var gestureDistance = 0f
                                    detectVerticalDragGestures(
                                        onDragStart = {
                                            returnJob?.cancel()
                                            gestureDistance = 0f
                                        },
                                        onDragCancel = { returnToRest() },
                                        onDragEnd = {
                                            if (gestureDistance >= dismissDistance) requestDismiss()
                                            else returnToRest()
                                        }
                                    ) { _, delta ->
                                        gestureDistance += delta
                                        dragOffset = (dragOffset + delta).coerceAtLeast(0f)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                Modifier.size(width = 32.dp, height = 4.dp).background(
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    RoundedCornerShape(2.dp)
                                )
                            )
                        }
                        BottomSheetBody(
                            title, Modifier, PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            navigationBarsPadding = true,
                            horizontalAlignment = Alignment.Start,
                            verticalArrangement = verticalArrangement,
                            content = content
                        )
                    }
                }
            }
        }
    }
}
