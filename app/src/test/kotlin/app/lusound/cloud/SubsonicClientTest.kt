package app.lusound.cloud

import java.security.MessageDigest
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
 * [SubsonicClient.readLibrary] against a fake Subsonic endpoint.
 *
 * The client walks `getAlbumList2` -> `getAlbum` and `getPlaylists` -> `getPlaylist`, so the fake server
 * answers by path and reports back every request it received. That makes the walk itself (offsets,
 * ordering, where it stops) assertable, not just the returned snapshot.
 */
class SubsonicClientTest {
    private val password = "hunter2"

    private val songOne =
        """{"id":"s1","title":"First Song","artist":"Artist One","album":"Album One","duration":215,"suffix":"flac","coverArt":"cover-1","bitRate":1411,"samplingRate":44100}"""
    private val songTwo =
        """{"id":"s2","title":"Second Song","artist":"Artist One","album":"Album One","duration":180,"suffix":"mp3","coverArt":"cover-1","bitRate":320}"""
    private val songThree =
        """{"id":"s3","title":"Third Song","artist":"Artist Two","album":"Album Two","duration":240,"suffix":"flac","coverArt":"cover-3"}"""
    private val playlistOnly = """{"id":"s4","title":"Playlist Only","artist":"Artist Three","suffix":"opus"}"""

    private fun client(server: MockWebServer) = SubsonicClient(
        Server("srv1", "Test", server.url("/").toString(), "admin", "cipher", 0, null, "SUBSONIC", null),
        password,
    )

    private fun ok(content: String = ""): MockResponse {
        val body = if (content.isEmpty()) """{"status":"ok","version":"1.16.1"}""" else """{"status":"ok","version":"1.16.1",$content}"""
        return MockResponse().setHeader("Content-Type", "application/json").setBody("""{"subsonic-response":$body}""")
    }

    private fun failed(code: Int, message: String): MockResponse = MockResponse().setHeader("Content-Type", "application/json")
        .setBody("""{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":$code,"message":"$message"}}}""")

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

    /** Album `al1` holds two songs, album `al2` one, and the second page is empty so the walk stops. */
    private fun completeLibrary(request: RecordedRequest): MockResponse {
        val url = requireNotNull(request.requestUrl)
        return when (url.encodedPath) {
            "/rest/ping.view" -> ok()
            "/rest/getAlbumList2.view" -> when (url.queryParameter("offset")) {
                "0" -> ok(""""albumList2":{"album":[{"id":"al1"},{"id":"al2"}]}""")
                "2" -> ok(""""albumList2":{"album":[]}""")
                else -> unexpected()
            }
            "/rest/getAlbum.view" -> when (url.queryParameter("id")) {
                "al1" -> ok(""""album":{"id":"al1","song":[$songOne,$songTwo]}""")
                "al2" -> ok(""""album":{"id":"al2","song":[$songThree]}""")
                else -> unexpected()
            }
            "/rest/getPlaylists.view" -> ok(""""playlists":{"playlist":[{"id":"pl1","name":"Road Trip"},{"id":"pl2","name":"Empty List"}]}""")
            "/rest/getPlaylist.view" -> when (url.queryParameter("id")) {
                // `s3` already arrived through the album walk; it must not appear twice in the snapshot.
                "pl1" -> ok(""""playlist":{"id":"pl1","name":"Road Trip","entry":[$songThree,$playlistOnly]}""")
                "pl2" -> ok(""""playlist":{"id":"pl2","name":"Empty List","entry":[]}""")
                else -> unexpected()
            }
            else -> unexpected()
        }
    }

    @Test
    fun readsEveryAlbumAndPlaylistThroughTheAuthenticatedRestEndpoints() = runBlocking {
        val (server, recording) = start(::completeLibrary)
        try {
            val snapshot = client(server).readLibrary()

            assertEquals(listOf("s1", "s2", "s3", "s4"), snapshot.songs.map { it.id })
            val first = snapshot.songs.single { it.id == "s1" }
            assertEquals("First Song", first.title)
            assertEquals("Artist One", first.artist)
            assertEquals("Album One", first.album)
            assertEquals(215L, first.duration ?: -1L)
            assertEquals("flac", first.suffix)
            assertEquals("cover-1", first.coverArt)
            // Subsonic states the bitrate in kilobits and the sample rate in Hz; both are stored as-is.
            assertEquals(1411, first.bitrateKbps)
            assertEquals(44_100, first.sampleRateHz)
            assertNull("Subsonic does not report a bit depth", first.bitDepth)
            val second = snapshot.songs.single { it.id == "s2" }
            assertEquals("Second Song", second.title)
            assertEquals(180L, second.duration ?: -1L)
            assertEquals("mp3", second.suffix)
            assertEquals(320, second.bitrateKbps)
            assertNull("an absent samplingRate must stay unknown", second.sampleRateHz)
            val third = snapshot.songs.single { it.id == "s3" }
            assertEquals("Third Song", third.title)
            assertEquals("Artist Two", third.artist)
            assertEquals("Album Two", third.album)
            assertEquals(240L, third.duration ?: -1L)
            assertEquals("cover-3", third.coverArt)
            val fourth = snapshot.songs.single { it.id == "s4" }
            assertEquals("Playlist Only", fourth.title)
            assertNull("an absent album must stay null instead of failing the whole sync", fourth.album)
            assertNull(fourth.duration)
            assertNull(fourth.coverArt)
            assertNull("a server that reports no quality must leave it unknown", fourth.bitrateKbps)
            assertNull(fourth.sampleRateHz)
            assertNull(fourth.bitDepth)
            assertEquals("opus", fourth.suffix)

            assertEquals(listOf("pl1", "pl2"), snapshot.playlists.map { it.id })
            assertEquals(listOf("Road Trip", "Empty List"), snapshot.playlists.map { it.name })
            assertEquals(listOf("s3", "s4"), snapshot.playlists.first().entry.orEmpty().map { it.id })
            assertTrue(snapshot.playlists[1].entry.orEmpty().isEmpty())

            val requests = recording.requests.toList()
            assertEquals(
                "the pagination walk must stop on the first empty page",
                listOf(
                    "/rest/ping.view",
                    "/rest/getAlbumList2.view",
                    "/rest/getAlbum.view",
                    "/rest/getAlbum.view",
                    "/rest/getAlbumList2.view",
                    "/rest/getPlaylists.view",
                    "/rest/getPlaylist.view",
                    "/rest/getPlaylist.view",
                ),
                requests.map { requireNotNull(it.requestUrl).encodedPath },
            )

            val albumListCalls = requests.filter { requireNotNull(it.requestUrl).encodedPath == "/rest/getAlbumList2.view" }
            assertEquals(listOf("0", "2"), albumListCalls.map { requireNotNull(it.requestUrl).queryParameter("offset") })
            assertTrue(albumListCalls.all { requireNotNull(it.requestUrl).queryParameter("type") == "alphabeticalByName" })
            assertTrue(albumListCalls.all { requireNotNull(it.requestUrl).queryParameter("size") == "500" })
            assertEquals(
                listOf("al1", "al2"),
                requests.filter { requireNotNull(it.requestUrl).encodedPath == "/rest/getAlbum.view" }
                    .map { requireNotNull(it.requestUrl).queryParameter("id") },
            )
            assertEquals(
                listOf("pl1", "pl2"),
                requests.filter { requireNotNull(it.requestUrl).encodedPath == "/rest/getPlaylist.view" }
                    .map { requireNotNull(it.requestUrl).queryParameter("id") },
            )

            for (request in requests) {
                val url = requireNotNull(request.requestUrl)
                assertEquals("admin", url.queryParameter("u"))
                assertEquals("1.16.1", url.queryParameter("v"))
                assertEquals("LuSound", url.queryParameter("c"))
                assertEquals("json", url.queryParameter("f"))
                val salt = requireNotNull(url.queryParameter("s"))
                assertEquals(32, salt.length)
                assertTrue(salt, salt.all { it in "0123456789abcdef" })
                assertEquals(md5(password + salt), url.queryParameter("t"))
                assertNull("the plaintext password must never be sent as `p`", url.queryParameter("p"))
                assertFalse(request.path.orEmpty(), request.path.orEmpty().contains(password))
                assertFalse(url.toString(), url.toString().contains(password))
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun rejectsARepeatedAlbumIdAcrossPages() {
        val (server, recording) = start { request ->
            val url = requireNotNull(request.requestUrl)
            when (url.encodedPath) {
                "/rest/ping.view" -> ok()
                // Both pages advertise the same album, so continuing would silently duplicate a library.
                "/rest/getAlbumList2.view" -> ok(""""albumList2":{"album":[{"id":"al1"}]}""")
                "/rest/getAlbum.view" -> ok(""""album":{"id":"al1","song":[$songOne]}""")
                else -> unexpected()
            }
        }
        try {
            val error = assertThrows(SubsonicException::class.java) { runBlocking { client(server).readLibrary() } }
            assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("重复专辑"))
            assertEquals(
                "the walk must stop at the repeated page instead of finishing the sync",
                4,
                recording.requests.size,
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun rejectsAMissingAlbumListContainer() {
        val (server, _) = start { request ->
            val url = requireNotNull(request.requestUrl)
            when (url.encodedPath) {
                "/rest/ping.view" -> ok()
                "/rest/getAlbumList2.view" -> ok(""""playlists":{"playlist":[]}""")
                else -> unexpected()
            }
        }
        try {
            val error = assertThrows(SubsonicException::class.java) { runBlocking { client(server).readLibrary() } }
            assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("albumList2"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun rejectsAnErrorStatusAtAnyStep() {
        val cases = listOf(
            Triple("/rest/ping.view", failed(40, "Wrong username or password"), 1),
            Triple("/rest/getAlbum.view", failed(70, "Album not found"), 3),
            Triple("/rest/getPlaylist.view", failed(70, "Playlist not found"), 7),
        )
        for ((failingPath, failure, expectedRequests) in cases) {
            val (server, recording) = start { request ->
                val url = requireNotNull(request.requestUrl)
                if (url.encodedPath == failingPath) failure else completeLibrary(request)
            }
            try {
                val error = assertThrows(SubsonicException::class.java) { runBlocking { client(server).readLibrary() } }
                assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("失败"))
                assertFalse("the password must stay out of error messages", error.message.orEmpty().contains(password))
                assertEquals("no sync may continue past $failingPath", expectedRequests, recording.requests.size)
            } finally {
                server.shutdown()
            }
        }
    }

    @Test
    fun reportsHttpFailuresWithoutRetryingAClientError() {
        val (server, recording) = start { request ->
            val url = requireNotNull(request.requestUrl)
            if (url.encodedPath == "/rest/ping.view") MockResponse().setResponseCode(401).setBody("unauthorized") else completeLibrary(request)
        }
        try {
            val error = assertThrows(SubsonicException::class.java) { runBlocking { client(server).readLibrary() } }
            assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("401"))
            assertEquals(1, recording.requests.size)
        } finally {
            server.shutdown()
        }
    }

    private fun md5(value: String): String =
        MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
