package com.babylon.wallet.android.data.dapp

import com.babylon.wallet.android.data.repository.p2plink.RetiredP2PLinksRepository
import com.babylon.wallet.android.domain.model.messages.IncomingMessage
import com.radixdlt.sargon.AccountAddress
import com.radixdlt.sargon.P2pTransportProfile
import io.mockk.coEvery
import io.mockk.mockk
import com.radixdlt.sargon.RadixConnectPassword
import com.radixdlt.sargon.extensions.string
import com.radixdlt.sargon.samples.sampleMainnet
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rdx.works.peerdroid.data.PeerdroidConnector
import rdx.works.peerdroid.domain.ConnectionIdHolder
import rdx.works.peerdroid.domain.DataChannelWrapperEvent
import rdx.works.peerdroid.domain.PeerConnectionStatus
import timber.log.Timber

@OptIn(ExperimentalCoroutinesApi::class)
class PeerdroidClientImplTest {

    private val connectionId = "1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b"
    private val incoming = MutableSharedFlow<DataChannelWrapperEvent>()
    private val retiredConnectorIds = mutableSetOf<String>()
    private val retired = mockk<RetiredP2PLinksRepository> {
        coEvery { isRetired(any()) } answers { firstArg<String>() in retiredConnectorIds }
    }
    private val logged = mutableListOf<String>()

    // A well-formed request of an older interaction version: the wallet answers it with a failure.
    private val incompatibleRequest = """
        {"items":{"discriminator":"unauthorizedRequest","oneTimeAccounts":{"numberOfAccounts":{"quantity":1,"quantifier":"exactly"}}},
        "interactionId":"1","metadata":{"version":1,"networkId":1,"origin":"https://dapp.example.com",
        "dAppDefinitionAddress":"${AccountAddress.sampleMainnet().string}"}}
    """.trimIndent()
    private val sent = mutableListOf<String>()

    private val tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            logged += message
        }
    }

    private val connector = object : PeerdroidConnector {
        override val anyChannelConnected: Flow<Boolean> = flowOf(true)
        override val peerConnectionStatus: Flow<Map<String, PeerConnectionStatus>> = flowOf(emptyMap())
        override val dataChannelMessagesFromRemoteClients: SharedFlow<DataChannelWrapperEvent> = incoming

        override suspend fun connectToConnectorExtension(
            p2pTransportProfile: P2pTransportProfile,
            encryptionKey: RadixConnectPassword
        ): Result<Unit> = Result.success(Unit)

        override suspend fun deleteConnector(connectionId: ConnectionIdHolder) = Unit
        override fun terminateConnectionToConnectorExtension() = Unit
        override suspend fun sendDataChannelMessageToRemoteClient(remoteConnectorId: String, message: String): Result<Unit> {
            sent += message
            return Result.success(Unit)
        }
        override suspend fun sendDataChannelMessageToAllRemoteClients(message: String) = Result.success(Unit)
    }

    @Before
    fun setUp() {
        Timber.plant(tree)
    }

    @After
    fun tearDown() {
        Timber.uproot(tree)
    }

    @Test
    fun `a message that is neither a dApp request nor a ledger response is rejected without logging its contents`() = runTest {
        val sut = PeerdroidClientImpl(
            peerdroidConnector = connector,
            retiredP2PLinksRepository = retired,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            json = Json { ignoreUnknownKeys = true },
            applicationScope = backgroundScope
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { sut.listenForIncomingRequests().collect {} }
        runCurrent()
        val message = """{"interactionId":"1","persona":{"label":"Alice","seedPhrase":"zoo zoo zoo"}}"""

        incoming.emit(DataChannelWrapperEvent.MessageFromRemoteConnectionId(ConnectionIdHolder(connectionId), message))
        runCurrent()

        assertTrue(logged.toString(), logged.any { it.startsWith("🤖 link=1a2b3c4d rejected a ") })
        assertTrue(logged.none { "Alice" in it || "zoo" in it || connectionId in it })
    }
    @Test
    fun `the connector extension's removal of the link is passed on, whether the link was removed here or not`() = runTest {
        val sut = client()
        val removals = mutableListOf<IncomingMessage.LinkRemoved>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { sut.listenForLinkRemovals().collect { removals += it } }
        runCurrent()

        incoming.emit(DataChannelWrapperEvent.MessageFromRemoteConnectionId(ConnectionIdHolder(connectionId), LINK_REMOVED))
        retiredConnectorIds += connectionId
        incoming.emit(DataChannelWrapperEvent.MessageFromRemoteConnectionId(ConnectionIdHolder(connectionId), LINK_REMOVED))
        runCurrent()

        assertEquals(listOf(IncomingMessage.LinkRemoved(connectionId), IncomingMessage.LinkRemoved(connectionId)), removals)
    }

    @Test
    fun `nothing else a link removed here sends is handled, nor answered`() = runTest {
        val sut = client()
        val messages = mutableListOf<Any>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { sut.listenForIncomingRequests().collect { messages += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { sut.listenForLedgerResponses().collect { messages += it } }
        runCurrent()
        retiredConnectorIds += connectionId

        // Even a request of an incompatible version, which would otherwise be answered with a failure.
        incoming.emit(DataChannelWrapperEvent.MessageFromRemoteConnectionId(ConnectionIdHolder(connectionId), incompatibleRequest))
        runCurrent()

        assertTrue(messages.isEmpty())
        assertTrue(sent.isEmpty())
        assertTrue(logged.toString(), logged.any { it.startsWith("🤖 link=1a2b3c4d ignored a message") })
    }

    @Test
    fun `each message is parsed once and answered once, however many listen`() = runTest {
        val sut = client()
        // As the wallet listens: requests, their errors and link removals all the time, ledger responses while waiting
        // for the ledger.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { sut.listenForIncomingRequests().collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { sut.listenForIncomingRequestErrors().collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { sut.listenForLinkRemovals().collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { sut.listenForLedgerResponses().collect {} }
        runCurrent()

        incoming.emit(DataChannelWrapperEvent.MessageFromRemoteConnectionId(ConnectionIdHolder(connectionId), incompatibleRequest))
        runCurrent()

        assertEquals("the dApp gets one failure", 1, sent.size)
        assertEquals(logged.toString(), 1, logged.count { it.startsWith("🤖 link=1a2b3c4d rejected interaction=1") })
    }

    private fun TestScope.client() = PeerdroidClientImpl(
        peerdroidConnector = connector,
        retiredP2PLinksRepository = retired,
        ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        json = Json { ignoreUnknownKeys = true },
        applicationScope = backgroundScope
    )

    private companion object {
        const val LINK_REMOVED = """{"discriminator":"linkRemoved"}"""
    }
}
