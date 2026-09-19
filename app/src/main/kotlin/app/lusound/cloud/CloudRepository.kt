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
        require(draft.name.isNotBlank() && (draft.kind == "PLEX" || draft.username.isNotBlank()) && draft.password.isNotEmpty()) { "服务器名称和凭据不能为空；Subsonic / Jellyfin 还需要用户名" }
        val existing = database.servers().get(draft.id)
        val normalized = normalizeServerUrl(draft.baseUrl)
        if (existing != null && (existing.baseUrl != normalized || existing.username != draft.username.trim() || existing.kind != draft.kind)) {
            throw IllegalArgumentException("修改服务器地址或账号请新增连接；当前连接只允许更新名称和密码")
        }
        val server = Server(draft.id, draft.name.trim(), normalized, draft.username.trim(), vault.encrypt(draft.password), 0, null, draft.kind, null)
        when (draft.kind) {
            "PLEX" -> persist(server, PlexClient(server, draft.password).readLibrary())
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
            "PLEX" -> PlexClient(server, vault.decrypt(server.passwordCipher)).readLibrary()
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
            database.library().getTracks().filter { it.sourceKind == TrackSource.CLOUD && it.sourceRef == id }.map { it.uri }.chunked(500)
                .forEach { database.library().deleteTracks(it) }
            database.servers().delete(id)
        }
    }

    private suspend fun persist(server: Server, snapshot: CloudSnapshot) {
        val tracks = cloudTracksOf(server, snapshot.songs)
        database.withTransaction {
            database.servers().save(server.copy(lastSync = System.currentTimeMillis(), syncError = null))
            val currentUris = tracks.map { it.uri }.toSet()
            val removed = app.lusound.library.staleCloudTracks(database.library().getTracks(), server.id, currentUris)
            removed.chunked(500).forEach { database.library().deleteTracks(it) }
            database.library().upsertTracks(tracks)
            val previous = database.library().cloudPlaylists(server.id)
            removedCloudPlaylistIds(previous, snapshot.playlists.map { it.id }.toSet()).forEach { database.library().deletePlaylist(it) }
            snapshot.playlists.forEach { playlist ->
                val existing = existingCloudPlaylist(previous, playlist.id)
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
