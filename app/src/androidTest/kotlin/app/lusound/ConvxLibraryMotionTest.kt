package app.lusound

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lusound.ncm.readNcmTrack
import app.lusound.ui.albumKey
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the external encrypted track and real Room library to exercise Convx navigation and focus. */
@RunWith(AndroidJUnit4::class)
class ConvxLibraryMotionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun albumSearchAndSettingsTransitionsRemainInteractive(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as LuSoundApplication
        val sample = File(app.getExternalFilesDir(null), "ncm-sample.ncm")
        check(sample.isFile) { "Push the encrypted fixture to external files/ncm-sample.ncm before ConvxLibraryMotionTest" }
        val track = readNcmTrack(app, Uri.fromFile(sample))
        val albumTracks = (1..12).map { number ->
            track.copy(
                uri = Uri.fromFile(sample).buildUpon().fragment("convx-library-motion-$number").build().toString(),
                album = "Convx motion ${number.toString().padStart(2, '0')}",
            )
        }
        val fixtureUris = (listOf(track) + albumTracks).map { it.uri }
        val previous = app.database.library().getTracks().filter { it.uri in fixtureUris }
        val insertedUris = fixtureUris.filter { uri -> previous.none { it.uri == uri } }
        try {
            app.database.library().upsertTracks(listOf(track))
            compose.waitUntil(10000) { compose.onAllNodesWithTag("track_${track.uri}").fetchSemanticsNodes().isNotEmpty() }
            app.database.library().upsertTracks(albumTracks)
            compose.onNodeWithTag("tab_ALBUMS").performScrollTo().performClick()
            compose.waitUntil(10000) {
                compose.onAllNodesWithTag("group_ALBUMS_${albumKey(albumTracks.first())}").fetchSemanticsNodes().isNotEmpty()
            }
            val lastAlbum = albumTracks.last()
            val groupTag = "group_ALBUMS_${albumKey(lastAlbum)}"
            val artworkTag = "group_art_ALBUMS_${albumKey(lastAlbum)}"
            compose.onNodeWithTag("library_list").performScrollToNode(hasTestTag(groupTag))
            val tile = compose.onNodeWithTag(artworkTag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("Album artwork must have measurable bounds", tile.width > 0f && tile.height > 0f)
            compose.onNodeWithTag("group_ALBUMS_${albumKey(albumTracks.first())}").assertIsNotDisplayed()

            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag(groupTag).performClick()
            compose.mainClock.advanceTimeBy(160L)
            screenshot(app, "visual-album-push-midpoint.png")
            compose.mainClock.advanceTimeBy(1500L)
            compose.onNodeWithTag("play_group").assertIsDisplayed()
            val hero = compose.onNodeWithTag("group_hero_art", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("The shared album cover must expand into the detail hero", hero.width > tile.width && hero.height > tile.height)
            compose.onNodeWithTag("group_back").performClick()
            compose.mainClock.advanceTimeBy(160L)
            screenshot(app, "visual-album-pop-midpoint.png")
            compose.mainClock.advanceTimeBy(1500L)
            compose.onNodeWithTag("group_hero_art", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag(groupTag).assertIsDisplayed()
            val restored = compose.onNodeWithTag(artworkTag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("Returning must preserve the offscreen album grid's original artwork position: $tile -> $restored",
                abs(restored.left - tile.left) < 2f && abs(restored.top - tile.top) < 2f &&
                    abs(restored.right - tile.right) < 2f && abs(restored.bottom - tile.bottom) < 2f)
            compose.mainClock.autoAdvance = true

            // The standalone button owns each focus request; changing category must consume it.
            compose.onNodeWithTag("nav_search").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("search").assertIsFocused().performTextInput(track.title)
            compose.onNodeWithTag("tab_ALBUMS").performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("library_list").performScrollToNode(hasTestTag("search"))
            compose.onNodeWithTag("search").assertIsNotFocused().assertTextContains(track.title)
            compose.onNodeWithTag("tab_SONGS").performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("search").assertIsNotFocused()
            compose.onNodeWithTag("nav_search").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("search").assertIsFocused()

            // Clicking the selected tab also expands an inline navigation bar after scrolling.
            compose.onNodeWithTag("nav_library").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("nav_settings").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("settings_large_title").assertIsDisplayed()
            val expandedBack = compose.onNodeWithTag("settings").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("settings_license").performScrollTo().assertIsDisplayed()
            compose.waitForIdle()
            compose.onNodeWithTag("settings_large_title").assertIsNotDisplayed()
            compose.onNodeWithTag("settings_compact_title").assertIsDisplayed()
            val collapsedBack = compose.onNodeWithTag("settings").fetchSemanticsNode().boundsInRoot
            assertTrue("Settings chrome must move upward as the large title scrolls away", collapsedBack.top < expandedBack.top)
            screenshot(app, "visual-settings-collapsed.png")
            compose.onNodeWithTag("nav_settings").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("nav_library").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("library_list").assertIsDisplayed()
            compose.onNodeWithTag("settings_list").assertDoesNotExist()
            compose.onNodeWithTag("search").assertIsNotFocused()
        } finally {
            compose.mainClock.autoAdvance = true
            app.database.library().deleteTracks(insertedUris)
            app.database.library().upsertTracks(previous)
        }
    }
}
