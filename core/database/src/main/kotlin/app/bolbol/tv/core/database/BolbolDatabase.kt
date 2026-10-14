package app.bolbol.tv.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The app's single Room database. v1 shipped in v1.0.0, so since then every schema change bumps [version], adds a
 * tested migration to [Migrations] and exports the new schema JSON (hard rule 11; never a destructive fallback:
 * progress and My List are the user's data). The schema is exported to `core/database/schemas`.
 * - v2 (v1.4): `download` table (offline saving).
 */
@Database(
    entities = [
        ProgressEntity::class,
        TitleSnapshotEntity::class,
        PrefsEntity::class,
        SubtitleStyleEntity::class,
        SubtitleOffsetEntity::class,
        HomeCacheEntity::class,
        MyListEntity::class,
        DownloadEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class BolbolDatabase : RoomDatabase() {
    abstract fun watchDao(): WatchDao
    abstract fun prefsDao(): PrefsDao
    abstract fun homeCacheDao(): HomeCacheDao
    abstract fun myListDao(): MyListDao
    abstract fun downloadDao(): DownloadDao

    companion object {
        const val FILE_NAME = "bolbol.db"

        /** Opens (lazily, on first query) the on-disk database. Keep one instance per process. */
        fun create(context: Context): BolbolDatabase =
            Room.databaseBuilder(context.applicationContext, BolbolDatabase::class.java, FILE_NAME)
                .addMigrations(*Migrations.ALL)
                .build()
    }
}
