package com.babylon.wallet.android.domain.usecases.p2plink

import com.babylon.wallet.android.data.dapp.PeerdroidClient
import com.babylon.wallet.android.data.dapp.model.Account
import com.babylon.wallet.android.data.dapp.model.ConnectorExtensionExchangeInteraction
import com.babylon.wallet.android.data.repository.p2plink.P2PLinksRepository
import com.radixdlt.sargon.RadixConnectPurpose
import com.radixdlt.sargon.extensions.clientID
import com.radixdlt.sargon.extensions.hex
import com.radixdlt.sargon.extensions.string
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import rdx.works.core.sargon.activeAccountsOnCurrentNetwork
import rdx.works.profile.domain.GetProfileUseCase
import timber.log.Timber
import javax.inject.Inject

/**
 * Keeps every connected Connector Extension up to date with the accounts of the current network:
 * a connection gets the list when it opens (a new link, or the connector coming back), and every
 * open connection gets it again when the accounts change.
 */
class ObserveAccountsAndSyncWithConnectorExtensionUseCase @Inject constructor(
    private val p2pLinksRepository: P2PLinksRepository,
    private val getProfileUseCase: GetProfileUseCase,
    private val peerdroidClient: PeerdroidClient,
    private val jsonSerializer: Json
) {

    suspend operator fun invoke() {
        // The list each open connection was last sent. A connection that closes is forgotten, so it
        // gets the list again when it opens: the connector may have changed meanwhile.
        val sentTo = mutableMapOf<String, String>()
        combine(
            observeOpenGeneralPurposeConnectionIds(),
            observeAccountsOnCurrentNetworkInteractionMessage()
        ) { connectionIds, accountListMessage ->
            connectionIds to accountListMessage
        }.collect { (connectionIds, message) ->
            sentTo.keys.retainAll(connectionIds)
            connectionIds.filter { sentTo[it] != message }.forEach { connectionId ->
                peerdroidClient.sendMessage(connectionId, message).fold(
                    onSuccess = {
                        Timber.d("Synced accounts with CE $connectionId")
                        sentTo[connectionId] = message
                    },
                    onFailure = { throwable ->
                        Timber.e("Failed to sync accounts with CE $connectionId. Error: ${throwable.message}")
                    }
                )
            }
        }
    }

    // Both sides change on their own: a new link is saved only after its connection is open.
    private fun observeOpenGeneralPurposeConnectionIds(): Flow<Set<String>> {
        return combine(
            peerdroidClient.openConnectionIds,
            p2pLinksRepository.observeP2PLinks()
        ) { openConnectionIds, links ->
            links.asList()
                .filter { it.connectionPurpose == RadixConnectPurpose.GENERAL }
                .map { it.clientID().hex }
                .filter { connectionId -> connectionId in openConnectionIds }
                .toSet()
        }.distinctUntilChanged()
    }

    private fun observeAccountsOnCurrentNetworkInteractionMessage(): Flow<String> {
        return getProfileUseCase.flow.map {
            val accounts = it.activeAccountsOnCurrentNetwork
            val accountListExchangeInteraction = ConnectorExtensionExchangeInteraction.AccountList(
                accounts = accounts.map { account ->
                    Account(
                        address = account.address.string,
                        label = account.displayName.value,
                        appearanceId = account.appearanceId.value.toInt()
                    )
                }
            )
            jsonSerializer.encodeToString<ConnectorExtensionExchangeInteraction>(accountListExchangeInteraction)
        }.distinctUntilChanged()
    }
}
