package com.babylon.wallet.android.data.dapp

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DappMessengerImplTest {

    private val sent = mutableListOf<String>()

    // As over iroh: the send returns once the connector confirmed it, a round trip later.
    private val peerdroidClient = mockk<PeerdroidClient> {
        coEvery { sendMessage(any<String>(), any<String>()) } coAnswers {
            delay(ROUND_TRIP_MS)
            sent += secondArg<String>()
            Result.success(Unit)
        }
    }

    private val sut = DappMessengerImpl(peerdroidClient)

    @Test
    fun `the answer goes out even when the screen that asked closes while it is being sent`() = runTest {
        val screen = launch {
            sut.sendWalletInteractionResponseFailure(remoteConnectorId = "connector", payload = "rejected")
        }
        runCurrent()

        // The person rejected: the request screen closes, and its work is cancelled.
        screen.cancel()
        advanceTimeBy(ROUND_TRIP_MS + 1)
        runCurrent()

        assertEquals(listOf("rejected"), sent)
    }

    @Test
    fun `an answer lost to a dropped connection is sent again once the link is open`() = runTest {
        val open = MutableStateFlow(true)
        var attempts = 0
        val flaky = mockk<PeerdroidClient> {
            every { isConnectionOpen("connector") } returns open
            coEvery { sendMessage(any<String>(), any<String>()) } coAnswers {
                attempts += 1
                if (attempts == 1) {
                    // The connection drops while the answer goes out; the link reconnects later.
                    open.value = false
                    Result.failure(IllegalStateException("connection lost (maybe delivered)"))
                } else {
                    sent += secondArg<String>()
                    Result.success(Unit)
                }
            }
        }

        val result = async { DappMessengerImpl(flaky).sendWalletInteractionResponseFailure("connector", "signed") }
        advanceTimeBy(5_000)
        assertEquals("waits for the link", emptyList<String>(), sent)
        open.value = true
        advanceTimeBy(2_000)

        assertTrue(result.await().isSuccess)
        assertEquals(listOf("signed"), sent)
        assertEquals(2, attempts)
    }

    @Test
    fun `an answer is not retried for ever`() = runTest {
        val never = mockk<PeerdroidClient> {
            every { isConnectionOpen("connector") } returns MutableStateFlow(false)
            coEvery { sendMessage(any<String>(), any<String>()) } returns Result.failure(IllegalStateException("not connected"))
        }

        val result = async { DappMessengerImpl(never).sendWalletInteractionResponseFailure("connector", "signed") }
        advanceTimeBy(61_000)

        assertTrue(result.await().isFailure)
    }

    private companion object {
        const val ROUND_TRIP_MS = 300L
    }
}
