package app.lusound.library

import androidx.room.withTransaction

/**
 * A playlist with a song moved from index [from] to index [to], as the list a local playlist should
 * be rewritten to.
 *
 * Pure list-of-pairs logic so the two properties reordering must never break are JVM-testable: the
 * same songs survive (including a `trackUri` that deliberately appears twice) and the resulting
 * order is exactly 0..n-1. The persisted form is the returned positions.
 *
 * An index outside the list is rejected rather than clamped. A move that changes nothing
 * (`from == to`) still renumbers, which is what cleans up a playlist whose positions are not
 * contiguous.
 */
fun movedEntryPositions(entries: List<Pair<String, Int>>, from: Int, to: Int): List<Pair<String, Int>> {
    require(from in entries.indices) { "要移动的歌曲不在歌单中：$from" }
    require(to in entries.indices) { "移动目标位置越界：$to" }
    val reordered = entries.toMutableList()
    reordered.add(to, reordered.removeAt(from))
    return reordered.mapIndexed { index, (uri, _) -> uri to index }
}

/**
 * Moves the entry at [from] to [to] inside one playlist and rewrites the whole entry list as a
 * contiguous 0..n-1 sequence.
 *
 * The read and the rewrite share one transaction, so a concurrent add or remove cannot commit
 * between them and then be silently dropped by `clearPlaylist`.
 *
 * The rewrite is what makes the move safe. `playlist_entries` is keyed on `(playlistId, position)`,
 * so an in-place swap or an UPDATE of one row collides with the row that still holds the target
 * position. `DELETE` followed by `INSERT` at fresh positions cannot: SQLite releases the old
 * `(playlistId, position)` keys at the delete, and the whole sequence runs inside that same
 * transaction. Rewriting every entry rather than only the moved range is deliberate — the outcome is
 * a clean sequence no matter how positions were arranged before, and it is the same shape the cloud
 * sync path already uses (`clearPlaylist` + `insertEntry`).
 */
suspend fun reorderPlaylistEntry(database: LibraryDatabase, playlistId: Long, from: Int, to: Int) {
    database.withTransaction {
        val entries = database.library().playlistEntries(playlistId)
        val moved = movedEntryPositions(entries.map { it.trackUri to it.position }, from, to)
        database.library().clearPlaylist(playlistId)
        moved.forEach { (uri, position) -> database.library().insertEntry(PlaylistEntry(playlistId, uri, position)) }
    }
}
