package app.lusound

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lusound.library.Track
import app.lusound.library.TrackSource
import app.lusound.library.scanDocumentTree
import app.lusound.metadata.*
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * External user-provided FLACs, an MP3 with a `USLT` frame, an M4A with `©lyr`, and real
 * LRCLIB/MusicBrainz/CAA; no lyrics or audio fixtures in the APK.
 *
 * A runner must push the samples named in each test into the app's external files directory first.
 * Tests that need only the two standard FLACs say so; the sidecar cases write their own `.lrc`.
 */
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
                    "外部验证样本", requireNotNull(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong(), null, "flac", TrackSource.DOCUMENT, null)
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

    /**
     * E01 on a plain file: `sidecar-sample.lrc` next to `sidecar-sample.flac`.
     *
     * Needs `standard-missing.flac` (the FLAC without lyrics tags) pushed to the app external files
     * directory; the sidecar is written by the test itself. The audio is copied so that the other
     * cases keep seeing the untouched sample.
     */
    @Test fun readSidecarLrcNextToTheAudio(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LuSoundApplication
        val directory = requireNotNull(app.getExternalFilesDir(null))
        val source = File(directory, "standard-missing.flac")
        check(source.isFile) { "Push standard-missing.flac (a FLAC without lyrics tags) to app external files before running this integration test" }
        val audio = File(directory, "sidecar-sample.flac")
        source.copyTo(audio, overwrite = true)
        val sidecar = File(directory, "sidecar-sample.lrc")
        val lyrics = "[ar:测试]\n[00:01.00]旁置第一句\n[00:03.50]旁置第二句"
        sidecar.writeText(lyrics)
        val track = readExternalTrack(audio, "flac")
        val enabled = metadataEnabled(app)
        try {
            setMetadataEnabled(app, false)
            app.database.library().upsertTracks(listOf(track))
            val local = app.metadata.local(track)
            assertEquals("旁置 LRC", local.lyricsSource)
            assertEquals(Uri.fromFile(sidecar).toString(), local.lyricsUrl)
            assertEquals(listOf("旁置第一句", "旁置第二句"), parseLyrics(requireNotNull(local.lyrics)).map { it.text })
            // An explicit refresh drops the sidecar source so the file is read again, not the cache.
            app.database.metadata().invalidate(track.uri)
            assertNull(requireNotNull(app.database.metadata().get(track.uri)).lyrics)
            assertEquals(listOf("旁置第一句", "旁置第二句"), parseLyrics(requireNotNull(app.metadata.local(track).lyrics)).map { it.text })
            // A sidecar that is gone is not an error, and no stale lyrics survive a refresh.
            assertTrue(sidecar.delete())
            app.database.metadata().invalidate(track.uri)
            assertNull(app.metadata.local(track).lyrics)
            // A sidecar that exists but cannot be read is reported instead of being called "no lyrics".
            sidecar.writeBytes(ByteArray(2 * 1024 * 1024 + 1) { 'a'.code.toByte() })
            app.database.metadata().invalidate(track.uri)
            try {
                app.metadata.local(track)
                fail("An unreadable sidecar must surface as an error")
            } catch (expected: IOException) {
                assertTrue(expected.message.orEmpty().contains("旁置歌词文件超过"))
            }
        } finally {
            setMetadataEnabled(app, false)
            app.database.library().deleteTracks(listOf(track.uri))
            sidecar.delete()
            audio.delete()
            if (enabled) setMetadataEnabled(app, true)
        }
    }

    /**
     * E01 through SAF: the sibling directory of an authorised document tree.
     *
     * Needs no pushed sample: the audio is a generated WAV inside a directory this test creates
     * through Android's real external-storage document provider.
     */
    @Test fun discoverSidecarInsideAnAuthorisedDocumentTree(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context as LuSoundApplication
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS")
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Music")
        val root = checkNotNull(DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, "LuSoundLyrics-${System.nanoTime()}"))
        val tree = DocumentsContract.buildTreeDocumentUri(root.authority, DocumentsContract.getDocumentId(root))
        val lyrics = "[00:01.00]文件夹旁置歌词一\n[00:02.50]文件夹旁置歌词二"
        var trackUri: String? = null
        try {
            val audio = checkNotNull(DocumentsContract.createDocument(resolver, root, "audio/wav", "song.wav"))
            checkNotNull(resolver.openOutputStream(audio)).use { it.write(testWav()) }
            val sidecar = checkNotNull(DocumentsContract.createDocument(resolver, root, "text/plain", "song.lrc"))
            checkNotNull(resolver.openOutputStream(sidecar)).use { it.write(lyrics.toByteArray()) }
            context.grantUriPermission(context.packageName, tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            automation.dropShellPermissionIdentity()
            val snapshot = scanDocumentTree(context, tree)
            val track = snapshot.tracks.single()
            trackUri = track.uri
            app.database.library().upsertTracks(snapshot.tracks)
            val local = app.metadata.local(track)
            assertEquals("旁置 LRC", local.lyricsSource)
            assertEquals(listOf("文件夹旁置歌词一", "文件夹旁置歌词二"), parseLyrics(requireNotNull(local.lyrics)).map { it.text })
            assertTrue(local.lyricsUrl.orEmpty().startsWith(tree.toString()))
            // Deleting the sidecar leaves the folder readable and the track without lyrics.
            automation.adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS")
            DocumentsContract.deleteDocument(resolver, sidecar)
            automation.dropShellPermissionIdentity()
            app.database.metadata().invalidate(track.uri)
            val afterDelete = app.metadata.local(track)
            assertNull(afterDelete.lyrics)
            assertNull(afterDelete.lyricsSource)
        } finally {
            trackUri?.let { app.database.library().deleteTracks(listOf(it)) }
            automation.adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS")
            if (resolver.persistedUriPermissions.any { it.uri == tree }) resolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            DocumentsContract.deleteDocument(resolver, root)
            automation.dropShellPermissionIdentity()
        }
    }

    /**
     * E02: embedded lyrics beyond FLAC, and their priority over a sidecar.
     *
     * A runner must push two real samples into the app external files directory:
     * `standard-uslt.mp3` (an MP3 whose ID3v2 tag carries a `USLT` frame) and
     * `standard-lyrics.m4a` (an M4A whose `moov/udta/meta/ilst` carries `©lyr`). Both must carry at
     * least two lines of lyrics. They are copied here so the sidecar written below cannot disturb
     * the other cases.
     */
    @Test fun readEmbeddedId3AndMp4Lyrics(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LuSoundApplication
        val directory = requireNotNull(app.getExternalFilesDir(null))
        val samples = listOf("standard-uslt.mp3" to "embedded-sample.mp3", "standard-lyrics.m4a" to "embedded-sample.m4a").map { (sample, copy) ->
            val source = File(directory, sample)
            check(source.isFile) { "Push $sample (real audio with embedded lyrics) to app external files before running this integration test" }
            File(directory, copy).also { source.copyTo(it, overwrite = true) }
        }
        val tracks = samples.mapIndexed { index, file -> readExternalTrack(file, if (index == 0) "mp3" else "m4a") }
        val sidecar = File(directory, "embedded-sample.lrc")
        val enabled = metadataEnabled(app)
        try {
            setMetadataEnabled(app, false)
            app.database.library().upsertTracks(tracks)
            val embedded = tracks.map { track ->
                val metadata = app.metadata.local(track)
                assertEquals("文件内嵌", metadata.lyricsSource)
                val raw = requireNotNull(metadata.lyrics) { "Embedded lyrics must be found in ${track.uri}" }
                assertTrue("The embedded lyrics of ${track.uri} must have at least two lines: $raw", raw.lines().count { it.isNotBlank() } >= 2)
                raw
            }
            // The file's own tags beat a lyrics file with the matching name, even when the cache is
            // forced to be re-read.
            sidecar.writeText("[00:01.00]旁置歌词\n[00:02.00]旁置第二句")
            val cached = requireNotNull(app.database.metadata().get(tracks[0].uri))
            app.database.metadata().save(cached.copy(sourceRevision = "outdated"))
            val preferred = app.metadata.local(tracks[0])
            assertEquals("文件内嵌", preferred.lyricsSource)
            assertEquals(embedded[0], preferred.lyrics)
        } finally {
            setMetadataEnabled(app, false)
            app.database.library().deleteTracks(tracks.map { it.uri })
            sidecar.delete()
            samples.forEach { it.delete() }
            if (enabled) setMetadataEnabled(app, true)
        }
    }

    /** A track for one sample pushed into the app's external files directory. */
    private fun readExternalTrack(file: File, container: String): Track {
        val reader = MediaMetadataRetriever()
        return try {
            reader.setDataSource(file.absolutePath)
            Track(Uri.fromFile(file).toString(), requireNotNull(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)),
                requireNotNull(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)), requireNotNull(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)),
                "外部验证样本", requireNotNull(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong(), null, container, TrackSource.DOCUMENT, null)
        } finally { reader.release() }
    }

    @Test fun timestampsAndCandidateMatchingRejectOtherVersions() {
        val lines = parseLyrics("[offset:500]\n[00:01.20][00:03.456]line one\n[00:05]line two")
        assertEquals(listOf(1700L, 3956L, 5500L), lines.map { it.timeMs })
        assertEquals(-1, currentLyricIndex(lines, 0))
        assertEquals(1, currentLyricIndex(lines, 3956))
        assertNull(usableLyrics("[00:00.00]暂无歌词"))
        val track = Track("file:///test.flac", "布拉格广场", "蔡依林 / 周杰伦", "看我72变", "Music", 294600, null, "flac", TrackSource.DOCUMENT, null)
        val correct = LrcRecord(1, "布拉格廣場", "蔡依林&周杰倫", "看我72變", 295.0, false, null, "[00:01.00]test")
        assertEquals(correct, matchLyrics(track, listOf(correct.copy(id = 2, artistName = "另一位歌手"), correct.copy(id = 3, duration = 192.0), correct)))
    }
}
