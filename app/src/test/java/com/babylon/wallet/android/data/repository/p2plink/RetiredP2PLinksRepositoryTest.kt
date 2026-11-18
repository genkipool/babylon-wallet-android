package com.babylon.wallet.android.data.repository.p2plink

import com.radixdlt.sargon.Exactly32Bytes
import com.radixdlt.sargon.RadixConnectPassword
import com.radixdlt.sargon.extensions.init
import com.radixdlt.sargon.extensions.toBagOfBytes
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import rdx.works.profile.datastore.EncryptedPreferencesManager
import kotlin.time.Duration.Companion.days

@OptIn(ExperimentalCoroutinesApi::class)
class RetiredP2PLinksRepositoryTest {

    private val stored = MutableStateFlow<Result<String>?>(null)
    private val preferences = mockk<EncryptedPreferencesManager> {
        every { getRetiredP2PLinksJson() } returns stored
        coEvery { saveRetiredP2PLinksJson(any()) } answers { stored.value = Result.success(firstArg()) }
    }

    private fun password(seed: Int) =
        RadixConnectPassword.init(Exactly32Bytes.init(ByteArray(32) { (seed + it).toByte() }.toBagOfBytes()))

    @Test
    fun `keeps one notice per link, until it is forgotten`() = runTest {
        val sut = RetiredP2PLinksRepositoryImpl(preferences, UnconfinedTestDispatcher(testScheduler))

        sut.retire(password(1), nowMillis = 10)
        sut.retire(password(2), nowMillis = 20)
        sut.retire(password(1), nowMillis = 30)

        val links = sut.observeRetiredP2PLinks().first()
        assertEquals(listOf(password(2) to 20L, password(1) to 30L), links.map { it.connectionPassword to it.retiredAtMillis })
        assertTrue(sut.isRetired(links.last().connectorId))

        sut.forget(links.last().connectorId)
        assertFalse(sut.isRetired(links.last().connectorId))
        assertEquals(1, sut.observeRetiredP2PLinks().first().size)
    }

    @Test
    fun `what cannot be read is no notice`() = runTest {
        val sut = RetiredP2PLinksRepositoryImpl(preferences, UnconfinedTestDispatcher(testScheduler))

        stored.value = Result.success("not json")
        assertEquals(emptyList<RetiredP2PLink>(), sut.observeRetiredP2PLinks().first())
        stored.value = Result.failure(IllegalStateException("cannot decrypt"))
        assertEquals(emptyList<RetiredP2PLink>(), sut.observeRetiredP2PLinks().first())
    }

    @Test
    fun `a notice expires after its lifetime`() {
        val link = RetiredP2PLink(password(1), retiredAtMillis = 0)

        assertFalse(link.isExpired(30.days.inWholeMilliseconds - 1))
        assertTrue(link.isExpired(30.days.inWholeMilliseconds))
    }
}
