package app.lusound

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import android.content.Context
import app.lusound.playback.PlaybackService
import app.lusound.playback.toMediaItem
import app.lusound.library.Track
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lusound.cloud.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Requires the isolated real Jellyfin at localhost:8097 through adb reverse. */
@RunWith(AndroidJUnit4::class)
class JellyfinIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun authenticateSyncAndRemoveWithoutTouchingLocalLibrary() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>().applicationContext as LuSoundApplication
        var id = java.util.UUID.randomUUID().toString()
        var draft = ServerDraft(id, "Jellyfin Integration", "http://127.0.0.1:8097", "admin", "lusound-isolated-test-only", "JELLYFIN")
        val before = app.database.library().getTracks().filter { it.origin != "JELLYFIN:$id" }
        try {
            compose.onNodeWithTag("nav_settings").performClick()
            compose.onNodeWithTag("add_server").performScrollTo().performClick()
            compose.onNodeWithTag("protocol_JELLYFIN").performClick()
            compose.onNodeWithTag("server_name").performTextInput(draft.name)
            compose.onNodeWithTag("server_url").performTextInput(draft.baseUrl)
            compose.onNodeWithTag("server_username").performScrollTo().performTextInput(draft.username)
            compose.onNodeWithTag("server_password").performScrollTo().performTextInput(draft.password)
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.waitForIdle()
            screenshot(app, "jellyfin-config.png")
            compose.onNodeWithTag("save_server").performClick()
            compose.waitUntil(30000) { compose.onAllNodesWithTag("save_server").fetchSemanticsNodes().isEmpty() }
            id = app.database.servers().all().single { it.name == draft.name }.id
            draft = draft.copy(id = id)
            compose.onNodeWithTag("server_$id").performScrollTo().assertIsDisplayed()
            screenshot(app, "jellyfin-connected.png")
            app.cloud.sync(id)
            val tracks = app.database.library().getTracks().filter { it.origin == "JELLYFIN:$id" }
            assertTrue("Real Jellyfin should expose the generated audio", tracks.isNotEmpty())
            assertEquals(tracks.size, tracks.map { it.uri }.distinct().size)
            assertTrue(tracks.all { it.uri.startsWith("lusound://") && !it.uri.contains("t=") })
            val playlists = app.database.library().cloudPlaylists(id)
            assertTrue("Real server playlist must sync", playlists.isNotEmpty())
            val playlistUris = app.database.library().playlistTrackUris(playlists.first().id)
            assertTrue(playlistUris.isNotEmpty())
            val track = tracks.first()
            val artwork = requireNotNull(track.artworkUri)
            app.http.newCall(okhttp3.Request.Builder().url(artwork).build()).execute().use { response ->
                assertEquals(200, response.code)
                val bytes = requireNotNull(response.body).bytes()
                assertNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            }
            playRealStream(app, track)
            val stored = requireNotNull(app.database.servers().get(id))
            assertFalse(stored.passwordCipher.contains(draft.password))
            assertTrue(app.vault.decrypt(stored.passwordCipher).isNotBlank())
            assertNotEquals(draft.password, app.vault.decrypt(stored.passwordCipher))
            assertNotNull(stored.remoteUserId)
            try {
                app.cloud.save(draft.copy(password = "wrong-test-password"))
                fail("Invalid password must fail explicitly")
            } catch (error: java.io.IOException) {
                assertFalse(error.message.orEmpty().contains("wrong-test-password"))
            }
            assertEquals(tracks, app.database.library().getTracks().filter { it.origin == "JELLYFIN:$id" })
        } finally { if (app.database.servers().get(id) != null) app.cloud.remove(id) }
        assertEquals(before, app.database.library().getTracks())
    }

}
