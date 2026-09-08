/** Convx PlayingIndicator visual: three bars, alternating heights and a quiet paused state. GPL-3.0. */
package app.lusound.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

@Composable
fun PlayingBars(playing: Boolean, modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "playing_bars")
    val one by transition.animateFloat(0.2f, 0.8f, infiniteRepeatable(tween(540), RepeatMode.Reverse), label = "bar_one")
    val two by transition.animateFloat(0.8f, 0.3f, infiniteRepeatable(tween(410), RepeatMode.Reverse), label = "bar_two")
    val three by transition.animateFloat(0.4f, 1f, infiniteRepeatable(tween(630), RepeatMode.Reverse), label = "bar_three")
    Canvas(modifier) {
        val heights = if (playing) listOf(one, two, three) else listOf(0.2f, 0.2f, 0.2f)
        val width = size.width / 12f
        heights.forEachIndexed { index, fraction ->
            val height = size.height * 0.45f * fraction
            drawRoundRect(Color.White, Offset(size.width * 0.3f + index * width * 2, size.height * 0.7f - height), Size(width, height), CornerRadius(width / 2))
        }
    }
}
