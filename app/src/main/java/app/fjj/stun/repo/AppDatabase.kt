package app.fjj.stun.repo

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Profile::class], version = 7, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao

    companion object {
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN sortIndex INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN vaydnsPubkey TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE profiles ADD COLUMN vaydnsDomain TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE profiles ADD COLUMN vaydnsMode TEXT NOT NULL DEFAULT 'doh'")
                db.execSQL("ALTER TABLE profiles ADD COLUMN vaydnsResolvers TEXT NOT NULL DEFAULT 'https://dns.google/dns-query,https://cloudflare-dns.com/dns-query'")
                db.execSQL("ALTER TABLE profiles ADD COLUMN vaydnsQueryType TEXT NOT NULL DEFAULT 'txt'")
            }
        }
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "stun_database"
                )
                .addMigrations(MIGRATION_5_6, MIGRATION_6_7)
                .fallbackToDestructiveMigration(true)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
