package com.leatherbound.listening.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Drag-down-to-close for a full-screen page. The page follows your finger; let go past
 * a fifth of the screen (or flick) and it closes, otherwise it springs back.
 */
class SwipeDismissState internal constructor(
    private val scope: CoroutineScope,
    private val haptics: HapticFeedback,
    private val onDismiss: () -> Unit,
) {
    var offset by mutableFloatStateOf(0f)
        private set
    internal var height by mutableIntStateOf(1)
    private var dismissing = false

    internal val draggable = DraggableState { delta -> offset = (offset + delta).coerceAtLeast(0f) }

    /** Lets scrollable content inside the page hand over its downward overscroll. */
    internal val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            // While the page is pulled down, dragging up first lifts it back before scrolling.
            if (available.y < 0 && offset > 0) {
                val used = maxOf(available.y, -offset)
                offset += used
                return Offset(0f, used)
            }
            return Offset.Zero
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            // Content is already at the top: keep pulling and the page moves.
            if (available.y > 0 && source == NestedScrollSource.UserInput) {
                offset += available.y
                return Offset(0f, available.y)
            }
            return Offset.Zero
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (offset <= 0f) return Velocity.Zero
            settle(available.y)
            return available
        }
    }

    internal suspend fun settle(velocity: Float) {
        if (dismissing) return
        if (offset > height * 0.2f || velocity > 1800f) {
            dismissing = true
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            animate(offset, height.toFloat(), animationSpec = tween(160)) { v, _ -> offset = v }
            onDismiss()
        } else {
            animate(offset, 0f, animationSpec = spring()) { v, _ -> offset = v }
        }
    }

    internal fun settleAsync(velocity: Float) {
        scope.launch { settle(velocity) }
    }
}

@Composable
fun rememberSwipeDismissState(onDismiss: () -> Unit): SwipeDismissState {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val dismiss by rememberUpdatedState(onDismiss)
    return remember { SwipeDismissState(scope, haptics) { dismiss() } }
}

/** Put on the page's root: moves the whole page with the drag and catches scroll overflow. */
fun Modifier.swipeDismissContainer(state: SwipeDismissState): Modifier = this
    .onSizeChanged { state.height = it.height.coerceAtLeast(1) }
    .nestedScroll(state.connection)
    .graphicsLayer {
        translationY = state.offset
        alpha = 1f - (state.offset / state.height).coerceIn(0f, 1f) * 0.4f
    }

/** Put on parts of the page that don't scroll (header, handle) so dragging them also works. */
fun Modifier.swipeDismissHandle(state: SwipeDismissState): Modifier =
    draggable(state.draggable, Orientation.Vertical, onDragStopped = { v -> state.settleAsync(v) })

/** The little pill that says "this page can be pulled down". */
@Composable
fun DragHandle(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .wrapContentWidth()
            .size(width = 36.dp, height = 4.dp)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(50))
    )
}
