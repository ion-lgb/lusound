/** Convx Project (C) 2026, GPL-3.0; default Player.kt system-volume motion, adapted for LuSound. */
package app.lusound.ui

import android.media.AudioManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeMute
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Reads Android's actual music stream, including changes made with hardware volume buttons. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerVolume(modifier: Modifier) {
    val context = LocalContext.current
    val audio = remember(context) { requireNotNull(context.getSystemService(AudioManager::class.java)) }
    val maximum = remember(audio) { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    check(maximum > 0) { "Music stream volume range must contain at least one step; maximum=$maximum" }
    var systemVolume by remember(audio) { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maximum) }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(audio, lifecycle) {
        fun refresh() { systemVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maximum }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == "android.media.VOLUME_CHANGED_ACTION") refresh()
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"), ContextCompat.RECEIVER_EXPORTED)
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycle.lifecycle.addObserver(observer)
        refresh()
        onDispose { context.unregisterReceiver(receiver); lifecycle.lifecycle.removeObserver(observer) }
    }
    val interaction = remember { MutableInteractionSource() }
    val dragged by interaction.collectIsDraggedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val active = dragged || pressed
    var dragVolume by remember { mutableFloatStateOf(systemVolume) }
    val animatedVolume by animateFloatAsState(systemVolume, tween(150, easing = LinearOutSlowInEasing), label = "systemVolume")
    val thickness by animateDpAsState(if (active) 16.dp else 10.dp, spring(dampingRatio = 0.7f, stiffness = 600f), label = "volumeThickness")
    val scale by animateFloatAsState(if (active) 1.15f else 1f, spring(dampingRatio = 0.7f, stiffness = 600f), label = "volumeIconScale")
    LaunchedEffect(systemVolume, active) { if (!active) dragVolume = systemVolume }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.VolumeMute, null, Modifier.size(20.dp).graphicsLayer { scaleX = scale; scaleY = scale }, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Slider(value = if (active) dragVolume else animatedVolume, onValueChange = { value ->
            dragVolume = value
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, (value * maximum).roundToInt(), 0)
            systemVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maximum
        }, enabled = !audio.isVolumeFixed, interactionSource = interaction, thumb = {},
            track = { state ->
                Box(Modifier.fillMaxWidth().height(thickness).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f))) {
                    Box(Modifier.fillMaxWidth(state.value).fillMaxHeight().clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)))
                }
            }, modifier = Modifier.weight(1f).testTag("player_volume").semantics { contentDescription = "系统音乐音量" })
        Spacer(Modifier.width(12.dp))
        Icon(Icons.Rounded.VolumeDown, null, Modifier.size(20.dp).graphicsLayer { scaleX = scale; scaleY = scale }, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
