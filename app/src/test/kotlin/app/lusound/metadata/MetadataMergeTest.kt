package app.lusound.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * When cached lyrics and covers are trusted, and what wins when a file and the cache disagree.
 *
 * The failure these rules prevent is a stale match outliving the file it was made for, or a local
 * source that has gone away being kept alive by the cache.
 */
class MetadataMergeTest {
    private val uri = "content://media/external/audio/media/1"
    private val fingerprint = "fingerprint-1"
    private val revision = "1725000000:31457280"

    private fun cached(
        lyrics: String? = "[00:01.00]远端词",
        lyricsSource: String? = MetadataSource.LYRICS_REMOTE,
        lyricsUrl: String? = "https://lrclib.net/api/get/1",
        coverUri: String? = "file:///covers/remote.img",
        coverSource: String? = MetadataSource.COVER_REMOTE,
        coverUrl: String? = "https://musicbrainz.org/release/1/cover-art",
        sourceRevision: String = revision,
        checkedAt: Long = 1_700_000_000_000L,
        error: String? = null,
        rowFingerprint: String = fingerprint,
    ) = TrackMetadata(uri, rowFingerprint, sourceRevision, lyrics, lyricsSource, lyricsUrl, coverUri, coverSource, coverUrl, checkedAt, error)

    // ---- when the file can be skipped ---------------------------------------

    @Test
    fun aCachedRowAtTheCurrentRevisionWithLyricsIsHandedBack() {
        val row = cached()

        assertSame(row, freshCachedMetadata(row, revision))
    }

    @Test
    fun aRowFromADifferentRevisionIsReadAgain() {
        assertNull(freshCachedMetadata(cached(sourceRevision = "older"), revision))
    }

    @Test
    fun aRowWithoutLyricsIsReadAgainEvenAtTheSameRevision() {
        // A sidecar .lrc can appear next to the audio without the audio file's revision changing, so a
        // lyrics-less row must be re-read or the new file would never be noticed.
        assertNull(freshCachedMetadata(cached(lyrics = null, lyricsSource = null), revision))
    }

    @Test
    fun aProviderThatReportsNoRevisionNeverShortCircuits() {
        assertNull(freshCachedMetadata(cached(sourceRevision = "unversioned"), "unversioned"))
    }

    @Test
    fun nothingCachedMeansReadTheFile() {
        assertNull(freshCachedMetadata(null, revision))
    }

    // ---- what the merge stores ----------------------------------------------

    @Test
    fun theFilesOwnContentWinsOverAnythingCached() {
        val merged = mergeMetadata(uri, fingerprint, revision,
            LocalMetadata("[00:01.00]内嵌词", MetadataSource.EMBEDDED, null, "file:///covers/embedded.img"), cached())

        assertEquals("[00:01.00]内嵌词", merged.lyrics)
        assertEquals(MetadataSource.EMBEDDED, merged.lyricsSource)
        assertNull("an embedded lyric has no remote source to link to", merged.lyricsUrl)
        assertEquals("file:///covers/embedded.img", merged.coverUri)
        assertEquals(MetadataSource.EMBEDDED, merged.coverSource)
        assertNull(merged.coverUrl)
    }

    @Test
    fun aSidecarWinsOverTheCachedRemoteResultAndKeepsItsOwnSource() {
        val merged = mergeMetadata(uri, fingerprint, revision,
            LocalMetadata("[00:01.00]旁置词", MetadataSource.SIDECAR, "content://tree/document/9", null), cached())

        assertEquals("[00:01.00]旁置词", merged.lyrics)
        assertEquals(MetadataSource.SIDECAR, merged.lyricsSource)
        assertEquals("content://tree/document/9", merged.lyricsUrl)
        assertEquals("a missing local cover still falls back to the cache", "file:///covers/remote.img", merged.coverUri)
        assertEquals(MetadataSource.COVER_REMOTE, merged.coverSource)
        assertEquals("https://musicbrainz.org/release/1/cover-art", merged.coverUrl)
    }

    @Test
    fun theCachedRemoteResultIsUsedWhenTheFileHasNothing() {
        val merged = mergeMetadata(uri, fingerprint, revision, LocalMetadata(null, null, null, null), cached())

        assertEquals("[00:01.00]远端词", merged.lyrics)
        assertEquals(MetadataSource.LYRICS_REMOTE, merged.lyricsSource)
        assertEquals("https://lrclib.net/api/get/1", merged.lyricsUrl)
        assertEquals(MetadataSource.COVER_REMOTE, merged.coverSource)
    }

    @Test
    fun aCachedLocalSourceIsNeverUsedAsAFallback() {
        // The file used to have embedded lyrics and no longer does. Keeping the cached copy alive would
        // mean showing lyrics the file does not have, with no way for the user to clear them.
        val stale = cached(lyrics = "[00:01.00]旧内嵌词", lyricsSource = MetadataSource.EMBEDDED, lyricsUrl = null,
            coverUri = "file:///covers/old.img", coverSource = MetadataSource.EMBEDDED, coverUrl = null)

        val merged = mergeMetadata(uri, fingerprint, revision, LocalMetadata(null, null, null, null), stale)

        assertNull(merged.lyrics)
        assertNull(merged.lyricsSource)
        assertNull(merged.coverUri)
        assertNull(merged.coverSource)
    }

    @Test
    fun theLookupHistoryCarriesOver() {
        val failed = cached(lyrics = null, lyricsSource = null, error = "LRCLIB 请求失败", checkedAt = 1_600_000_000_000L)

        val merged = mergeMetadata(uri, fingerprint, revision, LocalMetadata(null, null, null, null), failed)

        assertEquals(1_600_000_000_000L, merged.checkedAt)
        assertEquals("LRCLIB 请求失败", merged.error)
    }

    @Test
    fun anEmptyCacheProducesAFreshRow() {
        val merged = mergeMetadata(uri, fingerprint, revision, LocalMetadata(null, null, null, null), null)

        assertEquals(uri, merged.uri)
        assertEquals(fingerprint, merged.fingerprint)
        assertEquals(revision, merged.sourceRevision)
        assertEquals(0L, merged.checkedAt)
        assertNull(merged.error)
        assertNull(merged.lyrics)
    }

    @Test
    fun aNewRevisionAndFingerprintReplaceTheOldOnes() {
        val merged = mergeMetadata(uri, "new-fingerprint", "new-revision", LocalMetadata("[00:01.00]词", MetadataSource.EMBEDDED, null, null), cached())

        assertEquals("new-fingerprint", merged.fingerprint)
        assertEquals("new-revision", merged.sourceRevision)
    }
}
