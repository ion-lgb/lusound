package app.lusound.cloud

import android.net.Uri
import androidx.room.withTransaction
import app.lusound.library.*
import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes cloud mutations so a late sync cannot restore a removed server or old credentials. */
class CloudRepository(private val database: LibraryDatabase, private val vault: CredentialVault) {
    private val mutations = Mutex()

    suspend fun save(draft: ServerDraft) = mutations.withLock {
        require(draft.name.isNotBlank() && draft.username.isNotBlank() && draft.password.isNotEmpty()) { "服务器名称、用户名和密码不能为空" }
        val existing = database.servers().get(draft.id)
        val normalized = normalizeServerUrl(draft.baseUrl)
        if (existing != null && (existing.baseUrl != normalized || existing.username != draft.username.trim() || existing.kind != draft.kind)) {
            throw IllegalArgumentException("修改服务器地址或账号请新增连接；当前连接只允许更新名称和密码")
        }
        val server = Server(draft.id, draft.name.trim(), normalized, draft.username.trim(), vault.encrypt(draft.password), 0, null, draft.kind, null)
        when (draft.kind) {
            "SUBSONIC" -> persist(server, SubsonicClient(server, draft.password).readLibrary())
            "JELLYFIN" -> {
                val client = JellyfinClient(server, null)
                val login = client.login(draft.password)
                val connected = server.copy(passwordCipher = vault.encrypt(login.token), remoteUserId = login.userId)
                persist(connected, JellyfinClient(connected, login.token).readLibrary())
            }
            else -> throw IllegalArgumentException("不支持的服务器协议：${draft.kind}")
        }
    }

    suspend fun sync(id: String) = mutations.withLock {
        val server = database.servers().get(id) ?: throw SubsonicException("服务器已移除")
        try { persist(server, when (server.kind) {
            "SUBSONIC" -> SubsonicClient(server, vault.decrypt(server.passwordCipher)).readLibrary()
            "JELLYFIN" -> JellyfinClient(server, vault.decrypt(server.passwordCipher)).readLibrary()
            else -> throw IllegalArgumentException("不支持的服务器协议：${server.kind}")
        }) }
        catch (error: IOException) {
            database.servers().recordError(id, error.message ?: "服务器同步读取失败")
            throw error
        }
    }

    suspend fun remove(id: String) = mutations.withLock {
        database.withTransaction {
            val server = database.servers().get(id) ?: throw IOException("服务器已移除")
            database.library().cloudPlaylists(id).forEach { database.library().deletePlaylist(it.id) }
            database.library().getTracks().filter { it.origin == "${server.kind}:$id" }.map { it.uri }.chunked(500)
                .forEach { database.library().deleteTracks(it) }
            database.servers().delete(id)
        }
    }

    private suspend fun persist(server: Server, snapshot: CloudSnapshot) {
        val tracks = snapshot.songs.map { song ->
            require(song.id.isNotBlank()) { "服务器返回空歌曲 ID" }
            Track(cloudTrackUri(server.id, song.id), song.title, song.artist.orEmpty(), song.album.orEmpty(),
                "在线音乐", (song.duration ?: 0) * 1000, song.coverArt?.let { coverUrl(server, it) },
                song.suffix.orEmpty(), "${server.kind}:${server.id}")
        }
        database.withTransaction {
            database.servers().save(server.copy(lastSync = System.currentTimeMillis(), syncError = null))
            val currentUris = tracks.map { it.uri }.toSet()
            val removed = database.library().getTracks().filter { it.origin == "${server.kind}:${server.id}" && it.uri !in currentUris }.map { it.uri }
            removed.chunked(500).forEach { database.library().deleteTracks(it) }
            database.library().upsertTracks(tracks)
            val previous = database.library().cloudPlaylists(server.id)
            previous.filter { playlist -> snapshot.playlists.none { it.id == playlist.remoteId } }.forEach { database.library().deletePlaylist(it.id) }
            snapshot.playlists.forEach { playlist ->
                val existing = previous.firstOrNull { it.remoteId == playlist.id }
                val id = if (existing == null) database.library().insertPlaylist(Playlist(0, playlist.name, server.id, playlist.id)) else {
                    database.library().savePlaylist(existing.copy(name = playlist.name)); existing.id
                }
                database.library().clearPlaylist(id)
                playlist.entry.orEmpty().forEachIndexed { index, song -> database.library().insertEntry(PlaylistEntry(id, cloudTrackUri(server.id, song.id), index)) }
            }
        }
    }
}

fun cloudTrackUri(serverId: String, songId: String): String = Uri.Builder().scheme("lusound").authority(serverId).appendPath(songId).build().toString()
