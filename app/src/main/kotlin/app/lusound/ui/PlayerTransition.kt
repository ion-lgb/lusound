/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 * Adapted for LuSound: instance-scoped recordings and playback callbacks.
 */
package app.lusound.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.convx.music.ui.player.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/** One composition owns the transition state, measured endpoints and recorded layers. */
@Stable
data class PlayerMotion(
    val fraction: MutableFloatState,
    val miniRect: MutableState<Rect>,
    val fullRect: MutableState<Rect>,
    val miniArtwork: MutableState<Rect>,
    val fullArtwork: MutableState<Rect>,
    val miniLayer: GraphicsLayer,
    val fullLayer: GraphicsLayer,
    val scope: CoroutineScope,
    val animation: MutableState<Job?>,
    val expandedChange: State<(Boolean) -> Unit>,
    val dragging: MutableState<Boolean>,
    val previousTrack: MutableState<(() -> Unit)?>,
    val destination: MutableState<Boolean>,
    val cornerExpansion: MutableFloatState,
    val velocity: MutableFloatState,
) {
    val progress: Float get() = fraction.floatValue
}

@Composable
fun rememberPlayerMotion(expanded: Boolean, onExpandedChange: (Boolean) -> Unit): PlayerMotion {
    val mini = rememberGraphicsLayer()
    val full = rememberGraphicsLayer()
    val scope = rememberCoroutineScope()
    val callback = rememberUpdatedState(onExpandedChange)
    val motion = remember(mini, full, scope) {
        PlayerMotion(mutableFloatStateOf(0f), mutableStateOf(Rect.Zero), mutableStateOf(Rect.Zero),
            mutableStateOf(Rect.Zero), mutableStateOf(Rect.Zero), mini, full, scope,
            mutableStateOf(null), callback, mutableStateOf(false), mutableStateOf(null), mutableStateOf(false), mutableFloatStateOf(0f), mutableFloatStateOf(0f))
    }
    LaunchedEffect(expanded) {
        if (!motion.dragging.value && expanded != motion.destination.value) settlePlayer(motion, expanded, motion.velocity.floatValue)
    }
    val settled = motion.progress >= 1f
    LaunchedEffect(settled) {
        animate(motion.cornerExpansion.floatValue, if (settled) 1f else 0f,
            animationSpec = tween(SCREEN_CORNER_EXPANSION_MILLIS)) { value, _ -> motion.cornerExpansion.floatValue = value }
    }
    return motion
}

private fun settlePlayer(motion: PlayerMotion, expanded: Boolean, velocity: Float) {
    motion.destination.value = expanded
    motion.animation.value?.cancel()
    motion.animation.value = motion.scope.launch {
        animate(motion.progress, if (expanded) 1f else 0f, initialVelocity = velocity,
            animationSpec = spring(dampingRatio = 0.68f, stiffness = 380f)) { value, frameVelocity ->
            motion.velocity.floatValue = frameVelocity
            motion.fraction.floatValue = value.coerceIn(0f, 1f)
        }
    }
}

fun collapsePlayer(motion: PlayerMotion) {
    motion.expandedChange.value(false)
    settlePlayer(motion, false, motion.velocity.floatValue)
}

/** Direction locks after touch slop; reversing the finger always reverses the same progress. */
@Composable
fun Modifier.playerGestures(motion: PlayerMotion, previous: () -> Unit, next: () -> Unit): Modifier {
    val previousAction by rememberUpdatedState(previous)
    val nextAction by rememberUpdatedState(next)
    var swipeOffset by remember { mutableFloatStateOf(0f) }
    var swipeAnimation by remember { mutableStateOf<Job?>(null) }
    val resetSwipe: () -> Unit = {
        swipeAnimation?.cancel()
        swipeAnimation = motion.scope.launch {
            animate(swipeOffset, 0f, animationSpec = spring(dampingRatio = 1f, stiffness = 200f)) { value, _ -> swipeOffset = value }
        }
    }
    return pointerInput(motion) {
        val tracker = VelocityTracker()
        var horizontal = false
        var directionChosen = false
        var distanceX = 0f
        detectDragGestures(
            onDragStart = {
                motion.animation.value?.cancel()
                motion.velocity.floatValue = 0f
                swipeAnimation?.cancel()
                motion.dragging.value = true
                horizontal = false
                directionChosen = false
                distanceX = 0f
                tracker.resetTracking()
            },
            onDragCancel = {
                resetSwipe()
                motion.dragging.value = false
                val expand = motion.progress >= 0.5f
                motion.expandedChange.value(expand)
                settlePlayer(motion, expand, 0f)
            },
            onDragEnd = {
                resetSwipe()
                motion.dragging.value = false
                if (horizontal) {
                    if (abs(distanceX) >= size.width * 0.25f) {
                        if (distanceX < 0f) nextAction() else previousAction()
                    }
                }
                val travel = (motion.fullRect.value.height - motion.miniRect.value.height).coerceAtLeast(1f)
                val velocity = if (horizontal) 0f else -tracker.calculateVelocity().y / travel
                val expand = if (abs(velocity) > 0.65f) velocity > 0f else motion.progress >= 0.5f
                motion.expandedChange.value(expand)
                settlePlayer(motion, expand, velocity)
            },
            onDrag = { change, amount ->
                if (!directionChosen) {
                    horizontal = abs(amount.x) > abs(amount.y)
                    directionChosen = true
                }
                change.consume()
                tracker.addPosition(change.uptimeMillis, change.position)
                if (horizontal) {
                    distanceX += amount.x
                    swipeOffset += amount.x
                } else {
                    val travel = (motion.fullRect.value.height - motion.miniRect.value.height).coerceAtLeast(1f)
                    motion.fraction.floatValue = (motion.progress - amount.y / travel).coerceIn(0f, 1f)
                }
            },
        )
    }.graphicsLayer { translationX = swipeOffset }
}

/** Vertical-only recognition leaves horizontal gestures to the real artwork pager. */
fun Modifier.playerSheetDrag(motion: PlayerMotion): Modifier = pointerInput(motion) {
    val tracker = VelocityTracker()
    detectVerticalDragGestures(
        onDragStart = {
            motion.animation.value?.cancel()
            motion.velocity.floatValue = 0f
            motion.dragging.value = true
            tracker.resetTracking()
        },
        onDragCancel = {
            motion.dragging.value = false
            val expanded = motion.progress >= 0.5f
            motion.expandedChange.value(expanded)
            settlePlayer(motion, expanded, 0f)
        },
        onDragEnd = {
            motion.dragging.value = false
            val travel = (motion.fullRect.value.height - motion.miniRect.value.height).coerceAtLeast(1f)
            val velocity = -tracker.calculateVelocity().y / travel
            val expanded = if (abs(velocity) > 0.65f) velocity > 0f else motion.progress >= 0.5f
            motion.expandedChange.value(expanded)
            settlePlayer(motion, expanded, velocity)
        },
        onVerticalDrag = { change, amount ->
            change.consume()
            tracker.addPosition(change.uptimeMillis, change.position)
            val travel = (motion.fullRect.value.height - motion.miniRect.value.height).coerceAtLeast(1f)
            motion.fraction.floatValue = (motion.progress - amount / travel).coerceIn(0f, 1f)
        },
    )
}

fun Modifier.recordMiniPlayer(motion: PlayerMotion): Modifier = onGloballyPositioned {
    motion.miniRect.value = it.boundsInRoot()
}.drawWithContent {
    motion.miniLayer.record { this@drawWithContent.drawContent() }
    if (motion.progress <= 0f) {
        motion.miniLayer.alpha = 1f
        drawLayer(motion.miniLayer)
    }
}

fun Modifier.recordFullPlayer(motion: PlayerMotion): Modifier = onGloballyPositioned {
    motion.fullRect.value = it.boundsInRoot()
}.drawWithContent {
    motion.fullLayer.record { this@drawWithContent.drawContent() }
    if (motion.progress >= 1f) {
        motion.fullLayer.alpha = 1f
        drawLayer(motion.fullLayer)
    }
}.graphicsLayer {
    val radius = sharedContainerCornerRadius(motion.miniRect.value.height / 2f, 28.dp.toPx(), motion.progress, motion.cornerExpansion.floatValue)
    shape = androidx.compose.foundation.shape.RoundedCornerShape(radius.toDp())
    clip = true
}

fun Modifier.miniPlayerArtwork(motion: PlayerMotion): Modifier = onGloballyPositioned {
    motion.miniArtwork.value = it.boundsInRoot()
}.graphicsLayer { alpha = if (motion.progress > 0f && motion.progress < 1f) 0f else 1f }

fun Modifier.fullPlayerArtwork(motion: PlayerMotion): Modifier = onGloballyPositioned {
    motion.fullArtwork.value = it.boundsInRoot()
}.graphicsLayer { alpha = if (motion.progress > 0f && motion.progress < 1f) 0f else 1f }

/** The two recordings share one clip; the cover flies independently at full decode size. */
@Composable
fun PlayerMorphOverlay(motion: PlayerMotion, artwork: String?, color: Color) {
    Spacer(Modifier.fillMaxSize().testTag("player_transition").semantics {
        progressBarRangeInfo = ProgressBarRangeInfo(motion.progress, 0f..1f)
    }.drawWithContent {
        val p = motion.progress
        val source = motion.miniRect.value
        val target = motion.fullRect.value
        if (p > 0f && p < 1f && !source.isEmpty && !target.isEmpty) {
            val rect = sharedContainerRect(source, target, p).translate(-target.topLeft)
            val radius = sharedContainerCornerRadius(source.height / 2f, 28.dp.toPx(), p, motion.cornerExpansion.floatValue)
            val outline = Path().apply { addRoundRect(RoundRect(rect, CornerRadius(radius))) }
            clipPath(outline) {
                drawRect(color, rect.topLeft, rect.size)
                translate(rect.left, rect.top) {
                    motion.miniLayer.alpha = 1f - easeOutCubic((p / 0.25f).coerceIn(0f, 1f))
                    drawLayer(motion.miniLayer)
                    motion.fullLayer.alpha = easeInCubic((p / 0.25f).coerceIn(0f, 1f))
                    val ratio = rect.width / target.width
                    scale(ratio, ratio, Offset.Zero) { drawLayer(motion.fullLayer) }
                }
            }
        }
    })
    val artworkLayer = rememberGraphicsLayer()
    PlayerCover(artwork, Modifier.fillMaxWidth().aspectRatio(1f).testTag("player_shared_artwork").semantics {
        val mini = motion.miniArtwork.value
        val full = motion.fullArtwork.value
        this[PlayerMorphSourceBounds] = mini
        this[PlayerMorphTargetBounds] = full
        this[PlayerMorphArtworkBounds] = if (mini.isEmpty || full.isEmpty) Rect.Zero else sharedArtworkRect(mini, full, motion.progress)
        progressBarRangeInfo = ProgressBarRangeInfo(motion.progress, 0f..1f)
    }.drawWithContent {
        // Keep a full-resolution square recording alive before the first gesture.
        // Read endpoint measurements after layout, here in draw: conditionally creating
        // this content from onGloballyPositioned state loses the first morph frame.
        artworkLayer.record { this@drawWithContent.drawContent() }
        val p = motion.progress
        val mini = motion.miniArtwork.value
        val full = motion.fullArtwork.value
        if (p > 0f && p < 1f && !mini.isEmpty && !full.isEmpty) {
            val rect = sharedArtworkRect(mini, full, p).translate(-motion.fullRect.value.topLeft)
            val radius = androidx.compose.ui.util.lerp(8.dp.toPx(), 12.dp.toPx(), p)
            val outline = Path().apply { addRoundRect(RoundRect(rect, CornerRadius(radius))) }
            clipPath(outline) {
                translate(rect.left, rect.top) {
                    scale(rect.width / size.width, rect.height / size.height, Offset.Zero) { drawLayer(artworkLayer) }
                }
            }
        }
    }, 0.dp)
}

/** Measured geometry exposed for real-device shared-artwork handoff assertions. */
val PlayerMorphSourceBounds = SemanticsPropertyKey<Rect>("PlayerMorphSourceBounds")
val PlayerMorphTargetBounds = SemanticsPropertyKey<Rect>("PlayerMorphTargetBounds")
val PlayerMorphArtworkBounds = SemanticsPropertyKey<Rect>("PlayerMorphArtworkBounds")
