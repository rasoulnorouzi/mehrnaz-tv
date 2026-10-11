package app.bolbol.tv.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.zIndex
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Decode size of every backdrop request: never larger than 1280 x 720 (docs/perf-budget.md). */
private const val BACKDROP_WIDTH_PX = 1280
private const val BACKDROP_HEIGHT_PX = 720
private const val BACKDROP_FADE_MS = 250

private enum class SlotState { Empty, Loading, Ready, Failed }

/**
 * Full-width backdrop for the home hero and the details header.
 *
 * - One Coil request per URL, sized 1280 x 720 at most, Crop, no blur, no parallax.
 * - **Static scrim**: two gradients (from the background colour at the start edge and at the
 *   bottom, to transparent) are built once in `drawWithCache` and never recomputed per frame.
 *   The bottom fades into [BolbolColors.Background], so content below the hero joins seamlessly.
 * - When [imageUrl] changes the new image is loaded **off screen first**, then crossfaded in once
 *   (250 ms) over the old one, so there is never a blank flash. A failed load keeps the old
 *   image. A null/blank URL fades the image out to the plain background.
 * - Feed it a debounced URL while the user scrolls through a row:
 *   `HeroBackdrop(rememberDebouncedValue(focusedUrl))`.
 *
 * [content] is drawn on top of the scrim (title, meta, buttons), in the Box's coordinate space.
 */
@Composable
fun HeroBackdrop(
    imageUrl: String?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val target = imageUrl?.takeIf { it.isNotBlank() }
    // Two image slots; the one with index `top` is drawn above the other.
    val urls = remember { mutableStateListOf(target, null) }
    val states = remember { mutableStateListOf(SlotState.Loading, SlotState.Empty) }
    val alphas = remember { arrayOf(Animatable(1f), Animatable(0f)) }
    var top by remember { mutableIntStateOf(0) }

    LaunchedEffect(target) {
        val front = top
        val back = 1 - top
        if (target == urls[front]) {
            // Back to the image that is already shown: drop whatever was loading behind it.
            urls[back] = null
            states[back] = SlotState.Empty
            alphas[back].snapTo(0f)
            return@LaunchedEffect
        }
        if (target == null) {
            alphas[front].animateTo(0f, tween(BACKDROP_FADE_MS))
            urls[front] = null
            states[front] = SlotState.Empty
            return@LaunchedEffect
        }
        // 1. load the new image invisibly in the back slot
        alphas[back].snapTo(0f)
        urls[back] = target
        states[back] = SlotState.Loading
        val result = snapshotFlow { states[back] }.first { it == SlotState.Ready || it == SlotState.Failed }
        if (result == SlotState.Failed) {
            urls[back] = null
            states[back] = SlotState.Empty
            return@LaunchedEffect
        }
        // 2. fade it in once, above the old one
        try {
            top = back
            alphas[back].animateTo(1f, tween(BACKDROP_FADE_MS))
        } finally {
            // Also runs when a newer URL cancels this effect mid-fade: settle in a clean state.
            withContext(NonCancellable) {
                alphas[back].snapTo(1f)
                alphas[front].snapTo(0f)
                top = back
                urls[front] = null
                states[front] = SlotState.Empty
            }
        }
    }

    Box(modifier.background(BolbolColors.Background)) {
        for (slot in 0..1) {
            val url = urls[slot]
            if (url != null) {
                key(slot) {
                    CoverImage(
                        url = url,
                        widthPx = BACKDROP_WIDTH_PX,
                        heightPx = BACKDROP_HEIGHT_PX,
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(if (top == slot) 1f else 0f)
                            .graphicsLayer { alpha = alphas[slot].value },
                        onSuccess = { states[slot] = SlotState.Ready },
                        onError = { states[slot] = SlotState.Failed },
                    )
                }
            }
        }
        Box(
            Modifier
                .matchParentSize()
                .zIndex(2f)
                .drawWithCache {
                    val rtl = layoutDirection == LayoutDirection.Rtl
                    val solid = BolbolColors.Background
                    val clear = solid.copy(alpha = 0f) // same hue, so the ramp does not darken
                    val fromStart = Brush.horizontalGradient(
                        0f to solid.copy(alpha = 0.94f),
                        0.45f to solid.copy(alpha = 0.55f),
                        0.8f to clear,
                        startX = if (rtl) size.width else 0f,
                        endX = if (rtl) 0f else size.width,
                    )
                    val fromBottom = Brush.verticalGradient(
                        0f to clear,
                        0.5f to clear,
                        1f to solid,
                        startY = 0f,
                        endY = size.height,
                    )
                    onDrawBehind {
                        drawRect(fromBottom)
                        drawRect(fromStart)
                    }
                },
        )
        Box(Modifier.fillMaxSize().zIndex(3f)) { content() }
    }
}

/**
 * Returns [value] only after it has stopped changing for [wait] (default 300 ms). Use it to turn
 * fast D-pad focus changes into one backdrop change: the first value is returned immediately.
 */
@Composable
fun <T> rememberDebouncedValue(value: T, wait: Duration = 300.milliseconds): T {
    var debounced by remember { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (value != debounced) {
            delay(wait)
            debounced = value
        }
    }
    return debounced
}
