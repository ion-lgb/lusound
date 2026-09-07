package app.lusound.cloud

import android.util.Log
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class SubsonicException(message: String) : IOException(message)

fun normalizeServerUrl(value: String): String {
    val url = value.trim().toHttpUrl()
    require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "服务器地址不能包含账号、密码、查询参数或片段" }
    return url.newBuilder().encodedPath(url.encodedPath.trimEnd('/') + "/").build().toString()
}

/** No redirects: a redirect must never send Subsonic query credentials to another endpoint. */
fun baseHttpClient(): OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
    .addInterceptor(RetryInterceptor()).build()

private class RetryInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder().header("User-Agent", "LuSound/0.3.0 Android").build()
        for (attempt in 1..3) {
            try {
                val response = chain.proceed(request)
                if (response.code !in setOf(408, 429, 500, 502, 503, 504) || attempt == 3) return response
                warnRetry(request.url, attempt, response.code)
                response.close()
            } catch (error: IOException) {
                if (chain.call().isCanceled() || attempt == 3) throw error
                warnRetry(request.url, attempt, 0)
            }
            try { Thread.sleep(attempt * 500L) } catch (error: InterruptedException) {
                Thread.currentThread().interrupt(); throw IOException("网络重试被中断", error)
            }
        }
        throw IOException("网络请求超过重试上限")
    }
}

private fun warnRetry(url: HttpUrl, attempt: Int, status: Int) {
    Log.w("LuSoundNetwork", buildJsonObject {
        put("event", "request_retry"); put("endpoint", url.encodedPath); put("attempt", attempt); put("status", status)
    }.toString())
}

fun authenticate(request: Request, server: Server, password: String): Request {
    val base = server.baseUrl.toHttpUrl()
    val url = request.url
    if (url.scheme != base.scheme || url.host != base.host || url.port != base.port || !url.encodedPath.startsWith(base.encodedPath + "rest/")) {
        throw SubsonicException("拒绝向配置范围以外的地址发送服务器凭据")
    }
    val salt = UUID.randomUUID().toString().replace("-", "")
    val token = MessageDigest.getInstance("MD5").digest((password + salt).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    val authenticated = url.newBuilder().removeAllQueryParameters("lusound_server")
        .addQueryParameter("u", server.username).addQueryParameter("t", token).addQueryParameter("s", salt)
        .addQueryParameter("v", "1.16.1").addQueryParameter("c", "LuSound").addQueryParameter("f", "json").build()
    return request.newBuilder().url(authenticated).build()
}

/** Resolves an opaque server marker only inside the network stack. Tokens never enter stored media metadata. */
class SavedServerInterceptor(private val servers: ServerDao, private val vault: CredentialVault) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val id = request.url.queryParameter("lusound_server") ?: return chain.proceed(request)
        val server = servers.getForRequest(id) ?: throw SubsonicException("服务器已移除，无法读取该音频或封面")
        val secret = vault.decrypt(server.passwordCipher)
        if (server.kind == "SUBSONIC") return chain.proceed(authenticate(request, server, secret))
        if (server.kind != "JELLYFIN") throw SubsonicException("不支持的服务器协议")
        val base = server.baseUrl.toHttpUrl()
        val url = request.url
        if (url.scheme != base.scheme || url.host != base.host || url.port != base.port || !url.encodedPath.startsWith(base.encodedPath)) {
            throw SubsonicException("拒绝向配置范围以外的地址发送 Jellyfin 凭据")
        }
        return chain.proceed(request.newBuilder().url(url.newBuilder().removeAllQueryParameters("lusound_server").build())
            .header("X-Emby-Token", secret).build())
    }
}

fun mediaUrl(server: Server, endpoint: String, mediaId: String): String = server.baseUrl.toHttpUrl().newBuilder()
    .addPathSegments("rest/$endpoint.view").addQueryParameter("id", mediaId)
    .addQueryParameter("lusound_server", server.id).build().toString()

fun streamUrl(server: Server, songId: String): String = when (server.kind) {
    "SUBSONIC" -> mediaUrl(server, "stream", songId)
    "JELLYFIN" -> server.baseUrl.toHttpUrl().newBuilder().addPathSegment("Audio").addPathSegment(songId)
        .addPathSegment("stream").addQueryParameter("static", "true").addQueryParameter("lusound_server", server.id).build().toString()
    else -> throw SubsonicException("不支持的服务器协议")
}

fun coverUrl(server: Server, artworkId: String): String = when (server.kind) {
    "SUBSONIC" -> mediaUrl(server, "getCoverArt", artworkId)
    "JELLYFIN" -> server.baseUrl.toHttpUrl().newBuilder().addPathSegment("Items").addPathSegment(artworkId)
        .addPathSegments("Images/Primary").addQueryParameter("maxWidth", "800").addQueryParameter("lusound_server", server.id).build().toString()
    else -> throw SubsonicException("不支持的服务器协议")
}
