package dev.omniwallet.core.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CredentialDao {

    @Query("SELECT * FROM credentials")
    fun observeAll(): Flow<List<CredentialEntity>>

    @Query("SELECT * FROM credentials")
    suspend fun getAll(): List<CredentialEntity>

    @Upsert
    suspend fun upsertAll(entities: List<CredentialEntity>)

    /**
     * Apply a merged scan result atomically.
     *
     * Named for what it does: it writes, it never deletes. The merge in the
     * domain layer deliberately never drops an entry either -- a device that is
     * merely switched off has not had its cards removed. Atomicity matters
     * because a half-applied scan would look to the next read like cards had
     * vanished.
     */
    @Transaction
    suspend fun saveAll(entities: List<CredentialEntity>) {
        upsertAll(entities)
    }

    @Query("UPDATE credentials SET favourite = :favourite WHERE id = :id")
    suspend fun setFavourite(id: String, favourite: Boolean)

    @Query("UPDATE credentials SET custom_name = :customName WHERE id = :id")
    suspend fun setCustomName(id: String, customName: String?)

    @Query("UPDATE credentials SET hidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: String, hidden: Boolean)

    @Query("UPDATE credentials SET last_used_at = :atMillis WHERE id = :id")
    suspend fun setLastUsed(id: String, atMillis: Long)

    /**
     * Set or clear the tagged place.
     *
     * One statement writing all three columns, so a place can never be left
     * half-written -- a label with no coordinate, or a coordinate with no way
     * to name or remove it.
     */
    @Query(
        "UPDATE credentials SET place_label = :label, place_lat = :latitude, " +
            "place_lon = :longitude WHERE id = :id",
    )
    suspend fun setPlace(id: String, label: String?, latitude: Double?, longitude: Double?)
}
