package com.babylon.wallet.android.domain.usecases.p2plink

import com.babylon.wallet.android.data.dapp.PeerdroidClient
import com.babylon.wallet.android.data.repository.p2plink.P2PLinksRepository
import com.radixdlt.sargon.P2pLink
import com.radixdlt.sargon.Profile
import com.radixdlt.sargon.RadixConnectPurpose
import com.radixdlt.sargon.annotation.UsesSampleValues
import com.radixdlt.sargon.extensions.P2pLinks
import com.radixdlt.sargon.extensions.asIdentifiable
import com.radixdlt.sargon.extensions.clientID
import com.radixdlt.sargon.extensions.hex
import com.radixdlt.sargon.samples.sample
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import rdx.works.profile.domain.GetProfileUseCase

@OptIn(ExperimentalCoroutinesApi::class, UsesSampleValues::class)
class ObserveAccountsAndSyncWithConnectorExtensionUseCaseTest {

    private val linkA = P2pLink.sample().copy(connectionPurpose = RadixConnectPurpose.GENERAL)
    private val linkB = P2pLink.sample.other().copy(connectionPurpose = RadixConnectPurpose.GENERAL)
    private val idA = linkA.clientID().hex
    private val idB = linkB.clientID().hex

    private val openConnectionIds = MutableStateFlow<Set<String>>(emptySet())
    private val links = MutableStateFlow(listOf(linkA, linkB))
    private val profile = MutableStateFlow(Profile.sample())

    // Messages sent, per connection.
    private val sent = mutableListOf<Pair<String, String>>()

    private val peerdroidClient = mockk<PeerdroidClient> {
        every { openConnectionIds } returns this@ObserveAccountsAndSyncWithConnectorExtensionUseCaseTest.openConnectionIds
        coEvery { sendMessage(any<String>(), any<String>()) } answers {
            sent += firstArg<String>() to secondArg<String>()
            Result.success(Unit)
        }
    }
    private val p2pLinksRepository = mockk<P2PLinksRepository>()
    private val getProfileUseCase = mockk<GetProfileUseCase> {
        every { flow } returns profile
    }

    private fun sut(): ObserveAccountsAndSyncWithConnectorExtensionUseCase {
        val linksFlow = MutableStateFlow(links.value.asIdentifiable())
        every { p2pLinksRepository.observeP2PLinks() } returns linksFlow
        linksMirror = linksFlow
        return ObserveAccountsAndSyncWithConnectorExtensionUseCase(
            p2pLinksRepository = p2pLinksRepository,
            getProfileUseCase = getProfileUseCase,
            peerdroidClient = peerdroidClient,
            jsonSerializer = Json { ignoreUnknownKeys = true }
        )
    }

    private lateinit var linksMirror: MutableStateFlow<P2pLinks>

    private fun sentTo(id: String) = sent.count { it.first == id }

    @Test
    fun `a connection that opens gets the accounts, once while they do not change`() = runTest {
        val useCase = sut()
        backgroundScope.launch { useCase() }
        runCurrent()
        assertEquals(0, sent.size)

        openConnectionIds.value = setOf(idA)
        runCurrent()
        assertEquals(1, sentTo(idA))

        openConnectionIds.value = setOf(idA, idB)
        runCurrent()
        assertEquals(1, sentTo(idA))
        assertEquals(1, sentTo(idB))
    }

    @Test
    fun `every open connection gets the accounts again when they change`() = runTest {
        val useCase = sut()
        backgroundScope.launch { useCase() }
        openConnectionIds.value = setOf(idA, idB)
        runCurrent()

        // Another profile: other accounts.
        profile.value = Profile.sample.other()
        runCurrent()

        assertEquals(2, sentTo(idA))
        assertEquals(2, sentTo(idB))
        val (first, second) = sent.filter { it.first == idA }.map { it.second }
        assert(first != second)
        assertEquals(second, sent.last { it.first == idB }.second)
    }

    @Test
    fun `a connection that comes back gets the accounts again`() = runTest {
        val useCase = sut()
        backgroundScope.launch { useCase() }
        openConnectionIds.value = setOf(idA)
        runCurrent()

        openConnectionIds.value = emptySet()
        runCurrent()
        openConnectionIds.value = setOf(idA)
        runCurrent()

        assertEquals(2, sentTo(idA))
    }

    @Test
    fun `a new link saved after its connection opened gets the accounts`() = runTest {
        links.value = listOf(linkA)
        val useCase = sut()
        backgroundScope.launch { useCase() }
        openConnectionIds.value = setOf(idA, idB)
        runCurrent()
        assertEquals(0, sentTo(idB))

        linksMirror.value = listOf(linkA, linkB).asIdentifiable()
        runCurrent()

        assertEquals(1, sentTo(idA))
        assertEquals(1, sentTo(idB))
    }

    @Test
    fun `links of another purpose are not sent the accounts`() = runTest {
        links.value = listOf(linkA, linkB.copy(connectionPurpose = RadixConnectPurpose.UNKNOWN))
        val useCase = sut()
        backgroundScope.launch { useCase() }
        openConnectionIds.value = setOf(idA, idB)
        runCurrent()

        assertEquals(1, sentTo(idA))
        assertEquals(0, sentTo(idB))
    }
}
