/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 * Default player presentation adapted to LuSound's Media3 playback snapshot.
 */
package app.lusound.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import app.lusound.metadata.TrackMetadata
import coil3.compose.AsyncImage
import com.convx.music.ui.component.backdrop.catalog.components.LiquidSlider
import com.convx.music.ui.component.backdrop.Backdrop
import com.convx.music.ui.component.backdrop.backdrops.layerBackdrop
import com.convx.music.ui.component.backdrop.backdrops.rememberLayerBackdrop
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
fun MiniPlayer(state: PlaybackState, backdrop: Backdrop, expand: () -> Unit, toggle: () -> Unit, next: () -> Unit, motion: PlayerMotion, ownsMorph: Boolean) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 1.04f else 1f,
        spring(stiffness = Spring.StiffnessMediumLow), label = "accessoryPressScale")
    val density = LocalDensity.current
    var narrow by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().onSizeChanged { narrow = it.width < with(density) { 260.dp.toPx() } }.then(if (ownsMorph) Modifier.recordMiniPlayer(motion) else Modifier).graphicsLayer { scaleX = pressScale; scaleY = pressScale }
        .glass(backdrop).playerGestures(motion, { checkNotNull(motion.previousTrack.value) { "Player overlay has not registered previous-track playback" }() }, next)
        .clickable(interactionSource = interaction, indication = null, onClick = expand)
        .testTag("mini_player").padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        PlayerCover(state.artwork, Modifier.size(44.dp).testTag("mini_artwork").then(if (ownsMorph) Modifier.miniPlayerArtwork(motion) else Modifier), 8.dp)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(state.title, Modifier.basicMarquee(iterations = 1, initialDelayMillis = 3000, velocity = 30.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!narrow) Text(if (state.error != null) "播放失败，点击查看" else state.artist.ifBlank { "本地音乐" },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(toggle, Modifier.size(48.dp).testTag("mini_toggle").semantics { contentDescription = "播放或暂停" }) {
            PlayerPlayPauseIcon(state.playing, 28.dp)
        }
        if (!narrow) IconButton(next, Modifier.size(48.dp).testTag("mini_next")) {
            Icon(Icons.Rounded.FastForward, "下一首", Modifier.size(28.dp))
        }
    }
}

@Composable
fun PlayerOverlay(controller: MediaController, state: PlaybackState, metadata: TrackMetadata?, retryMetadata: () -> Unit, equalizer: () -> Unit, motion: PlayerMotion) {
    SideEffect { motion.previousTrack.value = { controller.seekToPreviousMediaItem() } }
    Box(Modifier.fillMaxSize().onGloballyPositioned { motion.fullRect.value = it.boundsInRoot() }) {
        if (motion.progress > 0f || motion.dragging.value) {
            PlayerScreen(controller, state, metadata, retryMetadata, { collapsePlayer(motion) }, equalizer, motion)
        }
        PlayerMorphOverlay(motion, state.artwork, MaterialTheme.colorScheme.surfaceContainer)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(controller: MediaController, state: PlaybackState, metadata: TrackMetadata?, retryMetadata: () -> Unit, close: () -> Unit, equalizer: () -> Unit, motion: PlayerMotion) {
    val backdrop = rememberLayerBackdrop()
    var queueOpen by remember { mutableStateOf(false) }
    var lyricsOpen by remember { mutableStateOf(false) }
    var fullscreenLyrics by remember { mutableStateOf(false) }
    val presentation = PlayerPresentation(state.mediaId, state.title, state.artist, state.artwork)
    val previous: () -> Unit = { controller.seekToPreviousMediaItem() }
    val next: () -> Unit = { controller.seekToNextMediaItem() }
    BoxWithConstraints(Modifier.fillMaxSize().recordFullPlayer(motion).background(MaterialTheme.colorScheme.background).testTag("player_screen")) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) { MusicAtmosphere(state.artwork) }
        // Reserve transport, volume, actions and scalable text before sizing artwork.
        val textExpansion = (120f * (LocalDensity.current.fontScale - 1f).coerceAtLeast(0f)).dp
        val coverSize = minOf(maxWidth - 64.dp, (maxHeight - 560.dp - textExpansion).coerceAtLeast(120.dp), 420.dp)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().playerGestures(motion, previous, next), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                ActionIcon(Icons.Rounded.KeyboardArrowDown, "收起播放器", "close_player", close)
                Box(Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)))
                ActionIcon(Icons.Rounded.Tune, "均衡器", "player_eq", equalizer)
            }
            AnimatedContent(lyricsOpen, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "playerInlineLyrics",
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 28.dp).height(coverSize)) { showLyrics ->
                if (showLyrics) {
                    InlineLyrics(metadata, state.position, controller::seekTo, retryMetadata, Modifier.fillMaxSize())
                } else {
                    PlayerArtworkCarousel(controller, presentation, coverSize, motion, Modifier.fillMaxSize(), !lyricsOpen)
                }
            }
            AnimatedContent(presentation, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(140)) }, label = "playerTrack") { item ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    AnimatedVisibility(lyricsOpen, enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) {
                        Row {
                            PlayerCover(item.artwork, Modifier.size(56.dp).then(if (item == presentation && lyricsOpen) Modifier.fullPlayerArtwork(motion) else Modifier), 12.dp)
                            Spacer(Modifier.width(12.dp))
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(item.title, Modifier.testTag("player_title").basicMarquee(iterations = 1, initialDelayMillis = 3000, velocity = 30.dp), fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(item.artist.ifBlank { "未知艺术家" }, Modifier.padding(top = 4.dp), fontSize = 17.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    AnimatedVisibility(lyricsOpen, enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) {
                        IconButton({ fullscreenLyrics = true }, Modifier.testTag("fullscreen_lyrics")) { Icon(Icons.Rounded.Fullscreen, "全屏歌词") }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            val duration = state.duration.coerceAtLeast(1).toFloat()
            val position by rememberUpdatedState(state.position)
            key(state.mediaId, state.duration) {
                LiquidSlider(value = { position.toFloat().coerceIn(0f, duration) },
                    onValueChange = { controller.seekTo(it.toLong()) }, valueRange = 0f..duration,
                    visibilityThreshold = 1f, backdrop = backdrop,
                    modifier = Modifier.fillMaxWidth().height(40.dp).testTag("seek").semantics {
                        contentDescription = "播放进度"
                        progressBarRangeInfo = ProgressBarRangeInfo(state.position.toFloat().coerceIn(0f, duration), 0f..duration)
                        setProgress { controller.seekTo(it.toLong()); true }
                    })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(timeLabel(state.position), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("−${timeLabel((state.duration - state.position).coerceAtLeast(0))}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                IconButton(previous, Modifier.size(64.dp).testTag("previous")) { Icon(Icons.Rounded.SkipPrevious, "上一首", Modifier.size(48.dp)) }
                IconButton({ if (state.playing) controller.pause() else { if (controller.playerError != null) controller.prepare(); controller.play() } },
                    Modifier.size(100.dp).testTag("player_toggle").semantics { contentDescription = "播放或暂停" }) { PlayerPlayPauseIcon(state.playing, 72.dp) }
                IconButton(next, Modifier.size(64.dp).testTag("next")) { Icon(Icons.Rounded.FastForward, "下一首", Modifier.size(48.dp)) }
            }
            PlayerVolume(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconToggleButton(state.shuffle, { controller.shuffleModeEnabled = it }, Modifier.testTag("shuffle")) {
                    Icon(Icons.Rounded.Shuffle, if (state.shuffle) "关闭随机播放" else "随机播放", tint = if (state.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton({ lyricsOpen = !lyricsOpen }, Modifier.testTag(if (fullscreenLyrics) "close_inline_lyrics" else if (lyricsOpen) "close_lyrics" else "open_lyrics")) {
                    Icon(if (lyricsOpen) Icons.Rounded.Close else Icons.Rounded.Subtitles, if (lyricsOpen) "关闭歌词" else "歌词")
                }
                IconButton({ queueOpen = true }, Modifier.testTag("open_queue")) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, "播放队列 · ${controller.mediaItemCount}") }
                IconButton({ controller.repeatMode = (state.repeat + 1) % 3 }, Modifier.testTag("repeat")) {
                    Icon(if (state.repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        "循环模式：${state.repeat}", tint = if (state.repeat != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            state.error?.let { Text("播放失败：$it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
            Spacer(Modifier.height(20.dp))
        }
    }
    if (fullscreenLyrics) LyricsSheet(metadata, state.position, controller::seekTo, retryMetadata) { fullscreenLyrics = false }
    if (queueOpen) QueueDialog(controller) { queueOpen = false }
}

@Composable
fun PlayerCover(uri: String?, modifier: Modifier, radius: Dp) {
    Box(modifier.clip(RoundedCornerShape(radius)).background(Brush.linearGradient(listOf(Color(0xFF526486), Color(0xFF202A44), Color(0xFF8A7190)))), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.MusicNote, null, Modifier.fillMaxSize(0.4f), tint = Color.White.copy(alpha = 0.6f))
        AnimatedContent(uri, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(140)) }, label = "playerCoverImage") { image ->
            if (image != null) AsyncImage(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

@Composable
private fun PlayerPlayPauseIcon(playing: Boolean, size: Dp) {
    AnimatedContent(playing, transitionSpec = {
        ((fadeIn(tween(180, easing = FastOutSlowInEasing)) + scaleIn(tween(180, easing = FastOutSlowInEasing), initialScale = 0.85f)) togetherWith
            (fadeOut(tween(140, easing = FastOutSlowInEasing)) + scaleOut(tween(140, easing = FastOutSlowInEasing), targetScale = 0.85f))).using(SizeTransform(clip = false))
    }, label = "play_pause") { isPlaying ->
        Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, Modifier.size(if (isPlaying) size * 0.88f else size))
    }
}

private fun timeLabel(ms: Long): String = "${ms / 60000}:${(ms / 1000 % 60).toString().padStart(2, '0')}"
