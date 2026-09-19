package app.lusound.library

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v6 → v7: append the nullable file-identity column.
 *
 * Append-only like the quality migration before it: adding a nullable column cannot disturb the two
 * cascading foreign keys that point at `tracks`, needs no staging tables, and works on every SQLite
 * version Android has shipped.
 *
 * Existing rows get NULL and therefore no identity. That is deliberate: their identity can only come
 * from a provider that reports the file's name, size and modification time, which is read during a
 * scan. The next scan or import fills it in, and until then nothing is hidden or grouped -- a wrong
 * guess here would hide a song, so absent data means absent grouping.
 */
internal fun trackIdentityMigration(): List<String> = listOf(
    "ALTER TABLE `tracks` ADD COLUMN `identityKey` TEXT",
)

val TRACK_IDENTITY_MIGRATION: Migration = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        trackIdentityMigration().forEach(db::execSQL)
    }
}
