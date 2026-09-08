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

@Dao
interface MetadataDao {
    @Query("SELECT * FROM track_metadata") fun observeAll(): Flow<List<TrackMetadata>>
    @Query("SELECT * FROM track_metadata WHERE uri = :uri") suspend fun get(uri: String): TrackMetadata?
    @Upsert suspend fun save(value: TrackMetadata)
    @Query("""UPDATE track_metadata SET checkedAt = 0, error = NULL,
        lyrics = CASE WHEN lyricsSource = 'LRCLIB' THEN NULL ELSE lyrics END,
        lyricsUrl = CASE WHEN lyricsSource = 'LRCLIB' THEN NULL ELSE lyricsUrl END,
        lyricsSource = CASE WHEN lyricsSource = 'LRCLIB' THEN NULL ELSE lyricsSource END,
        coverUri = CASE WHEN coverSource = 'Cover Art Archive' THEN NULL ELSE coverUri END,
        coverUrl = CASE WHEN coverSource = 'Cover Art Archive' THEN NULL ELSE coverUrl END,
        coverSource = CASE WHEN coverSource = 'Cover Art Archive' THEN NULL ELSE coverSource END
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
