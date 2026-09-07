package app.lusound

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lusound.cloud.SERVER_MIGRATION
import app.lusound.library.LibraryDatabase
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
        val upgraded = Room.databaseBuilder(context, LibraryDatabase::class.java, name).addMigrations(SERVER_MIGRATION, app.lusound.cloud.JELLYFIN_MIGRATION).build()
        try {
            assertEquals("Local", upgraded.library().getTracks().single().title)
            assertEquals(listOf("content://test/1"), upgraded.library().playlistTrackUris(1))
            assertNull(upgraded.library().getPlaylist(1)!!.serverId)
            upgraded.library().insertEntry(app.lusound.library.PlaylistEntry(1, "content://test/1", 1))
            assertEquals(listOf("content://test/1", "content://test/1"), upgraded.library().playlistTrackUris(1))
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
            .addMigrations(app.lusound.cloud.JELLYFIN_MIGRATION).build()
        try {
            val server = requireNotNull(upgraded.servers().get("existing"))
            assertEquals("SUBSONIC", server.kind)
            assertEquals("encrypted-v2-value", server.passwordCipher)
            assertEquals(123L, server.lastSync)
            assertNull(server.remoteUserId)
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
