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
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): OmniWalletDatabase =
        Room.databaseBuilder(context, OmniWalletDatabase::class.java, OmniWalletDatabase.NAME)
            // M4 replaces this builder with SQLCipher's open helper. Nothing
            // else in the data layer has to change for that.
            .build()

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
