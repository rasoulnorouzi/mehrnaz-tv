package app.bolbol.tv.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The app's single Room database. Version 1 while unreleased: new tables are added to it in place, which changes the
 * identity hash, so an already-installed debug build must clear its app data. Once a build is used for real,
 * every schema change needs a migration (never destructive fallback: progress is the user's data).
 * The schema is exported to `core/database/schemas`.
 */
@Database(
    entities = [
        ProgressEntity::class,
        TitleSnapshotEntity::class,
        PrefsEntity::class,
        SubtitleStyleEntity::class,
        SubtitleOffsetEntity::class,
        HomeCacheEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class BolbolDatabase : RoomDatabase() {
    abstract fun watchDao(): WatchDao
    abstract fun prefsDao(): PrefsDao
    abstract fun homeCacheDao(): HomeCacheDao

    companion object {
        const val FILE_NAME = "bolbol.db"

        /** Opens (lazily, on first query) the on-disk database. Keep one instance per process. */
        fun create(context: Context): BolbolDatabase =
            Room.databaseBuilder(context.applicationContext, BolbolDatabase::class.java, FILE_NAME).build()
    }
}
