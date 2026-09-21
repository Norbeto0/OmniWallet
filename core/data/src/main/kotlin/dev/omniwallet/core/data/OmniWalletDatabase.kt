package dev.omniwallet.core.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CredentialEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class OmniWalletDatabase : RoomDatabase() {
    abstract fun credentialDao(): CredentialDao

    companion object {
        const val NAME = "omniwallet-secure.db"

        /**
         * v1 -> v2: the three `place_*` columns behind M7's nearby ranking.
         *
         * Written by hand rather than reached with `fallbackToDestructiveMigration`,
         * which would be one line and would delete the user's renames,
         * favourites and usage history on upgrade. Everything in this table
         * except those is re-derivable from the device; those are not, and a
         * silent wipe of them is exactly the failure the merge rules elsewhere
         * in this module exist to prevent.
         *
         * All three columns are nullable with no default, so existing rows come
         * through as untagged, which is the correct reading of "this user has
         * never tagged a place".
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE credentials ADD COLUMN place_label TEXT")
                db.execSQL("ALTER TABLE credentials ADD COLUMN place_lat REAL")
                db.execSQL("ALTER TABLE credentials ADD COLUMN place_lon REAL")
            }
        }

        /** Every migration, in order. Passed to both database builders. */
        val MIGRATIONS = arrayOf(MIGRATION_1_2)
    }
}
