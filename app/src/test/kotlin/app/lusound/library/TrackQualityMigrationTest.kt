package app.lusound.library

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The v5 → v6 quality migration, executed against a real SQLite on the JVM.
 *
 * This migration only appends nullable columns, so unlike the v4 → v5 rebuild it cannot disturb the
 * cascading children. That difference is asserted rather than assumed: playlists, playlist entries
 * and cached metadata must all still be there afterwards, and the migrated table must match the
 * schema Room generates from the entity (a mismatch there is a first-launch crash on a device).
 */
class TrackQualityMigrationTest {
    private lateinit var db: Connection

    @Before
    fun openVersionFiveDatabase() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
        versionFiveSchema().forEach(::execute)
        execute("INSERT INTO `tracks` (`uri`,`title`,`artist`,`album`,`folder`,`durationMs`,`artworkUri`,`container`,`sourceKind`,`sourceRef`) VALUES ('content://media/1','标题','歌手','专辑','Music',1000,NULL,'flac','MEDIASTORE',NULL)")
        execute("INSERT INTO `playlists` (`id`,`name`) VALUES (1,'本地歌单')")
        execute("INSERT INTO `playlist_entries` (`playlistId`,`trackUri`,`position`) VALUES (1,'content://media/1',0)")
        execute("INSERT INTO `track_metadata` (`uri`,`fingerprint`,`sourceRevision`,`lyrics`,`lyricsSource`,`checkedAt`) VALUES ('content://media/1','fp','rev','[00:01.00]词','LRCLIB',123)")
    }

    @After
    fun close() {
        db.close()
    }

    @Test
    fun existingRowsKeepTheirDataAndGetUnknownQuality() {
        migrate()

        db.createStatement().use { statement ->
            statement.executeQuery("SELECT `title`,`container`,`sourceKind`,`bitrateKbps`,`sampleRateHz`,`bitDepth` FROM `tracks`").use { rows ->
                assertTrue(rows.next())
                assertEquals("标题", rows.getString(1))
                assertEquals("flac", rows.getString(2))
                assertEquals(TrackSource.MEDIASTORE, rows.getString(3))
                assertNull("nothing has reported quality yet", rows.getObject(4))
                assertNull(rows.getObject(5))
                assertNull(rows.getObject(6))
                assertEquals("the migration must not invent rows", false, rows.next())
            }
        }
    }

    @Test
    fun appendingColumnsLeavesTheCascadingChildrenUntouched() {
        migrate()

        assertEquals(1, count("playlists"))
        assertEquals(1, count("playlist_entries"))
        assertEquals(1, count("track_metadata"))
        assertEquals("content://media/1", query("SELECT `trackUri` FROM `playlist_entries`").single())
        assertEquals("[00:01.00]词", query("SELECT `lyrics` FROM `track_metadata`").single())
    }

    @Test
    fun qualityCanBeStoredAndReadBack() {
        migrate()

        execute("UPDATE `tracks` SET `bitrateKbps`=1411, `sampleRateHz`=44100, `bitDepth`=16 WHERE `uri`='content://media/1'")
        assertEquals(listOf("1411|44100|16"), query("SELECT `bitrateKbps`,`sampleRateHz`,`bitDepth` FROM `tracks`"))
    }

    @Test
    fun migratedTracksTableMatchesTheExportedVersionSixSchema() {
        migrate()

        val database = Json.parseToJsonElement(File("schemas/app.lusound.library.LibraryDatabase/6.json").readText())
            .jsonObject.getValue("database").jsonObject
        val tracks = database.getValue("entities").jsonArray
            .map { it.jsonObject }
            .single { it.getValue("tableName").jsonPrimitive.content == "tracks" }
        val expected = tracks.getValue("fields").jsonArray.map { field ->
            val value = field.jsonObject
            listOf(
                value.getValue("columnName").jsonPrimitive.content,
                value.getValue("affinity").jsonPrimitive.content,
                (value["notNull"]?.jsonPrimitive?.booleanOrNull ?: false).toString(),
                value["defaultValue"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }

        assertEquals(expected, liveColumns("tracks"))
    }

    private fun migrate() {
        db.autoCommit = false
        try {
            trackQualityMigration().forEach(::execute)
            db.commit()
        } catch (error: Exception) {
            db.rollback()
            throw error
        } finally {
            db.autoCommit = true
        }
    }

    private fun execute(sql: String) {
        db.createStatement().use { it.execute(sql) }
    }

    private fun query(sql: String): List<String> = db.createStatement().use { statement ->
        statement.executeQuery(sql).use { rows ->
            val width = rows.metaData.columnCount
            buildList { while (rows.next()) add((1..width).joinToString("|") { rows.getString(it) ?: "" }) }
        }
    }

    private fun count(table: String): Int = db.createStatement().use { statement ->
        statement.executeQuery("SELECT COUNT(*) FROM `$table`").use { rows -> rows.next(); rows.getInt(1) }
    }

    /** Column name, declared type, NOT NULL and default, as the database actually stores them. */
    private fun liveColumns(table: String): List<List<String>> = db.createStatement().use { statement ->
        statement.executeQuery("SELECT `name`, `type`, `notnull`, `dflt_value` FROM pragma_table_info('$table')").use { rows ->
            buildList {
                while (rows.next()) {
                    add(listOf(rows.getString(1), rows.getString(2), (rows.getInt(3) != 0).toString(), rows.getString(4).orEmpty()))
                }
            }
        }
    }

    private fun versionFiveSchema(): List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `tracks` (`uri` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `album` TEXT NOT NULL, `folder` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, `artworkUri` TEXT, `container` TEXT NOT NULL, `sourceKind` TEXT NOT NULL, `sourceRef` TEXT, PRIMARY KEY(`uri`))",
        "CREATE TABLE IF NOT EXISTS `playlists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `serverId` TEXT, `remoteId` TEXT)",
        "CREATE TABLE IF NOT EXISTS `playlist_entries` (`playlistId` INTEGER NOT NULL, `trackUri` TEXT NOT NULL, `position` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`playlistId`, `position`), FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`trackUri`) REFERENCES `tracks`(`uri`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_playlist_entries_trackUri` ON `playlist_entries` (`trackUri`)",
        "CREATE TABLE IF NOT EXISTS `track_metadata` (`uri` TEXT NOT NULL, `fingerprint` TEXT NOT NULL, `sourceRevision` TEXT NOT NULL, `lyrics` TEXT, `lyricsSource` TEXT, `lyricsUrl` TEXT, `coverUri` TEXT, `coverSource` TEXT, `coverUrl` TEXT, `checkedAt` INTEGER NOT NULL, `error` TEXT, PRIMARY KEY(`uri`), FOREIGN KEY(`uri`) REFERENCES `tracks`(`uri`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    )
}
