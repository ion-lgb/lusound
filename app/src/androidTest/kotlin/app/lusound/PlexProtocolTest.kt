package app.lusound

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lusound.cloud.*
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Protocol contract test, not a substitute for a signed-in Plex server acceptance test. */
@RunWith(AndroidJUnit4::class)
class PlexProtocolTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun connectSyncResolveAuthenticatedAudioAndPreserveDataOnFailure() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>() as LuSoundApplication
        val fixture = MockWebServer()
        val token = "lusound-plex-contract-only"
        fixture.dispatcher = PlexFixture(token)
        fixture.start()
        var serverId: String? = null
        try {
            compose.onNodeWithTag("nav_settings").performClick()
            compose.onNodeWithTag("add_server").performScrollTo().performClick()
            compose.onNodeWithTag("protocol_PLEX").performClick()
            compose.onNodeWithTag("server_username").assertDoesNotExist()
            compose.onNodeWithTag("server_name").performTextInput("Plex protocol test")
            compose.onNodeWithTag("server_url").performTextInput(fixture.url("/").toString())
            compose.onNodeWithTag("server_password").performTextInput(token)
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            screenshot(app, "plex-config.png")
            compose.onNodeWithTag("save_server").performClick()
            compose.waitUntil(30000) { compose.onAllNodesWithTag("save_server").fetchSemanticsNodes().isEmpty() }
            val server = app.database.servers().all().single { it.name == "Plex protocol test" }
            serverId = server.id
            compose.onNodeWithTag("server_${server.id}").assertIsDisplayed()
            screenshot(app, "plex-connected.png")
            assertEquals("PLEX", server.kind)
            assertEquals(token, app.vault.decrypt(server.passwordCipher))
            assertFalse(server.passwordCipher.contains(token))
            val tracks = app.database.library().getTracks().filter { it.origin == "PLEX:${server.id}" }
            assertEquals(1, tracks.size)
            val playlist = app.database.library().cloudPlaylists(server.id).single()
            assertEquals(listOf(tracks.single().uri, tracks.single().uri), app.database.library().playlistTrackUris(playlist.id))
            assertFalse(tracks.single().uri.contains(token))
            app.http.newCall(okhttp3.Request.Builder().url(requireNotNull(tracks.single().artworkUri)).build()).execute().use {
                assertEquals(200, it.code)
                val bytes = requireNotNull(it.body).bytes()
                assertNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            }
            playRealStream(app, tracks.single())
            try {
                app.cloud.save(ServerDraft(server.id, server.name, server.baseUrl, "", "invalid-token", "PLEX"))
                fail("Invalid token must fail")
            } catch (error: IOException) {
                assertTrue(error.message.orEmpty().contains("401"))
                assertFalse(error.message.orEmpty().contains("invalid-token"))
            }
            assertEquals(tracks, app.database.library().getTracks().filter { it.origin == "PLEX:${server.id}" })
            assertEquals(token, app.vault.decrypt(requireNotNull(app.database.servers().get(server.id)).passwordCipher))
            listOf("https://other.example/library/parts/1", "//other.example/file", "/library/../../private", "/library/1?X-Plex-Token=secret").forEach { path ->
                try { plexResourceUrl(server, path); fail("Unsafe resource path must fail: $path") } catch (expected: IOException) { assertNotNull(expected.message) }
            }
            val calls = List(fixture.requestCount) { requireNotNull(fixture.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)) }
            assertTrue(calls.any { it.path == "/library/parts/1/file.wav" })
            assertTrue(calls.all { it.getHeader("User-Agent")?.startsWith("LuSound/") == true })
            assertTrue(calls.all { it.getHeader("X-Plex-Client-Identifier") == server.id })
            assertTrue(calls.none { it.path.orEmpty().contains(token) || it.path.orEmpty().contains("lusound_server") })
        } finally {
            serverId?.let { app.cloud.remove(it) }
            fixture.shutdown()
        }
    }
}

private class PlexFixture(private val token: String) : Dispatcher() {
    override fun dispatch(request: RecordedRequest): MockResponse {
        if (request.getHeader("X-Plex-Token") != token) return MockResponse().setResponseCode(401).setBody("Unauthorized")
        val url = requireNotNull(request.requestUrl)
        val track = """{"ratingKey":"1","type":"track","title":"Generated audio","grandparentTitle":"Test artist","parentTitle":"Test album","duration":6000,"thumb":"/library/metadata/1/thumb","Media":[{"container":"wav","Part":[{"key":"/library/parts/1/file.wav"}]}]}"""
        val body = when (url.encodedPath) {
            "/library/sections" -> """{"size":1,"Directory":[{"key":"10","type":"artist"}]}"""
            "/library/sections/10/all" -> """{"size":1,"totalSize":1,"offset":0,"Metadata":[$track]}"""
            "/playlists" -> """{"size":1,"totalSize":1,"offset":0,"Metadata":[{"ratingKey":"20","type":"playlist","title":"Cloud playlist","playlistType":"audio"}]}"""
            "/playlists/20/items" -> """{"size":2,"totalSize":2,"offset":0,"Metadata":[${track.dropLast(1)},"playlistItemID":1},${track.dropLast(1)},"playlistItemID":2}]}"""
            "/library/metadata/1" -> """{"size":1,"Metadata":[$track]}"""
            "/library/parts/1/file.wav" -> return MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(testWav()))
            "/library/metadata/1/thumb" -> {
                val bitmap = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.BLUE)
                val stream = java.io.ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
                bitmap.recycle()
                return MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(stream.toByteArray()))
            }
            else -> return MockResponse().setResponseCode(404)
        }
        return MockResponse().setHeader("Content-Type", "application/json").setBody("""{"MediaContainer":$body}""")
    }
}
