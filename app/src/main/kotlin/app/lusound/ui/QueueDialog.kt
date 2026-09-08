/**
 * Flat queue rows and animateItem placement adapted from Convx Queue.kt / Items.kt.
 * Convx Project (C) 2026, GPL-3.0; see NOTICE. Playback stays in LuSound's controller.
 */
package app.lusound.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController

private data class QueueEntry(val key: Long, val item: MediaItem)
private data class QueueState(val entries: List<QueueEntry>, val currentIndex: Int, val editable: Boolean, val nextKey: Long)

/** Remote Media3 windows share a fake UID; match full media items one occurrence at a time. */
private fun queueSnapshot(controller: MediaController, previous: QueueState): QueueState {
    val remaining = previous.entries.toMutableList()
    var nextKey = previous.nextKey
    val entries = (0 until controller.mediaItemCount).map { index ->
        val item = controller.getMediaItemAt(index)
        val previousIndex = remaining.indexOfFirst { it.item == item }
        val key = if (previousIndex >= 0) remaining.removeAt(previousIndex).key else nextKey++
        QueueEntry(key, item)
    }
    return QueueState(entries, controller.currentMediaItemIndex,
        controller.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS), nextKey)
}

/** Explicit local moves preserve even indistinguishable duplicate occurrences before controller events. */
private fun moveQueueEntry(queue: QueueState, from: Int, to: Int): QueueState {
    val moving = queue.entries[from]
    val remaining = queue.entries.filterIndexed { index, _ -> index != from }
    val entries = remaining.take(to) + moving + remaining.drop(to)
    val currentKey = queue.entries.getOrNull(queue.currentIndex)?.key
    return queue.copy(entries = entries, currentIndex = entries.indexOfFirst { it.key == currentKey })
}

private fun removeQueueEntry(queue: QueueState, index: Int): QueueState {
    val entries = queue.entries.filterIndexed { entryIndex, _ -> entryIndex != index }
    val currentIndex = when {
        queue.currentIndex > index -> queue.currentIndex - 1
        queue.currentIndex == index -> index.coerceAtMost(entries.lastIndex)
        else -> queue.currentIndex
    }
    return queue.copy(entries = entries, currentIndex = currentIndex)
}

/** Timeline snapshots update while paused; stable occurrence keys allow native move animations. */
@Composable
fun QueueDialog(controller: MediaController, close: () -> Unit) {
    var queue by remember(controller) { mutableStateOf(queueSnapshot(controller, QueueState(emptyList(), -1, false, 0L))) }
    DisposableEffect(controller) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { queue = queueSnapshot(controller, queue) }
        }
        controller.addListener(listener)
        queue = queueSnapshot(controller, queue)
        onDispose { controller.removeListener(listener) }
    }
    PlayerQueueSheet(queue.entries.getOrNull(queue.currentIndex)?.item?.mediaMetadata?.artworkUri?.toString(),
        "播放队列 · ${queue.entries.size}", close) { collapse ->
        if (queue.entries.isEmpty()) Text("队列为空，请到资料库添加歌曲", Modifier.testTag("queue_empty"))
        else LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("queue_list")) {
            itemsIndexed(queue.entries, key = { _, entry -> entry.key }) { index, entry ->
                val item = entry.item
                val title = item.mediaMetadata.title?.toString().orEmpty().ifBlank { "未命名歌曲" }
                val current = index == queue.currentIndex
                Column(Modifier.animateItem().fillMaxWidth()
                    .background(if (current) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f) else Color.Transparent)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable {
                        controller.seekToDefaultPosition(index); controller.play(); collapse()
                    }.testTag("queue_$index").padding(start = 12.dp, top = 6.dp, end = 12.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Artwork(item.mediaMetadata.artworkUri?.toString(), Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)))
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text("${index + 1}. $title", maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface)
                            item.mediaMetadata.artist?.toString()?.let { artist ->
                                Text(artist, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp,
                                    lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (current) Text("当前歌曲", Modifier.testTag("queue_current_$index"), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton({
                            queue = moveQueueEntry(queue, index, index - 1)
                            controller.moveMediaItem(index, index - 1)
                            queue = queueSnapshot(controller, queue)
                        }, Modifier.testTag("queue_up_$index"), enabled = queue.editable && index > 0) {
                            Icon(Icons.Rounded.ArrowUpward, "上移：$title")
                        }
                        IconButton({
                            queue = moveQueueEntry(queue, index, index + 1)
                            controller.moveMediaItem(index, index + 1)
                            queue = queueSnapshot(controller, queue)
                        }, Modifier.testTag("queue_down_$index"), enabled = queue.editable && index < queue.entries.lastIndex) {
                            Icon(Icons.Rounded.ArrowDownward, "下移：$title")
                        }
                        IconButton({
                            queue = removeQueueEntry(queue, index)
                            controller.removeMediaItem(index)
                            queue = queueSnapshot(controller, queue)
                        }, Modifier.testTag("queue_remove_$index"), enabled = queue.editable) {
                            Icon(Icons.Rounded.Close, "从队列移除：$title")
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 12.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
