package app.lusound

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lusound.library.Track
import app.lusound.metadata.*
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** External user-provided FLACs and real LRCLIB/MusicBrainz/CAA; no lyrics or audio fixtures in the APK. */
@RunWith(AndroidJUnit4::class)
class MetadataIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun readEmbeddedAndAutomaticallyFetchMissingMetadata(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LuSoundApplication
        val sources = listOf("standard-embedded.flac", "standard-missing.flac").map { File(app.getExternalFilesDir(null), it) }
        sources.forEach { check(it.isFile) { "Push the two standard FLAC samples to app external files before running this integration test" } }
        val hashes = sources.map { MessageDigest.getInstance("SHA-256").digest(it.readBytes()) }
        val tracks = sources.map { file ->
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(file.absolutePath)
                Track(Uri.fromFile(file).toString(), requireNotNull(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)),
                    requireNotNull(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)), requireNotNull(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)),
                    "外部验证样本", requireNotNull(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong(), null, "flac", "DOCUMENT")
            } finally { reader.release() }
        }
        val enabled = metadataEnabled(app)
        try {
            setMetadataEnabled(app, false)
            app.database.library().upsertTracks(tracks)
            val embedded = app.metadata.local(tracks[0])
            assertEquals("文件内嵌", embedded.lyricsSource)
            assertNotNull(embedded.coverUri)
            assertTrue(parseLyrics(requireNotNull(embedded.lyrics)).size > 20)
            val missing = app.metadata.local(tracks[1])
            assertNull(missing.coverUri)
            assertNull(missing.lyrics)
            setMetadataEnabled(app, true) // Actual WorkManager network-constrained automatic lookup.
            val matched = withTimeout(180000) {
                var value = app.database.metadata().get(tracks[1].uri)
                while (value == null || value.checkedAt == 0L) { delay(500); value = app.database.metadata().get(tracks[1].uri) }
                value
            }
            assertNull("Metadata lookup failed: ${matched.error}", matched.error)
            assertEquals("LRCLIB", matched.lyricsSource)
            assertEquals("Cover Art Archive", matched.coverSource)
            assertTrue(parseLyrics(requireNotNull(matched.lyrics)).size > 20)
            assertTrue(File(requireNotNull(Uri.parse(matched.coverUri).path)).length() > 1000)
            setMetadataEnabled(app, false)
            app.database.library().upsertTracks(tracks) // A rescan must preserve the enrichment cache.
            assertEquals(matched, app.metadata.local(tracks[1]))
            app.database.metadata().invalidate(tracks[0].uri)
            assertEquals(embedded.lyrics, app.metadata.local(tracks[0]).lyrics)
            app.database.metadata().save(embedded.copy(sourceRevision = "outdated", lyrics = "[00:01]stale", lyricsSource = "LRCLIB"))
            assertEquals(embedded.lyrics, app.metadata.local(tracks[0]).lyrics)
            playRealStream(app, tracks[0])
            playRealStream(app, tracks[1])
            compose.waitUntil(10000) { compose.onAllNodesWithTag("track_${tracks[1].uri}").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("track_${tracks[1].uri}").performScrollTo().performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("mini_player").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("mini_player").performClick()
            compose.onNodeWithTag("player_screen").assertIsDisplayed()
            compose.waitForIdle()
            screenshot(app, "metadata-player.png")
            compose.onNodeWithTag("open_lyrics").performScrollTo().performClick()
            compose.onNodeWithTag("lyrics_list").assertIsDisplayed()
            compose.waitForIdle()
            screenshot(app, "metadata-lyrics.png")
            compose.onNodeWithTag("lyric_1").performScrollTo().performClick()
            app.database.metadata().invalidate(tracks[1].uri)
            val cleared = requireNotNull(app.database.metadata().get(tracks[1].uri))
            assertNull(cleared.lyrics)
            assertNull(cleared.coverUri)
            assertEquals(0L, cleared.checkedAt)
            for (index in sources.indices) assertArrayEquals(hashes[index], MessageDigest.getInstance("SHA-256").digest(sources[index].readBytes()))
        } finally {
            setMetadataEnabled(app, false)
            app.database.library().deleteTracks(tracks.map { it.uri })
            if (enabled) setMetadataEnabled(app, true)
        }
    }

    @Test fun timestampsAndCandidateMatchingRejectOtherVersions() {
        val lines = parseLyrics("[offset:500]\n[00:01.20][00:03.456]line one\n[00:05]line two")
        assertEquals(listOf(1700L, 3956L, 5500L), lines.map { it.timeMs })
        assertEquals(-1, currentLyricIndex(lines, 0))
        assertEquals(1, currentLyricIndex(lines, 3956))
        assertNull(usableLyrics("[00:00.00]暂无歌词"))
        val track = Track("file:///test.flac", "布拉格广场", "蔡依林 / 周杰伦", "看我72变", "Music", 294600, null, "flac", "DOCUMENT")
        val correct = LrcRecord(1, "布拉格廣場", "蔡依林&周杰倫", "看我72變", 295.0, false, null, "[00:01.00]test")
        assertEquals(correct, matchLyrics(track, listOf(correct.copy(id = 2, artistName = "另一位歌手"), correct.copy(id = 3, duration = 192.0), correct)))
    }
}
