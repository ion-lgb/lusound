package app.lusound.library

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v4 → v5: split the overloaded `origin` string into `sourceKind` + `sourceRef`, and normalise the
 * three container vocabularies into one `container` value.
 *
 * `tracks` is a foreign-key parent: `playlist_entries.trackUri` and `track_metadata.uri` both
 * reference it with `ON DELETE CASCADE`. SQLite implements `DROP TABLE` as an implicit
 * `DELETE FROM`, so dropping the parent while foreign keys are enforced cascades into both child
 * tables and silently empties every playlist and every cached lyric. The children are therefore
 * stashed into `*_swap` tables before the rebuild and restored after it, which keeps this migration
 * correct whether or not the framework happens to have foreign keys enabled.
 *
 * The statements are exposed as data rather than inlined in [Migration.migrate] so that
 * `TrackSourceMigrationTest` can execute exactly this SQL against a real SQLite on the JVM, without
 * a device. The container mapping below deliberately duplicates [audioContainer]; that test asserts
 * the two agree for every documented input, so the copies cannot drift apart unnoticed. Any change
 * to one mapping must change the other.
 */
internal fun trackSourceMigration(): List<String> = listOf(
    "DROP TABLE IF EXISTS `playlist_entries_swap`",
    "CREATE TABLE `playlist_entries_swap` AS SELECT `playlistId`, `trackUri`, `position` FROM `playlist_entries`",
    "DROP TABLE IF EXISTS `track_metadata_swap`",
    "CREATE TABLE `track_metadata_swap` AS SELECT `uri`, `fingerprint`, `sourceRevision`, `lyrics`, `lyricsSource`, `lyricsUrl`, `coverUri`, `coverSource`, `coverUrl`, `checkedAt`, `error` FROM `track_metadata`",
    "DROP TABLE IF EXISTS `tracks_new`",
    "CREATE TABLE `tracks_new` (`uri` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `album` TEXT NOT NULL, `folder` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, `artworkUri` TEXT, `container` TEXT NOT NULL, `sourceKind` TEXT NOT NULL, `sourceRef` TEXT, PRIMARY KEY(`uri`))",
    """
    INSERT INTO `tracks_new` (`uri`, `title`, `artist`, `album`, `folder`, `durationMs`, `artworkUri`, `container`, `sourceKind`, `sourceRef`)
    SELECT `uri`, `title`, `artist`, `album`, `folder`, `durationMs`, `artworkUri`,
        CASE lower(trim(`format`))
            WHEN 'mpeg' THEN 'mp3' WHEN 'mp3' THEN 'mp3'
            WHEN 'mp4' THEN 'm4a' WHEN 'm4a' THEN 'm4a' WHEN 'x-m4a' THEN 'm4a'
            WHEN 'aac' THEN 'aac' WHEN 'x-aac' THEN 'aac'
            WHEN 'flac' THEN 'flac' WHEN 'x-flac' THEN 'flac'
            WHEN 'wav' THEN 'wav' WHEN 'x-wav' THEN 'wav' WHEN 'wave' THEN 'wav' WHEN 'vnd.wave' THEN 'wav'
            WHEN 'ogg' THEN 'ogg' WHEN 'x-ogg' THEN 'ogg'
            WHEN 'opus' THEN 'opus'
            WHEN 'aiff' THEN 'aiff' WHEN 'x-aiff' THEN 'aiff' WHEN 'aif' THEN 'aiff'
            WHEN 'wma' THEN 'wma' WHEN 'x-ms-wma' THEN 'wma'
            WHEN 'amr' THEN 'amr'
            ELSE lower(trim(`format`))
        END,
        CASE
            WHEN `origin` LIKE 'DOCUMENT_TREE:%' THEN 'DOCUMENT_TREE'
            WHEN `origin` = 'MEDIASTORE' THEN 'MEDIASTORE'
            WHEN `origin` = 'DOCUMENT' THEN 'DOCUMENT'
            ELSE 'CLOUD'
        END,
        CASE
            WHEN `origin` LIKE 'DOCUMENT_TREE:%' THEN substr(`origin`, length('DOCUMENT_TREE:') + 1)
            WHEN `origin` IN ('MEDIASTORE', 'DOCUMENT') THEN NULL
            WHEN instr(`origin`, ':') = 0 THEN NULL
            ELSE substr(`origin`, instr(`origin`, ':') + 1)
        END
    FROM `tracks`
    """.trimIndent(),
    "DROP TABLE `tracks`",
    "ALTER TABLE `tracks_new` RENAME TO `tracks`",
    "DELETE FROM `playlist_entries`",
    "INSERT INTO `playlist_entries` (`playlistId`, `trackUri`, `position`) SELECT `playlistId`, `trackUri`, `position` FROM `playlist_entries_swap`",
    "DROP TABLE `playlist_entries_swap`",
    "DELETE FROM `track_metadata`",
    "INSERT INTO `track_metadata` (`uri`, `fingerprint`, `sourceRevision`, `lyrics`, `lyricsSource`, `lyricsUrl`, `coverUri`, `coverSource`, `coverUrl`, `checkedAt`, `error`) SELECT `uri`, `fingerprint`, `sourceRevision`, `lyrics`, `lyricsSource`, `lyricsUrl`, `coverUri`, `coverSource`, `coverUrl`, `checkedAt`, `error` FROM `track_metadata_swap`",
    "DROP TABLE `track_metadata_swap`",
)

val TRACK_SOURCE_MIGRATION: Migration = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        trackSourceMigration().forEach(db::execSQL)
    }
}
