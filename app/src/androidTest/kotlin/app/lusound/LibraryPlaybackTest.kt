package app.lusound

import android.Manifest
import android.content.ContentValues
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.lusound.library.LibraryDatabase
import app.lusound.library.scanMediaStore
import app.lusound.library.replaceMediaLibrary
import app.lusound.playback.PlaybackService
import app.lusound.playback.toMediaItem
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real media provider, SQLite and decoder: no mocked integrations. */
@RunWith(AndroidJUnit4::class)
class LibraryPlaybackTest {
    @get:Rule(order = 0) val permissions = GrantPermissionRule.grant(Manifest.permission.READ_MEDIA_AUDIO)

    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun scanPersistPlaySeekAndPause() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "lusound-integration.wav")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/LuSoundTest")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values))
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        var controller: MediaController? = null
        try {
            checkNotNull(resolver.openOutputStream(uri)).use { it.write(wav()) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            val scanned = scanMediaStore(context)
            assertTrue("MediaStore WAV must be scanned", scanned.tracks.any { it.uri == uri.toString() })
            replaceMediaLibrary(database, scanned, context)
            replaceMediaLibrary(database, scanned, context)
            val tracks = database.library().getTracks()
            assertEquals(1, tracks.count { it.uri == uri.toString() })
            val track = tracks.single { it.uri == uri.toString() }
            val playlist = database.library().insertPlaylist(app.lusound.library.Playlist(0, "Integration"))
            database.library().insertEntry(app.lusound.library.PlaylistEntry(playlist, track.uri))
            assertEquals(listOf(track.uri), database.library().playlistTrackUris(playlist))
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
            instrumentation.runOnMainSync {
                future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
            }
            controller = future.get(15, TimeUnit.SECONDS)
            val player = controller
            instrumentation.runOnMainSync {
                player.setMediaItems(listOf(toMediaItem(track), toMediaItem(track)), 0, 0)
                player.prepare()
                player.play()
            }
            var playing = false
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (!playing && System.nanoTime() < deadline) {
                instrumentation.runOnMainSync { playing = player.isPlaying }
                Thread.sleep(100)
            }
            assertTrue("Real decoder should start audio", playing)
            instrumentation.runOnMainSync {
                assertEquals(2, player.mediaItemCount)
                player.seekTo(2000)
                player.pause()
                assertFalse(player.playWhenReady)
                assertTrue(player.currentPosition >= 2000)
                player.seekToNextMediaItem()
                assertEquals(1, player.currentMediaItemIndex)
            }
            compose.waitUntil(10000) { compose.onAllNodesWithTag("mini_player").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("mini_player").performClick()
            compose.onNodeWithTag("player_screen").assertIsDisplayed()
            compose.onNodeWithTag("seek").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(1000f) }
            compose.onNodeWithTag("open_queue").performScrollTo().performClick()
            compose.onNodeWithTag("queue_0").performClick()
            compose.onNodeWithTag("close_player").performScrollTo().performClick()
            compose.onNodeWithTag("mini_player").assertIsDisplayed()
            instrumentation.runOnMainSync { player.pause() }
            // A successful scan without a detached volume must not erase its playlist members.
            replaceMediaLibrary(database, app.lusound.library.MediaLibrarySnapshot(emptyList(), emptySet()), context)
            assertEquals(listOf(track.uri), database.library().playlistTrackUris(playlist))
            replaceMediaLibrary(database, scanned, context)
            assertEquals(listOf(track.uri), database.library().playlistTrackUris(playlist))
            // Missing media on a still-mounted volume is an actual deletion.
            replaceMediaLibrary(database, app.lusound.library.MediaLibrarySnapshot(emptyList(), scanned.mountedVolumes), context)
            assertTrue(database.library().getTracks().isEmpty())
            assertTrue(database.library().playlistTrackUris(playlist).isEmpty())
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { controller?.release() }
            database.close()
            resolver.delete(uri, null, null)
        }
    }

    private fun wav(): ByteArray {
        val samples = 44100 * 6
        val buffer = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
        buffer.putInt(16).putShort(1).putShort(1).putInt(44100).putInt(88200).putShort(2).putShort(16)
        buffer.put("data".toByteArray()).putInt(samples * 2)
        repeat(samples) { buffer.putShort((kotlin.math.sin(it * 2 * Math.PI * 440 / 44100) * 1000).toInt().toShort()) }
        return buffer.array()
    }
}
