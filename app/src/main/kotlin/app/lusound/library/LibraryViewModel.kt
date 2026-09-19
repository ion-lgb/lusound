package app.lusound.library

import androidx.room.withTransaction

import android.app.Application
import android.content.Intent
import android.database.ContentObserver
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.core.net.toUri
import java.io.IOException
import android.provider.DocumentsContract
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import app.lusound.metadata.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Android lifecycle adapter around the media provider and Room. */
class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val database = (application as app.lusound.LuSoundApplication).database
    val metadata = database.metadata().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    /**
     * The library as the user sees it: a row that another row already represents is left out, so the
     * same file imported through two sources is listed once.
     *
     * Nothing is deleted here. See [shadowedUris]: a hidden row keeps its playlists, its cached lyrics
     * and its ability to reappear if the row being shown goes away.
     */
    val tracks = combine(database.library().observeTracks(), database.metadata().observeAll()) { tracks, extras ->
        val byUri = extras.associateBy { it.uri }
        val shadowed = shadowedUris(tracks)
        tracks.filterNot { it.uri in shadowed }.map { track ->
            byUri[track.uri]?.takeIf { it.fingerprint == metadataFingerprint(track) }?.coverUri?.let { track.copy(artworkUri = it) } ?: track
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun loadMetadata(track: Track) { viewModelScope.launch { runLibraryOperation { (getApplication<Application>() as app.lusound.LuSoundApplication).metadata.local(track) } } }
    fun retryMetadata(uri: String) { viewModelScope.launch { database.metadata().invalidate(uri); scheduleMetadata(getApplication()) } }
    val playlists = database.library().observePlaylists().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    /**
     * Playlist entries with any hidden row rewritten to the row that stands in for it, so a song stays
     * in the playlist it was added to even while its duplicate is hidden from the library listing.
     * Without this, hiding a row would make it vanish from every playlist that referenced it.
     */
    val entries = combine(database.library().observeEntries(), database.library().observeTracks()) { entries, tracks ->
        val redirect = representativeByUri(tracks)
        if (redirect.isEmpty()) entries else entries.map { entry -> redirect[entry.trackUri]?.let { entry.copy(trackUri = it) } ?: entry }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()

    /**
     * How many stored rows are currently hidden because another row is the same file.
     *
     * Shown so the user knows the library is not losing anything silently, and so cleaning the rows up
     * is their decision rather than something the app does behind them.
     */
    val duplicateCount = database.library().observeTracks()
        .map { shadowedUris(it).size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    private val mutableScanning = MutableStateFlow(false)
    val scanning = mutableScanning.asStateFlow()
    private val folderMutex = Mutex()
    private val mutableFolders = MutableStateFlow<List<String>>(emptyList())
    val folders = mutableFolders.asStateFlow()
    private val mutableFolderScanning = MutableStateFlow(false)
    val folderScanning = mutableFolderScanning.asStateFlow()
    private val mutableFolderStatus = MutableStateFlow<String?>(null)
    val folderStatus = mutableFolderStatus.asStateFlow()
    init {
        viewModelScope.launch { runLibraryOperation { reloadFolders() } }
        viewModelScope.launch {
            database.library().observeTracks().map { tracks -> tracks.map(::metadataFingerprint) }.distinctUntilChanged()
                .collect { scheduleMetadata(application) }
        }
    }
    private var rescan: Job? = null
    private var observing = false
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { refresh() }
    }

    fun startObserving() {
        if (!observing) {
            getApplication<Application>().contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
            observing = true
        }
        refresh()
    }

    fun refresh() {
        rescan?.cancel()
        rescan = viewModelScope.launch {
            delay(250)
            mutableScanning.value = true
            try {
                runLibraryOperation {
                    val scanned = scanMediaStore(getApplication())
                    replaceMediaLibrary(database, scanned, getApplication())
                    scheduleMetadata(getApplication())
                }
            } finally { mutableScanning.value = false }
        }
    }

    fun importDocuments(uris: List<Uri>) {
        viewModelScope.launch {
            uris.forEach { uri ->
                runLibraryOperation {
                    getApplication<Application>().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    database.library().upsertTracks(listOf(readAudioDocument(getApplication(), uri)))
                    scheduleMetadata(getApplication())
                }
            }
        }
    }

    fun importFolder(uri: Uri) {
        viewModelScope.launch { folderMutex.withLock {
            runLibraryOperation {
                require(DocumentsContract.isTreeUri(uri)) { "请选择目录" }
                getApplication<Application>().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                reloadFolders()
                scanFolder(uri)
            }
        } }
    }

    fun rescanFolder(value: String) {
        viewModelScope.launch { folderMutex.withLock { runLibraryOperation { scanFolder(value.toUri()) } } }
    }

    fun removeFolder(value: String, removed: () -> Unit) {
        viewModelScope.launch { folderMutex.withLock { runLibraryOperation {
            val resolver = getApplication<Application>().contentResolver
            val uri = value.toUri()
            if (resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }) {
                resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            database.withTransaction {
                database.library().getTracks().filter { it.sourceKind == TrackSource.DOCUMENT_TREE && it.sourceRef == value }.map { it.uri }
                    .chunked(500).forEach { database.library().deleteTracks(it) }
            }
            reloadFolders()
            mutableFolderStatus.value = "已移除目录映射，原文件未删除"
            removed()
        } } }
    }

    private suspend fun scanFolder(uri: Uri) {
        mutableFolderScanning.value = true
        mutableFolderStatus.value = null
        try {
            val snapshot = scanDocumentTree(getApplication(), uri)
            replaceDocumentTree(database, snapshot)
            scheduleMetadata(getApplication())
            mutableFolderStatus.value = "已扫描 ${snapshot.tracks.size} 首音频；不支持的加密文件 ${snapshot.unsupportedFiles.size} 个"
            reloadFolders()
        } finally { mutableFolderScanning.value = false }
    }

    private suspend fun reloadFolders() {
        val granted = getApplication<Application>().contentResolver.persistedUriPermissions
            .filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }.map { it.uri.toString() }
        val stored = database.library().getTracks().filter { it.sourceKind == TrackSource.DOCUMENT_TREE }.mapNotNull { it.sourceRef }
        mutableFolders.value = (granted + stored).distinct().sorted()
    }

    /**
     * Deletes the rows hidden behind a duplicate, only when the user asks for it.
     *
     * Playlist entries are repointed at the row that represents them first, inside the same
     * transaction, because deleting a track cascades into its playlist entries: without the repoint,
     * this would remove the song from every playlist it appears in.
     */
    fun removeDuplicateRows() {
        viewModelScope.launch {
            runLibraryOperation {
                var cleaned = 0
                database.withTransaction {
                    val redirect = representativeByUri(database.library().getTracks())
                    cleaned = redirect.size
                    redirect.forEach { (hidden, keeper) -> database.library().retargetEntries(hidden, keeper) }
                    redirect.keys.chunked(500).forEach { database.library().deleteTracks(it) }
                }
                mutableFolderStatus.value = "已清理 $cleaned 条重复记录"
            }
        }
    }

    fun createPlaylist(name: String) {        if (name.isBlank()) { reportError("歌单名称不能为空"); return }
        viewModelScope.launch { runLibraryOperation { database.library().insertPlaylist(Playlist(0, name.trim(), null, null)) } }
    }
    /** Renames a local playlist; a cloud playlist is owned by its server and stays read-only. */
    fun renamePlaylist(id: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) { reportError("歌单名称不能为空"); return }
        viewModelScope.launch { runLibraryOperation { database.withTransaction {
            val playlist = requireNotNull(database.library().getPlaylist(id)) { "歌单不存在" }
            require(playlist.serverId == null) { "服务器歌单只读" }
            database.library().savePlaylist(playlist.copy(name = trimmed))
        } } }
    }
    fun deletePlaylist(id: Long) { viewModelScope.launch { runLibraryOperation { database.library().deletePlaylist(id) } } }
    fun addToPlaylist(id: Long, uri: String) { viewModelScope.launch { runLibraryOperation { database.withTransaction {
        require(database.library().getPlaylist(id)?.serverId == null) { "服务器歌单只读" }
        if (uri !in database.library().playlistTrackUris(id)) database.library().insertEntry(PlaylistEntry(id, uri, database.library().nextPosition(id)))
    } } } }
    /** Moves a song inside a local playlist; [to] is where the entry at [from] should end up. */
    fun moveInPlaylist(id: Long, from: Int, to: Int) { viewModelScope.launch { runLibraryOperation {
        require(database.library().getPlaylist(id)?.serverId == null) { "服务器歌单只读" }
        reorderPlaylistEntry(database, id, from, to)
    } } }
    fun removeFromPlaylist(id: Long, uri: String) { viewModelScope.launch { runLibraryOperation { require(database.library().getPlaylist(id)?.serverId == null) { "服务器歌单只读" }; database.library().removeEntry(id, uri) } } }
    fun clearError() { mutableError.value = null }
    fun reportError(message: String) { mutableError.value = message }

    private suspend fun runLibraryOperation(action: suspend () -> Unit) {
        try { action() }
        catch (error: SecurityException) { reportError("读取权限失效，请重新授权：${error.message}") }
        catch (error: IOException) { reportError("音乐库读取失败：${error.message}") }
        catch (error: IllegalArgumentException) { reportError("媒体文件或 URI 无效：${error.message}") }
        catch (error: UnsupportedOperationException) { reportError(error.message ?: "文件提供程序不支持此次操作") }
        catch (error: SQLiteException) { reportError("音乐库保存失败：${error.message}") }
    }
    override fun onCleared() {
        if (observing) getApplication<Application>().contentResolver.unregisterContentObserver(observer)

    }
}
