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
    val tracks = combine(database.library().observeTracks(), database.metadata().observeAll()) { tracks, extras ->
        val byUri = extras.associateBy { it.uri }
        tracks.map { track -> byUri[track.uri]?.takeIf { it.fingerprint == metadataFingerprint(track) }?.coverUri?.let { track.copy(artworkUri = it) } ?: track }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun loadMetadata(track: Track) { viewModelScope.launch { runLibraryOperation { (getApplication<Application>() as app.lusound.LuSoundApplication).metadata.local(track) } } }
    fun retryMetadata(uri: String) { viewModelScope.launch { database.metadata().invalidate(uri); scheduleMetadata(getApplication()) } }
    val playlists = database.library().observePlaylists().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val entries = database.library().observeEntries().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
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
        viewModelScope.launch { folderMutex.withLock { runLibraryOperation { scanFolder(Uri.parse(value)) } } }
    }

    fun removeFolder(value: String, removed: () -> Unit) {
        viewModelScope.launch { folderMutex.withLock { runLibraryOperation {
            val resolver = getApplication<Application>().contentResolver
            val uri = Uri.parse(value)
            if (resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }) {
                resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            database.withTransaction {
                database.library().getTracks().filter { it.origin == "DOCUMENT_TREE:$value" }.map { it.uri }
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
        val stored = database.library().getTracks().map { it.origin }.filter { it.startsWith("DOCUMENT_TREE:") }.map { it.removePrefix("DOCUMENT_TREE:") }
        mutableFolders.value = (granted + stored).distinct().sorted()
    }

    fun createPlaylist(name: String) {
        if (name.isBlank()) { reportError("歌单名称不能为空"); return }
        viewModelScope.launch { runLibraryOperation { database.library().insertPlaylist(Playlist(0, name.trim(), null, null)) } }
    }
    fun deletePlaylist(id: Long) { viewModelScope.launch { runLibraryOperation { database.library().deletePlaylist(id) } } }
    fun addToPlaylist(id: Long, uri: String) { viewModelScope.launch { runLibraryOperation { database.withTransaction {
        require(database.library().getPlaylist(id)?.serverId == null) { "服务器歌单只读" }
        if (uri !in database.library().playlistTrackUris(id)) database.library().insertEntry(PlaylistEntry(id, uri, database.library().nextPosition(id)))
    } } } }
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
