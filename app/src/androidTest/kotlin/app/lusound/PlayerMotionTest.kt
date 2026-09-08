package app.lusound

import android.content.ComponentName
import android.net.Uri
import android.media.AudioManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lusound.ncm.ncmPlaybackUri
import app.lusound.ncm.readNcmTrack
import app.lusound.ui.PlayerMorphSourceBounds
import app.lusound.ui.PlayerMorphTargetBounds
import app.lusound.ui.PlayerMorphArtworkBounds
import com.convx.music.ui.player.sharedArtworkRect
import androidx.compose.ui.geometry.Rect
import app.lusound.playback.PlaybackService
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the measured player with the external encrypted fixture and the real service. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerMotionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun miniAndArtworkGesturesReverseWithoutRestartingPlayback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as LuSoundApplication
        val sample = File(app.getExternalFilesDir(null), "ncm-sample.ncm")
        check(sample.isFile) { "Push the encrypted test fixture to external files/ncm-sample.ncm before running PlayerMotionTest" }
        val track = readNcmTrack(app, Uri.fromFile(sample))
        val artwork = requireNotNull(track.artworkUri) { "The encrypted NCM fixture must contain its original album artwork" }
        lateinit var future: ListenableFuture<MediaController>
        instrumentation.runOnMainSync {
            future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
        }
        val controller = future.get(15, TimeUnit.SECONDS)
        try {
            instrumentation.runOnMainSync {
                controller.setMediaItem(MediaItem.Builder().setMediaId(sample.toURI().toString())
                    .setUri(ncmPlaybackUri(Uri.fromFile(sample).toString()))
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).setArtworkUri(Uri.parse(artwork)).build()).build())
                controller.prepare()
                controller.play()
            }
            compose.waitUntil(15000) {
                var playing = false
                instrumentation.runOnMainSync { assertNull(controller.playerError); playing = controller.isPlaying }
                playing
            }
            compose.waitUntil(10000) { compose.onAllNodesWithTag("mini_player").fetchSemanticsNodes().size == 1 }
            val miniBounds = compose.onNodeWithTag("mini_player").fetchSemanticsNode().boundsInRoot
            assertTrue(miniBounds.width > 0f && miniBounds.height > 0f)
            var initialPosition = 0L
            instrumentation.runOnMainSync { controller.seekTo(3000); initialPosition = controller.currentPosition }
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("mini_player").performTouchInput {
                down(center)
                moveBy(Offset(0f, -180f), 160L)
            }
            compose.mainClock.advanceTimeByFrame()
            val opening = progress()
            assertTrue("Upward drag must expose an intermediate shared-container progress: $opening", opening > 0f && opening < 1f)
            val firstFrameArtwork = compose.onNodeWithTag("player_shared_artwork").fetchSemanticsNode().config
            assertTrue("First morph frame must already have a measured artwork target", !firstFrameArtwork[PlayerMorphTargetBounds].isEmpty)
            assertTrue("First morph frame must already draw a positive artwork rectangle", !firstFrameArtwork[PlayerMorphArtworkBounds].isEmpty)
            repeat(3) { compose.mainClock.advanceTimeByFrame() }
            assertEquals("Holding the finger must not advance sheet progress", opening, progress(), 0.001f)
            // The upstream press spring scales the mini bar after finger-down.
            val miniArtworkBounds = compose.onNodeWithTag("mini_artwork", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val artworkSemantics = compose.onNodeWithTag("player_shared_artwork").fetchSemanticsNode().config
            assertRectNear(miniArtworkBounds, artworkSemantics[PlayerMorphSourceBounds])
            val measuredTarget = compose.onNodeWithTag("player_artwork").fetchSemanticsNode().boundsInRoot
            assertRectNear(measuredTarget, artworkSemantics[PlayerMorphTargetBounds])
            assertRectNear(sharedArtworkRect(miniArtworkBounds, measuredTarget, opening), artworkSemantics[PlayerMorphArtworkBounds])
            screenshot(app, "visual-player-opening-midpoint.png")
            compose.onNodeWithTag("mini_player").performTouchInput { moveBy(Offset(0f, 90f), 160L) }
            compose.mainClock.advanceTimeByFrame()
            assertTrue("Reversing the same finger must reduce progress", progress() < opening)
            compose.onNodeWithTag("mini_player").performTouchInput { up() }
            compose.mainClock.advanceTimeBy(1500L)
            assertEquals(0f, progress(), 0.001f)
            compose.onNodeWithTag("player_screen").assertDoesNotExist()
            compose.onNodeWithTag("mini_player").performClick()
            compose.mainClock.advanceTimeBy(1500L)
            assertEquals(1f, progress(), 0.001f)
            screenshot(app, "visual-player-expanded.png")
            val fullBounds = compose.onNodeWithTag("player_screen").fetchSemanticsNode().boundsInRoot
            val artBounds = compose.onNodeWithTag("player_artwork").fetchSemanticsNode().boundsInRoot
            assertTrue(fullBounds.height > miniBounds.height)
            assertTrue(artBounds.width > miniBounds.height)
            assertTrue(fullBounds.contains(artBounds.center))
            assertRectNear(artBounds, compose.onNodeWithTag("player_shared_artwork").fetchSemanticsNode().config[PlayerMorphArtworkBounds])
            compose.onNodeWithTag("player_artwork").performTouchInput {
                down(center)
                moveBy(Offset(0f, 180f), 160L)
            }
            compose.mainClock.advanceTimeByFrame()
            val closing = progress()
            assertTrue("Artwork drag must shrink the same container", closing > 0f && closing < 1f)
            compose.onNodeWithTag("player_artwork").performTouchInput { moveBy(Offset(0f, -90f), 160L) }
            compose.mainClock.advanceTimeByFrame()
            assertTrue("Reversed collapse must expand continuously", progress() > closing)
            compose.onNodeWithTag("player_artwork").performTouchInput { up() }
            compose.mainClock.advanceTimeBy(1500L)
            assertEquals(1f, progress(), 0.001f)
            compose.onNodeWithTag("close_player").performClick()
            compose.mainClock.advanceTimeBy(1500L)
            compose.onNodeWithTag("player_screen").assertDoesNotExist()
            instrumentation.runOnMainSync {
                assertTrue(controller.isPlaying)
                assertNull(controller.playerError)
                assertTrue("Morphing must preserve playback position", controller.currentPosition >= initialPosition)
                assertEquals(sample.toURI().toString(), controller.currentMediaItem?.mediaId)
            }
        } finally {
            compose.mainClock.autoAdvance = true
            instrumentation.runOnMainSync { controller.stop(); controller.clearMediaItems(); controller.release() }
        }
    }

    @Test fun queueCarouselTracksRealPlaybackAndVolumeRestoresSystemState() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as LuSoundApplication
        val sample = File(app.getExternalFilesDir(null), "ncm-sample.ncm")
        check(sample.isFile) { "Push the encrypted test fixture to external files/ncm-sample.ncm before running PlayerMotionTest" }
        val audio = requireNotNull(app.getSystemService(AudioManager::class.java))
        check(!audio.isVolumeFixed) { "Player volume integration test requires an Android device with adjustable music-stream volume" }
        val originalVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maximumVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        lateinit var future: ListenableFuture<MediaController>
        instrumentation.runOnMainSync {
            future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
        }
        val controller = future.get(15, TimeUnit.SECONDS)
        try {
            instrumentation.runOnMainSync {
                controller.setMediaItems((0..1).map { index ->
                    MediaItem.Builder().setMediaId("player-carousel-$index")
                        .setUri(ncmPlaybackUri(Uri.fromFile(sample).toString()))
                        .setMediaMetadata(MediaMetadata.Builder().setTitle("Carousel fixture $index").setArtist("LuSound").build()).build()
                })
                controller.prepare()
                controller.play()
            }
            compose.waitUntil(15000) {
                var playing = false
                instrumentation.runOnMainSync { assertNull(controller.playerError); playing = controller.isPlaying }
                playing
            }
            compose.waitUntil(10000) { compose.onAllNodesWithTag("mini_player").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithTag("mini_player").performClick()
            compose.onNodeWithTag("player_carousel").performTouchInput { swipeLeft(durationMillis = 400) }
            compose.waitUntil(10000) {
                var index = -1
                instrumentation.runOnMainSync { index = controller.currentMediaItemIndex }
                index == 1
            }
            compose.onNodeWithTag("player_title").assertTextEquals("Carousel fixture 1")
            compose.onNodeWithTag("previous").performScrollTo().performClick()
            compose.waitUntil(10000) {
                var index = -1
                instrumentation.runOnMainSync { index = controller.currentMediaItemIndex }
                index == 0
            }
            compose.onNodeWithTag("player_title").assertTextEquals("Carousel fixture 0")
            // Verify actual Android audio state, then exercise its broadcast -> slider path.
            val selectedVolume = if (originalVolume > 0) originalVolume - 1 else 1
            compose.onNodeWithTag("player_volume").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) {
                it(selectedVolume.toFloat() / maximumVolume)
            }
            compose.waitUntil(5000) { audio.getStreamVolume(AudioManager.STREAM_MUSIC) == selectedVolume }
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
            compose.waitUntil(5000) {
                val shown = compose.onNodeWithTag("player_volume").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
                kotlin.math.abs(shown - originalVolume.toFloat() / maximumVolume) < 0.01f
            }
            compose.onNodeWithTag("open_lyrics").performScrollTo().performClick()
            compose.onNodeWithTag("close_lyrics").assertExists().performClick()
            compose.onNodeWithTag("open_lyrics").assertExists()
            instrumentation.runOnMainSync { assertEquals(0, controller.currentMediaItemIndex); assertNull(controller.playerError) }
        } finally {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
            instrumentation.runOnMainSync { controller.stop(); controller.clearMediaItems(); controller.release() }
        }
    }

    private fun assertRectNear(expected: Rect, actual: Rect) {
        assertEquals(expected.left, actual.left, 1f)
        assertEquals(expected.top, actual.top, 1f)
        assertEquals(expected.right, actual.right, 1f)
        assertEquals(expected.bottom, actual.bottom, 1f)
    }

    private fun progress(): Float = compose.onNodeWithTag("player_transition").fetchSemanticsNode()
        .config[SemanticsProperties.ProgressBarRangeInfo].current
}
