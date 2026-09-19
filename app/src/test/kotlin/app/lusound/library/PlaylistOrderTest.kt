package app.lusound.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The ordering half of playlist reordering, without Android.
 *
 * `playlist_entries` is keyed on `(playlistId, position)`, so the rewritten positions this function
 * returns have to be a contiguous sequence from zero or the write half cannot succeed. Duplicates are
 * a deliberate user choice in this app, so the same URI may legitimately appear twice and must
 * survive.
 */
class PlaylistOrderTest {
    private val entries = listOf("a" to 0, "b" to 1, "c" to 2)

    @Test
    fun movingAnEntryUpShiftsTheEntryAboveItDown() {
        assertEquals(listOf("b" to 0, "a" to 1, "c" to 2), movedEntryPositions(entries, 1, 0))
    }

    @Test
    fun movingAnEntryDownShiftsTheEntryBelowItUp() {
        assertEquals(listOf("a" to 0, "c" to 1, "b" to 2), movedEntryPositions(entries, 1, 2))
        assertEquals(listOf("b" to 0, "c" to 1, "a" to 2), movedEntryPositions(entries, 0, 2))
        assertEquals(listOf("c" to 0, "a" to 1, "b" to 2), movedEntryPositions(entries, 2, 0))
    }

    @Test
    fun duplicateSongsKeepBothPositionsInTheirMovedOrder() {
        val duplicated = listOf("a" to 0, "b" to 1, "b" to 2, "c" to 3)
        // Swapping the two identical entries is visibly a no-op, but it must not drop or merge them.
        assertEquals(listOf("a" to 0, "b" to 1, "b" to 2, "c" to 3), movedEntryPositions(duplicated, 1, 2))
        assertEquals(listOf("a" to 0, "b" to 1, "b" to 2, "c" to 3), movedEntryPositions(duplicated, 2, 1))
        // Moving one of two identical entries across a different song is indistinguishable from
        // moving the other, so the surviving sequence is the same either way — the point is that both
        // copies are still there.
        assertEquals(listOf("b" to 0, "a" to 1, "b" to 2, "c" to 3), movedEntryPositions(duplicated, 2, 0))
        assertEquals(listOf("a" to 0, "b" to 1, "c" to 2, "b" to 3), movedEntryPositions(duplicated, 3, 2))
        assertEquals(listOf("a" to 0, "b" to 1, "c" to 2, "b" to 3), movedEntryPositions(duplicated, 2, 3))
    }

    @Test
    fun movingAnEntryToItsOwnPositionRenumbersWithoutChangingTheOrder() {
        assertEquals(entries, movedEntryPositions(entries, 1, 1))
    }

    @Test
    fun aPlaylistWithGapsIsRewrittenAsAContiguousSequence() {
        // Positions left over from an earlier removal could be 0, 5, 9; any move must normalise them.
        val gapped = listOf("a" to 0, "b" to 5, "c" to 9)
        assertEquals(listOf("b" to 0, "a" to 1, "c" to 2), movedEntryPositions(gapped, 1, 0))
        assertEquals(listOf("a" to 0, "b" to 1, "c" to 2), movedEntryPositions(gapped, 1, 1))
    }

    @Test
    fun everyOutcomeIsANoGapNoDuplicateSequence() {
        val sources = listOf(entries, listOf("a" to 4, "b" to 7, "c" to 8), listOf("a" to 0, "a" to 3))
        for (source in sources) for (from in source.indices) for (to in source.indices) {
            val moved = movedEntryPositions(source, from, to)
            assertEquals("positions for $source $from -> $to", source.indices.map { it }, moved.map { (_, position) -> position })
            assertEquals("entries for $source $from -> $to", source.map { it.first }.sorted(), moved.map { it.first }.sorted())
        }
    }

    @Test
    fun anIndexOutsideThePlaylistIsRejected() {
        for (pair in listOf(3 to 0, 0 to 3, -1 to 0, 0 to -1)) {
            try {
                movedEntryPositions(entries, pair.first, pair.second)
                fail("Move ${pair.first} -> ${pair.second} must be rejected")
            } catch (error: IllegalArgumentException) {
                assertTrue(error.message.orEmpty(), error.message.orEmpty().isNotBlank())
            }
        }
    }

    @Test
    fun anEmptyPlaylistHasNothingToMove() {
        try {
            movedEntryPositions(emptyList(), 0, 0)
            fail("An empty playlist has no entry to move")
        } catch (_: IllegalArgumentException) { }
    }
}
