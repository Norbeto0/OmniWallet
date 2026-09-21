package dev.omniwallet.core.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [CredentialEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class OmniWalletDatabase : RoomDatabase() {
    abstract fun credentialDao(): CredentialDao

    companion object {
        const val NAME = "omniwallet-secure.db"
    }
}
