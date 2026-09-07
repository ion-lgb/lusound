package app.lusound

import android.content.Context
import org.junit.Assert.*
import app.lusound.library.Track
import app.lusound.playback.PlaybackService
import app.lusound.playback.toMediaItem

internal fun screenshot(context: Context, name: String) {
        // Android dialog window animations run outside the Compose test clock.
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(700)
        val bitmap = requireNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        java.io.File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

internal fun playRealStream(app: LuSoundApplication, track: Track) {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController>
        instrumentation.runOnMainSync {
            future = androidx.media3.session.MediaController.Builder(app, androidx.media3.session.SessionToken(app,
                android.content.ComponentName(app, PlaybackService::class.java))).buildAsync()
        }
        val controller = future.get(15, java.util.concurrent.TimeUnit.SECONDS)
        val local = java.io.File(app.cacheDir, "mixed-queue-test.wav").apply { writeBytes(testWav()) }
        try {
            instrumentation.runOnMainSync {
                controller.setMediaItems(listOf(toMediaItem(track), androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(local))))
                controller.prepare()
                controller.play()
            }
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20)
            var playing = false
            while (!playing && System.nanoTime() < deadline) {
                instrumentation.runOnMainSync {
                    assertNull("Stream must decode without errors", controller.playerError)
                    playing = controller.isPlaying
                }
                Thread.sleep(100)
            }
            assertTrue("Authenticated Navidrome stream must play", playing)
            instrumentation.runOnMainSync {
                controller.seekTo(3000)
                controller.pause()
                assertTrue(controller.currentPosition >= 3000)
                controller.seekToNextMediaItem()
                controller.play()
            }
            val localDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
            var localPlaying = false
            while (!localPlaying && System.nanoTime() < localDeadline) {
                instrumentation.runOnMainSync {
                    assertNull(controller.playerError)
                    localPlaying = controller.currentMediaItemIndex == 1 && controller.isPlaying
                }
                Thread.sleep(100)
            }
            assertTrue("Mixed queue must transition from cloud to local audio", localPlaying)
        } finally { instrumentation.runOnMainSync { controller.stop(); controller.clearMediaItems(); controller.release() }; local.delete() }
    }
