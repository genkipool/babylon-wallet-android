package com.babylon.wallet.android.data.repository.p2plink

import com.radixdlt.sargon.RadixConnectPassword
import com.radixdlt.sargon.extensions.bytes
import com.radixdlt.sargon.extensions.hex
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import rdx.works.core.di.IoDispatcher
import rdx.works.core.sargon.init
import rdx.works.peerdroid.domain.ConnectionIdHolder
import rdx.works.profile.datastore.EncryptedPreferencesManager
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * A link removed in the wallet whose connector extension was not told yet: its connector is kept (closed to
 * everything else) until the extension got the notice, see [LinkRemovalNotifier].
 */
data class RetiredP2PLink(
    val connectionPassword: RadixConnectPassword,
    val retiredAtMillis: Long
) {

    /** The id of the link's connector, as the incoming messages name it. */
    val connectorId: String
        get() = ConnectionIdHolder(connectionPassword).id

    fun isExpired(nowMillis: Long): Boolean = nowMillis - retiredAtMillis >= NOTICE_LIFETIME.inWholeMilliseconds

    companion object {
        /** How long the extension may stay offline and still be told: as long as it keeps its own notices. */
        val NOTICE_LIFETIME: Duration = 30.days
    }
}

interface RetiredP2PLinksRepository {

    /** Every retired link, expired ones too: their connectors are still to be deleted. */
    fun observeRetiredP2PLinks(): Flow<List<RetiredP2PLink>>

    suspend fun isRetired(connectorId: String): Boolean

    /** One notice per link: retiring a link again only restarts its lifetime. */
    suspend fun retire(connectionPassword: RadixConnectPassword, nowMillis: Long = System.currentTimeMillis())

    suspend fun forget(connectorId: String)
}

class RetiredP2PLinksRepositoryImpl @Inject constructor(
    private val encryptedPreferencesManager: EncryptedPreferencesManager,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : RetiredP2PLinksRepository {

    // Every change reads, then writes the whole list: one at a time.
    private val mutex = Mutex()

    override fun observeRetiredP2PLinks(): Flow<List<RetiredP2PLink>> {
        return encryptedPreferencesManager.getRetiredP2PLinksJson()
            .map { serialized -> decode(serialized?.getOrNull()) }
            .distinctUntilChanged()
            .flowOn(ioDispatcher)
    }

    override suspend fun isRetired(connectorId: String): Boolean {
        return read().any { it.connectorId == connectorId }
    }

    override suspend fun retire(connectionPassword: RadixConnectPassword, nowMillis: Long) {
        val retired = RetiredP2PLink(connectionPassword = connectionPassword, retiredAtMillis = nowMillis)
        update { links -> links.filterNot { it.connectorId == retired.connectorId } + retired }
    }

    override suspend fun forget(connectorId: String) {
        update { links -> links.filterNot { it.connectorId == connectorId } }
    }

    private suspend fun read(): List<RetiredP2PLink> = withContext(ioDispatcher) {
        decode(encryptedPreferencesManager.getRetiredP2PLinksJson().first()?.getOrNull())
    }

    private suspend fun update(change: (List<RetiredP2PLink>) -> List<RetiredP2PLink>) = withContext(ioDispatcher) {
        mutex.withLock {
            val links = change(read())
            encryptedPreferencesManager.saveRetiredP2PLinksJson(
                json.encodeToString(serializer, links.map { RetiredP2PLinkDto(it.connectionPassword.value.bytes.hex, it.retiredAtMillis) })
            )
        }
    }

    // What cannot be read is dropped: a notice is a courtesy, never a reason to fail.
    private fun decode(serialized: String?): List<RetiredP2PLink> {
        if (serialized.isNullOrEmpty()) return emptyList()
        val dtos = runCatching { json.decodeFromString(serializer, serialized) }.getOrDefault(emptyList())
        return dtos.mapNotNull { dto ->
            RadixConnectPassword.init(dto.connectionPassword)?.let { password ->
                RetiredP2PLink(connectionPassword = password, retiredAtMillis = dto.retiredAt)
            }
        }
    }

    @Serializable
    private data class RetiredP2PLinkDto(
        @SerialName("connectionPassword") val connectionPassword: String,
        @SerialName("retiredAt") val retiredAt: Long
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val serializer = ListSerializer(RetiredP2PLinkDto.serializer())
    }
}
