package com.leatherbound.listening.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Velocity
import kotlin.math.roundToInt

/**
 * A header that slides away as the list below it scrolls down and comes back as soon as you
 * scroll up again. Put [connection] on a parent of both (Modifier.nestedScroll) and the header
 * content in [CollapsingHeader].
 */
@Stable
class CollapsingHeaderState {
    /** Full height of the header in px, measured each layout. */
    internal var height by mutableFloatStateOf(0f)
    /** How many px of the header are hidden, 0 (shown) to [height] (hidden). */
    var collapsed by mutableFloatStateOf(0f)
        private set

    val fraction: Float get() = if (height > 0f) collapsed / height else 0f

    val connection = object : NestedScrollConnection {
        // Runs before the list scrolls: scrolling down hides the header first, scrolling up shows it first.
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val before = collapsed
            collapsed = (collapsed - available.y).coerceIn(0f, height)
            return Offset(0f, before - collapsed)
        }

        // Don't leave it half-hidden when the list comes to rest.
        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            if (collapsed > 0f && collapsed < height) {
                animate(collapsed, if (fraction > 0.5f) height else 0f) { value, _ -> collapsed = value }
            }
            return Velocity.Zero
        }
    }

    fun expand() { collapsed = 0f }

    internal fun measured(px: Float) {
        height = px
        if (collapsed > px) collapsed = px
    }
}

@Composable
fun rememberCollapsingHeaderState() = remember { CollapsingHeaderState() }

/** Lays out [content] at its full size but only shows the part that isn't collapsed, sliding it up and fading it out. */
@Composable
fun CollapsingHeader(state: CollapsingHeaderState, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.clipToBounds()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minHeight = 0))
                state.measured(placeable.height.toFloat())
                val shown = (placeable.height - state.collapsed.roundToInt()).coerceAtLeast(0)
                layout(placeable.width, shown) { placeable.place(0, shown - placeable.height) }
            }
            .graphicsLayer { alpha = 1f - state.fraction }
    ) { content() }
}
