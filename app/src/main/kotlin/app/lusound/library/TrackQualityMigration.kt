package app.lusound.library

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v5 → v6: append the nullable quality columns.
 *
 * Deliberately different in shape from the v4 → v5 rebuild next door. This migration only adds
 * columns, so it cannot disturb the two cascading foreign keys that point at `tracks`, needs no
 * staging tables, and works on every SQLite version Android has shipped (adding a nullable column
 * with no default has always been supported, unlike dropping or renaming one).
 *
 * Existing rows get NULL, which is the honest value: nothing has reported their quality yet. A later
 * MediaStore scan, document import or server sync fills it in, and the UI shows unknown until then
 * rather than inferring a bitrate from the container name.
 */
internal fun trackQualityMigration(): List<String> = listOf(
    "ALTER TABLE `tracks` ADD COLUMN `bitrateKbps` INTEGER",
    "ALTER TABLE `tracks` ADD COLUMN `sampleRateHz` INTEGER",
    "ALTER TABLE `tracks` ADD COLUMN `bitDepth` INTEGER",
)

val TRACK_QUALITY_MIGRATION: Migration = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        trackQualityMigration().forEach(db::execSQL)
    }
}
