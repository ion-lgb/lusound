package app.lusound.metadata

/**
 * The cache rules behind a track's lyrics and cover.
 *
 * These decide when a cached result is trusted and when the file has to be read again, which is what
 * keeps a stale match from outliving the file it was made for. They are pure functions so the rules are
 * checked on every build instead of only on a device with real audio files.
 */

/**
 * The cached row that can be handed back without reading the file again, or null when the file must be
 * read. The caller has already discarded a row whose fingerprint is not this track's.
 *
 * Two conditions, and both matter: the row must still have been read at the file's current revision,
 * and it must actually hold lyrics. A row without lyrics is re-read even at a matching revision,
 * because a `.lrc` can appear next to the audio without the audio file's own revision changing, and
 * re-reading is the only way a refresh can notice it.
 *
 * A provider that reports no revision (`unversioned`) never short-circuits: an unknown version is not
 * evidence that the cached lyrics still belong to this file.
 */
fun freshCachedMetadata(cached: TrackMetadata?, revision: String): TrackMetadata? =
    cached?.takeIf { it.lyrics != null && revision != "unversioned" && it.sourceRevision == revision }

/**
 * What to store for a track, given what its file provides and what the network previously provided.
 *
 * The documented priority is the audio file's own tags, then a sidecar `.lrc`, then the cached remote
 * result — both local kinds arrive already decided in [local]. Only a *remote* cached source is ever
 * used as the fallback, so a local source that disappeared cannot be kept alive by the cache in the
 * other direction: it comes back only when the file does.
 *
 * `checkedAt` and `error` carry over from the cache so re-reading a file does not make its lookup look
 * like it never happened, and a remembered failure survives until something succeeds.
 */
fun mergeMetadata(uri: String, fingerprint: String, revision: String, local: LocalMetadata, cached: TrackMetadata?): TrackMetadata {
    val remoteLyrics = cached?.takeIf { it.lyricsSource == MetadataSource.LYRICS_REMOTE }
    val remoteCover = cached?.takeIf { it.coverSource == MetadataSource.COVER_REMOTE }
    return TrackMetadata(
        uri, fingerprint, revision,
        local.lyrics ?: remoteLyrics?.lyrics,
        local.lyricsSource ?: remoteLyrics?.lyricsSource,
        if (local.lyrics != null) local.lyricsUrl else remoteLyrics?.lyricsUrl,
        local.cover ?: remoteCover?.coverUri,
        if (local.cover != null) MetadataSource.EMBEDDED else remoteCover?.coverSource,
        if (local.cover != null) null else remoteCover?.coverUrl,
        cached?.checkedAt ?: 0, cached?.error,
    )
}
