package app.lusound.cloud

import java.io.IOException
import java.util.UUID
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.api.client.extensions.*
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.*

data class JellyfinLogin(val token: String, val userId: String)

/** Uses the official SDK with the same retry and User-Agent policy as streaming. */
class JellyfinClient(private val server: Server, token: String?) {
    private val factory = OkHttpFactory(baseHttpClient())
    private val api = factory.create(server.baseUrl, token, ClientInfo("LuSound", "0.7.0"),
        DeviceInfo(server.id, "LuSound Android"), HttpClientOptions(followRedirects = false), factory)

    suspend fun login(password: String): JellyfinLogin = checked("Users/AuthenticateByName") {
        val result = api.userApi.authenticateUserByName(AuthenticateUserByName(username = server.username, pw = password)).content
        val accessToken = result.accessToken?.takeIf { it.isNotBlank() } ?: throw IOException("Jellyfin 登录响应缺少访问令牌")
        val userId = result.user?.id ?: throw IOException("Jellyfin 登录响应缺少用户 ID")
        JellyfinLogin(accessToken, userId.toString())
    }

    suspend fun readLibrary(): CloudSnapshot = checked("Items") {
        val user = UUID.fromString(requireNotNull(server.remoteUserId) { "Jellyfin 连接缺少用户 ID，请重新登录" })
        val songs = pages({ it.id.toString() }) { offset -> api.itemsApi.getItems(userId = user, recursive = true,
            includeItemTypes = listOf(BaseItemKind.AUDIO), fields = listOf(ItemFields.MEDIA_SOURCES),
            startIndex = offset, limit = 500, sortBy = listOf(ItemSortBy.SORT_NAME)).content }.map(::song)
        val playlists = pages({ it.id.toString() }) { offset -> api.itemsApi.getItems(userId = user, recursive = true,
            includeItemTypes = listOf(BaseItemKind.PLAYLIST), startIndex = offset, limit = 500).content }
            .filter { it.mediaType == MediaType.AUDIO }.map { playlist ->
                val entries = pages({ requireNotNull(it.playlistItemId) { "Jellyfin 歌单条目缺少独立 ID" } }) { offset -> api.playlistsApi.getPlaylistItems(playlistId = playlist.id, userId = user,
                    startIndex = offset, limit = 500, fields = listOf(ItemFields.MEDIA_SOURCES)).content }
                    .filter { it.type == BaseItemKind.AUDIO }.map(::song)
                RemotePlaylist(playlist.id.toString(), requireNotNull(playlist.name) { "Jellyfin 歌单缺少名称" }, entries)
            }
        CloudSnapshot((songs + playlists.flatMap { it.entry.orEmpty() }).distinctBy { it.id }, playlists)
    }
}

private suspend fun pages(identity: (BaseItemDto) -> String, fetch: suspend (Int) -> BaseItemDtoQueryResult): List<BaseItemDto> {
    val result = mutableListOf<BaseItemDto>()
    val seen = mutableSetOf<String>()
    var expected: Int? = null
    do {
        val page = fetch(result.size)
        val total = page.totalRecordCount
        if (expected != null && expected != total) throw IOException("Jellyfin 音乐库在分页同步期间发生变化，请重新同步")
        expected = total
        val items = page.items
        if (items.isEmpty() && result.size < total) throw IOException("Jellyfin 提前返回空页，请重新同步")
        if (items.any { !seen.add(identity(it)) }) throw IOException("Jellyfin 分页返回重复条目，请重新同步；上次成功数据已保留")
        result.addAll(items)
        if (result.size > total) throw IOException("Jellyfin 分页数量超过 TotalRecordCount")
    } while (result.size < requireNotNull(expected))
    return result
}

private fun song(item: BaseItemDto): RemoteSong {
    val cover = when {
        item.imageTags?.containsKey(ImageType.PRIMARY) == true -> item.id.toString()
        item.albumPrimaryImageTag != null -> item.albumId?.toString()
        else -> null
    }
    return RemoteSong(item.id.toString(), requireNotNull(item.name) { "Jellyfin 歌曲缺少名称" },
        item.artists?.joinToString(" / "), item.album, item.runTimeTicks?.div(10_000_000),
        item.container ?: item.mediaSources?.firstOrNull()?.container, cover)
}

private suspend fun <T> checked(endpoint: String, operation: suspend () -> T): T = try {
    operation()
} catch (error: InvalidStatusException) {
    throw IOException("Jellyfin $endpoint 返回 HTTP ${error.status}，请检查地址、账号权限或重新登录", error)
} catch (error: ApiClientException) {
    throw IOException("Jellyfin $endpoint 请求失败，请检查网络及服务器协议兼容性", error)
}
