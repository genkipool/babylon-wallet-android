package com.babylon.wallet.android.presentation.settings.linkedconnectors.add

import com.babylon.wallet.android.domain.model.p2plink.LinkConnectionPayload
import com.babylon.wallet.android.domain.usecases.p2plink.EstablishP2PLinkConnectionUseCase
import com.babylon.wallet.android.domain.usecases.p2plink.ParseLinkConnectionDetailsUseCase
import com.babylon.wallet.android.presentation.StateViewModelTest
import com.babylon.wallet.android.utils.AppEventBus
import com.radixdlt.sargon.P2pLink
import com.radixdlt.sargon.PublicKey
import com.radixdlt.sargon.RadixConnectPassword
import com.radixdlt.sargon.RadixConnectPurpose
import com.radixdlt.sargon.samples.sample
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddLinkConnectorViewModelTest : StateViewModelTest<AddLinkConnectorViewModel>() {

    private val parseLinkConnectionDetailsUseCase = mockk<ParseLinkConnectionDetailsUseCase>()
    private val establishP2PLinkConnectionUseCase = mockk<EstablishP2PLinkConnectionUseCase>()
    private val appEventBus = mockk<AppEventBus>(relaxed = true)

    override fun initVM() = AddLinkConnectorViewModel(
        parseLinkConnectionDetailsUseCase = parseLinkConnectionDetailsUseCase,
        establishP2PLinkConnectionUseCase = establishP2PLinkConnectionUseCase,
        appEventBus = appEventBus
    )

    @Test
    fun `a second tap while the connector links does not link it again`() = runTest {
        val payload = LinkConnectionPayload(
            password = RadixConnectPassword.sample(),
            publicKey = PublicKey.Ed25519.sample(),
            purpose = RadixConnectPurpose.GENERAL,
            existingP2PLink = P2pLink.sample()
        )
        coEvery { parseLinkConnectionDetailsUseCase(QR) } returns Result.success(payload)
        val linking = CompletableDeferred<Result<Unit>>()
        coEvery { establishP2PLinkConnectionUseCase.update(payload) } coAnswers { linking.await() }
        val vm = vm.value
        vm.onQrCodeScanned(QR)
        advanceUntilIdle()

        vm.onContinueClick()
        advanceUntilIdle()
        vm.onContinueClick()
        advanceUntilIdle()
        linking.complete(Result.success(Unit))
        advanceUntilIdle()

        coVerify(exactly = 1) { establishP2PLinkConnectionUseCase.update(payload) }
    }

    private companion object {
        const val QR = "qr"
    }
}
