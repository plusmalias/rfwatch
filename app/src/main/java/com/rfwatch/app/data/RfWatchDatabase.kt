package com.rfwatch.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
    version = 1,
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
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
