package app.lusound.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deciding which rows are the same file, and which one is shown.
 *
 * The bias throughout is towards *not* grouping: a wrong match hides a song and lets a playlist play
 * a different file, while a missed match only leaves the library as it already is. Several cases below
 * exist purely to pin that bias.
 */
class FileIdentityTest {
    private val name = "track.flac"
    private val folder = "Music/Album"
    private val size = 31_457_280L
    private val modified = 1_725_000_123_456L

    @Test
    fun thesameFileIdentifiedFromEitherProviderHasOneIdentity() {
        assertEquals(
            fileIdentityKey(name, folder, size, modified),
            fileIdentityKey(name, folder, size, modified),
        )
    }

    @Test
    fun everyPartOfTheIdentityMatters() {
        val base = fileIdentityKey(name, folder, size, modified)

        assertNotEquals(base, fileIdentityKey("other.flac", folder, size, modified))
        assertNotEquals(base, fileIdentityKey(name, "Music/Other", size, modified))
        assertNotEquals(base, fileIdentityKey(name, folder, size + 1, modified))
        assertNotEquals(base, fileIdentityKey(name, folder, size, modified + 1_000))
    }

    @Test
    fun subSecondTimestampsAgreeBecauseTheMediaIndexOnlyReportsSeconds() {
        // MediaStore reports DATE_MODIFIED in seconds while documents report milliseconds. The same
        // file must produce one identity from both, so the key compares whole seconds.
        assertEquals(
            fileIdentityKey(name, folder, size, 1_725_000_123_456L),
            fileIdentityKey(name, folder, size, 1_725_000_123_000L),
        )
    }

    @Test
    fun folderSpellingDifferencesThatProvidersDifferOnAreIgnored() {
        val base = fileIdentityKey(name, folder, size, modified)

        assertEquals(base, fileIdentityKey(name, "/Music/Album/", size, modified))
        assertEquals(base, fileIdentityKey(name, "  Music/Album  ", size, modified))
        assertEquals(base, fileIdentityKey(name, "Music/Album///", size, modified))
    }

    @Test
    fun anIncompleteIdentityIsRefusedRatherThanGuessed() {
        // Grouping on a partial identity is exactly the mistake that hides a song.
        assertNull(fileIdentityKey("", folder, size, modified))
        assertNull(fileIdentityKey("   ", folder, size, modified))
        assertNull(fileIdentityKey(name, folder, 0, modified))
        assertNull(fileIdentityKey(name, folder, -1, modified))
        assertNull(fileIdentityKey(name, folder, size, 0))
        assertNull(fileIdentityKey(name, folder, size, -1))
    }

    @Test
    fun aFolderIsNotRequiredButStillDistinguishes() {
        val withoutFolder = fileIdentityKey(name, "", size, modified)
        assertEquals(withoutFolder, fileIdentityKey(name, "/", size, modified))
        assertNotEquals(withoutFolder, fileIdentityKey(name, folder, size, modified))
    }

    // ---- choosing what to show ----------------------------------------------

    @Test
    fun anExplicitImportOutranksTheMediaIndexWhichOutranksAServer() {
        val imported = track("content://tree/document/1", TrackSource.DOCUMENT_TREE, "content://tree")
        val indexed = track("content://media/external/audio/media/1", TrackSource.MEDIASTORE, null)
        val cloud = track("lusound://srv1/1", TrackSource.CLOUD, "srv1")

        listOf(listOf(imported, indexed, cloud), listOf(cloud, indexed, imported), listOf(indexed, imported)).forEach { group ->
            assertEquals(imported.uri, representativeTrack(group)?.uri)
        }
        assertEquals(indexed.uri, representativeTrack(listOf(indexed, cloud))?.uri)
        assertEquals(cloud.uri, representativeTrack(listOf(cloud))?.uri)
    }

    @Test
    fun theChoiceIsStableWhenPrioritiesTie() {
        val first = track("content://tree-a/document/1", TrackSource.DOCUMENT_TREE, "content://tree-a")
        val second = track("content://tree-b/document/1", TrackSource.DOCUMENT_TREE, "content://tree-b")

        assertEquals(representativeTrack(listOf(first, second)), representativeTrack(listOf(second, first)))
        assertNull(representativeTrack(emptyList()))
    }

    // ---- what gets hidden ---------------------------------------------------

    @Test
    fun theHiddenRowsPointAtTheOneThatRepresentsThem() {
        val indexed = track("content://media/external/audio/media/1", TrackSource.MEDIASTORE, null, identity = "same")
        val imported = track("content://tree/document/1", TrackSource.DOCUMENT_TREE, "content://tree", identity = "same")

        assertEquals(mapOf(indexed.uri to imported.uri), representativeByUri(listOf(indexed, imported)))
        assertEquals(setOf(indexed.uri), shadowedUris(listOf(indexed, imported)))
    }

    @Test
    fun threeRowsOfOneFileLeaveTwoHiddenOnes() {
        val imported = track("content://tree/document/1", TrackSource.DOCUMENT_TREE, "content://tree", identity = "same")
        val indexed = track("content://media/external/audio/media/1", TrackSource.MEDIASTORE, null, identity = "same")
        val cloud = track("lusound://srv1/1", TrackSource.CLOUD, "srv1", identity = "same")

        assertEquals(mapOf(indexed.uri to imported.uri, cloud.uri to imported.uri), representativeByUri(listOf(imported, indexed, cloud)))
    }

    @Test
    fun rowsWithoutAnIdentityAreNeverHidden() {
        val a = track("content://media/external/audio/media/1", TrackSource.MEDIASTORE, null, identity = null)
        val b = track("content://media/external/audio/media/2", TrackSource.MEDIASTORE, null, identity = null)
        val c = track("lusound://srv1/1", TrackSource.CLOUD, "srv1", identity = null)

        assertTrue(representativeByUri(listOf(a, b, c)).isEmpty())
        assertTrue(shadowedUris(listOf(a, b, c)).isEmpty())
    }

    @Test
    fun aGroupOfOneHidesNothing() {
        val only = track("content://media/external/audio/media/1", TrackSource.MEDIASTORE, null, identity = "same")

        assertTrue(representativeByUri(listOf(only)).isEmpty())
        assertTrue(shadowedUris(listOf(only)).isEmpty())
    }

    @Test
    fun differentIdentitiesNeverMix() {
        val a = track("content://media/external/audio/media/1", TrackSource.MEDIASTORE, null, identity = "one")
        val b = track("content://media/external/audio/media/2", TrackSource.MEDIASTORE, null, identity = "two")

        assertTrue(representativeByUri(listOf(a, b)).isEmpty())
    }

    @Test
    fun twoFilesWithTheSameNameSizeAndTimestampInDifferentFoldersStaySeparate() {
        // The false positive this guards against: playing one of them must never play the other.
        val here = fileIdentityKey(name, "Music/Album", size, modified)
        val elsewhere = fileIdentityKey(name, "Podcasts/Album", size, modified)

        assertNotEquals(here, elsewhere)
    }

    @Test
    fun identitySurvivesARoundTripThroughTheStore() {
        // The key is opaque text; it must be usable as a plain column value.
        val key = requireNotNull(fileIdentityKey(name, folder, size, modified))
        assertTrue(key, key.length == 64)
        assertTrue(key, key.all { it in "0123456789abcdef" })
    }

    private fun track(uri: String, sourceKind: String, sourceRef: String?, identity: String? = null) =
        Track(uri, "标题", "歌手", "专辑", "/Music", 1_000, null, "flac", sourceKind, sourceRef, identityKey = identity)
}
