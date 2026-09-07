package app.lusound.cloud

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

private val subsonicJson: Json = Json { ignoreUnknownKeys = true; explicitNulls = false }

/** A single configured Subsonic endpoint. Required response payloads are validated before persistence. */
class SubsonicClient(private val server: Server, private val password: String) {
    private val api = Retrofit.Builder().baseUrl(server.baseUrl)
        .client(baseHttpClient().newBuilder().addInterceptor { chain -> chain.proceed(authenticate(chain.request(), server, password)) }.build())
        .addConverterFactory(subsonicJson.asConverterFactory("application/json".toMediaType()))
        .build().create(SubsonicApi::class.java)

    suspend fun ping() { checked(api.ping(), "ping") }

    suspend fun readLibrary(): CloudSnapshot {
        ping()
        val songs = mutableListOf<RemoteSong>()
        val seenAlbums = mutableSetOf<String>()
        var offset = 0
        while (true) {
            val body = checked(api.albums("alphabeticalByName", 500, offset), "getAlbumList2 offset=$offset")
            val page = (body.albumList2 ?: throw SubsonicException("getAlbumList2 返回缺少 albumList2")).album.orEmpty()
            if (page.isEmpty()) break
            for (album in page) {
                if (!seenAlbums.add(album.id)) throw SubsonicException("getAlbumList2 分页返回重复专辑，已停止同步以保留原有音乐库")
                songs.addAll((checked(api.album(album.id), "getAlbum id=${album.id}").album
                    ?: throw SubsonicException("getAlbum 返回缺少 album")).song.orEmpty())
            }
            offset += page.size
        }
        val summaries = (checked(api.playlists(), "getPlaylists").playlists
            ?: throw SubsonicException("getPlaylists 返回缺少 playlists")).playlist.orEmpty()
        val playlists = summaries.map { summary ->
            checked(api.playlist(summary.id), "getPlaylist id=${summary.id}").playlist
                ?: throw SubsonicException("getPlaylist 返回缺少 playlist")
        }
        songs.addAll(playlists.flatMap { it.entry.orEmpty() })
        return CloudSnapshot(songs.distinctBy { it.id }, playlists)
    }

    private fun checked(response: Response<SubsonicResponse>, operation: String): SubsonicBody {
        if (!response.isSuccessful) {
            val body = response.errorBody()?.string().orEmpty().take(2048)
            throw SubsonicException("$operation 失败 HTTP=${response.code()} body=${redact(body)}")
        }
        val body = response.body()?.response ?: throw SubsonicException("$operation 返回空响应 HTTP=${response.code()}")
        if (body.status != "ok") {
            val error = body.error ?: throw SubsonicException("$operation 返回未知状态 ${body.status}")
            throw SubsonicException("$operation 失败 code=${error.code}：${redact(error.message)}")
        }
        return body
    }
    private fun redact(message: String): String = message.replace(password, "[redacted]")
        .replace(Regex("(?i)([?&](?:u|p|t|s)=)[^&\\s\"<]+"), "$1[redacted]")
}

data class CloudSnapshot(val songs: List<RemoteSong>, val playlists: List<RemotePlaylist>)
