package app.lusound.cloud

import app.lusound.library.Playlist
import app.lusound.library.TrackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mapping and the deletion decisions a server sync is built from.
 *
 * A sync overwrites library rows and rebuilds playlists, so getting these wrong loses data; they are
 * checked here rather than only against a live server.
 */
class CloudSyncPlanTest {
    private val server = Server("srv1", "Music", "https://music.example.com/", "admin", "cipher", 0, null, "SUBSONIC", null)

    /**
     * Stands in for [cloudTrackUri], which builds its URI through `android.net.Uri.Builder` and so
     * needs a device. Its own shape is covered by the instrumented suite; what these tests check is
     * that the mapping threads the right server and song ids into it.
     */
    private val uriOf: (String, String) -> String = { serverId, songId -> "lusound://$serverId/$songId" }

    private fun song(
        id: String = "song-1",
        title: String = "标题",
        artist: String? = "歌手",
        album: String? = "专辑",
        duration: Long? = 215,
        suffix: String? = "flac",
        coverArt: String? = "cover-1",
        bitrateKbps: Int? = null,
        sampleRateHz: Int? = null,
        bitDepth: Int? = null,
    ) = RemoteSong(id, title, artist, album, duration, suffix, coverArt, bitrateKbps, sampleRateHz, bitDepth)

    @Test
    fun aSongBecomesARowKeyedOnItsServerAndId() {
        val track = cloudTracksOf(server, listOf(song()), uriOf).single()

        assertEquals("lusound://srv1/song-1", track.uri)
        assertEquals(TrackSource.CLOUD, track.sourceKind)
        assertEquals("srv1", track.sourceRef)
        assertEquals("标题", track.title)
        assertEquals("歌手", track.artist)
        assertEquals("专辑", track.album)
    }

    @Test
    fun serverSongsCarryTheirServersMarkerAndNotALocalFolder() {
        val track = cloudTracksOf(server, listOf(song()), uriOf).single()

        assertEquals("在线音乐", track.folder)
        val artwork = requireNotNull(track.artworkUri)
        assertTrue(artwork, artwork.contains("lusound_server=srv1"))
        assertTrue(artwork, artwork.startsWith("https://music.example.com/rest/getCoverArt.view"))
    }

    @Test
    fun secondsBecomeMillisecondsAndAnAbsentDurationBecomesZero() {
        assertEquals(215_000L, cloudTracksOf(server, listOf(song(duration = 215)), uriOf).single().durationMs)
        assertEquals(0L, cloudTracksOf(server, listOf(song(duration = null)), uriOf).single().durationMs)
    }

    @Test
    fun theContainerIsNormalisedTheSameWayAsEveryOtherSource() {
        assertEquals("mp3", cloudTracksOf(server, listOf(song(suffix = "MPEG")), uriOf).single().container)
        assertEquals("flac", cloudTracksOf(server, listOf(song(suffix = "FLAC")), uriOf).single().container)
        assertEquals("", cloudTracksOf(server, listOf(song(suffix = null)), uriOf).single().container)
    }

    @Test
    fun qualityTheServerReportedIsKeptAndNothingIsInvented() {
        val reported = cloudTracksOf(server, listOf(song(bitrateKbps = 1411, sampleRateHz = 44_100, bitDepth = 16)), uriOf).single()
        assertEquals(1411, reported.bitrateKbps)
        assertEquals(44_100, reported.sampleRateHz)

        val silent = cloudTracksOf(server, listOf(song()), uriOf).single()
        assertNull(silent.bitrateKbps)
        assertNull(silent.sampleRateHz)
        assertNull(silent.bitDepth)
    }

    @Test
    fun aSongTheServerCannotIdentifyIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { cloudTracksOf(server, listOf(song(id = "")), uriOf) }
        assertThrows(IllegalArgumentException::class.java) { cloudTracksOf(server, listOf(song(id = "  ")), uriOf) }
    }

    @Test
    fun aSongWithoutAnIdIsRefusedBeforeAnyUriIsBuilt() {
        val built = mutableListOf<String>()

        assertThrows(IllegalArgumentException::class.java) {
            cloudTracksOf(server, listOf(song(id = "")), { _, songId -> built += songId; "x" })
        }

        assertEquals(emptyList<String>(), built)
    }

    // ---- playlist reconciliation --------------------------------------------

    private fun playlist(id: Long, remoteId: String?) = Playlist(id, "名称 $id", "srv1", remoteId)

    @Test
    fun aPlaylistTheServerNoLongerListsIsRemoved() {
        val previous = listOf(playlist(1, "p1"), playlist(2, "p2"))

        assertEquals(listOf(2L), removedCloudPlaylistIds(previous, setOf("p1")))
    }

    @Test
    fun aPlaylistTheServerStillListsIsKept() {
        val previous = listOf(playlist(1, "p1"), playlist(2, "p2"))

        assertEquals(emptyList<Long>(), removedCloudPlaylistIds(previous, setOf("p1", "p2")))
    }

    @Test
    fun aPlaylistWithoutARemoteCounterpartIsNeverDeleted() {
        // A local playlist must not disappear because a server stopped mentioning it, and a snapshot
        // that lists nothing at all is the case that would otherwise wipe everything.
        val previous = listOf(playlist(1, null), playlist(2, "p2"))

        assertEquals(listOf(2L), removedCloudPlaylistIds(previous, emptySet()))
    }

    @Test
    fun aResyncRenamesTheExistingRowRatherThanAddingASecondCopy() {
        val previous = listOf(playlist(1, "p1"), playlist(2, "p2"))

        assertEquals(2L, existingCloudPlaylist(previous, "p2")?.id)
        assertNull(existingCloudPlaylist(previous, "p3"))
        assertNull("a missing remote id must not match a local playlist", existingCloudPlaylist(previous, "名称 1"))
    }
}
