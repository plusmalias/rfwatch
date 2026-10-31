package com.rfwatch.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.rfwatch.app.data.crypto.KeystoreKeyManager
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

@Database(
    entities = [
        DeviceEntity::class,
        ObservationEntity::class,
        AlertEntity::class,
        EventEntity::class,
        GeofenceEntity::class,
        BaselineEntity::class,
        SessionEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class RfWatchDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun observationDao(): ObservationDao
    abstract fun alertDao(): AlertDao
    abstract fun eventDao(): EventDao
    abstract fun geofenceDao(): GeofenceDao
    abstract fun baselineDao(): BaselineDao
    abstract fun sessionDao(): SessionDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE devices ADD COLUMN brand TEXT")
                db.execSQL("ALTER TABLE devices ADD COLUMN category TEXT")
                db.execSQL("ALTER TABLE devices ADD COLUMN observedLat REAL")
                db.execSQL("ALTER TABLE devices ADD COLUMN observedLon REAL")
                db.execSQL("ALTER TABLE devices ADD COLUMN observedAccuracyM REAL")
                db.execSQL("ALTER TABLE devices ADD COLUMN observedAtMs INTEGER")
            }
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE devices ADD COLUMN radioDetails TEXT")
            }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE devices ADD COLUMN kind TEXT")
                db.execSQL("ALTER TABLE devices ADD COLUMN model TEXT")
                db.execSQL("ALTER TABLE devices ADD COLUMN seenCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE devices ADD COLUMN rssiMin INTEGER")
                db.execSQL("ALTER TABLE devices ADD COLUMN rssiMax INTEGER")
                db.execSQL("ALTER TABLE devices ADD COLUMN wifiSecurity TEXT")
                db.execSQL("ALTER TABLE devices ADD COLUMN wifiBand TEXT")
            }
        }
        /**
         * Opens the encrypted database. The passphrase comes from the Android Keystore via
         * [KeystoreKeyManager]; SQLCipher's [SupportFactory] encrypts the whole file at rest.
         */
        fun create(context: Context): RfWatchDatabase {
            SQLiteDatabase.loadLibs(context)
            val passphrase = KeystoreKeyManager(context).getOrCreatePassphrase()
            val factory = SupportFactory(passphrase, null, /* clearPassphrase = */ true)
            return Room.databaseBuilder(context, RfWatchDatabase::class.java, "rfwatch.db")
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
