package app.lusound.cloud

import java.io.IOException
import java.util.Collections
import java.util.concurrent.TimeUnit
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
 * [PlexClient.readLibrary] and [PlexClient.resolveStream] against a fake PMS.
 *
 * Plex paginates through request headers and reports totals through response headers *and* the body, so
 * the fake server serves both and reports every request back. Saved data is only replaced when the walk is
 * provably complete, which is what the failure cases pin down.
 */
class PlexClientTest {
    private val token = "plex-token-123"

    private val trackOne =
        """{"ratingKey":"11","type":"track","title":"First","grandparentTitle":"Artist A","parentTitle":"Album A","duration":125000,"thumb":"/library/metadata/11/thumb/1","Media":[{"container":"flac","bitrate":1411,"Part":[{"key":"/library/parts/11/1.flac"}]}]}"""
    private val trackTwo =
        """{"ratingKey":"22","type":"track","title":"Second","grandparentTitle":"Artist B","parentTitle":"Album B","duration":61000,"parentThumb":"/library/metadata/22/thumb/9"}"""
    private val entryTrack =
        """{"ratingKey":"77","type":"track","title":"Entry Song","grandparentTitle":"Entry Artist","parentTitle":"Entry Album","duration":90000,"thumb":"/library/metadata/77/thumb/1","playlistItemID":501,"Media":[{"container":"flac","Part":[{"key":"/library/parts/77/a.flac"}]}]}"""
    private val streamTrack =
        """{"ratingKey":"7","type":"track","title":"Streamable","Media":[{"container":"flac","Part":[{"key":"/library/parts/123/file.flac"}]}]}"""

    private fun client(server: MockWebServer) = PlexClient(
        Server("srv1", "Test", server.url("/").toString(), "", "cipher", 0, null, "PLEX", null),
        token,
    )

    private fun plex(body: String, total: Int? = null, start: Int? = null): MockResponse {
        val response = MockResponse().setHeader("Content-Type", "application/json").setBody("""{"MediaContainer":$body}""")
        if (total != null) response.setHeader("X-Plex-Container-Total-Size", total)
        if (start != null) response.setHeader("X-Plex-Container-Start", start)
        return response
    }

    private fun sections(): MockResponse = plex("""{"size":1,"Directory":[{"key":"10","type":"artist"}]}""")

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

    /** Two tracks on two pages plus one audio playlist holding one entry. */
    private fun completeLibrary(request: RecordedRequest): MockResponse {
        val url = requireNotNull(request.requestUrl)
        return when (url.encodedPath) {
            "/library/sections" -> sections()
            "/library/sections/10/all" -> when (request.getHeader("X-Plex-Container-Start")) {
                "0" -> plex("""{"size":1,"totalSize":2,"offset":0,"Metadata":[$trackOne]}""", total = 2, start = 0)
                "1" -> plex("""{"size":1,"totalSize":2,"offset":1,"Metadata":[$trackTwo]}""", total = 2, start = 1)
                else -> unexpected()
            }
            "/playlists" -> plex(
                """{"size":1,"totalSize":1,"offset":0,"Metadata":[{"ratingKey":"p1","type":"playlist","title":"Road Trip","playlistType":"audio"}]}""",
                total = 1,
                start = 0,
            )
            "/playlists/p1/items" -> plex("""{"size":1,"totalSize":1,"offset":0,"Metadata":[$entryTrack]}""", total = 1, start = 0)
            else -> unexpected()
        }
    }

    @Test
    fun readLibraryWalksEveryPageAndKeepsPlaylistOrder() = runBlocking {
        val (server, recording) = start(::completeLibrary)
        try {
            val snapshot = client(server).readLibrary()

            assertEquals(listOf("11", "22", "77"), snapshot.songs.map { it.id })
            val first = snapshot.songs.single { it.id == "11" }
            assertEquals("First", first.title)
            assertEquals("Artist A", first.artist)
            assertEquals("Album A", first.album)
            assertEquals("Plex reports milliseconds, the library stores seconds", 125L, first.duration ?: -1L)
            assertEquals("flac", first.suffix)
            assertEquals("/library/metadata/11/thumb/1", first.coverArt)
            // Plex states the media bitrate in kilobits per second, which is the stored unit.
            assertEquals(1411, first.bitrateKbps)
            assertNull("Plex does not report a sample rate here", first.sampleRateHz)
            val second = snapshot.songs.single { it.id == "22" }
            assertEquals("Second", second.title)
            assertEquals(61L, second.duration ?: -1L)
            assertNull("a track without Media has no container", second.suffix)
            assertNull("and therefore no quality either", second.bitrateKbps)
            assertEquals("the album art is the fallback cover", "/library/metadata/22/thumb/9", second.coverArt)
            val entry = snapshot.songs.single { it.id == "77" }
            assertEquals(90L, entry.duration ?: -1L)

            assertEquals(listOf("p1"), snapshot.playlists.map { it.id })
            assertEquals(listOf("Road Trip"), snapshot.playlists.map { it.name })
            assertEquals(listOf("77"), snapshot.playlists.single().entry.orEmpty().map { it.id })

            val requests = recording.requests.toList()
            assertEquals(
                listOf(
                    "/library/sections",
                    "/library/sections/10/all",
                    "/library/sections/10/all",
                    "/playlists",
                    "/playlists/p1/items",
                ),
                requests.map { requireNotNull(it.requestUrl).encodedPath },
            )

            val trackPages = requests.filter { requireNotNull(it.requestUrl).encodedPath == "/library/sections/10/all" }
            assertEquals(listOf("0", "1"), trackPages.map { it.getHeader("X-Plex-Container-Start") })
            assertTrue(trackPages.all { it.getHeader("X-Plex-Container-Size") == "500" })
            assertTrue(trackPages.all { requireNotNull(it.requestUrl).queryParameter("type") == "10" })
            val playlistPages = requests.filter { requireNotNull(it.requestUrl).encodedPath == "/playlists" }
            assertEquals(listOf("0"), playlistPages.map { it.getHeader("X-Plex-Container-Start") })
            assertTrue(playlistPages.all { requireNotNull(it.requestUrl).queryParameter("playlistType") == "audio" })
            val itemPages = requests.filter { requireNotNull(it.requestUrl).encodedPath == "/playlists/p1/items" }
            assertEquals(listOf("0"), itemPages.map { it.getHeader("X-Plex-Container-Start") })

            for (request in requests) {
                val url = requireNotNull(request.requestUrl)
                assertEquals(token, request.getHeader("X-Plex-Token"))
                assertEquals("srv1", request.getHeader("X-Plex-Client-Identifier"))
                assertEquals("application/json", request.getHeader("Accept"))
                assertFalse(url.toString(), url.toString().contains(token))
            }
        } finally {
            server.shutdown()
        }
    }

    private fun libraryFailure(page: (String?) -> MockResponse): IOException {
        val (server, _) = start { request ->
            val url = requireNotNull(request.requestUrl)
            when (url.encodedPath) {
                "/library/sections" -> sections()
                "/library/sections/10/all" -> page(request.getHeader("X-Plex-Container-Start"))
                else -> unexpected()
            }
        }
        try {
            return assertThrows(IOException::class.java) { runBlocking { client(server).readLibrary() } }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun readLibraryRejectsAnyPageThatCannotBeProvenComplete() {
        val cases = listOf<Triple<String, (String?) -> MockResponse, String>>(
            Triple(
                "a page shorter than its own size",
                { _: String? -> plex("""{"size":2,"totalSize":2,"offset":0,"Metadata":[$trackOne]}""", total = 2, start = 0) },
                "分页",
            ),
            Triple(
                "a later page repeating an item",
                { start: String? ->
                    if (start == "0") plex("""{"size":1,"totalSize":2,"offset":0,"Metadata":[$trackOne]}""", total = 2, start = 0)
                    else plex("""{"size":1,"totalSize":2,"offset":1,"Metadata":[$trackOne]}""", total = 2, start = 1)
                },
                "重复",
            ),
            Triple(
                "a total header disagreeing with the body",
                { _: String? -> plex("""{"size":1,"totalSize":2,"offset":0,"Metadata":[$trackOne]}""", total = 3, start = 0) },
                "不一致",
            ),
            Triple(
                "an offset header disagreeing with the body",
                { _: String? -> plex("""{"size":1,"totalSize":2,"offset":1,"Metadata":[$trackOne]}""", total = 2, start = 0) },
                "不一致",
            ),
            Triple(
                "a non-numeric total header",
                { _: String? ->
                    MockResponse().setHeader("Content-Type", "application/json")
                        .setHeader("X-Plex-Container-Total-Size", "many")
                        .setBody("""{"MediaContainer":{"size":1,"Metadata":[$trackOne]}}""")
                },
                "无效",
            ),
            Triple(
                "no total in the body or the headers",
                { _: String? -> plex("""{"size":1,"Metadata":[$trackOne]}""") },
                "总条数",
            ),
        )
        for ((label, page, expected) in cases) {
            val error = libraryFailure(page)
            assertTrue("$label: ${error.message}", error.message.orEmpty().contains(expected))
        }
    }

    @Test
    fun resolveStreamReturnsThePartUrlForASinglePartTrack() {
        val (server, recording) = start { request ->
            val url = requireNotNull(request.requestUrl)
            if (url.encodedPath == "/library/metadata/7") plex("""{"size":1,"Metadata":[$streamTrack]}""") else unexpected()
        }
        try {
            assertEquals(
                server.url("/library/parts/123/file.flac").toString() + "?lusound_server=srv1",
                client(server).resolveStream("7"),
            )
            val request = requireNotNull(recording.requests.singleOrNull())
            assertEquals("/library/metadata/7", requireNotNull(request.requestUrl).encodedPath)
            assertEquals(token, request.getHeader("X-Plex-Token"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun resolveStreamRejectsAnythingThatIsNotExactlyOnePlayablePart() {
        val cases = listOf(
            "a ratingKey that disagrees with the song" to
                """{"size":1,"Metadata":[{"ratingKey":"8","type":"track","title":"Other","Media":[{"container":"flac","Part":[{"key":"/library/parts/8/a.flac"}]}]}]}""",
            "an item that is not a track" to
                """{"size":1,"Metadata":[{"ratingKey":"7","type":"album","title":"Album","Media":[{"container":"flac","Part":[{"key":"/library/parts/7/a.flac"}]}]}]}""",
            "a response with two items" to
                """{"size":2,"Metadata":[{"ratingKey":"7","type":"track","title":"A","Media":[{"container":"flac","Part":[{"key":"/library/parts/7/a.flac"}]}]},{"ratingKey":"7","type":"track","title":"B","Media":[{"container":"flac","Part":[{"key":"/library/parts/7/b.flac"}]}]}]}""",
            "an item with no media at all" to
                """{"size":1,"Metadata":[{"ratingKey":"7","type":"track","title":"No media"}]}""",
            "an item with an empty media list" to
                """{"size":1,"Metadata":[{"ratingKey":"7","type":"track","title":"No media","Media":[]}]}""",
            "an item whose media has no parts" to
                """{"size":1,"Metadata":[{"ratingKey":"7","type":"track","title":"No parts","Media":[{"container":"flac","Part":[]}]}]}""",
            "an item whose media has two parts" to
                """{"size":1,"Metadata":[{"ratingKey":"7","type":"track","title":"Two parts","Media":[{"container":"flac","Part":[{"key":"/library/parts/7/a.flac"},{"key":"/library/parts/7/b.flac"}]}]}]}""",
            "a part outside the Plex library" to
                """{"size":1,"Metadata":[{"ratingKey":"7","type":"track","title":"Elsewhere","Media":[{"container":"flac","Part":[{"key":"/music/7/a.flac"}]}]}]}""",
        )
        for ((label, body) in cases) {
            val (server, recording) = start { request ->
                val url = requireNotNull(request.requestUrl)
                if (url.encodedPath == "/library/metadata/7") plex(body) else unexpected()
            }
            try {
                val error = assertThrows(label, IOException::class.java) { client(server).resolveStream("7") }
                assertTrue("$label: ${error.message}", error.message.orEmpty().isNotBlank())
                assertEquals("$label must fail on the first metadata response", 1, recording.requests.size)
            } finally {
                server.shutdown()
            }
        }
    }

    @Test
    fun resolveStreamReportsServerErrorsOnlyAfterRetrying() {
        val (server, recording) = start { request ->
            val url = requireNotNull(request.requestUrl)
            if (url.encodedPath == "/library/metadata/7") {
                MockResponse().setHeader("Content-Type", "application/json").setResponseCode(500).setBody("boom")
            } else {
                unexpected()
            }
        }
        try {
            val error = assertThrows(IOException::class.java) { client(server).resolveStream("7") }
            assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("500"))
            assertEquals("a server error is retried before it is reported", 3, recording.requests.size)
            assertTrue(recording.requests.all { it.getHeader("X-Plex-Token") == token })
        } finally {
            server.shutdown()
        }
    }
}
