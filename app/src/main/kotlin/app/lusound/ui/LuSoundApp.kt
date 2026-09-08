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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
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
        CompositionLocalProvider(LocalContentColor provides colors.onBackground) {
        val tracks by library.tracks.collectAsStateWithLifecycle()
        val metadata by library.metadata.collectAsStateWithLifecycle()
        val playlists by library.playlists.collectAsStateWithLifecycle()
        val entries by library.entries.collectAsStateWithLifecycle()
        val scanning by library.scanning.collectAsStateWithLifecycle()
        val error by library.error.collectAsStateWithLifecycle()
        var tab by rememberSaveable { mutableStateOf(LibraryTab.SONGS) }
        var group by rememberSaveable { mutableStateOf<String?>(null) }
        var query by rememberSaveable { mutableStateOf("") }
        var settings by rememberSaveable { mutableStateOf(false) }
        var fullPlayer by rememberSaveable { mutableStateOf(false) }
        var createPlaylist by remember { mutableStateOf(false) }
        var addTrack by remember { mutableStateOf<Track?>(null) }
        var playlistName by remember { mutableStateOf("") }
        val playback = rememberPlayback(controller)
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
        val backdrop = rememberLayerBackdrop()
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(error) { error?.let { snackbar.showSnackbar(it); library.clearError() } }
        BackHandler(fullPlayer || settings || group != null) {
            when { fullPlayer -> fullPlayer = false; settings -> settings = false; else -> group = null }
        }
        val visibleTracks = tracks.filter { track ->
            val matches = query.isBlank() || listOf(track.title, track.artist, track.album).any { it.contains(query, true) }
            matches && when (tab) {
                LibraryTab.SONGS -> true
                LibraryTab.ALBUMS -> group == null || albumKey(track) == group
                LibraryTab.ARTISTS -> group == null || track.artist == group
                LibraryTab.FOLDERS -> group == null || track.folder == group
                LibraryTab.PLAYLISTS -> entries.any { it.playlistId.toString() == group && it.trackUri == track.uri }
            }
        }
        val orderedTracks = if (tab == LibraryTab.PLAYLISTS) {
            val indexed = visibleTracks.associateBy { it.uri }
            entries.filter { it.playlistId.toString() == group }.sortedBy { it.position }.mapNotNull { indexed[it.trackUri] }
        } else visibleTracks
        fun play(index: Int) {
            if (controller == null) { library.reportError("播放服务尚未连接，请稍后重试。"); return }
            controller.setMediaItems(orderedTracks.map(::toMediaItem), index, 0)
            controller.prepare(); controller.play()
        }
        Box(Modifier.fillMaxSize().background(colors.background).testTag("lusound_root")) {
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                MusicAtmosphere(playback.artwork)
                Column(Modifier.fillMaxSize()) {
                Column(Modifier.statusBarsPadding().padding(horizontal = 24.dp)) {
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("琉声 / LUSOUND", color = colors.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            Text(if (settings) "设置" else "资料库", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        }
                        ActionIcon(if (settings) Icons.AutoMirrored.Rounded.ArrowBack else Icons.Rounded.Settings, if (settings) "返回资料库" else "设置", "settings", { settings = !settings })
                    }
                    if (!settings) {
                        Text(if (scanning) "正在发现你的音乐…" else "${tracks.size} 首歌曲 · 你的音乐，在一起", color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
                        TextField(query, { query = it }, Modifier.fillMaxWidth().testTag("search"), placeholder = { Text("搜索歌曲、艺术家、专辑") },
                            leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, shape = RoundedCornerShape(24.dp),
                            colors = TextFieldDefaults.colors(focusedContainerColor = colors.surfaceContainerHigh.copy(alpha = 0.65f), unfocusedContainerColor = colors.surfaceContainerHigh.copy(alpha = 0.45f),
                                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
                            items(LibraryTab.entries) { item ->
                                FilterChip(tab == item, { tab = item; group = null }, { Text(item.label) }, Modifier.testTag("tab_${item.name}"),
                                    shape = RoundedCornerShape(50), border = null, colors = FilterChipDefaults.filterChipColors(
                                        containerColor = colors.surfaceContainer.copy(alpha = 0.6f), selectedContainerColor = colors.primary, selectedLabelColor = colors.onPrimary))
                            }
                        }
                    }
                }
                if (settings) {
                    SettingsContent(importFiles, requestNotifications, openEqualizer, servers, library, importFolder, { folder ->
                        controller?.let { player ->
                            for (index in player.mediaItemCount - 1 downTo 0) {
                                if (player.getMediaItemAt(index).mediaId.startsWith("$folder/document/")) player.removeMediaItem(index)
                            }
                        }
                    }) { id ->
                        controller?.let { player ->
                            for (index in player.mediaItemCount - 1 downTo 0) {
                                if (android.net.Uri.parse(player.getMediaItemAt(index).mediaId).host == id) player.removeMediaItem(index)
                            }
                        }
                    }
                } else {
                    LazyColumn(Modifier.weight(1f).testTag("library_list"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 200.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!hasPermission) item {
                            Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                                Text("让音乐回到你身边", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                                Text("允许读取共享存储中的音乐，或选择文件导入。", color = colors.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
                                Button(requestPermission, Modifier.testTag("grant_audio")) { Text("允许读取音乐") }
                                TextButton(importFiles, Modifier.testTag("import_audio")) { Text("导入文件") }
                            }
                        }
                        item {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                if (group != null) ActionIcon(Icons.AutoMirrored.Rounded.ArrowBack, "返回分类", "group_back", { group = null })
                                Text(if (group == null) tab.label else when (tab) {
                                    LibraryTab.PLAYLISTS -> playlists.firstOrNull { it.id.toString() == group }?.name.orEmpty()
                                    LibraryTab.ALBUMS -> visibleTracks.firstOrNull()?.album.orEmpty()
                                    else -> group.orEmpty()
                                }, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (tab == LibraryTab.PLAYLISTS && group == null) ActionIcon(Icons.Rounded.Add, "新建歌单", "create_playlist", { createPlaylist = true })
                                if (hasPermission) ActionIcon(Icons.Rounded.Refresh, "刷新音乐库", "refresh", library::refresh)
                            }
                        }
                        if (tab != LibraryTab.SONGS && group == null) {
                            if (tab == LibraryTab.PLAYLISTS) {
                                items(playlists, key = { it.id }) { playlist ->
                                    GroupRow(playlist.name, "${entries.count { it.playlistId == playlist.id }} 首${if (playlist.serverId != null) " · 服务器歌单（只读）" else ""}", { group = playlist.id.toString() })
                                }
                                if (playlists.isEmpty()) item { Text("创建歌单后，可通过歌曲右侧按钮添加音乐。", color = colors.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
                            } else {
                                val groups = visibleTracks.groupBy { when (tab) { LibraryTab.ALBUMS -> albumKey(it); LibraryTab.ARTISTS -> it.artist; else -> it.folder } }
                                items(groups.keys.sorted()) { key ->
                                    val grouped = groups.getValue(key)
                                    GroupRow(if (tab == LibraryTab.ALBUMS) grouped.first().album.ifBlank { "未知专辑" } else key.ifBlank { "未知" }, "${grouped.size} 首", { group = key })
                                }
                            }
                        } else {
                            itemsIndexed(orderedTracks, key = { index, track -> "${index}:${track.uri}" }) { index, track ->
                                TrackRow(track, playback.mediaId == track.uri, { play(index) }, { addTrack = track })
                            }
                            if (visibleTracks.isEmpty() && !scanning && hasPermission) item {
                                Text("这里还没有歌曲\n将音频放入 Music 文件夹，或在设置中导入。", color = colors.onSurfaceVariant, modifier = Modifier.padding(20.dp))
                            }
                        }
                    }
                }
            }
            }
            Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (playback.mediaId != null) MiniPlayer(playback, backdrop, { fullPlayer = true }, { controller?.let { if (it.isPlaying) it.pause() else it.play() } }, { controller?.seekToNextMediaItem() })
                Row(Modifier.fillMaxWidth().glass(backdrop).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton({ settings = false }, Modifier.weight(1f).heightIn(min = 52.dp).testTag("nav_library").semantics { selected = !settings }, colors = ButtonDefaults.textButtonColors(containerColor = if (!settings) colors.onSurface.copy(alpha = 0.12f) else Color.Transparent, contentColor = if (!settings) colors.onSurface else colors.onSurfaceVariant)) { Icon(Icons.Rounded.LibraryMusic, null); Spacer(Modifier.width(8.dp)); Text("资料库") }
                    TextButton({ settings = true }, Modifier.weight(1f).heightIn(min = 52.dp).testTag("nav_settings").semantics { selected = settings }, colors = ButtonDefaults.textButtonColors(containerColor = if (settings) colors.onSurface.copy(alpha = 0.12f) else Color.Transparent, contentColor = if (settings) colors.onSurface else colors.onSurfaceVariant)) { Icon(Icons.Rounded.Tune, null); Spacer(Modifier.width(8.dp)); Text("设置") }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).statusBarsPadding())
            AnimatedVisibility(fullPlayer, enter = slideInVertically { it / 3 } + fadeIn(), exit = slideOutVertically { it / 3 } + fadeOut()) {
                if (controller != null) PlayerScreen(controller, playback, currentMetadata, { playback.mediaId?.let(library::retryMetadata) }, { fullPlayer = false }, openEqualizer)
            }
        }
        if (createPlaylist) MusicSheet(canDismiss = { true }, onDismissRequest = { createPlaylist = false }, title = { Text("新建歌单") },
            text = { OutlinedTextField(playlistName, { playlistName = it }, Modifier.testTag("playlist_name"), label = { Text("名称") }, shape = MaterialTheme.shapes.medium, colors = musicFieldColors(), singleLine = true) },
            confirmButton = { TextButton({ library.createPlaylist(playlistName); if (playlistName.isNotBlank()) { createPlaylist = false; playlistName = "" } }, Modifier.testTag("save_playlist")) { Text("创建") } },
            dismissButton = { TextButton({ createPlaylist = false }) { Text("取消") } })
        addTrack?.let { track ->
            MusicSheet(canDismiss = { true }, onDismissRequest = { addTrack = null }, title = { Text("添加到歌单") }, text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TextButton({ controller?.let { it.addMediaItem(toMediaItem(track)); if (it.playbackState == androidx.media3.common.Player.STATE_IDLE) it.prepare() }; addTrack = null }, enabled = controller != null) { Text("加入播放队列") }
                    playlists.filter { it.serverId == null }.forEach { playlist -> TextButton({ library.addToPlaylist(playlist.id, track.uri); addTrack = null }) { Text(playlist.name) } }
                    if (playlists.none { it.serverId == null }) Text("请先在「歌单」分类中创建歌单。")
                    if (tab == LibraryTab.PLAYLISTS && group != null && playlists.firstOrNull { it.id.toString() == group }?.serverId == null) TextButton({ library.removeFromPlaylist(requireNotNull(group).toLong(), track.uri); addTrack = null }) { Text("从当前歌单移除") }
                }
            }, dismissButton = {}, confirmButton = { TextButton({ addTrack = null }) { Text("关闭") } })
        }
    }
}

}

private fun albumKey(track: Track): String = "${track.album}\u0000${track.artist}"

@Composable
fun ActionIcon(icon: ImageVector, label: String, tag: String, action: () -> Unit) {
    IconButton(action, Modifier.size(48.dp).testTag(tag)) { Icon(icon, label) }
}

@Composable
private fun GroupRow(title: String, subtitle: String, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick = action).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.LibraryMusic, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
    }
}

@Composable
private fun TrackRow(track: Track, current: Boolean, play: () -> Unit, add: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if (current) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
        .clickable(onClick = play).testTag("track_${track.uri}").padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(track.artworkUri, Modifier.size(54.dp))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text("${track.artist.ifBlank { "未知艺术家" }} · ${track.format.uppercase()} · ${if (track.format == "ncm") "来源：网易云" else if (track.uri.startsWith("lusound://")) "在线" else "本地"}", maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        ActionIcon(Icons.AutoMirrored.Rounded.PlaylistAdd, "添加到歌单", "add_${track.uri}", add)
    }
}

@Composable
fun Artwork(uri: String?, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(listOf(Color(0xFF526486), Color(0xFF202A44), Color(0xFF8A7190)))), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.MusicNote, null, Modifier.fillMaxSize(0.4f), tint = Color.White.copy(alpha = 0.6f))
        if (uri != null) AsyncImage(uri, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
    }
}
