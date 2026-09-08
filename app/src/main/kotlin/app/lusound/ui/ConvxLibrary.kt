/** Convx Library/Items/ScreenTitleBar/ChipsRow adapters, GPL-3.0. Domain objects stay in LuSound. */
@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
package app.lusound.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lusound.library.*
import com.convx.music.ui.component.floatingtabbar.FloatingTabBarScrollConnection
import com.convx.music.ui.utils.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

private data class LibraryPage(val tab: LibraryTab, val group: String?)
private data class LibraryGroup(val key: String, val title: String, val subtitle: String, val artwork: String?)

fun albumKey(track: Track): String = "${track.album}\u0000${track.artist}"

private fun pageTracks(tracks: List<Track>, entries: List<PlaylistEntry>, query: String, page: LibraryPage): List<Track> {
    val filtered = tracks.filter { track ->
        (query.isBlank() || listOf(track.title, track.artist, track.album).any { it.contains(query, true) }) && when (page.tab) {
            LibraryTab.SONGS -> true
            LibraryTab.ALBUMS -> page.group == null || albumKey(track) == page.group
            LibraryTab.ARTISTS -> page.group == null || track.artist == page.group
            LibraryTab.FOLDERS -> page.group == null || track.folder == page.group
            LibraryTab.PLAYLISTS -> entries.any { it.playlistId.toString() == page.group && it.trackUri == track.uri }
        }
    }
    if (page.tab != LibraryTab.PLAYLISTS) return filtered
    val indexed = filtered.associateBy { it.uri }
    return entries.filter { it.playlistId.toString() == page.group }.sortedBy { it.position }.mapNotNull { indexed[it.trackUri] }
}

@Composable
fun LibraryContent(tracks: List<Track>, playlists: List<Playlist>, entries: List<PlaylistEntry>, tab: LibraryTab, group: String?,
    query: String, search: (String) -> Unit, select: (LibraryTab, String?) -> Unit, scanning: Boolean, hasPermission: Boolean,
    requestPermission: () -> Unit, importFiles: () -> Unit, refresh: () -> Unit, createPlaylist: () -> Unit,
    play: (List<Track>, Int) -> Unit, add: (Track) -> Unit, currentId: String?, playing: Boolean,
    settings: () -> Unit, scroll: FloatingTabBarScrollConnection, searchRequest: Int, searchHandled: () -> Unit) {
    val pageState = rememberSaveableStateHolder()
    SharedTransitionLayout {
        AnimatedContent(LibraryPage(tab, group), Modifier.fillMaxSize(), transitionSpec = {
            if (initialState.group != null && targetState.group == null) {
                (slideInHorizontally(Motion.push()) { Motion.parallaxOffset(it) } + fadeIn(Motion.push())) togetherWith
                    (slideOutHorizontally(Motion.push()) { it } + fadeOut(Motion.push()))
            } else if (targetState.group != null) {
                (slideInHorizontally(Motion.push()) { it } + fadeIn(Motion.push())) togetherWith
                    (slideOutHorizontally(Motion.push()) { Motion.parallaxOffset(it) } + fadeOut(Motion.push()))
            } else fadeIn(tween(200)) togetherWith fadeOut(tween(160))
        }, label = "library_page") { page ->
            pageState.SaveableStateProvider(if (page.group == null) page.tab.name else "${page.tab.name}:${page.group}") {
                val visible = remember(tracks, entries, query, page) { pageTracks(tracks, entries, query, page) }
                val list = rememberLazyListState()
                val zoom = rememberHeroZoom(220.dp)
                val focus = remember { FocusRequester() }
                LaunchedEffect(searchRequest) {
                    if (searchRequest > 0 && page == LibraryPage(tab, group)) {
                        list.scrollToItem(if (page.group == null) 1 else 2)
                        withFrameNanos { }
                        focus.requestFocus()
                        searchHandled()
                    }
                }
                val threshold = with(LocalDensity.current) { 100.dp.toPx() }
                val collapsed by remember { derivedStateOf { if (list.firstVisibleItemIndex > 0) 1f else (list.firstVisibleItemScrollOffset / threshold).coerceIn(0f, 1f) } }
                val title = if (page.group == null) "资料库" else when (page.tab) {
                    LibraryTab.PLAYLISTS -> playlists.firstOrNull { it.id.toString() == page.group }?.name.orEmpty()
                    LibraryTab.ALBUMS -> visible.firstOrNull()?.album.orEmpty()
                    else -> page.group.substringAfterLast('/')
                }
                Box(Modifier.fillMaxSize().nestedScroll(scroll)) {
                    LazyColumn(state = list, overscrollEffect = zoom.listOverscroll(), modifier = Modifier.fillMaxSize().heroPullZoom(zoom).testTag("library_list"), contentPadding = PaddingValues(bottom = 200.dp)) {
                        item(key = "title") {
                            Column(Modifier.statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (page.group != null) ActionIcon(Icons.AutoMirrored.Rounded.ArrowBack, "返回分类", "group_back", { select(page.tab, null) })
                                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    ActionIcon(Icons.Rounded.Settings, "设置", "settings", settings)
                                }
                                Text(if (scanning) "正在发现你的音乐…" else "${visible.size} 首歌曲", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (page.group != null) item(key = "hero") {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 44.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Artwork(visible.firstOrNull()?.artworkUri, Modifier.widthIn(max = 280.dp).fillMaxWidth().aspectRatio(1f).testTag("group_hero_art")
                                    .graphicsLayer { scaleX = zoom.scale; scaleY = zoom.scale }
                                    .sharedElement(rememberSharedContentState("group_${page.tab}_${page.group}"), this@AnimatedContent)
                                    .then(if (page.tab == LibraryTab.ARTISTS) Modifier.clip(CircleShape) else Modifier))
                                Spacer(Modifier.height(16.dp))
                                Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(visible.firstOrNull()?.artist.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Button({ if (visible.isNotEmpty()) play(visible, 0) }, enabled = visible.isNotEmpty(), modifier = Modifier.padding(top = 12.dp).testTag("play_group")) {
                                    Icon(Icons.Rounded.PlayArrow, null); Text("播放")
                                }
                            }
                        }
                        item(key = "search") {
                            OutlinedTextField(query, search, Modifier.fillMaxWidth().padding(horizontal = 20.dp).focusRequester(focus).testTag("search"),
                                placeholder = { Text("搜索歌曲、艺术家、专辑") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                                trailingIcon = { if (query.isNotEmpty()) IconButton({ search("") }, Modifier.testTag("clear_search")) { Icon(Icons.Rounded.Close, "清除搜索") } },
                                singleLine = true, shape = RoundedCornerShape(24.dp), colors = musicFieldColors())
                        }
                        item(key = "categories") {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (category in LibraryTab.entries) {
                                    val selected = category == page.tab
                                    val radius by animateDpAsState(if (selected) 20.dp else 8.dp,
                                        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), label = "chip_corner")
                                    FilterChip(selected, { select(category, null) }, { Text(category.label) },
                                        Modifier.animateContentSize().testTag("tab_${category.name}"), shape = RoundedCornerShape(radius), border = null,
                                        leadingIcon = if (selected) ({ Icon(Icons.Rounded.Done, null, Modifier.size(18.dp)) }) else null)
                                }
                            }
                        }
                        if (!hasPermission) item(key = "permission") {
                            Column(Modifier.padding(20.dp)) {
                                Text("让音乐回到你身边", style = MaterialTheme.typography.headlineSmall)
                                Text("允许读取共享存储中的音乐，或选择文件导入。")
                                Button(requestPermission, Modifier.testTag("grant_audio")) { Text("允许读取音乐") }
                                TextButton(importFiles, Modifier.testTag("import_audio")) { Text("导入文件") }
                            }
                        }
                        item(key = "section") {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(page.tab.label, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                                if (page.tab == LibraryTab.PLAYLISTS && page.group == null) ActionIcon(Icons.Rounded.Add, "新建歌单", "create_playlist", createPlaylist)
                                if (hasPermission) ActionIcon(Icons.Rounded.Refresh, "刷新音乐库", "refresh", refresh)
                            }
                        }
                        if (page.tab != LibraryTab.SONGS && page.group == null) {
                            val groups: List<LibraryGroup> = if (page.tab == LibraryTab.PLAYLISTS) playlists.map { playlist ->
                                val uris = entries.filter { it.playlistId == playlist.id }.map { it.trackUri }
                                LibraryGroup(playlist.id.toString(), playlist.name, "${uris.size} 首${if (playlist.serverId != null) " · 只读" else ""}", tracks.firstOrNull { it.uri in uris }?.artworkUri)
                            } else visible.groupBy { when (page.tab) { LibraryTab.ALBUMS -> albumKey(it); LibraryTab.ARTISTS -> it.artist; else -> it.folder } }.toSortedMap().map { (key, songs) ->
                                LibraryGroup(key, if (page.tab == LibraryTab.ALBUMS) songs.first().album.ifBlank { "未知专辑" } else key.substringAfterLast('/').ifBlank { "未知" }, "${songs.size} 首", songs.first().artworkUri)
                            }
                            if (page.tab == LibraryTab.FOLDERS) items(groups, key = { it.key }) { item ->
                                ListItem(headlineContent = { Text(item.title) }, supportingContent = { Text(item.subtitle) }, leadingContent = { Icon(Icons.Rounded.Folder, null) },
                                    modifier = Modifier.animateItem().clickable { select(page.tab, item.key) }.testTag("group_${page.tab}_${item.key}"))
                            } else items(groups.chunked(2), key = { it.first().key }) { row ->
                                Row(Modifier.fillMaxWidth().animateItem().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    for (item in row) Column(Modifier.weight(1f).clickable { select(page.tab, item.key) }.testTag("group_${page.tab}_${item.key}")) {
                                        Artwork(item.artwork, Modifier.fillMaxWidth().aspectRatio(1f).testTag("group_art_${page.tab}_${item.key}")
                                            .sharedElement(rememberSharedContentState("group_${page.tab}_${item.key}"), this@AnimatedContent)
                                            .then(if (page.tab == LibraryTab.ARTISTS) Modifier.clip(CircleShape) else Modifier))
                                        Text(item.title, Modifier.padding(top = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                        Text(item.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (row.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                            if (groups.isEmpty()) item { Text("暂无内容，可导入音乐或创建歌单。", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        } else {
                            itemsIndexed(visible, key = { index, track -> "${track.uri}:$index" }) { index, track ->
                                Column(Modifier.animateItem()) {
                                    TrackRow(track, currentId == track.uri, playing && currentId == track.uri, { play(visible, index) }, { add(track) })
                                    HorizontalDivider(Modifier.padding(start = 80.dp, end = 20.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                                }
                            }
                            if (visible.isEmpty() && !scanning) item { Text("这里还没有歌曲\n将音频放入 Music 文件夹，或在设置中导入。", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                    if (collapsed > 0f) Row(Modifier.fillMaxWidth().graphicsLayer { alpha = collapsed }.background(MaterialTheme.colorScheme.background.copy(alpha = 0.96f)).statusBarsPadding().height(52.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
