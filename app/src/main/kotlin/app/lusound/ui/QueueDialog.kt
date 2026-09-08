package app.lusound.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController

private data class QueueState(val items: List<MediaItem>, val currentIndex: Int, val editable: Boolean)

/** A timeline snapshot keeps duplicate songs as separate entries and updates even while playback is paused. */
@Composable
fun QueueDialog(controller: MediaController, close: () -> Unit) {
    fun snapshot(): QueueState = QueueState((0 until controller.mediaItemCount).map(controller::getMediaItemAt),
        controller.currentMediaItemIndex, controller.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS))
    var queue by remember(controller) { mutableStateOf(snapshot()) }
    DisposableEffect(controller) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { queue = snapshot() }
        }
        controller.addListener(listener)
        queue = snapshot()
        onDispose { controller.removeListener(listener) }
    }
    MusicSheet(canDismiss = { true }, onDismissRequest = close, title = { Text("播放队列 · ${queue.items.size}") }, text = {
        if (queue.items.isEmpty()) Text("队列为空，请到资料库添加歌曲", Modifier.testTag("queue_empty"))
        else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp).testTag("queue_list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(queue.items) { index, item ->
                val title = item.mediaMetadata.title?.toString().orEmpty().ifBlank { "未命名歌曲" }
                Surface(shape = MaterialTheme.shapes.medium, color = if (index == queue.currentIndex) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxWidth().padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Artwork(item.mediaMetadata.artworkUri?.toString(), Modifier.size(44.dp))
                            TextButton({ controller.seekToDefaultPosition(index); controller.play(); close() }, Modifier.weight(1f).testTag("queue_$index")) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text("${index + 1}. $title", maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                    item.mediaMetadata.artist?.toString()?.let { artist ->
                                        Text(artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        if (index == queue.currentIndex) Text("当前歌曲", Modifier.padding(horizontal = 12.dp).testTag("queue_current_$index"), style = MaterialTheme.typography.labelSmall)
                        Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconButton({ controller.moveMediaItem(index, index - 1) }, Modifier.testTag("queue_up_$index"), enabled = queue.editable && index > 0) {
                                Icon(Icons.Rounded.ArrowUpward, "上移：$title")
                            }
                            IconButton({ controller.moveMediaItem(index, index + 1) }, Modifier.testTag("queue_down_$index"), enabled = queue.editable && index < queue.items.lastIndex) {
                                Icon(Icons.Rounded.ArrowDownward, "下移：$title")
                            }
                            IconButton({ controller.removeMediaItem(index) }, Modifier.testTag("queue_remove_$index"), enabled = queue.editable) {
                                Icon(Icons.Rounded.Close, "从队列移除：$title")
                            }
                        }
                    }
                }
            }
        }
    }, dismissButton = {}, confirmButton = { TextButton(close, Modifier.testTag("close_queue")) { Text("关闭") } })
}
