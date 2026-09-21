package dev.omniwallet.core.data

import dev.omniwallet.core.domain.CredentialId
import dev.omniwallet.core.domain.CredentialMerge
import dev.omniwallet.core.domain.CredentialRepository
import dev.omniwallet.core.domain.DeviceKind
import dev.omniwallet.core.domain.RemoteCredential
import dev.omniwallet.core.domain.StoredCredential
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed library.
 *
 * Thin by design: the reconciliation rules live in [CredentialMerge], which is
 * pure and unit-tested, leaving this class with nothing but storage.
 */
@Singleton
class CredentialRepositoryImpl @Inject constructor(
    private val dao: CredentialDao,
) : CredentialRepository {

    override fun observeCredentials(): Flow<List<StoredCredential>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun applyScan(
        deviceKind: DeviceKind,
        discovered: List<RemoteCredential>,
        nowMillis: Long,
    ) {
        val merged = CredentialMerge.merge(
            existing = dao.getAll().map { it.toDomain() },
            discovered = discovered,
            deviceKind = deviceKind,
            nowMillis = nowMillis,
        )
        dao.saveAll(merged.map { it.toEntity() })
    }

    override suspend fun setFavourite(id: CredentialId, favourite: Boolean) =
        dao.setFavourite(id.value, favourite)

    override suspend fun rename(id: CredentialId, customName: String?) =
        // Blank restores the device's own name rather than storing an empty
        // string that would render as a nameless card.
        dao.setCustomName(id.value, customName?.takeIf { it.isNotBlank() })

    override suspend fun setHidden(id: CredentialId, hidden: Boolean) =
        dao.setHidden(id.value, hidden)

    override suspend fun markUsed(id: CredentialId, atMillis: Long) =
        dao.setLastUsed(id.value, atMillis)
}
