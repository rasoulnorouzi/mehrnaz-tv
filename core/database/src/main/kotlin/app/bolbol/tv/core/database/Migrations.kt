package app.bolbol.tv.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Every schema migration since v1.0.0 (hard rule 11: never edit the schema in place, never fall back to a destructive
 * migration). Each migration's SQL is a plain list so `MigrationTest` can run it on a real SQLite database built from the
 * exported schema JSON of the old version, and compare the result with the new version's JSON.
 */
object Migrations {

    /** v1 → v2 (v1.4 offline saving): adds the `download` table. Additive only; every v1 table is untouched. */
    val MIGRATION_1_2_SQL: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `download` (`key` TEXT NOT NULL, `postId` INTEGER NOT NULL, " +
            "`season` INTEGER NOT NULL, `episode` INTEGER NOT NULL, `type` TEXT, `title` TEXT NOT NULL, " +
            "`faTitle` TEXT NOT NULL, `posterUrl` TEXT, `backdropUrl` TEXT, `year` INTEGER, `imdbRating` REAL, " +
            "`hasDub` INTEGER NOT NULL, `hasSubtitle` INTEGER NOT NULL, `subtitleLine` TEXT, `quality` INTEGER NOT NULL, " +
            "`audio` TEXT NOT NULL, `url` TEXT NOT NULL, `subtitleUrl` TEXT, `label` TEXT NOT NULL, `sizeText` TEXT, " +
            "`status` TEXT NOT NULL, `bytesDownloaded` INTEGER NOT NULL, `totalBytes` INTEGER, `failure` TEXT, " +
            "`createdAtMs` INTEGER NOT NULL, `videoPath` TEXT NOT NULL, `subtitlePath` TEXT, `posterPath` TEXT, " +
            "`validator` TEXT, PRIMARY KEY(`key`))",
        "CREATE INDEX IF NOT EXISTS `index_download_postId` ON `download` (`postId`)",
    )

    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_1_2_SQL.forEach(db::execSQL)
        }
    }

    /** Pass to `Room.databaseBuilder(…).addMigrations(*ALL)`. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
