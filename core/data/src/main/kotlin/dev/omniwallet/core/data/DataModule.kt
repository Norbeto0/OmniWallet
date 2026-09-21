package dev.omniwallet.core.data

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.omniwallet.core.domain.CredentialRepository
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabaseKey(@ApplicationContext context: Context): DatabaseKey =
        DatabaseKey(context)

    /**
     * The encrypted library database.
     *
     * SQLCipher rather than plain Room, because this is a map of the places a
     * person can physically get into. The passphrase comes from [DatabaseKey],
     * wrapped by the Android Keystore; the threat model is documented there and
     * is narrower than the word "encrypted" tends to suggest.
     */
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        key: DatabaseKey,
    ): OmniWalletDatabase {
        System.loadLibrary("sqlcipher")

        val database = Room.databaseBuilder(context, OmniWalletDatabase::class.java, OmniWalletDatabase.NAME)
            .openHelperFactory(SupportOpenHelperFactory(key.getOrCreate()))
            .build()

        // One-time upgrade from the M3 plaintext database. Checked by file
        // existence, so the usual path costs a single stat call.
        if (LegacyDatabaseMigration.hasLegacyDatabase(context)) {
            LegacyDatabaseMigration.migrate(context, database)
        }

        return database
    }

    @Provides
    @Singleton
    fun provideCredentialDao(database: OmniWalletDatabase): CredentialDao =
        database.credentialDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindCredentialRepository(impl: CredentialRepositoryImpl): CredentialRepository
}
