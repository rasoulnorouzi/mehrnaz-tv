package app.bolbol.tv.feature.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.bolbol.tv.core.designsystem.toPersianDigits
import app.bolbol.tv.core.model.AccountRepository
import app.bolbol.tv.core.model.LoginResult
import app.bolbol.tv.core.model.TitleSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Where the login form is: nothing going on, a request in flight, or the last attempt's Persian error. */
internal sealed interface LoginPhase {
    data object Idle : LoginPhase
    data object Submitting : LoginPhase
    data class Error(val message: String) : LoginPhase
}

/** Everything [AccountScreen] draws. [loaded] is false until the account state and the list were read once. */
internal data class AccountUiState(
    val loaded: Boolean = false,
    val loggedIn: Boolean = false,
    val displayName: String? = null,
    val vipDaysLeft: Int? = null,
    val myList: List<TitleSummary> = emptyList(),
    val login: LoginPhase = LoginPhase.Idle,
)

internal const val MSG_CREDENTIALS_REQUIRED = "نام کاربری و رمز عبور را وارد کنید."
internal const val MSG_WRONG_CREDENTIALS = "نام کاربری یا رمز عبور اشتباه است."
internal const val MSG_LOGIN_FAILED = "ورود انجام نشد؛ دوباره تلاش کنید."

/** «اشتراک ویژه: ۲۳ روز مانده», or null when there is no active plan. */
internal fun vipLabel(daysLeft: Int?): String? =
    daysLeft?.takeIf { it > 0 }?.let { "اشتراک ویژه: ${it.toPersianDigits()} روز مانده" }

/**
 * State and actions of [AccountScreen]: the optional Bolboljan login and "My List" (docs/auth.md).
 *
 * - Login is one request per button press (never retried automatically: unknown lockout rules). The password
 *   goes straight from the text field to [AccountRepository.login]; it is not kept in any state.
 * - Whenever the user is (or becomes) signed in, [AccountRepository.syncMyList] runs once (the repository throttles
 *   it), so the list is fresh when the screen opens and right after a login. Syncing never blocks the UI.
 * - [vipDaysLeft] feeds the «اشتراک ویژه» line; pass `DataGraph.accountVipDaysLeft` (nothing is shown by default).
 *
 * Create with [factory] (manual DI, docs/decisions.md §Architecture).
 */
class AccountViewModel(
    private val account: AccountRepository,
    vipDaysLeft: Flow<Int?> = flowOf(null),
) : ViewModel() {

    private val loginPhase = MutableStateFlow<LoginPhase>(LoginPhase.Idle)

    internal val state: StateFlow<AccountUiState> = combine(
        account.state,
        account.myList(),
        vipDaysLeft,
        loginPhase,
    ) { accountState, list, vip, login ->
        AccountUiState(
            loaded = true,
            loggedIn = accountState.loggedIn,
            displayName = accountState.displayName,
            vipDaysLeft = if (accountState.loggedIn) vip else null,
            myList = list,
            login = login,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AccountUiState())

    init {
        viewModelScope.launch {
            account.state.map { it.loggedIn }.distinctUntilChanged().collect { loggedIn ->
                if (loggedIn) launch { account.syncMyList() }
            }
        }
    }

    /** One login attempt. Blank fields are rejected here without a request; a second press while one runs is ignored. */
    internal fun login(username: String, password: String) {
        if (loginPhase.value == LoginPhase.Submitting) return
        if (username.isBlank() || password.isEmpty()) {
            loginPhase.value = LoginPhase.Error(MSG_CREDENTIALS_REQUIRED)
            return
        }
        loginPhase.value = LoginPhase.Submitting
        viewModelScope.launch {
            val phase = try {
                when (val result = account.login(username.trim(), password)) {
                    LoginResult.Success -> LoginPhase.Idle // the account state flips to signed in
                    LoginResult.WrongCredentials -> LoginPhase.Error(MSG_WRONG_CREDENTIALS)
                    is LoginResult.Failed -> LoginPhase.Error(result.message.ifBlank { MSG_LOGIN_FAILED })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                LoginPhase.Error(MSG_LOGIN_FAILED)
            }
            loginPhase.value = phase
        }
    }

    /** Typing again clears the last error. */
    internal fun onInputChanged() {
        if (loginPhase.value is LoginPhase.Error) loginPhase.value = LoginPhase.Idle
    }

    internal fun logout() {
        viewModelScope.launch {
            try {
                account.logout()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Nothing sensible to show; the state flow keeps the truth.
            }
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(account: AccountRepository, vipDaysLeft: Flow<Int?> = flowOf(null)): ViewModelProvider.Factory =
            viewModelFactory { initializer { AccountViewModel(account, vipDaysLeft) } }
    }
}
