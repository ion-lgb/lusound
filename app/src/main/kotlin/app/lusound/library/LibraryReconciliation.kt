package app.lusound.library

/**
 * Which stored rows a completed scan or sync has made stale.
 *
 * These are the only places the library deletes tracks on its own, and deleting a track cascades into
 * `playlist_entries` and `track_metadata`. A mistake here silently removes a user's playlist
 * membership rather than failing loudly, so the rules are pure functions that a JVM test can hold to
 * account on every build, instead of inline filters that are only exercised when a device, a real
 * provider and a real server happen to be at hand.
 *
 * The discipline they share: a scan may only invalidate rows that came from the very source it just
 * finished reading, and only when that source really could be read.
 */

/**
 * MediaStore rows that are gone from a completed scan.
 *
 * A row only counts as stale while its volume is still mounted. An unplugged SD card or detached USB
 * volume simply stops being listed by the provider, and reading that as a deletion would drop the
 * user's tracks and their playlist positions the moment a card is removed.
 *
 * The volume probe is only consulted for rows that are actually missing, because it reaches a system
 * service and can refuse to answer. That also means a present row can never fail the reconcile.
 */
fun staleMediaStoreTracks(existing: List<Track>, scannedUris: Set<String>, volumeMounted: (Track) -> Boolean): List<String> =
    existing.filter { it.sourceKind == TrackSource.MEDIASTORE && it.uri !in scannedUris && volumeMounted(it) }.map { it.uri }

/**
 * Rows from one authorised tree that are gone from a completed rescan of that tree.
 *
 * Only rows carrying this tree's own `sourceRef` are considered, so two overlapping authorised
 * directories, or the same song also found by MediaStore, cannot delete each other's entries.
 */
fun staleDocumentTreeTracks(existing: List<Track>, treeUri: String, scannedUris: Set<String>): List<String> =
    existing.filter { it.sourceKind == TrackSource.DOCUMENT_TREE && it.sourceRef == treeUri && it.uri !in scannedUris }.map { it.uri }

/**
 * Rows from one server that are gone from a completed sync of that server.
 *
 * Two servers may legitimately expose the same song, and a local file may share a title with either,
 * so the server id is what scopes the deletion — never the title, the container or the song id alone.
 */
fun staleCloudTracks(existing: List<Track>, serverId: String, currentUris: Set<String>): List<String> =
    existing.filter { it.sourceKind == TrackSource.CLOUD && it.sourceRef == serverId && it.uri !in currentUris }.map { it.uri }
