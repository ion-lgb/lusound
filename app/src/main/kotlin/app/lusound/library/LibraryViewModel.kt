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
    val tracks = database.library().observeTracks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val playlists = database.library().observePlaylists().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val entries = database.library().observeEntries().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private val mutableScanning = MutableStateFlow(false)
    val scanning = mutableScanning.asStateFlow()
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
                }
            }
        }
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
