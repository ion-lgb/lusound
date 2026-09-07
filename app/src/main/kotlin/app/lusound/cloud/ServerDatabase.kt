package app.lusound.cloud

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "servers")
data class Server(@PrimaryKey val id: String, val name: String, val baseUrl: String, val username: String,
    val passwordCipher: String, val lastSync: Long, val syncError: String?, @ColumnInfo(defaultValue = "'SUBSONIC'") val kind: String, val remoteUserId: String?)

@Dao
interface ServerDao {
    @Query("SELECT * FROM servers ORDER BY name") fun observe(): Flow<List<Server>>
    @Query("SELECT * FROM servers ORDER BY name") suspend fun all(): List<Server>
    @Query("SELECT * FROM servers WHERE id = :id") suspend fun get(id: String): Server?
    @Query("SELECT * FROM servers WHERE id = :id") fun getForRequest(id: String): Server?
    @Upsert suspend fun save(server: Server)
    @Query("DELETE FROM servers WHERE id = :id") suspend fun delete(id: String)
    @Query("UPDATE servers SET syncError = :message WHERE id = :id") suspend fun recordError(id: String, message: String)
}

/** Preserves all v1 local tracks, playlists and entries. */
val SERVER_MIGRATION: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS servers (id TEXT NOT NULL, name TEXT NOT NULL, baseUrl TEXT NOT NULL, username TEXT NOT NULL, passwordCipher TEXT NOT NULL, lastSync INTEGER NOT NULL, syncError TEXT, PRIMARY KEY(id))")
        db.execSQL("ALTER TABLE playlists ADD COLUMN serverId TEXT")
        db.execSQL("ALTER TABLE playlists ADD COLUMN remoteId TEXT")
        db.execSQL("CREATE TABLE playlist_entries_new (playlistId INTEGER NOT NULL, trackUri TEXT NOT NULL, position INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(playlistId, position), FOREIGN KEY(playlistId) REFERENCES playlists(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(trackUri) REFERENCES tracks(uri) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("INSERT INTO playlist_entries_new SELECT a.playlistId, a.trackUri, (SELECT COUNT(*) FROM playlist_entries b WHERE b.playlistId = a.playlistId AND b.trackUri < a.trackUri) FROM playlist_entries a")
        db.execSQL("DROP TABLE playlist_entries")
        db.execSQL("ALTER TABLE playlist_entries_new RENAME TO playlist_entries")
        db.execSQL("CREATE INDEX index_playlist_entries_trackUri ON playlist_entries(trackUri)")
    }
}

data class ServerDraft(val id: String, val name: String, val baseUrl: String, val username: String, val password: String, val kind: String)

/** Existing Subsonic connections keep their encrypted password and source IDs. */
val JELLYFIN_MIGRATION: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE servers ADD COLUMN kind TEXT NOT NULL DEFAULT 'SUBSONIC'")
        db.execSQL("ALTER TABLE servers ADD COLUMN remoteUserId TEXT")
    }
}
