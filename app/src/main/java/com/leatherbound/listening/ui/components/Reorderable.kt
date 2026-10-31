package com.leatherbound.listening.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Drag-and-drop ordering for a LazyColumn. Grab an item by its handle; as its middle passes
 * a neighbour, [onMove] swaps them (by key), and [onDrop] fires when you let go.
 * Only items whose keys are in [isReorderable] can be passed over (headers stay put).
 */
class ReorderState internal constructor(
    val listState: LazyListState,
    private val scope: CoroutineScope,
    private val haptics: HapticFeedback,
    private val onMove: (from: Any, to: Any) -> Unit,
    private val onDrop: () -> Unit,
    private val isReorderable: (Any) -> Boolean,
    /** How close to the top or bottom edge (px) a drag starts scrolling the list. */
    private val edgePx: Float,
) {
    var draggingKey by mutableStateOf<Any?>(null)
        private set
    var offset by mutableFloatStateOf(0f)
        private set

    internal fun start(key: Any) {
        draggingKey = key
        offset = 0f
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    internal fun drag(delta: Float) {
        val key = draggingKey ?: return
        offset += delta
        val info = listState.layoutInfo
        val current = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        val middle = current.offset + offset + current.size / 2f
        val target = info.visibleItemsInfo.firstOrNull {
            it.key != key && isReorderable(it.key) && middle > it.offset && middle < it.offset + it.size
        }
        if (target != null) {
            onMove(key, target.key)
            // The item will be laid out at the target's slot; keep it under the finger.
            offset -= (target.offset - current.offset)
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        // Scroll when dragging near the top or bottom edge.
        val edge = edgePx
        val top = current.offset + offset
        val bottom = top + current.size
        when {
            bottom > info.viewportEndOffset - edge -> scope.launch { listState.scrollBy(12f) }
            top < info.viewportStartOffset + edge && listState.firstVisibleItemIndex > 0 -> scope.launch { listState.scrollBy(-12f) }
        }
    }

    internal fun end() {
        if (draggingKey == null) return
        draggingKey = null
        offset = 0f
        onDrop()
    }
}

@Composable
fun rememberReorderState(
    onMove: (from: Any, to: Any) -> Unit,
    onDrop: () -> Unit,
    isReorderable: (Any) -> Boolean = { true },
    listState: LazyListState = rememberLazyListState(),
): ReorderState {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val edgePx = with(LocalDensity.current) { 64.dp.toPx() }
    val move by rememberUpdatedState(onMove)
    val drop by rememberUpdatedState(onDrop)
    val allowed by rememberUpdatedState(isReorderable)
    return remember(listState) { ReorderState(listState, scope, haptics, { a, b -> move(a, b) }, { drop() }, { allowed(it) }, edgePx) }
}

/** Lifts the item being dragged above the others and moves it with the finger; others animate into place. */
@Composable
fun LazyItemScope.reorderableItem(state: ReorderState, key: Any): Modifier {
    val dragging = state.draggingKey == key
    return if (dragging) {
        Modifier.zIndex(1f).graphicsLayer {
            translationY = state.offset
            shadowElevation = 12.dp.toPx()
            scaleX = 1.02f
            scaleY = 1.02f
        }
    } else Modifier.animateItem()
}

/** The ≡ grip that starts a drag. */
@Composable
fun DragGrip(state: ReorderState, key: Any, modifier: Modifier = Modifier) {
    Icon(
        Icons.Filled.DragHandle,
        contentDescription = "Drag to reorder",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.size(40.dp).pointerInput(key) {
            detectDragGestures(
                onDragStart = { state.start(key) },
                onDrag = { change, amount -> change.consume(); state.drag(amount.y) },
                onDragEnd = { state.end() },
                onDragCancel = { state.end() },
            )
        },
    )
}
