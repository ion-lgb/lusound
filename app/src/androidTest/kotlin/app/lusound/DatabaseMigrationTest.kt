package app.lusound

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lusound.cloud.SERVER_MIGRATION
import app.lusound.library.LibraryDatabase
import app.lusound.library.TRACK_IDENTITY_MIGRATION
import app.lusound.library.TRACK_QUALITY_MIGRATION
import app.lusound.library.TRACK_SOURCE_MIGRATION
import app.lusound.library.TrackSource
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    @Test fun upgradePreservesLocalPlaylistAndAllowsRepeatedSongs() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-${java.util.UUID.randomUUID()}.db"
        val old = createLegacyDatabase(context, name, 1)
        try {
            old.execSQL("INSERT INTO tracks VALUES ('content://test/1', 'Local', 'Artist', 'Album', 'Music', 1000, NULL, 'wav', 'MEDIASTORE')")
            old.execSQL("INSERT INTO playlists VALUES (1, 'My playlist')")
            old.execSQL("INSERT INTO playlist_entries VALUES (1, 'content://test/1')")
            old.version = 1
        } finally { old.close() }
        val upgraded = Room.databaseBuilder(context, LibraryDatabase::class.java, name).addMigrations(SERVER_MIGRATION, app.lusound.cloud.JELLYFIN_MIGRATION, app.lusound.metadata.METADATA_MIGRATION, TRACK_SOURCE_MIGRATION, TRACK_QUALITY_MIGRATION, TRACK_IDENTITY_MIGRATION).build()
        try {
            assertEquals("Local", upgraded.library().getTracks().single().title)
            assertEquals(listOf("content://test/1"), upgraded.library().playlistTrackUris(1))
            assertNull(upgraded.library().getPlaylist(1)!!.serverId)
            upgraded.library().insertEntry(app.lusound.library.PlaylistEntry(1, "content://test/1", 1))
            assertEquals(listOf("content://test/1", "content://test/1"), upgraded.library().playlistTrackUris(1))
            assertEquals("wav", upgraded.library().getTracks().single().container)
            assertEquals(TrackSource.MEDIASTORE, upgraded.library().getTracks().single().sourceKind)
            assertNull(upgraded.library().getTracks().single().sourceRef)
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }
    @Test fun upgradePreservesExistingSubsonicCredentials() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-${java.util.UUID.randomUUID()}.db"
        val old = createLegacyDatabase(context, name, 2)
        try {
            old.execSQL("INSERT INTO servers VALUES ('existing', 'Music', 'https://music.example.com/', 'listener', 'encrypted-v2-value', 123, NULL)")
        } finally { old.close() }
        val upgraded = Room.databaseBuilder(context, LibraryDatabase::class.java, name)
            .addMigrations(app.lusound.cloud.JELLYFIN_MIGRATION, app.lusound.metadata.METADATA_MIGRATION, TRACK_SOURCE_MIGRATION, TRACK_QUALITY_MIGRATION, TRACK_IDENTITY_MIGRATION).build()
        try {
            val server = requireNotNull(upgraded.servers().get("existing"))
            assertEquals("SUBSONIC", server.kind)
            assertEquals("encrypted-v2-value", server.passwordCipher)
            assertEquals(123L, server.lastSync)
            assertNull(server.remoteUserId)
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }

    /**
     * The v4 → v5 rebuild drops and recreates `tracks`, which is the parent of two cascading foreign
     * keys. This asserts on a device that playlists and the metadata cache came through it intact and
     * that every pre-existing source shape was split correctly.
     */
    @Test fun trackSourceSplitPreservesPlaylistsMetadataAndBackfillsOldValues() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-${java.util.UUID.randomUUID()}.db"
        val old = createLegacyDatabase(context, name, 4)
        try {
            old.execSQL("INSERT INTO tracks VALUES ('content://media/1','一','歌手','专辑','Music',1000,NULL,'mpeg','MEDIASTORE')")
            old.execSQL("INSERT INTO tracks VALUES ('content://tree/document/1','二','歌手','专辑','Music',2000,NULL,'FLAC','DOCUMENT_TREE:content://tree%2Froot')")
            old.execSQL("INSERT INTO tracks VALUES ('file:///a.ncm','三','歌手','专辑','授权导入',3000,NULL,'ncm','DOCUMENT')")
            old.execSQL("INSERT INTO tracks VALUES ('lusound://srv1/1','四','歌手','专辑','在线音乐',4000,NULL,'mp3','SUBSONIC:srv1')")
            old.execSQL("INSERT INTO playlists (id, name, serverId, remoteId) VALUES (1,'本地歌单',NULL,NULL)")
            old.execSQL("INSERT INTO playlist_entries VALUES (1,'content://media/1',0)")
            old.execSQL("INSERT INTO playlist_entries VALUES (1,'content://media/1',1)")
            old.execSQL("INSERT INTO playlist_entries VALUES (1,'lusound://srv1/1',2)")
            old.execSQL("INSERT INTO track_metadata (uri,fingerprint,sourceRevision,lyrics,lyricsSource,checkedAt) VALUES ('content://media/1','fp','rev','[00:01.00]词','LRCLIB',123)")
        } finally { old.close() }
        val upgraded = Room.databaseBuilder(context, LibraryDatabase::class.java, name)
            .addMigrations(SERVER_MIGRATION, app.lusound.cloud.JELLYFIN_MIGRATION, app.lusound.metadata.METADATA_MIGRATION, TRACK_SOURCE_MIGRATION, TRACK_QUALITY_MIGRATION, TRACK_IDENTITY_MIGRATION).build()
        try {
            val tracks = upgraded.library().getTracks().associateBy { it.uri }
            assertEquals(4, tracks.size)
            tracks.getValue("content://media/1").let {
                assertEquals("mp3", it.container)
                assertEquals(TrackSource.MEDIASTORE, it.sourceKind)
                assertNull(it.sourceRef)
            }
            tracks.getValue("content://tree/document/1").let {
                assertEquals("flac", it.container)
                assertEquals(TrackSource.DOCUMENT_TREE, it.sourceKind)
                assertEquals("content://tree%2Froot", it.sourceRef)
            }
            tracks.getValue("file:///a.ncm").let {
                assertEquals("ncm", it.container)
                assertEquals(TrackSource.DOCUMENT, it.sourceKind)
                assertNull(it.sourceRef)
            }
            tracks.getValue("lusound://srv1/1").let {
                assertEquals("mp3", it.container)
                assertEquals(TrackSource.CLOUD, it.sourceKind)
                assertEquals("srv1", it.sourceRef)
            }
            assertEquals(
                listOf("content://media/1", "content://media/1", "lusound://srv1/1"),
                upgraded.library().playlistTrackUris(1),
            )
            val metadata = requireNotNull(upgraded.metadata().get("content://media/1"))
            assertEquals("LRCLIB", metadata.lyricsSource)
            assertEquals("[00:01.00]词", metadata.lyrics)
            assertEquals(123L, metadata.checkedAt)
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }

    /**
     * The v5 → v6 quality migration only appends nullable columns, so unlike the v4 → v5 rebuild it
     * cannot disturb the cascading children. Existing rows must keep their data and read as unknown
     * until something reports a figure.
     */
    @Test fun trackQualityColumnsAreAppendedWithoutDisturbingExistingRows() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-${java.util.UUID.randomUUID()}.db"
        val old = createLegacyDatabase(context, name, 5)
        try {
            old.execSQL("INSERT INTO tracks VALUES ('content://media/1','一','歌手','专辑','Music',1000,NULL,'flac','MEDIASTORE',NULL)")
            old.execSQL("INSERT INTO playlists (id,name,serverId,remoteId) VALUES (1,'本地歌单',NULL,NULL)")
            old.execSQL("INSERT INTO playlist_entries VALUES (1,'content://media/1',0)")
            old.execSQL("INSERT INTO track_metadata (uri,fingerprint,sourceRevision,lyrics,lyricsSource,checkedAt) VALUES ('content://media/1','fp','rev','[00:01.00]词','LRCLIB',123)")
        } finally { old.close() }
        val upgraded = Room.databaseBuilder(context, LibraryDatabase::class.java, name)
            .addMigrations(SERVER_MIGRATION, app.lusound.cloud.JELLYFIN_MIGRATION, app.lusound.metadata.METADATA_MIGRATION, TRACK_SOURCE_MIGRATION, TRACK_QUALITY_MIGRATION, TRACK_IDENTITY_MIGRATION).build()
        try {
            val track = upgraded.library().getTracks().single()
            assertEquals("一", track.title)
            assertNull("nothing has reported quality yet", track.bitrateKbps)
            assertNull(track.sampleRateHz)
            assertNull(track.bitDepth)
            assertEquals(listOf("content://media/1"), upgraded.library().playlistTrackUris(1))
            assertEquals("LRCLIB", requireNotNull(upgraded.metadata().get("content://media/1")).lyricsSource)
            // The appended columns are writable and survive a round trip.
            upgraded.library().upsertTracks(listOf(track.copy(bitrateKbps = 1411, sampleRateHz = 44100, bitDepth = 16)))
            val updated = upgraded.library().getTracks().single()
            assertEquals(1411, updated.bitrateKbps)
            assertEquals(44100, updated.sampleRateHz)
            assertEquals(16, updated.bitDepth)
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }

    /**
     * The v6 → v7 identity migration appends one nullable column. Existing rows must keep their data
     * and must get no identity, so a freshly migrated library hides nothing until a scan has actually
     * read the files.
     */
    @Test fun trackIdentityColumnIsAppendedWithoutGroupingAnything() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-${java.util.UUID.randomUUID()}.db"
        val old = createLegacyDatabase(context, name, 6)
        try {
            old.execSQL("INSERT INTO tracks VALUES ('content://media/1','一','歌手','专辑','Music',1000,NULL,'flac','MEDIASTORE',NULL,1411,44100,16)")
            old.execSQL("INSERT INTO playlists (id,name,serverId,remoteId) VALUES (1,'本地歌单',NULL,NULL)")
            old.execSQL("INSERT INTO playlist_entries VALUES (1,'content://media/1',0)")
        } finally { old.close() }
        val upgraded = Room.databaseBuilder(context, LibraryDatabase::class.java, name)
            .addMigrations(SERVER_MIGRATION, app.lusound.cloud.JELLYFIN_MIGRATION, app.lusound.metadata.METADATA_MIGRATION, TRACK_SOURCE_MIGRATION, TRACK_QUALITY_MIGRATION, TRACK_IDENTITY_MIGRATION).build()
        try {
            val track = upgraded.library().getTracks().single()
            assertEquals("一", track.title)
            assertEquals(1411, track.bitrateKbps)
            assertNull("nothing has read the file yet, so nothing may be grouped", track.identityKey)
            assertEquals(listOf("content://media/1"), upgraded.library().playlistTrackUris(1))
            // The appended column is writable, and repointing a playlist entry does not clash with the
            // (playlistId, position) primary key.
            val key = requireNotNull(app.lusound.library.fileIdentityKey("track.flac", "Music", 1024, 1_725_000_123_456L))
            upgraded.library().upsertTracks(listOf(track.copy(identityKey = key)))
            assertEquals(key, upgraded.library().getTracks().single().identityKey)
            upgraded.library().insertEntry(app.lusound.library.PlaylistEntry(1, "content://media/2", 1))
            upgraded.library().retargetEntries("content://media/2", "content://media/1")
            assertEquals(listOf("content://media/1", "content://media/1"), upgraded.library().playlistTrackUris(1))
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }

}

private fun createLegacyDatabase(context: Context, name: String, version: Int): android.database.sqlite.SQLiteDatabase {
    val schema = InstrumentationRegistry.getInstrumentation().context.assets
        .open("app.lusound.library.LibraryDatabase/$version.json").bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
    val database = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
    val entities = schema.getJSONArray("entities")
    for (index in 0 until entities.length()) {
        val entity = entities.getJSONObject(index)
        database.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
        val indices = entity.optJSONArray("indices")
        for (item in 0 until (indices?.length() ?: 0)) database.execSQL(requireNotNull(indices).getJSONObject(item).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
    }
    database.version = version
    return database
}
