package app.lusound.cloud

import app.lusound.BuildConfig
import java.io.IOException
import java.util.Collections
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [JellyfinClient.readLibrary] against a fake Jellyfin server.
 *
 * The client is built on the official SDK, which sends real HTTP through the same [baseHttpClient] as the
 * other protocols, so a [MockWebServer] is enough to exercise it: no SDK internals are replaced. The fake
 * server answers both `/Items` walks and the playlist item walk with real-shaped `BaseItemDtoQueryResult`
 * bodies and reports every request back.
 */
class JellyfinClientTest {
    private val token = "token-abc"
    private val userId = "22222222-2222-2222-2222-222222222222"
    private val songA = "aaaaaaaa-1111-1111-1111-111111111111"
    private val songB = "bbbbbbbb-2222-2222-2222-222222222222"
    private val playlistId = "cccccccc-3333-3333-3333-333333333333"
    private val entryId = "dddddddd-4444-4444-4444-444444444444"
    private val playlistItemId = "eeeeeeee-5555-5555-5555-555555555555"
    private val albumB = "99999999-1111-1111-1111-111111111111"

    /** Every field Jellyfin marks as required for a media source, so the body stays server-shaped. */
    private val mediaSource =
        """{"Protocol":"File","Type":"Default","IsRemote":false,"ReadAtNativeFramerate":false,"IgnoreDts":false,"IgnoreIndex":false,"GenPtsInput":false,"SupportsTranscoding":true,"SupportsDirectStream":true,"SupportsDirectPlay":true,"IsInfiniteStream":false,"RequiresOpening":false,"RequiresClosing":false,"RequiresLooping":false,"SupportsProbing":true,"TranscodingSubProtocol":"http","HasSegments":false,"Container":"opus","Bitrate":960000,"MediaStreams":[{"IsInterlaced":false,"IsDefault":true,"IsForced":false,"IsHearingImpaired":false,"Type":"Audio","Index":0,"IsExternal":false,"IsTextSubtitleStream":false,"SupportsExternalStream":false,"Codec":"opus","SampleRate":48000,"BitDepth":24,"BitRate":960000}]}"""

    private val audioA =
        """{"Name":"First Song","Id":"$songA","Type":"Audio","MediaType":"Audio","Album":"Album One","Artists":["Artist One"],"RunTimeTicks":2150000000,"Container":"flac","ImageTags":{"Primary":"tag-1"}}"""

    /** No primary image tag and no top level container: both fallbacks must be used. */
    private val audioB =
        """{"Name":"Second Song","Id":"$songB","Type":"Audio","MediaType":"Audio","Album":"Album Two","AlbumId":"$albumB","Artists":["Artist Two","Guest Artist"],"RunTimeTicks":1800000000,"AlbumPrimaryImageTag":"album-tag","MediaSources":[$mediaSource]}"""

    private val playlistItem = """{"Name":"Road Trip","Id":"$playlistId","Type":"Playlist","MediaType":"Audio"}"""

    private val entryItem =
        """{"Name":"Entry Song","Id":"$entryId","Type":"Audio","MediaType":"Audio","PlaylistItemId":"$playlistItemId","RunTimeTicks":900000000,"Container":"mp3"}"""

    private fun client(server: MockWebServer) = JellyfinClient(
        Server("srv1", "Test", server.url("/").toString(), "admin", "cipher", 0, null, "JELLYFIN", userId),
        token,
    )

    private fun page(items: String, total: Int, startIndex: Int): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"Items":[$items],"TotalRecordCount":$total,"StartIndex":$startIndex}""")

    /** An unexpected request answers 404 so no test ever hits the retry loop or the network. */
    private fun unexpected(): MockResponse = MockResponse().setResponseCode(404).setBody("unexpected request")

    private class Recording(private val route: (RecordedRequest) -> MockResponse) : Dispatcher() {
        val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf<RecordedRequest>())
        override fun dispatch(request: RecordedRequest): MockResponse {
            requests.add(request)
            return route(request)
        }
    }

    private fun start(route: (RecordedRequest) -> MockResponse): Pair<MockWebServer, Recording> {
        val recording = Recording(route)
        val server = MockWebServer()
        server.dispatcher = recording
        server.start()
        return server to recording
    }

    private fun isAudioWalk(request: RecordedRequest): Boolean {
        val url = requireNotNull(request.requestUrl)
        return url.encodedPath == "/Items" && url.queryParameter("includeItemTypes") == "Audio"
    }

    /** Two audio pages (one item each) plus one audio playlist holding one entry. */
    private fun completeLibrary(request: RecordedRequest): MockResponse {
        val url = requireNotNull(request.requestUrl)
        return when {
            isAudioWalk(request) -> when (url.queryParameter("startIndex")) {
                "0" -> page(audioA, total = 2, startIndex = 0)
                "1" -> page(audioB, total = 2, startIndex = 1)
                else -> unexpected()
            }
            url.encodedPath == "/Items" && url.queryParameter("includeItemTypes") == "Playlist" ->
                page(playlistItem, total = 1, startIndex = 0)
            url.encodedPath == "/Playlists/$playlistId/Items" -> page(entryItem, total = 1, startIndex = 0)
            else -> unexpected()
        }
    }

    @Test
    fun readLibraryWalksAudioItemsAndPlaylistsWithTheSdk() = runBlocking {
        val (server, recording) = start(::completeLibrary)
        try {
            val snapshot = client(server).readLibrary()

            assertEquals(listOf(songA, songB, entryId), snapshot.songs.map { it.id })
            val first = snapshot.songs.single { it.id == songA }
            assertEquals("First Song", first.title)
            assertEquals("Artist One", first.artist)
            assertEquals("Album One", first.album)
            assertEquals("ticks are converted to seconds", 215L, first.duration ?: -1L)
            assertEquals("flac", first.suffix)
            assertEquals("a primary image tag makes the item its own cover", songA, first.coverArt)
            assertNull("a song with no media source reports no quality", first.bitrateKbps)
            assertNull(first.sampleRateHz)
            assertNull(first.bitDepth)
            val second = snapshot.songs.single { it.id == songB }
            assertEquals("Second Song", second.title)
            assertEquals("Artist Two / Guest Artist", second.artist)
            assertEquals(180L, second.duration ?: -1L)
            assertEquals("the media source container is the fallback", "opus", second.suffix)
            assertEquals("the album image is the fallback cover", albumB, second.coverArt)
            // Jellyfin reports bits per second; the library stores kilobits. The audio stream's own
            // figures win over the media source's, and they are the only place a bit depth appears.
            assertEquals(960, second.bitrateKbps)
            assertEquals(48_000, second.sampleRateHz)
            assertEquals(24, second.bitDepth)
            val entry = snapshot.songs.single { it.id == entryId }
            assertEquals("Entry Song", entry.title)
            assertNull(entry.artist)
            assertNull(entry.album)
            assertEquals(90L, entry.duration ?: -1L)
            assertEquals("mp3", entry.suffix)
            assertNull(entry.coverArt)

            assertEquals(listOf(playlistId), snapshot.playlists.map { it.id })
            assertEquals(listOf("Road Trip"), snapshot.playlists.map { it.name })
            assertEquals(listOf(entryId), snapshot.playlists.single().entry.orEmpty().map { it.id })

            val requests = recording.requests.toList()
            assertEquals(
                listOf("/Items", "/Items", "/Items", "/Playlists/$playlistId/Items"),
                requests.map { requireNotNull(it.requestUrl).encodedPath },
            )

            val audioPages = requests.filter(::isAudioWalk)
            assertEquals(listOf("0", "1"), audioPages.map { requireNotNull(it.requestUrl).queryParameter("startIndex") })
            for (request in audioPages) {
                val url = requireNotNull(request.requestUrl)
                assertEquals(userId, url.queryParameter("userId"))
                assertEquals("500", url.queryParameter("limit"))
                assertEquals("true", url.queryParameter("recursive"))
                // Retrofit repeats the parameter for a list, so the first value stays MediaSources.
                assertEquals("MediaSources", url.queryParameter("fields"))
                assertEquals(
                    "quality needs the per-stream fields, so MEDIA_STREAMS must be requested",
                    listOf("MediaSources", "MediaStreams"),
                    url.queryParameterValues("fields"),
                )
                assertEquals("SortName", url.queryParameter("sortBy"))
            }

            val playlistPage = requests.single { requireNotNull(it.requestUrl).queryParameter("includeItemTypes") == "Playlist" }
            assertEquals("/Items", requireNotNull(playlistPage.requestUrl).encodedPath)
            assertEquals("500", requireNotNull(playlistPage.requestUrl).queryParameter("limit"))

            val entryPage = requests.single { requireNotNull(it.requestUrl).encodedPath == "/Playlists/$playlistId/Items" }
            val entryUrl = requireNotNull(entryPage.requestUrl)
            assertEquals(userId, entryUrl.queryParameter("userId"))
            assertEquals("0", entryUrl.queryParameter("startIndex"))
            assertEquals("500", entryUrl.queryParameter("limit"))
            assertEquals("MediaSources", entryUrl.queryParameter("fields"))
            assertEquals(listOf("MediaSources", "MediaStreams"), entryUrl.queryParameterValues("fields"))

            for (request in requests) {
                val authorization = request.getHeader("Authorization").orEmpty()
                assertTrue(authorization, authorization.contains("Token=\"$token\""))
                assertTrue(authorization, authorization.contains("Client=\"LuSound\""))
                assertTrue(authorization, authorization.contains("Version=\"${BuildConfig.VERSION_NAME}\""))
                assertTrue(authorization, authorization.contains("DeviceId=\"srv1\""))
                val agent = request.getHeader("User-Agent").orEmpty()
                assertTrue(agent, agent.startsWith("LuSound/${BuildConfig.VERSION_NAME} "))
                val url = requireNotNull(request.requestUrl).toString()
                assertFalse(url, url.contains(token))
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun readLibraryRejectsADuplicateItemAcrossPages() {
        val (server, recording) = start { request ->
            if (isAudioWalk(request)) {
                page(audioA, total = 2, startIndex = requireNotNull(request.requestUrl).queryParameter("startIndex")?.toInt() ?: 0)
            } else {
                unexpected()
            }
        }
        try {
            val error = assertThrows(IOException::class.java) { runBlocking { client(server).readLibrary() } }
            assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("重复条目"))
            assertEquals(2, recording.requests.size)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun readLibraryRejectsATotalThatChangesMidWalk() {
        val (server, recording) = start { request ->
            when {
                !isAudioWalk(request) -> unexpected()
                requireNotNull(request.requestUrl).queryParameter("startIndex") == "0" -> page(audioA, total = 2, startIndex = 0)
                else -> page(audioB, total = 3, startIndex = 1)
            }
        }
        try {
            val error = assertThrows(IOException::class.java) { runBlocking { client(server).readLibrary() } }
            assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("发生变化"))
            assertEquals(2, recording.requests.size)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun readLibraryRejectsAnEarlyEmptyPage() {
        val (server, recording) = start { request ->
            when {
                !isAudioWalk(request) -> unexpected()
                requireNotNull(request.requestUrl).queryParameter("startIndex") == "0" -> page(audioA, total = 2, startIndex = 0)
                else -> page("", total = 2, startIndex = 1)
            }
        }
        try {
            val error = assertThrows(IOException::class.java) { runBlocking { client(server).readLibrary() } }
            assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("提前返回空页"))
            assertEquals(2, recording.requests.size)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun readLibraryReportsAnHttpFailureAsAnIOExceptionWithoutRetrying() {
        val (server, recording) = start { MockResponse().setResponseCode(401).setBody("unauthorized") }
        try {
            val error = assertThrows(IOException::class.java) { runBlocking { client(server).readLibrary() } }
            assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("401"))
            assertEquals("a 401 must not be replayed", 1, recording.requests.size)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun readLibraryReportsAMalformedBodyAsAnIOException() {
        val (server, _) = start {
            MockResponse().setHeader("Content-Type", "application/json").setBody("""{"Items":[]}""")
        }
        try {
            val error = assertThrows(IOException::class.java) { runBlocking { client(server).readLibrary() } }
            assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("请求失败"))
        } finally {
            server.shutdown()
        }
    }
}
