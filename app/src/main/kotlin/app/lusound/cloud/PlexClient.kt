package app.lusound.cloud

import java.io.IOException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

private val plexJson: Json = Json { ignoreUnknownKeys = true; explicitNulls = false }

/** Uses the configured PMS only. Playback resolves a fresh Part key instead of storing expiring URLs. */
class PlexClient(private val server: Server, private val token: String) {
    private val api = Retrofit.Builder().baseUrl(server.baseUrl)
        .client(baseHttpClient().newBuilder().addInterceptor { chain -> chain.proceed(authenticatePlex(chain.request(), server, token)) }.build())
        .addConverterFactory(plexJson.asConverterFactory("application/json".toMediaType())).build().create(PlexApi::class.java)

    suspend fun readLibrary(): CloudSnapshot {
        val container = checked(api.sections(), "library/sections")
        val sections = container.directories.orEmpty()
        if (sections.size != container.size || sections.map { it.key }.distinct().size != sections.size) throw IOException("Plex 返回的音乐库目录不完整")
        val songs = sections.filter { it.type == "artist" }.flatMap { section ->
            pages({ it.ratingKey }) { offset -> checked(api.tracks(section.key, 10, offset, 500), "library/sections/${section.key}/all offset=$offset") }.map(::plexSong)
        }
        val playlists = pages({ it.ratingKey }) { offset -> checked(api.playlists("audio", offset, 500), "playlists offset=$offset") }
            .filter { it.playlistType == "audio" }.map { playlist ->
                val tracks = pages({ requireNotNull(it.playlistItemID) { "Plex 歌单条目缺少 playlistItemID" }.toString() }) { offset ->
                    checked(api.playlistItems(playlist.ratingKey, offset, 500), "playlists/${playlist.ratingKey}/items offset=$offset")
                }.map(::plexSong)
                RemotePlaylist(playlist.ratingKey, playlist.title, tracks)
            }
        return CloudSnapshot((songs + playlists.flatMap { it.entry.orEmpty() }).distinctBy { it.id }, playlists)
    }

    /** Called by the Media3 loading thread, never by the UI thread. */
    fun resolveStream(songId: String): String {
        val item = checked(api.metadata(songId).execute(), "library/metadata/$songId").items?.singleOrNull()
            ?: throw IOException("Plex 歌曲 $songId 返回的媒体详情不完整")
        if (item.ratingKey != songId || item.type != "track") throw IOException("Plex 返回的媒体 ID 或类型与歌曲不一致")
        val media = item.media?.firstOrNull() ?: throw IOException("Plex 歌曲 $songId 没有可播放音频")
        val part = media.parts.singleOrNull() ?: throw IOException("Plex 歌曲 $songId 不是单文件音频，当前不支持多段媒体")
        if (!part.key.startsWith("/library/parts/")) throw IOException("Plex 音频资源路径无效")
        return plexResourceUrl(server, part.key)
    }

    private fun checked(response: Response<PlexResponse>, endpoint: String): PlexContainer {
        if (!response.isSuccessful) {
            val body = response.errorBody()?.string().orEmpty().replace(token, "[redacted]").take(1024)
            throw IOException("Plex $endpoint 失败 HTTP=${response.code()}，请检查地址、Token 和音乐库权限。body=$body")
        }
        val body = response.body()?.container ?: throw IOException("Plex $endpoint 返回空响应 HTTP=${response.code()}")
        val total = paginationHeader(response, "X-Plex-Container-Total-Size")
        val offset = paginationHeader(response, "X-Plex-Container-Start")
        if ((total != null && body.totalSize != null && total != body.totalSize) || (offset != null && body.offset != null && offset != body.offset)) {
            throw IOException("Plex $endpoint 响应头与正文分页信息不一致")
        }
        return body.copy(totalSize = body.totalSize ?: total, offset = body.offset ?: offset)
    }
}

private suspend fun pages(identity: (PlexItem) -> String, fetch: suspend (Int) -> PlexContainer): List<PlexItem> {
    val items = mutableListOf<PlexItem>()
    val seen = mutableSetOf<String>()
    var total: Int? = null
    do {
        val page = fetch(items.size)
        val records = page.items.orEmpty()
        val expected = page.totalSize ?: throw IOException("Plex 未返回分页总条数，无法安全同步；请检查服务器分页支持")
        if (page.size != records.size || expected < 0 || (page.offset != null && page.offset != items.size) || (total != null && total != expected)) {
            throw IOException("Plex 分页数量或偏移不一致，请重新同步；旧数据已保留")
        }
        total = expected
        if (records.any { identity(it).isBlank() || !seen.add(identity(it)) }) throw IOException("Plex 分页返回重复条目，请重新同步；旧数据已保留")
        if (records.isEmpty() && items.size < expected) throw IOException("Plex 分页提前结束，旧数据已保留")
        items.addAll(records)
        if (items.size > expected) throw IOException("Plex 分页超过总条数，旧数据已保留")
    } while (items.size < requireNotNull(total))
    return items
}

private fun plexSong(item: PlexItem): RemoteSong {
    if (item.type != "track" || item.ratingKey.isBlank()) throw IOException("Plex 音乐库返回非歌曲条目或空 ID")
    return RemoteSong(item.ratingKey, item.title, item.grandparentTitle, item.parentTitle, item.duration?.div(1000),
        item.media?.firstOrNull()?.container, item.thumb ?: item.parentThumb)
}

private fun paginationHeader(response: Response<PlexResponse>, name: String): Int? {
    val value = response.headers()[name] ?: return null
    return value.toIntOrNull()?.takeIf { it >= 0 } ?: throw IOException("Plex 分页响应头 $name 无效")
}
