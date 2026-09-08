package app.lusound.ui

import androidx.compose.animation.*
import androidx.compose.foundation.lazy.itemsIndexed

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.convx.music.ui.utils.Motion
import com.convx.music.ui.component.shapes.ContinuousRoundedRectangle
import com.convx.music.ui.component.floatingtabbar.rememberFloatingTabBarScrollConnection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import app.lusound.library.*
import app.lusound.playback.toMediaItem
import coil3.compose.AsyncImage
import com.convx.music.ui.component.backdrop.Backdrop
import com.convx.music.ui.component.backdrop.backdrops.layerBackdrop
import com.convx.music.ui.component.backdrop.backdrops.rememberLayerBackdrop

enum class LibraryTab(val label: String) { SONGS("歌曲"), ALBUMS("专辑"), ARTISTS("艺术家"), FOLDERS("文件夹"), PLAYLISTS("歌单") }

@Composable
fun LuSoundApp(library: LibraryViewModel, controller: MediaController?, hasPermission: Boolean,
    requestPermission: () -> Unit, importFiles: () -> Unit, importFolder: () -> Unit, requestNotifications: () -> Unit, openEqualizer: () -> Unit) {
    val servers: app.lusound.cloud.ServersViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    LuSoundTheme {
        val colors = MaterialTheme.colorScheme
        val focus = androidx.compose.ui.platform.LocalFocusManager.current
        CompositionLocalProvider(LocalContentColor provides colors.onBackground) {
        val tracks by library.tracks.collectAsStateWithLifecycle()
        val metadata by library.metadata.collectAsStateWithLifecycle()
        val playlists by library.playlists.collectAsStateWithLifecycle()
        val entries by library.entries.collectAsStateWithLifecycle()
        val scanning by library.scanning.collectAsStateWithLifecycle()
        val error by library.error.collectAsStateWithLifecycle()
        var tab by rememberSaveable { mutableStateOf(LibraryTab.SONGS) }
        var group by rememberSaveable { mutableStateOf<String?>(null) }
        var searchRequest by remember { mutableIntStateOf(0) }
        var query by rememberSaveable { mutableStateOf("") }
        var settings by rememberSaveable { mutableStateOf(false) }
        var fullPlayer by rememberSaveable { mutableStateOf(false) }
        var createPlaylist by remember { mutableStateOf(false) }
        var addTrack by remember { mutableStateOf<Track?>(null) }
        var playlistName by remember { mutableStateOf("") }
        val playback = rememberPlayback(controller)
        val playerMotion = rememberPlayerMotion(fullPlayer) { fullPlayer = it }
        val navScroll = rememberFloatingTabBarScrollConnection()
        val currentTrack = tracks.firstOrNull { it.uri == playback.mediaId }
        val currentMetadata = metadata.firstOrNull { it.uri == playback.mediaId && currentTrack != null && it.fingerprint == app.lusound.metadata.metadataFingerprint(currentTrack) }
        LaunchedEffect(currentTrack?.uri) { currentTrack?.let(library::loadMetadata) }
        LaunchedEffect(tracks, controller) {
            controller?.let { player ->
                val indexed = tracks.associateBy { it.uri }
                for (index in 0 until player.mediaItemCount) {
                    val item = player.getMediaItemAt(index)
                    val track = indexed[item.mediaId] ?: continue
                    val artwork = track.artworkUri
                    if (item.mediaMetadata.artworkUri?.toString() != artwork) player.replaceMediaItem(index,
                        item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setArtworkUri(artwork?.let(android.net.Uri::parse)).build()).build())
                }
            }
        }
        val mainPageState = rememberSaveableStateHolder()
        val backdrop = rememberLayerBackdrop()
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(error) { error?.let { snackbar.showSnackbar(it); library.clearError() } }
        BackHandler(fullPlayer || settings || group != null) {
            when { fullPlayer -> fullPlayer = false; settings -> settings = false; else -> group = null }
        }
        fun play(items: List<Track>, index: Int) {
            if (controller == null) { library.reportError("播放服务尚未连接，请稍后重试。"); return }
            controller.setMediaItems(items.map(::toMediaItem), index, 0)
            controller.prepare(); controller.play()
        }
        Box(Modifier.fillMaxSize().background(colors.background).testTag("lusound_root")) {
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                MusicAtmosphere(playback.artwork)
                AnimatedContent(settings, Modifier.fillMaxSize(), transitionSpec = {
                    (fadeIn(tween(200)) + slideInHorizontally(Motion.push()) { if (targetState) it / 8 else -it / 8 }) togetherWith
                        (fadeOut(tween(160)) + slideOutHorizontally(Motion.push()) { if (targetState) -it / 8 else it / 8 })
                }, label = "main_navigation") { showSettings ->
                    mainPageState.SaveableStateProvider(if (showSettings) "settings" else "library") {
                    if (showSettings) {
                        SettingsContent(importFiles, requestNotifications, openEqualizer, servers, library, importFolder, { folder ->
                            controller?.let { player -> for (index in player.mediaItemCount - 1 downTo 0) {
                                if (player.getMediaItemAt(index).mediaId.startsWith("$folder/document/")) player.removeMediaItem(index)
                            } }
                        }, { id ->
                            controller?.let { player -> for (index in player.mediaItemCount - 1 downTo 0) {
                                if (android.net.Uri.parse(player.getMediaItemAt(index).mediaId).host == id) player.removeMediaItem(index)
                            } }
                        }, { settings = false }, navScroll)
                    } else LibraryContent(tracks, playlists, entries, tab, group, query, { query = it },
                        { selected, selectedGroup -> tab = selected; group = selectedGroup }, scanning, hasPermission,
                        requestPermission, importFiles, library::refresh, { createPlaylist = true }, ::play, { addTrack = it },
                        playback.mediaId, playback.playing, { settings = true }, navScroll, searchRequest, { searchRequest = 0 })
                    }
                }
            }
            Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                ConvxNavigation(settings, { focus.clearFocus(); settings = it; navScroll.expand() }, { settings = false; group = null; tab = LibraryTab.SONGS; searchRequest++; navScroll.expand() },
                    backdrop, navScroll, if (playback.mediaId != null) ({ ownsMorph ->
                        MiniPlayer(playback, backdrop, { fullPlayer = true }, { controller?.let { if (it.isPlaying) it.pause() else it.play() } },
                            { controller?.seekToNextMediaItem() }, playerMotion, ownsMorph)
                    }) else null)
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).statusBarsPadding())
            if (controller != null) PlayerOverlay(controller, playback, currentMetadata,
                { playback.mediaId?.let(library::retryMetadata) }, openEqualizer, playerMotion)
        }
        if (createPlaylist) MusicSheet(canDismiss = { true }, onDismissRequest = { createPlaylist = false }, title = { Text("新建歌单") },
            text = { OutlinedTextField(playlistName, { playlistName = it }, Modifier.testTag("playlist_name"), label = { Text("名称") }, shape = MaterialTheme.shapes.medium, colors = musicFieldColors(), singleLine = true) },
            confirmButton = { val complete = LocalMusicSheetComplete.current; TextButton({ library.createPlaylist(playlistName); if (playlistName.isNotBlank()) complete { createPlaylist = false; playlistName = "" } }, Modifier.testTag("save_playlist")) { Text("创建") } },
            dismissButton = { val complete = LocalMusicSheetComplete.current; TextButton({ complete { createPlaylist = false } }) { Text("取消") } })
        addTrack?.let { track ->
            MusicSheet(canDismiss = { true }, onDismissRequest = { addTrack = null }, title = { Text("添加到歌单") }, text = {
                val complete = LocalMusicSheetComplete.current
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TextButton({ controller?.let { it.addMediaItem(toMediaItem(track)); if (it.playbackState == androidx.media3.common.Player.STATE_IDLE) it.prepare() }; complete { addTrack = null } }, enabled = controller != null) { Text("加入播放队列") }
                    playlists.filter { it.serverId == null }.forEach { playlist -> TextButton({ library.addToPlaylist(playlist.id, track.uri); complete { addTrack = null } }) { Text(playlist.name) } }
                    if (playlists.none { it.serverId == null }) Text("请先在「歌单」分类中创建歌单。")
                    if (tab == LibraryTab.PLAYLISTS && group != null && playlists.firstOrNull { it.id.toString() == group }?.serverId == null) TextButton({ library.removeFromPlaylist(requireNotNull(group).toLong(), track.uri); complete { addTrack = null } }) { Text("从当前歌单移除") }
                }
            }, dismissButton = {}, confirmButton = { val complete = LocalMusicSheetComplete.current; TextButton({ complete { addTrack = null } }) { Text("关闭") } })
        }
    }
}

}


@Composable
fun ActionIcon(icon: ImageVector, label: String, tag: String, action: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, Motion.press(), label = "control_press")
    IconButton(action, Modifier.size(48.dp).graphicsLayer { scaleX = scale; scaleY = scale }.testTag(tag), interactionSource = interaction) { Icon(icon, label) }
}

@Composable
fun TrackRow(track: Track, current: Boolean, playing: Boolean, play: () -> Unit, add: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(ContinuousRoundedRectangle(12.dp)).background(if (current) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
        .clickable(onClick = play).testTag("track_${track.uri}").padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            Artwork(track.artworkUri, Modifier.size(48.dp))
            if (current) PlayingBars(playing, Modifier.size(48.dp).background(Color.Black.copy(alpha = 0.3f)))
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
            Text("${track.artist.ifBlank { "未知艺术家" }} · ${track.format.uppercase()} · ${if (track.format == "ncm") "来源：网易云" else if (track.uri.startsWith("lusound://")) "在线" else "本地"}", maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        ActionIcon(Icons.AutoMirrored.Rounded.PlaylistAdd, "添加到歌单", "add_${track.uri}", add)
    }
}

@Composable
fun Artwork(uri: String?, modifier: Modifier) {
    Box(modifier.clip(ContinuousRoundedRectangle(12.dp)).background(Brush.linearGradient(listOf(Color(0xFF526486), Color(0xFF202A44), Color(0xFF8A7190)))), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.MusicNote, null, Modifier.fillMaxSize(0.4f), tint = Color.White.copy(alpha = 0.6f))
        val context = LocalContext.current
        val request = remember(uri, context) { ImageRequest.Builder(context).data(uri).crossfade(200).build() }
        if (uri != null) AsyncImage(request, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
    }
}
