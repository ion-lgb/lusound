/**
 * Standard line motion and fading edges adapted from Convx Lyrics.kt / FadingEdge.kt.
 * Convx Project (C) 2026, GPL-3.0; see NOTICE. LuSound retains its real LRC timestamps.
 */
package app.lusound.ui

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lusound.metadata.*
import kotlinx.coroutines.flow.first
import kotlin.math.abs

/** Centers the measured line; padding permits centering the first and last lines too. */
private suspend fun centerLyric(list: LazyListState, index: Int, duration: Int) {
    if (list.layoutInfo.visibleItemsInfo.none { it.index == index }) list.scrollToItem(index)
    val item = snapshotFlow { list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } }.first { it != null }
    requireNotNull(item)
    val layout = list.layoutInfo
    val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
    val offset = item.offset + item.size / 2 - center
    if (abs(offset) > 10) list.animateScrollBy(offset.toFloat(), tween(duration))
}

/** Convx's 64dp top/bottom destination-in mask, on an explicit offscreen layer. */
private fun Modifier.lyricFadingEdges(): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black), 0f, 64.dp.toPx()), blendMode = BlendMode.DstIn)
        drawRect(Brush.verticalGradient(listOf(Color.Black, Color.Transparent), size.height - 64.dp.toPx(), size.height), blendMode = BlendMode.DstIn)
    }

@Composable
fun InlineLyrics(metadata: TrackMetadata?, position: Long, seek: (Long) -> Unit, retry: () -> Unit, modifier: Modifier) {
    Column(modifier) {
        LyricsContent(metadata, position, seek, Modifier.weight(1f).fillMaxWidth())
        TextButton(retry, Modifier.testTag("retry_metadata"), enabled = metadataEnabled(LocalContext.current)) { Text("重新匹配") }
    }
}

@Composable
fun LyricsSheet(metadata: TrackMetadata?, position: Long, seek: (Long) -> Unit, retry: () -> Unit, close: () -> Unit) {
    val enabled = metadataEnabled(LocalContext.current)
    MusicSheet(canDismiss = { true }, onDismissRequest = close, title = { Text("歌词") }, text = {
        LyricsContent(metadata, position, seek, Modifier.fillMaxWidth().height(460.dp))
    }, confirmButton = {
        val complete = LocalMusicSheetComplete.current
        TextButton({ complete(close) }, Modifier.testTag("close_lyrics")) { Text("关闭") }
    }, dismissButton = { TextButton(retry, Modifier.testTag("retry_metadata"), enabled = enabled) { Text(if (enabled) "重新匹配" else "自动搜索已关闭") } })
}

@Composable
private fun LyricsContent(metadata: TrackMetadata?, position: Long, seek: (Long) -> Unit, modifier: Modifier) {
    val lines = remember(metadata?.lyrics) { parseLyrics(metadata?.lyrics.orEmpty()) }
    val active = currentLyricIndex(lines, position)
    val list = rememberLazyListState()
    val links = LocalUriHandler.current
    var following by remember(metadata?.lyrics) { mutableStateOf(true) }
    var initialCentered by remember(metadata?.lyrics) { mutableStateOf(false) }
    val manualScroll = remember(metadata?.lyrics) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) following = false
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(active, following, lines) {
        if (active >= 0 && following) {
            centerLyric(list, active, if (initialCentered) 1500 else 800)
            initialCentered = true
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(metadata?.lyricsSource?.let { "来源：$it" } ?: "联网后自动搜索，匹配成功后可离线阅读", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (lines.isNotEmpty()) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(Modifier.fillMaxSize().lyricFadingEdges().nestedScroll(manualScroll).testTag("lyrics_list"),
                        state = list, contentPadding = PaddingValues(vertical = maxHeight / 2)) {
                        itemsIndexed(lines, key = { index, line -> "$index-${line.timeMs}" }) { index, line ->
                            val isActive = active >= 0 && line.timeMs == lines[active].timeMs
                            val alpha by animateFloatAsState(if (isActive) 1f else 0.5f, tween(400), label = "lyricAlpha")
                            val scale by animateFloatAsState(if (isActive) 1.05f else 1f, tween(400), label = "lyricScale")
                            val targetBlur = if (!following || isActive || active < 0) 0f else when (abs(index - active)) {
                                1, 2 -> 0f
                                3 -> 2f
                                4 -> 4f
                                else -> 6f
                            }
                            val blur by animateFloatAsState(targetBlur, tween(1000), label = "standard_blur")
                            Text(line.text.ifBlank { "···" }, Modifier.fillMaxWidth()
                                .clickable { seek(line.timeMs); following = true }.testTag("lyric_$index")
                                .semantics { selected = isActive }
                                .padding(horizontal = 24.dp, vertical = 10.4.dp)
                                .graphicsLayer { this.alpha = alpha; scaleX = scale; scaleY = scale }
                                .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(blur.dp) else Modifier),
                                fontSize = 30.sp, lineHeight = 39.sp,
                                fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isActive) 1f else 0.7f))
                        }
                    }
                    androidx.compose.animation.AnimatedVisibility(!following, Modifier.align(Alignment.BottomCenter),
                        enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                        FilledTonalButton({ following = true }, Modifier.testTag("lyrics_resume_follow")) { Text("跟随当前歌词") }
                    }
                }
            } else if (metadata?.lyrics != null) {
                LazyColumn(Modifier.weight(1f).testTag("lyrics_plain")) {
                    item { Text(metadata.lyrics.ifEmpty { "纯音乐，无歌词" }, lineHeight = 28.sp) }
                }
            } else Text(if (metadata != null && metadata.checkedAt > 0) "暂未找到可信的歌词匹配" else "正在准备歌词；无网络时会等待连接", Modifier.testTag("lyrics_empty"))
            metadata?.error?.let { Text(it, Modifier.testTag("metadata_error"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            Row {
                metadata?.lyricsUrl?.let { url -> TextButton({ links.openUri(url) }) { Text("歌词来源") } }
                metadata?.coverUrl?.let { url -> TextButton({ links.openUri(url) }) { Text("封面来源") } }
            }
    }
}
