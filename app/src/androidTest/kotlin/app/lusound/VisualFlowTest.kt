package app.lusound

import android.content.ComponentName
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lusound.ncm.readNcmTrack
import app.lusound.playback.PlaybackService
import app.lusound.playback.toMediaItem
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Captures real library → player → queue → settings → connection flows with the external NCM sample. */
@RunWith(AndroidJUnit4::class)
class VisualFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun captureConnectedScreens(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as LuSoundApplication
        val sample = File(app.getExternalFilesDir(null), "ncm-sample.ncm")
        check(sample.isFile) { "Push the encrypted sample to app external files/ncm-sample.ncm before this visual test" }
        val track = readNcmTrack(app, Uri.fromFile(sample))
        val previous = app.database.library().getTracks().firstOrNull { it.uri == track.uri }
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
        instrumentation.runOnMainSync {
            future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
        }
        val controller = future.get(15, TimeUnit.SECONDS)
        try {
            app.database.library().upsertTracks(listOf(track))
            compose.waitUntil(10000) { compose.onAllNodesWithTag("track_${track.uri}").fetchSemanticsNodes().isNotEmpty() }
            instrumentation.runOnMainSync {
                controller.setMediaItems(listOf(toMediaItem(track), toMediaItem(track)))
                controller.prepare()
                controller.pause()
            }
            compose.waitUntil(10000) { compose.onAllNodesWithTag("mini_player").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            screenshot(app, "visual-library.png")
            compose.onNodeWithTag("mini_player").performClick()
            compose.onNodeWithTag("player_screen").assertIsDisplayed()
            compose.waitForIdle()
            screenshot(app, "visual-player.png")
            compose.onNodeWithTag("open_queue").performScrollTo().performClick()
            compose.onNodeWithTag("queue_list").assertIsDisplayed()
            compose.waitForIdle()
            screenshot(app, "visual-queue.png")
            compose.onNodeWithTag("close_queue").performClick()
            compose.onNodeWithTag("close_player").performScrollTo().performClick()
            compose.onNodeWithTag("nav_settings").performClick()
            compose.onNodeWithTag("add_server").assertIsDisplayed()
            compose.waitForIdle()
            screenshot(app, "visual-settings.png")
            compose.onNodeWithTag("add_server").performClick()
            compose.onNodeWithTag("server_name").assertIsDisplayed()
            compose.waitForIdle()
            screenshot(app, "visual-server.png")
            compose.onNodeWithTag("server_name").performTextInput("家里的音乐")
            compose.onNodeWithTag("server_url").performScrollTo().performTextInput("https://music.example.com/")
            compose.onNodeWithTag("server_password").performScrollTo().performTextInput("visual-test-only")
            compose.waitForIdle()
            compose.onNodeWithTag("server_password").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("save_server").assertIsDisplayed()
            compose.waitForIdle()
            screenshot(app, "visual-keyboard.png")
        } finally {
            instrumentation.runOnMainSync { controller.stop(); controller.clearMediaItems(); controller.release() }
            if (previous == null) app.database.library().deleteTracks(listOf(track.uri))
            else app.database.library().upsertTracks(listOf(previous))
        }
    }
}
