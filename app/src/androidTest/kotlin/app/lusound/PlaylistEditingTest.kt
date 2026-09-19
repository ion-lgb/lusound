package app.lusound

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lusound.library.LibraryViewModel
import app.lusound.library.Playlist
import app.lusound.library.PlaylistEntry
import app.lusound.library.Track
import app.lusound.library.TrackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Renaming and manually reordering a local playlist, against the application's real SQLite.
 *
 * `playlist_entries` is keyed on `(playlistId, position)`, so the interesting failure of a reorder is
 * a primary-key conflict, or a position sequence left with a gap or a duplicate. Both are asserted
 * here, and so is the duplicate song the app deliberately allows: [MOVED] sits at two positions.
 */
@RunWith(AndroidJUnit4::class)
class PlaylistEditingTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val database get() = (ApplicationProvider.getApplicationContext<Context>() as LuSoundApplication).database
    private var playlist = 0L
    /** Seeded before the rule starts the activity, so the first playlist grid already shows them. */
    @Before fun seed() = runBlocking {
        val library = database.library()
        library.upsertTracks(listOf(track(LEADING), track(MOVED), track(POSITIONED)))
        playlist = library.insertPlaylist(Playlist(0, "待重命名", null, null))
        // Gapped on purpose: 0, 3, 5, 7 is what a playlist looks like after earlier removals, and a
        // move must leave 0..n-1 behind instead of carrying the gaps along.
        library.insertEntry(PlaylistEntry(playlist, LEADING, 0))
        library.insertEntry(PlaylistEntry(playlist, MOVED, 3))
        library.insertEntry(PlaylistEntry(playlist, MOVED, 5))
        library.insertEntry(PlaylistEntry(playlist, POSITIONED, 7))
    }

    @After fun cleanUp() = runBlocking {
        database.library().deletePlaylist(playlist)
        database.library().deleteTracks(listOf(LEADING, MOVED, POSITIONED))
    }

    @Test fun renameAndReorderALocalPlaylist() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        lateinit var library: LibraryViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            library = ViewModelProvider(compose.activity)[LibraryViewModel::class.java]
        }
        assertEquals(listOf(LEADING, MOVED, MOVED, POSITIONED), stored())
        compose.onNodeWithTag("tab_PLAYLISTS").performScrollTo().performClick()
        compose.onNodeWithTag("group_PLAYLISTS_$playlist").performScrollTo().performClick()

        // The rename entry point exists on a local playlist and persists what it is given.
        compose.onNodeWithTag("rename_playlist_$playlist").assertIsDisplayed().performClick()
        compose.onNodeWithTag("rename_playlist_name").performTextClearance()
        compose.onNodeWithTag("rename_playlist_name").performTextInput("深夜歌单")
        compose.onNodeWithTag("save_rename_playlist").performClick()
        compose.waitUntil(TIMEOUT) { storedName() == "深夜歌单" }
        assertEquals("深夜歌单", storedName())
        compose.onNodeWithTag("rename_playlist_name").assertDoesNotExist()
        compose.onNodeWithTag("lusound_root").assertIsDisplayed()

        // The detail page drives the reorder: four rows, and neither end may move off the list.
        compose.onNodeWithTag("playlist_up_0").assertIsNotEnabled()
        compose.onNodeWithTag("playlist_down_3").assertIsNotEnabled()
        compose.onNodeWithTag("playlist_up_1").assertIsEnabled().performClick()
        compose.waitUntil(TIMEOUT) { stored() == listOf(MOVED, LEADING, MOVED, POSITIONED) }
        assertContiguous()

        compose.onNodeWithTag("playlist_down_0").assertIsEnabled().performClick()
        compose.waitUntil(TIMEOUT) { stored() == listOf(LEADING, MOVED, MOVED, POSITIONED) }
        assertEquals(listOf(LEADING, MOVED, MOVED, POSITIONED), stored())
        assertContiguous()

        // A blank name is rejected and the sheet stays open; the stored name is untouched.
        compose.onNodeWithTag("rename_playlist_$playlist").performClick()
        compose.onNodeWithTag("rename_playlist_name").performTextClearance()
        compose.onNodeWithTag("rename_playlist_name").performTextInput("   ")
        compose.onNodeWithTag("save_rename_playlist").performClick()
        compose.waitUntil(TIMEOUT) { library.error.value == null }
        compose.onNodeWithTag("rename_playlist_name").assertIsDisplayed()
        assertEquals("深夜歌单", storedName())
        screenshot(context, "playlist-editing.png")
    }

    @Test fun movingTheSecondDuplicateKeepsBothCopiesAndACleanSequence() {
        lateinit var library: LibraryViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            library = ViewModelProvider(compose.activity)[LibraryViewModel::class.java]
        }
        // Index 2 is the duplicate of index 1; moving it above its twin must keep both rows.
        library.moveInPlaylist(playlist, 2, 1)
        compose.waitUntil(TIMEOUT) { stored() == listOf(LEADING, MOVED, MOVED, POSITIONED) }
        assertContiguous()
        assertEquals(entries().map { it.trackUri }.sorted(), listOf(LEADING, MOVED, MOVED, POSITIONED).sorted())

        library.moveInPlaylist(playlist, 0, 3)
        compose.waitUntil(TIMEOUT) { stored() == listOf(MOVED, MOVED, POSITIONED, LEADING) }
        assertContiguous()

        // A cloud playlist is owned by its server: neither rename nor reorder may touch it.
        runBlocking(Dispatchers.IO) {
            val local = checkNotNull(database.library().getPlaylist(playlist))
            database.library().savePlaylist(local.copy(serverId = "test-server", remoteId = "remote-1"))
        }
        library.moveInPlaylist(playlist, 0, 1)
        compose.waitUntil(TIMEOUT) { library.error.value == null }
        assertEquals(listOf(MOVED, MOVED, POSITIONED, LEADING), stored())
        library.renamePlaylist(playlist, "服务器歌单不可改名")
        compose.waitUntil(TIMEOUT) { library.error.value == null }
        assertEquals("待重命名", storedName())
        assertEquals(emptySet<String>(), currentPlaylistControls())
    }

    // ---- helpers -------------------------------------------------------------

    /**
     * Persisted state, read back through the DAO rather than from the UI. These block a background
     * dispatcher rather than being `suspend` because `ComposeTestRule.waitUntil` takes a plain
     * predicate, so the condition it polls has to be callable from it.
     */
    private fun stored(): List<String> = runBlocking(Dispatchers.IO) { database.library().playlistEntries(playlist).map { it.trackUri } }

    private fun entries(): List<PlaylistEntry> = runBlocking(Dispatchers.IO) { database.library().playlistEntries(playlist) }

    private fun storedName(): String? = runBlocking(Dispatchers.IO) { database.library().getPlaylist(playlist)?.name }

    private fun assertContiguous() {
        val positions = entries().map { it.position }
        assertEquals("positions must be a no-gap sequence", positions.indices.toList(), positions)
    }

    /** Which rename/reorder affordances are on screen while the library is on its list page. */
    private fun currentPlaylistControls(): Set<String> = listOf("rename_playlist_$playlist", "playlist_up_0", "playlist_down_0")
        .filter { compose.onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty() }.toSet()

    private fun track(uri: String) = Track(uri, uri.substringAfterLast('/'), "测试艺术家", "测试专辑", "测试目录", 6_000, null, "wav", TrackSource.DOCUMENT, null)

    private companion object {
        const val LEADING = "file:///lusound-test/playlist-editing/leading.wav"
        const val MOVED = "file:///lusound-test/playlist-editing/moved.wav"
        const val POSITIONED = "file:///lusound-test/playlist-editing/positioned.wav"
        const val TIMEOUT = 10_000L
    }
}
