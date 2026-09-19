package app.lusound.cloud

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

@Serializable data class SubsonicResponse(@SerialName("subsonic-response") val response: SubsonicBody)
@Serializable data class SubsonicBody(val status: String, val version: String, val error: SubsonicError?,
    val albumList2: AlbumList?, val album: RemoteAlbum?, val playlists: PlaylistList?, val playlist: RemotePlaylist?)
@Serializable data class SubsonicError(val code: Int, val message: String)
@Serializable data class AlbumList(val album: List<AlbumSummary>?)
@Serializable data class AlbumSummary(val id: String)
@Serializable data class RemoteAlbum(val id: String, val song: List<RemoteSong>?)
/**
 * One song as a server reports it, shared by all three protocols.
 *
 * [bitrateKbps], [sampleRateHz] and [bitDepth] carry whatever the server actually stated, and stay
 * null when it stated nothing: the caller must not turn a container name into a quality figure. The
 * unit is kilobits per second everywhere, which is what Subsonic and Plex already send and what the
 * Jellyfin client converts into (Jellyfin reports bits per second).
 */
@Serializable data class RemoteSong(val id: String, val title: String, val artist: String?, val album: String?,
    val duration: Long?, val suffix: String?, val coverArt: String?,
    @SerialName("bitRate") val bitrateKbps: Int? = null,
    @SerialName("samplingRate") val sampleRateHz: Int? = null,
    val bitDepth: Int? = null)
@Serializable data class PlaylistList(val playlist: List<PlaylistSummary>?)
@Serializable data class PlaylistSummary(val id: String, val name: String)
@Serializable data class RemotePlaylist(val id: String, val name: String, val entry: List<RemoteSong>?)

interface SubsonicApi {
    @GET("rest/ping.view") suspend fun ping(): Response<SubsonicResponse>
    @GET("rest/getAlbumList2.view") suspend fun albums(@Query("type") type: String, @Query("size") size: Int, @Query("offset") offset: Int): Response<SubsonicResponse>
    @GET("rest/getAlbum.view") suspend fun album(@Query("id") id: String): Response<SubsonicResponse>
    @GET("rest/getPlaylists.view") suspend fun playlists(): Response<SubsonicResponse>
    @GET("rest/getPlaylist.view") suspend fun playlist(@Query("id") id: String): Response<SubsonicResponse>
}
