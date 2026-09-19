package app.lusound.library

import java.security.MessageDigest

/**
 * Deciding when two library rows describe the same file.
 *
 * The same file can enter the library more than once: once from the system media index and once from
 * an authorised folder, or through two folders that overlap. Those rows carry different URIs, so the
 * library shows the song twice.
 *
 * The identity is the file's own name, directory, size and modification time, which is what the two
 * providers agree on. Deliberately not used:
 * - the audio bytes, because hashing every file during a scan is far too expensive;
 * - the URI or the provider's path spelling, which differ by construction.
 *
 * Two deliberate conservatisms, because a false match hides a song and lets a playlist play the wrong
 * file, while a missed match only leaves the library as it already is:
 * - [lastModifiedMs] is compared in whole seconds, because the media index only reports seconds while
 *   documents report milliseconds, and the same file must produce the same identity from both;
 * - the directory participates, so two different files that happen to share a name, a size and a
 *   timestamp in different folders are not treated as one. Folders are compared with surrounding
 *   slashes trimmed, which is the only difference between the two providers' spellings of the same
 *   directory. Where the spellings genuinely differ the rows simply stay separate.
 */
fun fileIdentityKey(displayName: String, folder: String, sizeBytes: Long, lastModifiedMs: Long): String? {
    val name = displayName.trim()
    // An incomplete identity must never be used to claim two rows are the same file: a provider that
    // cannot report a size or a timestamp leaves the row ungrouped rather than grouped by name alone.
    if (name.isEmpty() || sizeBytes <= 0 || lastModifiedMs <= 0) return null
    val directory = folder.trim().trim('/')
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(name.toByteArray(Charsets.UTF_8))
    digest.update(0)
    digest.update(directory.toByteArray(Charsets.UTF_8))
    digest.update(0)
    digest.update(sizeBytes.toString().toByteArray(Charsets.US_ASCII))
    digest.update(0)
    digest.update((lastModifiedMs / 1000).toString().toByteArray(Charsets.US_ASCII))
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Which row should be shown when several describe the same file.
 *
 * An explicit import outranks a media-index row, which outranks a server row: the user asked for the
 * import to exist, while the index row is derived and can be rebuilt at any time. Ties break on the
 * URI so the choice is stable between runs and the library does not swap rows while scrolling.
 */
fun representativeTrack(duplicates: List<Track>): Track? = duplicates.minWithOrNull(
    compareBy({ sourcePriority(it.sourceKind) }, { it.uri }),
)

private fun sourcePriority(sourceKind: String): Int = when (sourceKind) {
    TrackSource.DOCUMENT, TrackSource.DOCUMENT_TREE -> 0
    TrackSource.MEDIASTORE -> 1
    TrackSource.CLOUD -> 2
    else -> 3
}

/**
 * Hidden row URI to the URI that stands in for it, so a playlist entry pointing at a hidden row still
 * finds the song and still plays. Without this, hiding a row would silently drop it from every
 * playlist that referenced it.
 *
 * Nothing is deleted anywhere in this file: a hidden row keeps its playlist entries, its cached lyrics
 * and its ability to become visible again if the representative goes away (a revoked folder, an
 * unplugged card). Rows without an identity are never grouped, and a group of one hides nothing.
 */
fun representativeByUri(tracks: List<Track>): Map<String, String> {
    val redirects = mutableMapOf<String, String>()
    tracks.filter { it.identityKey != null }
        .groupBy { it.identityKey }
        .values
        .filter { it.size > 1 }
        .forEach { group ->
            val keeper = representativeTrack(group)?.uri ?: return@forEach
            group.forEach { if (it.uri != keeper) redirects[it.uri] = keeper }
        }
    return redirects
}

/** The rows a library listing leaves out because another row is the same file. */
fun shadowedUris(tracks: List<Track>): Set<String> = representativeByUri(tracks).keys
