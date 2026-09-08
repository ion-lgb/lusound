package app.lusound.metadata

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import app.lusound.library.LibraryDatabase
import app.lusound.library.Track
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Serializes metadata requests and stores successes independently from transient lookup failures. */
class MetadataRepository(private val context: Context, private val database: LibraryDatabase) {
    private val mutex = Mutex()
    private val client = MetadataClient()

    suspend fun local(track: Track): TrackMetadata = withContext(Dispatchers.IO) { mutex.withLock { loadLocal(track) } }

    private suspend fun loadLocal(track: Track): TrackMetadata {
        val fingerprint = metadataFingerprint(track)
        val revision = sourceRevision(context, track)
        val cached = database.metadata().get(track.uri)?.takeIf { it.fingerprint == fingerprint }
        if (revision != "unversioned" && cached?.sourceRevision == revision) return cached
        val local = readLocalMetadata(context, track)
        val result = TrackMetadata(track.uri, fingerprint, revision,
            local.lyrics ?: cached?.takeIf { it.lyricsSource == "LRCLIB" }?.lyrics, if (local.lyrics != null) "文件内嵌" else cached?.lyricsSource?.takeIf { it == "LRCLIB" },
            if (local.lyrics != null) null else cached?.lyricsUrl,
            local.cover ?: cached?.takeIf { it.coverSource == "Cover Art Archive" }?.coverUri, if (local.cover != null) "文件内嵌" else cached?.coverSource?.takeIf { it == "Cover Art Archive" },
            if (local.cover != null) null else cached?.coverUrl, cached?.checkedAt ?: 0, cached?.error)
        savePresent(result)
        return result
    }

    suspend fun enrich(track: Track): Unit = withContext(Dispatchers.IO) { mutex.withLock {
        var result = loadLocal(track)
        val lifetime = if (result.error == null) 7 * 24 * 60 * 60 * 1000L else 60 * 60 * 1000L
        if (!metadataEnabled(context) || System.currentTimeMillis() - result.checkedAt < lifetime) return@withLock
        val failures = mutableListOf<String>()
        if (result.lyrics == null && track.title.isNotBlank() && track.artist.isNotBlank() && track.artist != "<unknown>") {
            try {
                val lyrics = client.findLyrics(track)
                if (lyrics != null) result = result.copy(lyrics = lyrics.text, lyricsSource = "LRCLIB", lyricsUrl = lyrics.sourceUrl)
            } catch (error: IOException) { failures.add(error.message.orEmpty()) }
            savePresent(result)
        }
        val existingRemoteCover = Uri.parse(track.artworkUri.orEmpty()).scheme in setOf("http", "https")
        if (metadataEnabled(context) && result.coverUri == null && !existingRemoteCover) {
            try {
                val cover = client.findCover(track)
                if (cover != null) result = result.copy(coverUri = saveCover(context, cover.sourceUrl, cover.bytes), coverSource = "Cover Art Archive", coverUrl = cover.sourceUrl)
            } catch (error: IOException) { failures.add(error.message.orEmpty()) }
        }
        result = result.copy(checkedAt = System.currentTimeMillis(), error = failures.takeIf { it.isNotEmpty() }?.joinToString("\n"))
        savePresent(result)
        if (failures.isNotEmpty()) throw IOException(failures.joinToString("\n"))
    } }

    suspend fun recordFailure(track: Track, message: String) {
        val cached = database.metadata().get(track.uri)
        val result = cached?.copy(error = message, checkedAt = System.currentTimeMillis())
            ?: TrackMetadata(track.uri, metadataFingerprint(track), "failed", null, null, null, null, null, null, System.currentTimeMillis(), message)
        savePresent(result)
    }

    /** Removal while a lookup is in flight cancels its result without resurrecting the deleted track. */
    private suspend fun savePresent(metadata: TrackMetadata): Unit = database.withTransaction {
        if (database.library().containsTrack(metadata.uri)) database.metadata().save(metadata)
    }
}
