package com.babylon.wallet.android.domain.usecases

import com.babylon.wallet.android.data.dapp.PeerdroidClient
import com.babylon.wallet.android.data.repository.homecards.HomeCardsRepository
import com.babylon.wallet.android.data.repository.state.StateRepository
import rdx.works.core.preferences.PreferencesManager
import rdx.works.core.preferences.RadixConnectPreferences
import rdx.works.profile.cloudbackup.data.GoogleSignInManager
import rdx.works.profile.data.repository.ProfileRepository
import rdx.works.profile.domain.GetProfileUseCase
import javax.inject.Inject

@Suppress("LongParameterList")
class DeleteWalletUseCase @Inject constructor(
    private val getProfileUseCase: GetProfileUseCase,
    private val profileRepository: ProfileRepository,
    private val googleSignInManager: GoogleSignInManager,
    private val stateRepository: StateRepository,
    private val peerdroidClient: PeerdroidClient,
    private val homeCardsRepository: HomeCardsRepository,
    private val preferencesManager: PreferencesManager,
    private val radixConnectPreferences: RadixConnectPreferences
) {

    suspend operator fun invoke() {
        val inOnboarding = getProfileUseCase.finishedOnboardingProfile() == null

        peerdroidClient.terminate()
        // What was learned about the links goes with them, also when only onboarding is reset.
        radixConnectPreferences.clearIrohCapableConnectionIds()
        radixConnectPreferences.clearIrohLinkMemories()
        stateRepository.clearCachedState()
        homeCardsRepository.walletReset()
        profileRepository.deleteWallet()
        if (!inOnboarding) {
            googleSignInManager.signOut()
            preferencesManager.clear()
        }
    }
}
