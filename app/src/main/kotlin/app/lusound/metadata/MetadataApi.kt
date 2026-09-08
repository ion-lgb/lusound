package app.lusound.metadata

import com.github.houbb.opencc4j.util.ZhConverterUtil
import app.lusound.cloud.baseHttpClient
import app.lusound.library.Track
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import kotlin.math.abs

@Serializable
data class LrcRecord(val id: Long, val trackName: String, val artistName: String, val albumName: String,
    val duration: Double, val instrumental: Boolean, val plainLyrics: String?, val syncedLyrics: String?)
@Serializable
data class ReleaseSearch(val releases: List<ReleaseRecord>)
@Serializable
data class ReleaseRecord(val id: String, val title: String, @SerialName("artist-credit") val artists: List<ArtistCredit>)
@Serializable
data class ArtistCredit(val name: String)
@Serializable
data class CoverResponse(val images: List<CoverImage>)
@Serializable
data class CoverImage(val front: Boolean, val thumbnails: Map<String, String>)

data class RemoteLyrics(val text: String, val sourceUrl: String)
data class RemoteCover(val bytes: ByteArray, val sourceUrl: String)

interface LyricsApi {
    @GET("api/search") suspend fun search(@Query("track_name") title: String): retrofit2.Response<List<LrcRecord>>
}
interface MusicBrainzApi {
    @GET("ws/2/release/") suspend fun search(@Query("query") query: String, @Query("fmt") format: String,
        @Query("limit") limit: Int): retrofit2.Response<ReleaseSearch>
}
interface CoverArchiveApi {
    @GET("release/{id}") suspend fun cover(@Path("id") releaseId: String): retrofit2.Response<CoverResponse>
}

/** Public metadata traffic never uses the authenticated server client. Each attempt is rate limited. */
class MetadataClient {
    private val http = baseHttpClient().newBuilder().followRedirects(true).followSslRedirects(true)
        .addInterceptor(MetadataRateLimit()).build()
    private val json = Json { ignoreUnknownKeys = true }
    private fun retrofit(base: String): Retrofit = Retrofit.Builder().baseUrl(base).client(http)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build()
    private val lyrics = retrofit("https://lrclib.net/").create(LyricsApi::class.java)
    private val music = retrofit("https://musicbrainz.org/").create(MusicBrainzApi::class.java)
    private val covers = retrofit("https://coverartarchive.org/").create(CoverArchiveApi::class.java)

    suspend fun findLyrics(track: Track): RemoteLyrics? {
        val title = ZhConverterUtil.toSimple(track.title).trim()
        val response = try { lyrics.search(title) }
        catch (error: SerializationException) { throw IOException("LRCLIB 响应格式无效：${error.message}", error) }
        if (!response.isSuccessful) throw responseError("LRCLIB title=$title", response)
        val candidates = response.body() ?: throw IOException("LRCLIB 未返回搜索结果正文")
        val match = matchLyrics(track, candidates) ?: return null
        val text = if (match.instrumental) "" else usableLyrics(match.syncedLyrics) ?: usableLyrics(match.plainLyrics) ?: return null
        return RemoteLyrics(text, "https://lrclib.net/api/get/${match.id}")
    }

    suspend fun findCover(track: Track): RemoteCover? {
        if (track.album.isBlank() || track.artist.isBlank()) return null
        val album = ZhConverterUtil.toTraditional(track.album)
        val artist = ZhConverterUtil.toTraditional(primaryArtist(track.artist))
        val query = "release:\"${escapeQuery(album)}\" AND artist:\"${escapeQuery(artist)}\""
        val response = try { music.search(query, "json", 5) }
        catch (error: SerializationException) { throw IOException("MusicBrainz 响应格式无效：${error.message}", error) }
        if (!response.isSuccessful) throw responseError("MusicBrainz query=$query", response)
        val records = response.body()?.releases ?: throw IOException("MusicBrainz 未返回专辑列表")
        for (release in records.filter { canonicalText(it.title) == canonicalText(track.album) && it.artists.any { credit -> artistMatches(track.artist, credit.name) } }) {
            if (!Regex("[a-f0-9-]{36}").matches(release.id)) throw IOException("MusicBrainz 专辑 ID 无效")
            val art = try { covers.cover(release.id) }
            catch (error: SerializationException) { throw IOException("Cover Art Archive 响应格式无效：${error.message}", error) }
            if (art.code() == 404) continue // An indexed release may legitimately have no artwork.
            if (!art.isSuccessful) throw responseError("Cover Art Archive release=${release.id}", art)
            val url = art.body()?.images?.firstOrNull { it.front }?.thumbnails?.get("500") ?: continue
            val parsed = url.toHttpUrlOrNull()
                ?: throw IOException("Cover Art Archive 返回无效图片地址")
            if (parsed.scheme != "https" || parsed.host !in setOf("coverartarchive.org", "archive.org") && !parsed.host.endsWith(".archive.org")) {
                throw IOException("Cover Art Archive 图片地址不属于受支持的 HTTPS 图片服务")
            }
            val bytes = withContext(Dispatchers.IO) {
                http.newCall(Request.Builder().url(parsed).build()).execute().use { image ->
                    if (!image.isSuccessful) throw IOException("下载封面失败：HTTP ${image.code}，专辑=${release.id}")
                    if (image.body.contentType()?.type != "image") throw IOException("封面返回的 Content-Type 不是图片")
                    val source = image.body.source()
                    source.request(5 * 1024 * 1024L + 1)
                    if (source.buffer.size > 5 * 1024 * 1024) throw IOException("远端封面超过 5 MB 限制")
                    val content = source.readByteArray()
                    if (content.size > 5 * 1024 * 1024) throw IOException("远端封面超过 5 MB 限制")
                    content
                }
            }
            return RemoteCover(bytes, "https://musicbrainz.org/release/${release.id}/cover-art")
        }
        return null
    }
}

private class MetadataRateLimit : Interceptor {
    private var lastRequest: Long = 0
    override fun intercept(chain: Interceptor.Chain): Response {
        synchronized(this) {
            val wait = 1100 - (android.os.SystemClock.elapsedRealtime() - lastRequest)
            if (wait > 0) try { Thread.sleep(wait) }
            catch (error: InterruptedException) { Thread.currentThread().interrupt(); throw IOException("元数据请求已中断", error) }
            lastRequest = android.os.SystemClock.elapsedRealtime()
        }
        return chain.proceed(chain.request())
    }
}

fun canonicalText(value: String): String = ZhConverterUtil.toSimple(java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC))
    .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

private fun primaryArtist(value: String): String = value.split(Regex("[/,&、;]"))[0].trim()
private fun artistMatches(local: String, remote: String): Boolean {
    val artist = canonicalText(primaryArtist(local))
    val credits = remote.split(Regex("(?i)\\b(?:feat\\.?|ft\\.?|featuring|with)\\s+|[/,&、;()_]"))
    return artist.isNotEmpty() && credits.any { canonicalText(it) == artist }
}

fun matchLyrics(track: Track, candidates: List<LrcRecord>): LrcRecord? = candidates
    .filter { it.duration.isFinite() && it.duration > 0 && abs(it.duration * 1000 - track.durationMs) <= 3000 &&
        canonicalText(it.trackName) == canonicalText(track.title) && artistMatches(track.artist, it.artistName) }
    .sortedWith(compareByDescending<LrcRecord> { canonicalText(it.albumName) == canonicalText(track.album) }
        .thenByDescending { !it.syncedLyrics.isNullOrBlank() }
        .thenBy { abs(it.duration * 1000 - track.durationMs) })
    .firstOrNull()

private fun escapeQuery(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
private fun responseError(label: String, response: retrofit2.Response<*>): IOException = IOException(
    "$label 请求失败：HTTP ${response.code()}；${response.errorBody()?.string()?.take(300)}")
