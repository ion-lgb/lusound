package app.lusound.metadata

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.lusound.library.Track
import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest

@Entity(tableName = "track_metadata", foreignKeys = [ForeignKey(entity = Track::class,
    parentColumns = ["uri"], childColumns = ["uri"], onDelete = ForeignKey.CASCADE)])
data class TrackMetadata(
    @PrimaryKey val uri: String, val fingerprint: String, val sourceRevision: String,
    val lyrics: String?, val lyricsSource: String?, val lyricsUrl: String?,
    val coverUri: String?, val coverSource: String?, val coverUrl: String?,
    val checkedAt: Long, val error: String?,
)

/**
 * Labels stored in `track_metadata.lyricsSource` and `track_metadata.coverSource`.
 *
 * They are persisted values rather than display strings: renaming one changes what existing rows
 * mean and has to be a deliberate migration. They also drive [MetadataDao.invalidate], which must
 * drop exactly the sources that cannot be re-derived from the audio file.
 */
object MetadataSource {
    /** Read out of the audio file's own tags, in any of the supported containers. */
    const val EMBEDDED = "文件内嵌"

    /** Read from a `.lrc` file that sits next to the audio file. */
    const val SIDECAR = "旁置 LRC"

    /** Fetched from LRCLIB; there is no local original to fall back to. */
    const val LYRICS_REMOTE = "LRCLIB"

    /** Fetched from the Cover Art Archive; there is no local original to fall back to. */
    const val COVER_REMOTE = "Cover Art Archive"
}

@Dao
interface MetadataDao {
    @Query("SELECT * FROM track_metadata") fun observeAll(): Flow<List<TrackMetadata>>
    @Query("SELECT * FROM track_metadata WHERE uri = :uri") suspend fun get(uri: String): TrackMetadata?
    @Upsert suspend fun save(value: TrackMetadata)

    /**
     * Forces the next load to look again.
     *
     * A remote source has no local original, so it is dropped and must be looked up again. An
     * embedded tag keeps its value: it is re-derived from the audio file whenever that file's
     * revision changes. A sidecar is dropped as well, even though it is local, because nothing in
     * the audio file's revision reflects a `.lrc` being edited or deleted, so re-reading it is the
     * only way a refresh can see the change.
     */
    @Query("""UPDATE track_metadata SET checkedAt = 0, error = NULL,
        lyrics = CASE WHEN lyricsSource IN ('${MetadataSource.LYRICS_REMOTE}', '${MetadataSource.SIDECAR}') THEN NULL ELSE lyrics END,
        lyricsUrl = CASE WHEN lyricsSource IN ('${MetadataSource.LYRICS_REMOTE}', '${MetadataSource.SIDECAR}') THEN NULL ELSE lyricsUrl END,
        lyricsSource = CASE WHEN lyricsSource IN ('${MetadataSource.LYRICS_REMOTE}', '${MetadataSource.SIDECAR}') THEN NULL ELSE lyricsSource END,
        coverUri = CASE WHEN coverSource = '${MetadataSource.COVER_REMOTE}' THEN NULL ELSE coverUri END,
        coverUrl = CASE WHEN coverSource = '${MetadataSource.COVER_REMOTE}' THEN NULL ELSE coverUrl END,
        coverSource = CASE WHEN coverSource = '${MetadataSource.COVER_REMOTE}' THEN NULL ELSE coverSource END
        WHERE uri = :uri""") suspend fun invalidate(uri: String)
}

val METADATA_MIGRATION: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS track_metadata (
            uri TEXT NOT NULL, fingerprint TEXT NOT NULL, sourceRevision TEXT NOT NULL, lyrics TEXT, lyricsSource TEXT, lyricsUrl TEXT,
            coverUri TEXT, coverSource TEXT, coverUrl TEXT, checkedAt INTEGER NOT NULL, error TEXT,
            PRIMARY KEY(uri), FOREIGN KEY(uri) REFERENCES tracks(uri) ON UPDATE NO ACTION ON DELETE CASCADE)""")
    }
}

fun metadataFingerprint(track: Track): String = MessageDigest.getInstance("SHA-256")
    .digest(listOf(track.uri, track.title, track.artist, track.album, track.durationMs.toString()).joinToString("\u0000").toByteArray())
    .joinToString("") { "%02x".format(it) }
