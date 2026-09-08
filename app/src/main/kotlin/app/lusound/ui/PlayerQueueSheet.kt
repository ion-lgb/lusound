/** Convx Queue/BottomSheet portrait motion adapted to LuSound. GPL-3.0; see NOTICE. */
package app.lusound.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** The queue expands within the player, with the upstream 0.70/360 spring and artwork wash. */
@Composable
fun PlayerQueueSheet(artwork: String?, title: String, close: () -> Unit, content: @Composable ColumnScope.(() -> Unit) -> Unit) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val closeAction by rememberUpdatedState(close)
    val collapse: () -> Unit = { scope.launch {
        progress.animateTo(0f, spring(dampingRatio = 0.70f, stiffness = 360f))
        closeAction()
    } }
    LaunchedEffect(Unit) { progress.animateTo(1f, spring(dampingRatio = 0.70f, stiffness = 360f)) }
    BackHandler(onBack = collapse)
    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().testTag("player_queue_sheet").semantics {
        paneTitle = "播放队列"
        progressBarRangeInfo = ProgressBarRangeInfo(progress.value.coerceIn(0f, 1f), 0f..1f)
    }) {
        val height = with(LocalDensity.current) { maxHeight.toPx() }
        Box(Modifier.fillMaxSize().graphicsLayer {
            translationY = height * (1f - progress.value)
            alpha = (progress.value * 4f).coerceIn(0f, 1f)
        }.clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(MaterialTheme.colorScheme.background)) {
            MusicAtmosphere(artwork)
            Column(Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 20.dp)) {
                Column(Modifier.fillMaxWidth().pointerInput(height) {
                    val velocity = VelocityTracker()
                    detectVerticalDragGestures(
                        onDragStart = { velocity.resetTracking(); scope.launch { progress.stop() } },
                        onDragCancel = { scope.launch { progress.animateTo(1f, spring(0.70f, 360f)) } },
                        onDragEnd = {
                            if (progress.value < 0.5f || velocity.calculateVelocity().y > 250f) collapse()
                            else scope.launch { progress.animateTo(1f, spring(0.70f, 360f)) }
                        },
                        onVerticalDrag = { change, amount ->
                            change.consume()
                            velocity.addPosition(change.uptimeMillis, change.position)
                            scope.launch { progress.snapTo((progress.value - amount / height).coerceIn(0f, 1f)) }
                        })
                }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.padding(top = 12.dp).size(32.dp, 4.dp).clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)))
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                        ActionIcon(Icons.Rounded.KeyboardArrowDown, "关闭队列", "close_queue", collapse)
                    }
                }
                content(collapse)
            }
        }
    }
}
