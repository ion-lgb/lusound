package app.lusound.cloud

import app.lusound.library.Playlist
import app.lusound.library.Track
import app.lusound.library.TrackSource
import app.lusound.library.audioContainer

/**
 * The decisions a server snapshot turns into, kept apart from the writes that carry them out.
 *
 * A sync overwrites library rows and rebuilds playlists, so the rules that decide *what* is written
 * decide whether a user keeps their library. Expressing them as pure functions means they are checked
 * on every build rather than only when a real Navidrome or Jellyfin happens to be running.
 */

/**
 * The library rows a snapshot maps to, before anything is written.
 *
 * [uriOf] is injectable only so this mapping can be unit tested: the real one is [cloudTrackUri], which
 * goes through `android.net.Uri.Builder` and therefore cannot run off-device. Production always uses
 * the default, and the URI it builds is covered by the instrumented suite.
 *
 * A song without an ID is refused outright: it would otherwise become a row keyed on an empty path and
 * collide with every other song the server failed to identify.
 */
fun cloudTracksOf(server: Server, songs: List<RemoteSong>, uriOf: (String, String) -> String = ::cloudTrackUri): List<Track> = songs.map { song ->
    require(song.id.isNotBlank()) { "服务器返回空歌曲 ID" }
    Track(
        uriOf(server.id, song.id), song.title, song.artist.orEmpty(), song.album.orEmpty(),
        // The folder is a label rather than a directory; server songs have no local location.
        "在线音乐", (song.duration ?: 0) * 1000, song.coverArt?.let { coverUrl(server, it) },
        audioContainer(song.suffix.orEmpty()), TrackSource.CLOUD, server.id,
        song.bitrateKbps, song.sampleRateHz, song.bitDepth,
    )
}

/**
 * Local playlist ids whose remote counterpart is gone from the snapshot.
 *
 * Only rows that came from this server are passed in, and a row without a remote id is left alone:
 * a playlist the user made locally must never be deleted because a server stopped mentioning it.
 */
fun removedCloudPlaylistIds(previous: List<Playlist>, remotePlaylistIds: Set<String>): List<Long> =
    previous.filter { it.remoteId != null && it.remoteId !in remotePlaylistIds }.map { it.id }

/**
 * The local row that already stands for a remote playlist, so a resync renames it instead of creating
 * a second copy of the same server playlist.
 */
fun existingCloudPlaylist(previous: List<Playlist>, remotePlaylistId: String): Playlist? =
    previous.firstOrNull { it.remoteId == remotePlaylistId }
