package app.bolbol.tv.feature.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.bolbol.tv.core.model.AccountRepository
import app.bolbol.tv.core.model.TitleSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything [AccountScreen] draws. [loaded] is false until the account state and the list were read once. */
internal data class AccountUiState(
    val loaded: Boolean = false,
    val loggedIn: Boolean = false,
    val myList: List<TitleSummary> = emptyList(),
)

/**
 * Position in this list of the card to focus when the grid opens:
 *  - the remembered title ([rememberedId], see [AccountViewModel.lastFocusedId]) if it is still in the list;
 *  - else, when a title was remembered but is gone (removed from My List on its details page), the card that slid
 *    into its place ([rememberedIndex], clamped to the list);
 *  - else the first card. -1 when the list is empty.
 */
internal fun List<TitleSummary>.initialFocusIndex(rememberedId: Long?, rememberedIndex: Int = 0): Int {
    if (isEmpty()) return -1
    if (rememberedId == null) return 0
    val found = indexOfFirst { it.id == rememberedId }
    return if (found >= 0) found else rememberedIndex.coerceIn(0, lastIndex)
}

/**
 * State of [AccountScreen], the «لیست من» tab: the local My List (docs/auth.md). Logging in and out lives in
 * [ProfileViewModel].
 *
 * Whenever the user is (or becomes) signed in, [AccountRepository.syncMyList] runs once (the repository throttles it),
 * so the list is fresh when the screen opens. Syncing never blocks the UI.
 *
 * [vipDaysLeft] is no longer shown here (the VIP status moved to the Profile screen); the parameter stays so existing
 * wiring keeps compiling.
 *
 * [lastFocusedId] survives navigation (the ViewModel does, the composition does not) so Back from a title's details
 * restores focus to the card that was opened, like Home and Browse do.
 *
 * Create with [factory] (manual DI, docs/decisions.md §Architecture).
 */
class AccountViewModel(
    private val account: AccountRepository,
    @Suppress("UNUSED_PARAMETER") vipDaysLeft: Flow<Int?> = flowOf(null),
) : ViewModel() {

    internal val state: StateFlow<AccountUiState> = combine(
        account.state,
        account.myList(),
    ) { accountState, list ->
        AccountUiState(loaded = true, loggedIn = accountState.loggedIn, myList = list)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AccountUiState())

    private var focusedId: Long? = null
    private var focusedIndex = 0

    /**
     * Id of the title card that last had focus, or null before the user has focused one. Read once when the grid
     * (re)enters the composition; kept out of [state] on purpose (it changes on every D-pad move and must not
     * recompose the screen).
     */
    internal val lastFocusedId: Long? get() = focusedId

    /** Position of [lastFocusedId] in the list when it was focused (the fallback if the title has left the list). */
    internal val lastFocusedIndex: Int get() = focusedIndex

    /** The poster of title [id] at position [index] gained focus. */
    internal fun onCardFocused(id: Long, index: Int) {
        focusedId = id
        focusedIndex = index
    }

    init {
        viewModelScope.launch {
            account.state.map { it.loggedIn }.distinctUntilChanged().collect { loggedIn ->
                if (loggedIn) launch { account.syncMyList() }
            }
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(account: AccountRepository, vipDaysLeft: Flow<Int?> = flowOf(null)): ViewModelProvider.Factory =
            viewModelFactory { initializer { AccountViewModel(account, vipDaysLeft) } }
    }
}
