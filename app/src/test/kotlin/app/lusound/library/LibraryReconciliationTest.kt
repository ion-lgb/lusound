package app.lusound.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide whether a library row is deleted.
 *
 * These matter more than most tests here: deleting a track cascades into playlist entries and cached
 * metadata, so a wrong answer removes a user's playlist membership silently. Every case below is one
 * the app can actually reach — a card being unplugged, two authorised folders overlapping, the same
 * song on two servers, or a scan that legitimately returns nothing.
 */
class LibraryReconciliationTest {
    private val mediaStore = track("content://media/external/audio/media/1", TrackSource.MEDIASTORE, null)
    private val mediaStoreOther = track("content://media/external/audio/media/2", TrackSource.MEDIASTORE, null)
    private val treeA = track("content://tree-a/document/1", TrackSource.DOCUMENT_TREE, "content://tree-a")
    private val treeB = track("content://tree-b/document/1", TrackSource.DOCUMENT_TREE, "content://tree-b")
    private val imported = track("content://downloads/document/9", TrackSource.DOCUMENT, null)
    private val serverOne = track("lusound://srv1/song-1", TrackSource.CLOUD, "srv1")
    private val serverTwo = track("lusound://srv2/song-1", TrackSource.CLOUD, "srv2")
    private val all = listOf(mediaStore, mediaStoreOther, treeA, treeB, imported, serverOne, serverTwo)

    // ---- MediaStore ----------------------------------------------------------

    @Test
    fun aTrackThatVanishedFromAMountedVolumeIsRemoved() {
        val removed = staleMediaStoreTracks(all, scannedUris = setOf(mediaStoreOther.uri), volumeMounted = { true })

        assertEquals(listOf(mediaStore.uri), removed)
    }

    @Test
    fun aTrackOnAnUnmountedVolumeIsKeptEvenThoughTheScanCannotSeeIt() {
        // The card was removed: the provider lists nothing, but deleting would drop the user's files
        // and their playlist positions until the card came back.
        val removed = staleMediaStoreTracks(all, scannedUris = emptySet(), volumeMounted = { false })

        assertEquals(emptyList<String>(), removed)
    }

    @Test
    fun onlyTheUnmountedVolumeLosesItsProtection() {
        val mounted = track("content://media/vol-b/audio/media/3", TrackSource.MEDIASTORE, null)
        val removed = staleMediaStoreTracks(
            listOf(mediaStore, mounted),
            scannedUris = emptySet(),
            volumeMounted = { it.uri == mediaStore.uri },
        )

        assertEquals(listOf(mediaStore.uri), removed)
    }

    @Test
    fun aTrackStillInTheScanIsKept() {
        val removed = staleMediaStoreTracks(all, scannedUris = setOf(mediaStore.uri, mediaStoreOther.uri), volumeMounted = { true })

        assertEquals(emptyList<String>(), removed)
    }

    @Test
    fun aMediaStoreScanNeverTouchesAnyOtherSource() {
        val removed = staleMediaStoreTracks(all, scannedUris = emptySet(), volumeMounted = { true })

        assertEquals(listOf(mediaStore.uri, mediaStoreOther.uri), removed)
        assertTrue("imported documents must survive", treeA.uri !in removed && treeB.uri !in removed && imported.uri !in removed)
        assertTrue("server rows must survive", serverOne.uri !in removed && serverTwo.uri !in removed)
    }

    @Test
    fun theVolumeProbeIsNotConsultedForTracksThatAreStillPresent() {
        // The probe reaches a system service and may refuse to answer; a present track must never be
        // able to fail the reconcile, and a large library must not pay for a lookup per row.
        val probed = mutableListOf<String>()
        staleMediaStoreTracks(all, scannedUris = setOf(mediaStore.uri, mediaStoreOther.uri)) { probed += it.uri; true }

        assertEquals(emptyList<String>(), probed)
    }

    // ---- authorised directories ---------------------------------------------

    @Test
    fun aTreeRescanOnlyTouchesThatTree() {
        val removed = staleDocumentTreeTracks(all, treeUri = "content://tree-a", scannedUris = emptySet())

        assertEquals(listOf(treeA.uri), removed)
    }

    @Test
    fun anOverlappingAuthorisedDirectoryKeepsItsOwnEntries() {
        // Both trees can see the same file; each keeps its own document URI and neither may delete the
        // other's row, because a playlist may reference either one.
        val removed = staleDocumentTreeTracks(all, treeUri = "content://tree-b", scannedUris = setOf(treeB.uri))

        assertEquals(emptyList<String>(), removed)
    }

    @Test
    fun aTreeRescanKeepsFilesThatAreStillThere() {
        val removed = staleDocumentTreeTracks(all, treeUri = "content://tree-a", scannedUris = setOf(treeA.uri))

        assertEquals(emptyList<String>(), removed)
    }

    @Test
    fun aTreeThatScannedNothingRemovesOnlyItsOwnRows() {
        val removed = staleDocumentTreeTracks(all, treeUri = "content://tree-a", scannedUris = emptySet())

        assertEquals(listOf(treeA.uri), removed)
    }

    // ---- servers -------------------------------------------------------------

    @Test
    fun aServerSyncOnlyTouchesThatServer() {
        // Both servers expose a song with the same id; only the syncing server's row may go.
        val removed = staleCloudTracks(all, serverId = "srv1", currentUris = emptySet())

        assertEquals(listOf(serverOne.uri), removed)
    }

    @Test
    fun aServerSyncKeepsSongsTheServerStillReturns() {
        val removed = staleCloudTracks(all, serverId = "srv1", currentUris = setOf(serverOne.uri))

        assertEquals(emptyList<String>(), removed)
    }

    @Test
    fun aServerSyncNeverTouchesLocalRowsThatShareTheSameSongId() {
        val removed = staleCloudTracks(all, serverId = "srv2", currentUris = setOf(serverTwo.uri))

        assertEquals(emptyList<String>(), removed)
        assertTrue(removed.none { it.startsWith("content://") })
    }

    // ---- shared expectations -------------------------------------------------

    @Test
    fun nothingIsRemovedWhenTheLibraryIsEmpty() {
        assertEquals(emptyList<String>(), staleMediaStoreTracks(emptyList(), emptySet()) { true })
        assertEquals(emptyList<String>(), staleDocumentTreeTracks(emptyList(), "content://tree-a", emptySet()))
        assertEquals(emptyList<String>(), staleCloudTracks(emptyList(), "srv1", emptySet()))
    }

    @Test
    fun everySourceRuleReturnsEachStaleUriExactlyOnce() {
        val removed = staleDocumentTreeTracks(all, treeUri = "content://tree-a", scannedUris = emptySet())

        assertEquals(listOf(treeA.uri), removed)
    }

    @Test
    fun aReferenceThatDoesNotMatchExactlyDeletesNothing() {
        // sourceRef is compared exactly. A provider that hands back a differently spelled tree URI, or
        // a server row whose id no longer matches, must not make a rescan delete rows it does not own;
        // deleting nothing is the safe direction, and the next scan can still correct the library.
        assertEquals(emptyList<String>(), staleDocumentTreeTracks(all, treeUri = "content://tree-a/", scannedUris = emptySet()))
        assertEquals(emptyList<String>(), staleCloudTracks(all, serverId = "SRV1", currentUris = emptySet()))
    }

    private fun track(uri: String, sourceKind: String, sourceRef: String?) =
        Track(uri, "标题", "歌手", "专辑", "/Music", 1_000, null, "flac", sourceKind, sourceRef)
}
