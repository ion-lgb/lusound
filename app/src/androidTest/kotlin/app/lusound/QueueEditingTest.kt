package app.lusound

import android.content.ComponentName
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lusound.playback.PlaybackService
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QueueEditingTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun reorderAndRemoveActualPlayerQueue() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val audio = File(context.cacheDir, "queue-editing.wav")
        audio.writeBytes(testWav())
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
        instrumentation.runOnMainSync {
            future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        }
        val controller = future.get(15, TimeUnit.SECONDS)
        try {
            instrumentation.runOnMainSync {
                controller.setMediaItems(listOf("first", "second", "third").map { id ->
                    MediaItem.Builder().setMediaId(id).setUri(android.net.Uri.fromFile(audio))
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(id).build()).build()
                }, 0, 1500)
                controller.prepare()
                controller.pause()
            }
            compose.waitUntil(10000) { compose.onAllNodesWithTag("mini_player").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("mini_player").performClick()
            compose.onNodeWithTag("open_queue").performScrollTo().performClick()
            compose.onNodeWithTag("queue_up_0").assertIsNotEnabled()
            compose.onNodeWithTag("queue_down_2").assertIsNotEnabled()
            compose.onNodeWithTag("queue_down_0").performClick()
            compose.waitForIdle()
            instrumentation.runOnMainSync {
                assertEquals("first", controller.currentMediaItem?.mediaId)
                assertEquals(1, controller.currentMediaItemIndex)
                assertTrue(controller.currentPosition >= 1500)
                assertFalse(controller.playWhenReady)
                assertEquals(listOf("second", "first", "third"), (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId })
            }
            compose.onNodeWithTag("queue_up_1").performClick()
            compose.waitForIdle()
            instrumentation.runOnMainSync { assertEquals(0, controller.currentMediaItemIndex) }
            screenshot(context, "queue-editing.png")
            compose.onNodeWithTag("queue_remove_2").performClick()
            compose.waitForIdle()
            instrumentation.runOnMainSync { assertEquals(2, controller.mediaItemCount); assertEquals("first", controller.currentMediaItem?.mediaId) }
            instrumentation.runOnMainSync { controller.seekTo(0); controller.play() }
            compose.waitUntil(10000) {
                var playing = false
                instrumentation.runOnMainSync { playing = controller.isPlaying }
                playing
            }
            compose.onNodeWithTag("queue_remove_0").performClick()
            compose.waitForIdle()
            instrumentation.runOnMainSync { assertEquals("second", controller.currentMediaItem?.mediaId); assertTrue(controller.playWhenReady) }
            compose.onNodeWithTag("queue_remove_0").performClick()
            compose.waitForIdle()
            instrumentation.runOnMainSync { assertEquals(0, controller.mediaItemCount); assertFalse(controller.isPlaying) }
            compose.onNodeWithTag("queue_empty").assertIsDisplayed()
            instrumentation.runOnMainSync {
                val duplicate = MediaItem.Builder().setMediaId("duplicate").setUri(android.net.Uri.fromFile(audio)).build()
                controller.setMediaItems(listOf(duplicate, duplicate), 1, 1000)
                controller.prepare()
                controller.pause()
            }
            compose.waitForIdle()
            compose.onNodeWithTag("queue_up_1").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("queue_remove_1").performClick()
            compose.waitForIdle()
            instrumentation.runOnMainSync {
                assertEquals(1, controller.mediaItemCount)
                assertEquals(0, controller.currentMediaItemIndex)
                assertEquals("duplicate", controller.currentMediaItem?.mediaId)
                assertTrue(controller.currentPosition >= 1000)
            }
        } finally {
            instrumentation.runOnMainSync { controller.clearMediaItems(); controller.release() }
            audio.delete()
        }
    }
}
