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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Executes the real v4 → v5 migration SQL against a real SQLite, on the JVM.
 *
 * The instrumented `DatabaseMigrationTest` remains the authoritative check on a device, because the
 * SQLite bundled here is newer than any Android release and Room's own schema validation only runs
 * there. What this test buys is that the SQL itself — the backfill expressions and, above all, the
 * foreign-key behaviour around rebuilding `tracks` — is verified on every build instead of only when
 * someone has a device and two media servers to hand.
 */
class TrackSourceMigrationTest {
    private lateinit var db: Connection

    @Before
    fun openV4Database() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
        v4Schema().forEach(::execute)
    }

    @After
    fun close() {
        db.close()
    }

    // ---- the hazard this migration exists to survive --------------------------

    @Test
    fun droppingTheTracksTableCascadesIntoItsChildren() {
        // Documents why the migration stashes children rather than simply rebuilding `tracks`:
        // with foreign keys enabled SQLite runs an implicit DELETE before dropping, and both child
        // tables declare ON DELETE CASCADE. Any future schema change to `tracks` must respect this.
        val scratch = DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            scratch.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
            v4Schema().forEach { scratch.createStatement().use { statement -> statement.execute(it) } }
            scratch.createStatement().use {
                it.execute("INSERT INTO `tracks` (`uri`,`title`,`artist`,`album`,`folder`,`durationMs`,`artworkUri`,`format`,`origin`) VALUES ('u','t','a','al','f',1,NULL,'flac','MEDIASTORE')")
                it.execute("INSERT INTO `playlists` (`id`,`name`) VALUES (1,'p')")
                it.execute("INSERT INTO `playlist_entries` (`playlistId`,`trackUri`,`position`) VALUES (1,'u',0)")
                it.execute("INSERT INTO `track_metadata` (`uri`,`fingerprint`,`sourceRevision`,`checkedAt`) VALUES ('u','fp','rev',1)")
                it.execute("DROP TABLE `tracks`")
            }
            assertEquals(0, count(scratch, "playlist_entries"))
            assertEquals(0, count(scratch, "track_metadata"))
        } finally {
            scratch.close()
        }
    }

    @Test
    fun migrationKeepsPlaylistEntriesDuplicatesAndCachedMetadata() {
        insertTrack("content://media/1", "mpeg", "MEDIASTORE")
        insertTrack("lusound://srv1/1", "mp3", "SUBSONIC:srv1")
        db.createStatement().use {
            it.execute("INSERT INTO `playlists` (`id`,`name`) VALUES (1,'本地歌单')")
            // The same song twice is a deliberate user choice and must survive reordering or rebuilding.
            it.execute("INSERT INTO `playlist_entries` (`playlistId`,`trackUri`,`position`) VALUES (1,'content://media/1',0)")
            it.execute("INSERT INTO `playlist_entries` (`playlistId`,`trackUri`,`position`) VALUES (1,'content://media/1',1)")
            it.execute("INSERT INTO `playlist_entries` (`playlistId`,`trackUri`,`position`) VALUES (1,'lusound://srv1/1',2)")
            it.execute("INSERT INTO `track_metadata` (`uri`,`fingerprint`,`sourceRevision`,`lyrics`,`lyricsSource`,`checkedAt`) VALUES ('content://media/1','fp','rev','[00:01.00]词','LRCLIB',123)")
        }

        migrate()

        assertEquals(2, count(db, "tracks"))
        val entries = query("SELECT `playlistId`,`trackUri`,`position` FROM `playlist_entries` ORDER BY `position`")
        assertEquals(listOf("1|content://media/1|0", "1|content://media/1|1", "1|lusound://srv1/1|2"), entries)
        val metadata = query("SELECT `uri`,`lyrics`,`lyricsSource`,`checkedAt` FROM `track_metadata`")
        assertEquals(listOf("content://media/1|[00:01.00]词|LRCLIB|123"), metadata)
        // The staging tables must not outlive the migration.
        assertFalse(tableExists("playlist_entries_swap"))
        assertFalse(tableExists("track_metadata_swap"))
        assertFalse(tableExists("tracks_new"))
    }

    // ---- the backfill --------------------------------------------------------

    @Test
    fun migrationSplitsEveryOriginShapeThatExistedInVersionFour() {
        insertTrack("content://media/1", "wav", "MEDIASTORE")
        insertTrack("content://tree/document/1", "flac", "DOCUMENT_TREE:content://com.android.externalstorage.documents/tree/primary%3AMusic")
        insertTrack("file:///music/a.ncm", "ncm", "DOCUMENT")
        insertTrack("lusound://srv1/9", "mp3", "SUBSONIC:srv1")
        insertTrack("lusound://srv2/7", "flac", "PLEX:srv2")

        migrate()

        assertTrack("content://media/1", "wav", TrackSource.MEDIASTORE, null)
        assertTrack("content://tree/document/1", "flac", TrackSource.DOCUMENT_TREE, "content://com.android.externalstorage.documents/tree/primary%3AMusic")
        assertTrack("file:///music/a.ncm", "ncm", TrackSource.DOCUMENT, null)
        assertTrack("lusound://srv1/9", "mp3", TrackSource.CLOUD, "srv1")
        assertTrack("lusound://srv2/7", "flac", TrackSource.CLOUD, "srv2")
    }

    @Test
    fun migratedContainerIsLowercasedAndUppercaseSubtypesAreNormalised() {
        insertTrack("content://media/upper", "FLAC", "MEDIASTORE")
        insertTrack("content://media/padded", "  WAV  ", "MEDIASTORE")

        migrate()

        assertTrack("content://media/upper", "flac", TrackSource.MEDIASTORE, null)
        assertTrack("content://media/padded", "wav", TrackSource.MEDIASTORE, null)
    }

    @Test
    fun sqlBackfillAgreesWithAudioContainerForEveryKnownInput() {
        // The mapping exists twice on purpose: once in Kotlin for new scans, once in SQL for existing
        // rows. This is the guard that stops the two copies drifting apart.
        val inputs = listOf(
            "mpeg", "mp3", "MP4", "m4a", "x-m4a", "aac", "x-aac", "flac", "x-flac",
            "wav", "x-wav", "wave", "vnd.wave", "ogg", "x-ogg", "opus", "aiff", "x-aiff", "aif",
            "wma", "x-ms-wma", "amr", "weird-codec", "  flac  ", "",
        )
        inputs.forEachIndexed { index, raw -> insertTrack("content://media/$index", raw, "MEDIASTORE") }

        migrate()

        inputs.forEachIndexed { index, raw ->
            assertEquals(raw, audioContainer(raw), containerOf("content://media/$index"))
        }
    }

    @Test
    fun migrationRemovesTheLegacyColumns() {
        insertTrack("content://media/1", "mpeg", "MEDIASTORE")

        migrate()

        val columns = query("SELECT `name` FROM pragma_table_info('tracks')")
        assertEquals(
            listOf("uri", "title", "artist", "album", "folder", "durationMs", "artworkUri", "container", "sourceKind", "sourceRef"),
            columns,
        )
        assertFalse(columns.contains("format"))
        assertFalse(columns.contains("origin"))
    }

    @Test
    fun anEmptyLibraryMigratesWithoutError() {
        migrate()

        assertEquals(0, count(db, "tracks"))
        assertNull(containerOfOrNull("content://nothing"))
    }

    /**
     * The migration hand-writes the new `tracks` table, while Room validates the result against the
     * schema KSP generates from the entity. If the two disagree on a column name, type, nullability
     * or default, the only place it shows up is a crash on a real device at first launch. Comparing
     * the migrated table against the exported version-five schema closes that gap on the JVM.
     */
    @Test
    fun migratedTracksTableMatchesTheExportedVersionFiveSchema() {
        insertTrack("content://media/1", "mpeg", "MEDIASTORE")

        migrate()

        val database = Json.parseToJsonElement(File("schemas/app.lusound.library.LibraryDatabase/5.json").readText())
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

    // ---- helpers -------------------------------------------------------------

    /** Runs the migration in one transaction, exactly as Room does. */
    private fun migrate() {
        db.autoCommit = false
        try {
            trackSourceMigration().forEach(::execute)
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

    private fun insertTrack(uri: String, format: String, origin: String) {
        db.prepareStatement("INSERT INTO `tracks` (`uri`,`title`,`artist`,`album`,`folder`,`durationMs`,`artworkUri`,`format`,`origin`) VALUES (?,?,?,?,?,?,?,?,?)").use {
            it.setString(1, uri); it.setString(2, "标题"); it.setString(3, "歌手"); it.setString(4, "专辑")
            it.setString(5, "Music"); it.setLong(6, 1_000); it.setString(7, null); it.setString(8, format); it.setString(9, origin)
            it.executeUpdate()
        }
    }

    private fun query(sql: String): List<String> = db.createStatement().use { statement ->
        statement.executeQuery(sql).use { rows ->
            val width = rows.metaData.columnCount
            buildList { while (rows.next()) add((1..width).joinToString("|") { rows.getString(it) ?: "" }) }
        }
    }

    private fun count(connection: Connection, table: String): Int = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT COUNT(*) FROM `$table`").use { rows -> rows.next(); rows.getInt(1) }
    }

    private fun tableExists(name: String): Boolean = db.prepareStatement("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?").use {
        it.setString(1, name)
        it.executeQuery().use { rows -> rows.next(); rows.getInt(1) > 0 }
    }

    /** Column name, declared type, NOT NULL and default, as the database actually stores them. */
    private fun liveColumns(table: String): List<List<String>> = db.createStatement().use { statement ->
        statement.executeQuery("SELECT `name`, `type`, `notnull`, `dflt_value` FROM pragma_table_info('$table')").use { rows ->
            buildList {
                while (rows.next()) {
                    // SQLite reports NOT NULL as 1/0; the exported schema stores true/false.
                    add(listOf(rows.getString(1), rows.getString(2), (rows.getInt(3) != 0).toString(), rows.getString(4).orEmpty()))
                }
            }
        }
    }

    private fun containerOf(uri: String): String = requireNotNull(containerOfOrNull(uri))

    private fun containerOfOrNull(uri: String): String? = db.prepareStatement("SELECT `container` FROM `tracks` WHERE `uri` = ?").use {
        it.setString(1, uri)
        it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
    }

    private fun assertTrack(uri: String, container: String, sourceKind: String, sourceRef: String?) {
        db.prepareStatement("SELECT `container`,`sourceKind`,`sourceRef` FROM `tracks` WHERE `uri` = ?").use {
            it.setString(1, uri)
            it.executeQuery().use { rows ->
                assertTrue("missing track $uri", rows.next())
                assertEquals(container, rows.getString(1))
                assertEquals(sourceKind, rows.getString(2))
                assertEquals(sourceRef, rows.getString(3))
            }
        }
    }

    private fun v4Schema(): List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `tracks` (`uri` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `album` TEXT NOT NULL, `folder` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, `artworkUri` TEXT, `format` TEXT NOT NULL, `origin` TEXT NOT NULL, PRIMARY KEY(`uri`))",
        "CREATE TABLE IF NOT EXISTS `playlists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `serverId` TEXT, `remoteId` TEXT)",
        "CREATE TABLE IF NOT EXISTS `playlist_entries` (`playlistId` INTEGER NOT NULL, `trackUri` TEXT NOT NULL, `position` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`playlistId`, `position`), FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`trackUri`) REFERENCES `tracks`(`uri`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_playlist_entries_trackUri` ON `playlist_entries` (`trackUri`)",
        "CREATE TABLE IF NOT EXISTS `track_metadata` (`uri` TEXT NOT NULL, `fingerprint` TEXT NOT NULL, `sourceRevision` TEXT NOT NULL, `lyrics` TEXT, `lyricsSource` TEXT, `lyricsUrl` TEXT, `coverUri` TEXT, `coverSource` TEXT, `coverUrl` TEXT, `checkedAt` INTEGER NOT NULL, `error` TEXT, PRIMARY KEY(`uri`), FOREIGN KEY(`uri`) REFERENCES `tracks`(`uri`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    )
}
