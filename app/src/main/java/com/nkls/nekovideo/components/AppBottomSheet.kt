package com.nkls.nekovideo.components

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.foundation.background
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import android.graphics.Color
import android.view.WindowManager
import com.nkls.nekovideo.R
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    title: String? = null,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    sheetMaxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
    navigationBarsPadding: Boolean = true,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(12.dp),
    properties: ModalBottomSheetProperties = ModalBottomSheetProperties(),
    content: @Composable ColumnScope.() -> Unit
) {
    HandleDragModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        sheetMaxWidth = sheetMaxWidth,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        properties = properties
    ) {
        BottomSheetBody(title, modifier, contentPadding, navigationBarsPadding, horizontalAlignment, verticalArrangement, content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HandleDragModalBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    sheetMaxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
    containerColor: ComposeColor = BottomSheetDefaults.ContainerColor,
    contentColor: ComposeColor = contentColorFor(containerColor),
    properties: ModalBottomSheetProperties = ModalBottomSheetProperties(),
    content: @Composable ColumnScope.() -> Unit
) {
    val scope = rememberCoroutineScope()
    val latestDismiss by rememberUpdatedState(onDismissRequest)
    var dragOffset by remember(sheetState) { mutableFloatStateOf(0f) }
    var isClosing by remember(sheetState) { mutableStateOf(false) }
    var returnJob by remember(sheetState) { mutableStateOf<Job?>(null) }

    fun returnToRest() {
        if (isClosing) return
        returnJob?.cancel()
        returnJob = scope.launch {
            animate(dragOffset, 0f, animationSpec = tween(160)) { value, _ -> dragOffset = value }
        }
    }

    fun dismissFromHandle(): Boolean {
        if (isClosing) return false
        isClosing = true
        returnJob?.cancel()
        scope.launch {
            try {
                sheetState.hide()
                if (!sheetState.isVisible) latestDismiss()
            } finally {
                isClosing = false
                returnToRest()
            }
        }
        return true
    }

    val dismissLabel = stringResource(R.string.action_cancel)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.offset { IntOffset(0, dragOffset.roundToInt()) },
        sheetState = sheetState,
        sheetMaxWidth = sheetMaxWidth,
        sheetGesturesEnabled = false,
        containerColor = containerColor,
        contentColor = contentColor,
        properties = properties,
        // Material's dragHandle slot installs its own click-to-dismiss action.
        dragHandle = null
    ) {
        Box(
            modifier = Modifier.align(Alignment.CenterHorizontally).width(64.dp).height(48.dp)
                .testTag("bottom_sheet_handle")
                .semantics { dismiss(dismissLabel) { dismissFromHandle() } }
                .pointerInput(sheetState) {
                    val dismissDistance = 48.dp.toPx()
                    var gestureDistance = 0f
                    detectVerticalDragGestures(
                        onDragStart = {
                            returnJob?.cancel()
                            gestureDistance = 0f
                        },
                        onDragCancel = { returnToRest() },
                        onDragEnd = {
                            // Distance alone decides: a short flick should not close the sheet.
                            if (gestureDistance < dismissDistance || !dismissFromHandle()) returnToRest()
                        }
                    ) { _, delta ->
                        if (!isClosing) {
                            gestureDistance += delta
                            dragOffset = (dragOffset + delta).coerceAtLeast(0f)
                        }
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
        content()
    }
}

/** Same sheet appearance, with a fixed dialog window: no draggable state or dismiss gestures. */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("DEPRECATION")
@Composable
fun NonDismissibleBottomSheet(
    title: String? = null,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    sheetMaxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
    navigationBarsPadding: Boolean = true,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(12.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        val view = LocalView.current
        val darkSystemBarIcons = MaterialTheme.colorScheme.surface.luminance() > 0.5f
        DisposableEffect(view, darkSystemBarIcons) {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                // The app surfaces provide the background; the dialog must not add a system bar color/scrim.
                window.setDimAmount(0f)
                window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                    WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                window.statusBarColor = Color.TRANSPARENT
                window.navigationBarColor = Color.TRANSPARENT
                window.navigationBarDividerColor = Color.TRANSPARENT
                window.isNavigationBarContrastEnforced = false
                window.isStatusBarContrastEnforced = false
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightNavigationBars = darkSystemBarIcons
                    isAppearanceLightStatusBars = darkSystemBarIcons
                }
            }
            onDispose { }
        }
        Box(Modifier.fillMaxSize().background(BottomSheetDefaults.ScrimColor), contentAlignment = Alignment.BottomCenter) {
            Surface(
                modifier = Modifier.widthIn(max = sheetMaxWidth).fillMaxWidth(),
                shape = BottomSheetDefaults.ExpandedShape,
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                BottomSheetBody(title, modifier, contentPadding, navigationBarsPadding, horizontalAlignment, verticalArrangement, content)
            }
        }
    }
}

@Composable
private fun BottomSheetBody(
    title: String?, modifier: Modifier, contentPadding: PaddingValues, navigationBarsPadding: Boolean,
    horizontalAlignment: Alignment.Horizontal, verticalArrangement: Arrangement.Vertical,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = (if (navigationBarsPadding) modifier.navigationBarsPadding() else modifier)
            .fillMaxWidth().padding(contentPadding),
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = verticalArrangement
    ) {
        if (title != null) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface)
            HorizontalDivider()
        }
        content()
    }
}
