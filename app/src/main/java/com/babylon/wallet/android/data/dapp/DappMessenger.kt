@file:Suppress("LongParameterList")

package com.babylon.wallet.android.data.dapp

import com.radixdlt.sargon.WalletToDappInteractionResponse
import com.radixdlt.sargon.extensions.toJson
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject

/**
 * The main responsibility of this class is to
 * - build the (dapp) responses
 * - to send responses to dapp
 *
 */
interface DappMessenger {

    suspend fun sendWalletInteractionResponseFailure(
        remoteConnectorId: String,
        payload: String
    ): Result<Unit>

    suspend fun sendTransactionWriteResponseSuccess(
        remoteConnectorId: String,
        payload: String
    ): Result<Unit>

    suspend fun sendWalletInteractionSuccessResponse(
        remoteConnectorId: String,
        response: WalletToDappInteractionResponse
    ): Result<Unit>
}

class DappMessengerImpl @Inject constructor(
    private val peerdroidClient: PeerdroidClient
) : DappMessenger {

    override suspend fun sendTransactionWriteResponseSuccess(
        remoteConnectorId: String,
        payload: String
    ): Result<Unit> = send(remoteConnectorId, payload)

    override suspend fun sendWalletInteractionResponseFailure(
        remoteConnectorId: String,
        payload: String
    ): Result<Unit> = send(remoteConnectorId, payload)

    override suspend fun sendWalletInteractionSuccessResponse(
        remoteConnectorId: String,
        response: WalletToDappInteractionResponse
    ): Result<Unit> {
        val messageJson = try {
            response.toJson()
        } catch (e: Exception) {
            Timber.d(e)
            ""
        }
        return send(remoteConnectorId, messageJson)
    }

    /**
     * Once the person decided, the answer goes out even if the screen that asked closes meanwhile
     * (it does as soon as they approve or reject). Over iroh a send waits for the connector to
     * confirm it, which takes a round trip: tied to the screen, it was cancelled half way, and the
     * connector never got the answer. Each transport bounds its own send.
     */
    private suspend fun send(remoteConnectorId: String, message: String): Result<Unit> =
        withContext(NonCancellable) {
            sendUntilDelivered(remoteConnectorId, message)
        }

    /**
     * An answer is not lost to a connection that drops while it goes out — the connector restarted,
     * or another program holding the same link took it over: it is sent again once the link is open,
     * for up to [RESEND_WINDOW_MS]. Safe for answers, and only for them: an answer names its
     * interaction, and a connector that already has it ignores the copy. A REQUEST is never sent
     * twice, and none goes through here.
     */
    private suspend fun sendUntilDelivered(remoteConnectorId: String, message: String): Result<Unit> {
        var result = peerdroidClient.sendMessage(remoteConnectorId, message)
        if (result.isSuccess) return result
        val outcome = withTimeoutOrNull(RESEND_WINDOW_MS) {
            var attempt = 1
            while (result.isFailure) {
                delay(RESEND_DELAY_MS)
                peerdroidClient.isConnectionOpen(remoteConnectorId).first { open -> open }
                attempt += 1
                Timber.w("Sending the answer again (attempt $attempt): the last one was not confirmed")
                result = peerdroidClient.sendMessage(remoteConnectorId, message)
            }
            result
        }
        return outcome ?: result
    }

    private companion object {
        const val RESEND_WINDOW_MS = 60_000L
        const val RESEND_DELAY_MS = 1_000L
    }
}
