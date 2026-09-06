package app.lusound.library

import androidx.room.*
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "tracks")
data class Track(
    @PrimaryKey val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val folder: String,
    val durationMs: Long,
    val artworkUri: String?,
    val format: String,
    val origin: String,
)

@Entity(tableName = "playlists")
data class Playlist(@PrimaryKey(autoGenerate = true) val id: Long, val name: String)

@Entity(
    tableName = "playlist_entries", primaryKeys = ["playlistId", "trackUri"],
    foreignKeys = [
        ForeignKey(entity = Playlist::class, parentColumns = ["id"], childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Track::class, parentColumns = ["uri"], childColumns = ["trackUri"], onDelete = ForeignKey.CASCADE),
    ], indices = [Index("trackUri")],
)
data class PlaylistEntry(val playlistId: Long, val trackUri: String)

@Dao
interface LibraryDao {
    @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE") fun observeTracks(): Flow<List<Track>>
    @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE") suspend fun getTracks(): List<Track>
    @Upsert suspend fun upsertTracks(tracks: List<Track>)
    @Query("DELETE FROM tracks WHERE uri IN (:uris)") suspend fun deleteTracks(uris: List<String>)
    @Query("SELECT * FROM playlists ORDER BY id") fun observePlaylists(): Flow<List<Playlist>>
    @Insert suspend fun insertPlaylist(playlist: Playlist): Long
    @Query("DELETE FROM playlists WHERE id = :id") suspend fun deletePlaylist(id: Long)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertEntry(entry: PlaylistEntry)
    @Query("SELECT * FROM playlist_entries") fun observeEntries(): Flow<List<PlaylistEntry>>
    @Query("SELECT trackUri FROM playlist_entries WHERE playlistId = :id") suspend fun playlistTrackUris(id: Long): List<String>
    @Query("DELETE FROM playlist_entries WHERE playlistId = :playlistId AND trackUri = :uri") suspend fun removeEntry(playlistId: Long, uri: String)
}

@Database(entities = [Track::class, Playlist::class, PlaylistEntry::class], version = 1, exportSchema = true)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
}

data class MediaLibrarySnapshot(val tracks: List<Track>, val mountedVolumes: Set<String>)

/** Reconcile online MediaStore volumes only; unplugged volumes and imported documents retain playlist membership. */
suspend fun replaceMediaLibrary(database: LibraryDatabase, snapshot: MediaLibrarySnapshot, context: Context) {
    val tracks = snapshot.tracks
    database.withTransaction {
        val currentUris = tracks.map { it.uri }.toSet()
        val removed = database.library().getTracks()
            .filter { it.origin == "MEDIASTORE" && isTrackVolumeMounted(context, it, snapshot.mountedVolumes) && it.uri !in currentUris }.map { it.uri }
        removed.chunked(500).forEach { database.library().deleteTracks(it) }
        database.library().upsertTracks(tracks)
    }
}

/** Before API29 MediaStore merges volumes under external; resolve the actual volume from the stored path. */
private fun isTrackVolumeMounted(context: Context, track: Track, mountedVolumes: Set<String>): Boolean {
    if (Build.VERSION.SDK_INT >= 29) return track.uri.split('/')[3] in mountedVolumes
    if ("external" !in mountedVolumes) return false
    val storage = checkNotNull(context.getSystemService(StorageManager::class.java)) { "StorageManager unavailable: cannot safely reconcile music" }
    val volume = storage.getStorageVolume(File(track.folder)) ?: return false
    return volume.state == Environment.MEDIA_MOUNTED || volume.state == Environment.MEDIA_MOUNTED_READ_ONLY
}
