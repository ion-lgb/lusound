package app.lusound.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.convx.music.ui.component.backdrop.Backdrop
import com.convx.music.ui.component.backdrop.backdrops.layerBackdrop
import com.convx.music.ui.component.backdrop.backdrops.rememberLayerBackdrop
import com.convx.music.ui.component.backdrop.catalog.components.LiquidSlider
import kotlinx.coroutines.delay

/** Immutable snapshot; the controller stays on the main thread. */
data class PlaybackState(val mediaId: String?, val title: String, val artist: String, val artwork: String?, val playing: Boolean,
    val position: Long, val duration: Long, val repeat: Int, val shuffle: Boolean, val error: String?)

@Composable
fun rememberPlayback(controller: MediaController?): PlaybackState {
    fun snapshot(): PlaybackState = PlaybackState(controller?.currentMediaItem?.mediaId,
        controller?.mediaMetadata?.title?.toString().orEmpty(), controller?.mediaMetadata?.artist?.toString().orEmpty(),
        controller?.mediaMetadata?.artworkUri?.toString(), controller?.isPlaying == true,
        controller?.currentPosition ?: 0, controller?.duration?.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0,
        controller?.repeatMode ?: Player.REPEAT_MODE_OFF, controller?.shuffleModeEnabled == true,
        controller?.playerError?.let { "${it.errorCodeName}：${it.cause?.message ?: it.message}" })
    var state by remember(controller) { mutableStateOf(snapshot()) }
    DisposableEffect(controller) {
        val listener = object : Player.Listener { override fun onEvents(player: Player, events: Player.Events) { state = snapshot() } }
        controller?.addListener(listener)
        onDispose { controller?.removeListener(listener) }
    }
    LaunchedEffect(controller) { while (true) { state = snapshot(); delay(500) } }
    return state
}

@Composable
fun MiniPlayer(state: PlaybackState, backdrop: Backdrop, expand: () -> Unit, toggle: () -> Unit, next: () -> Unit) {
    Row(Modifier.fillMaxWidth().glass(backdrop).clickable(onClick = expand).testTag("mini_player").padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(state.artwork, Modifier.size(48.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(if (state.error != null) "播放失败，点击查看" else state.artist.ifBlank { "本地音乐" }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        ActionIcon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "播放或暂停", "mini_toggle", toggle)
        ActionIcon(Icons.Rounded.SkipNext, "下一首", "mini_next", next)
    }
}

@Composable
fun PlayerScreen(controller: MediaController, state: PlaybackState, close: () -> Unit, equalizer: () -> Unit) {
    val backdrop = rememberLayerBackdrop()
    var queueOpen by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("player_screen")) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            Artwork(state.artwork, Modifier.fillMaxSize().blur(64.dp))
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.8f)))
        }
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                ActionIcon(Icons.Rounded.KeyboardArrowDown, "收起播放器", "close_player", close)
                Text("正 在 播 放", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ActionIcon(Icons.Rounded.Tune, "均衡器", "player_eq", equalizer)
            }
            Spacer(Modifier.height(32.dp))
            Artwork(state.artwork, Modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(1f))
            Spacer(Modifier.height(30.dp))
            Text(state.title, Modifier.fillMaxWidth(), fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(state.artist.ifBlank { "未知艺术家" }, Modifier.fillMaxWidth().padding(top = 8.dp), fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (state.mediaId?.startsWith("lusound://") == true) "在线" else "本地", Modifier.align(Alignment.Start).padding(top = 12.dp), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
            Spacer(Modifier.height(20.dp))
            val duration = state.duration.coerceAtLeast(1).toFloat()
            val position by rememberUpdatedState(state.position)
            key(state.mediaId, state.duration) {
            LiquidSlider(value = { position.toFloat().coerceIn(0f, duration) }, onValueChange = { controller.seekTo(it.toLong()) },
                valueRange = 0f..duration, visibilityThreshold = 1f, backdrop = backdrop,
                modifier = Modifier.fillMaxWidth().height(48.dp).testTag("seek").semantics {
                    contentDescription = "播放进度"
                    progressBarRangeInfo = ProgressBarRangeInfo(state.position.toFloat().coerceIn(0f, duration), 0f..duration)
                    setProgress { controller.seekTo(it.toLong()); true }
                })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(timeLabel(state.position), fontSize = 12.sp); Text(timeLabel(state.duration), fontSize = 12.sp) }
            Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                ActionIcon(Icons.Rounded.Shuffle, if (state.shuffle) "关闭随机播放" else "随机播放", "shuffle", { controller.shuffleModeEnabled = !state.shuffle })
                ActionIcon(Icons.Rounded.SkipPrevious, "上一首", "previous", { controller.seekToPreviousMediaItem() })
                FilledIconButton({ if (state.playing) controller.pause() else { if (controller.playerError != null) controller.prepare(); controller.play() } }, Modifier.size(76.dp).testTag("player_toggle")) {
                    Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "播放或暂停", Modifier.size(42.dp))
                }
                ActionIcon(Icons.Rounded.SkipNext, "下一首", "next", { controller.seekToNextMediaItem() })
                ActionIcon(if (state.repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    "循环模式：${state.repeat}", "repeat", { controller.repeatMode = (state.repeat + 1) % 3 })
            }
            Text("${if (state.shuffle) "随机播放" else "顺序播放"} · ${when (state.repeat) { Player.REPEAT_MODE_ONE -> "单曲循环"; Player.REPEAT_MODE_ALL -> "列表循环"; else -> "不循环" }}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            state.error?.let { Text("播放失败：$it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
            TextButton({ queueOpen = true }, Modifier.testTag("open_queue")) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null); Text("  播放队列 · ${controller.mediaItemCount}") }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (queueOpen) AlertDialog(onDismissRequest = { queueOpen = false }, title = { Text("播放队列") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            repeat(controller.mediaItemCount) { index ->
                TextButton({ controller.seekToDefaultPosition(index); controller.play(); queueOpen = false }, Modifier.testTag("queue_$index")) {
                    Text("${index + 1}. ${controller.getMediaItemAt(index).mediaMetadata.title}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }, confirmButton = { TextButton({ queueOpen = false }) { Text("关闭") } })
}

private fun timeLabel(ms: Long): String = "${ms / 60000}:${(ms / 1000 % 60).toString().padStart(2, '0')}"
