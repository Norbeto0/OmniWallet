package dev.omniwallet.core.data

import android.content.Context
import androidx.room.Room
import java.io.File

/**
 * Moves an M3 plaintext library into the encrypted database, once.
 *
 * Deleting the old database and re-scanning would be far less code, and would
 * quietly throw away exactly the part the user made: renames, favourites, what
 * is hidden, what was used when. Everything else is re-derivable from the
 * Flipper; that is not. Losing it to an upgrade the user did not ask for would
 * be the same category of mistake as a scan wiping the library.
 */
internal object LegacyDatabaseMigration {

    /** The M3 database name, before encryption. */
    const val LEGACY_NAME = "omniwallet.db"

    fun hasLegacyDatabase(context: Context): Boolean =
        context.getDatabasePath(LEGACY_NAME).exists()

    /**
     * Copy everything from the plaintext database into [target], then remove
     * the plaintext files.
     *
     * The old database is deleted only after the copy has returned, so an
     * interruption leaves the original intact and the migration simply runs
     * again next launch.
     */
    fun migrate(context: Context, target: OmniWalletDatabase) {
        // The schema migrations matter here too, and are easy to forget. The
        // legacy file was written at whatever version was current when the user
        // last ran the old build, so it can be *behind* the entity classes. Omit
        // these and Room throws "a migration from 1 to 2 was required but not
        // found", the catch below swallows it as an unreadable database, and
        // the upgrade quietly discards exactly the renames and favourites this
        // whole class exists to rescue.
        val legacy = Room.databaseBuilder(context, OmniWalletDatabase::class.java, LEGACY_NAME)
            .addMigrations(*OmniWalletDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()

        val rows = try {
            legacy.openHelper.readableDatabase // force open so a corrupt file fails here
            legacy.credentialDao().let { dao ->
                kotlinx.coroutines.runBlocking { dao.getAll() }
            }
        } catch (t: Throwable) {
            // An unreadable legacy database is not worth blocking the upgrade
            // for. The library rebuilds from the device; only metadata is lost,
            // and only in the case where it was already unreadable.
            emptyList()
        } finally {
            legacy.close()
        }

        if (rows.isNotEmpty()) {
            kotlinx.coroutines.runBlocking { target.credentialDao().saveAll(rows) }
        }

        deleteLegacyFiles(context)
    }

    private fun deleteLegacyFiles(context: Context) {
        val base = context.getDatabasePath(LEGACY_NAME)
        listOf(base, File("${base.path}-wal"), File("${base.path}-shm"), File("${base.path}-journal"))
            .forEach { runCatching { it.delete() } }
    }
}
