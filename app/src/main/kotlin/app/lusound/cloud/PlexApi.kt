package app.lusound.cloud

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

@Serializable data class PlexResponse(@SerialName("MediaContainer") val container: PlexContainer)
@Serializable data class PlexContainer(val size: Int, val totalSize: Int?, val offset: Int?,
    @SerialName("Directory") val directories: List<PlexDirectory>?, @SerialName("Metadata") val items: List<PlexItem>?)
@Serializable data class PlexDirectory(val key: String, val type: String)
@Serializable data class PlexItem(val ratingKey: String, val type: String, val title: String,
    val grandparentTitle: String?, val parentTitle: String?, val duration: Long?, val thumb: String?,
    val parentThumb: String?, val playlistType: String?, val playlistItemID: Long?, @SerialName("Media") val media: List<PlexMedia>?)
@Serializable data class PlexMedia(val container: String?, @SerialName("Part") val parts: List<PlexPart>)
@Serializable data class PlexPart(val key: String)

interface PlexApi {
    @GET("library/sections") suspend fun sections(): Response<PlexResponse>
    @GET("library/sections/{id}/all") suspend fun tracks(@Path("id") id: String, @Query("type") type: Int,
        @Header("X-Plex-Container-Start") offset: Int, @Header("X-Plex-Container-Size") size: Int): Response<PlexResponse>
    @GET("playlists") suspend fun playlists(@Query("playlistType") type: String,
        @Header("X-Plex-Container-Start") offset: Int, @Header("X-Plex-Container-Size") size: Int): Response<PlexResponse>
    @GET("playlists/{id}/items") suspend fun playlistItems(@Path("id") id: String,
        @Header("X-Plex-Container-Start") offset: Int, @Header("X-Plex-Container-Size") size: Int): Response<PlexResponse>
    @GET("library/metadata/{id}") fun metadata(@Path("id") id: String): Call<PlexResponse>
}
