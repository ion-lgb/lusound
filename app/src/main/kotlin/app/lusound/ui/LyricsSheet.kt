package app.lusound.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lusound.metadata.*

@Composable
fun LyricsSheet(metadata: TrackMetadata?, position: Long, seek: (Long) -> Unit, retry: () -> Unit, close: () -> Unit) {
    val lines = remember(metadata?.lyrics) { parseLyrics(metadata?.lyrics.orEmpty()) }
    val active = currentLyricIndex(lines, position)
    val list = rememberLazyListState()
    val links = LocalUriHandler.current
    val enabled = metadataEnabled(LocalContext.current)
    LaunchedEffect(active) { if (active >= 0 && !list.isScrollInProgress) list.animateScrollToItem((active - 1).coerceAtLeast(0)) }
    MusicSheet(canDismiss = { true }, onDismissRequest = close, title = { Text("歌词") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(metadata?.lyricsSource?.let { "来源：$it" } ?: "联网后自动搜索，匹配成功后可离线阅读", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (lines.isNotEmpty()) {
                LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth().heightIn(max = 420.dp).testTag("lyrics_list"), state = list, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(lines) { index, line ->
                        Text(line.text.ifBlank { "···" }, Modifier.fillMaxWidth().clickable { seek(line.timeMs) }.testTag("lyric_$index").padding(vertical = 12.dp),
                            fontSize = if (index == active) 22.sp else 19.sp, lineHeight = 30.sp,
                            fontWeight = if (index == active) FontWeight.Bold else FontWeight.Normal,
                            color = if (index == active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else if (metadata?.lyrics != null) {
                LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 420.dp).testTag("lyrics_plain")) {
                    item { Text(metadata.lyrics.ifEmpty { "纯音乐，无歌词" }, lineHeight = 28.sp) }
                }
            } else Text(if (metadata != null && metadata.checkedAt > 0) "暂未找到可信的歌词匹配" else "正在准备歌词；无网络时会等待连接", Modifier.testTag("lyrics_empty"))
            metadata?.error?.let { Text(it, Modifier.testTag("metadata_error"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            Row {
                metadata?.lyricsUrl?.let { url -> TextButton({ links.openUri(url) }) { Text("歌词来源") } }
                metadata?.coverUrl?.let { url -> TextButton({ links.openUri(url) }) { Text("封面来源") } }
            }
        }
    }, confirmButton = { TextButton(close, Modifier.testTag("close_lyrics")) { Text("关闭") } },
        dismissButton = { TextButton(retry, Modifier.testTag("retry_metadata"), enabled = enabled) { Text(if (enabled) "重新匹配" else "自动搜索已关闭") } })
}
