package app.bolbol.tv.feature.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.bolbol.tv.core.designsystem.toPersianDigits
import app.bolbol.tv.core.model.AccountRepository
import app.bolbol.tv.core.model.LoginResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The non-secret profile of the signed-in user (docs/auth.md §0, `Profile`), as the Profile screen shows it. Every
 * field is null when unknown. The token is never part of it.
 *
 * @property vipDaysLeft days left of an active VIP plan, null without one
 * @property vipExpire the plan's end date, exactly as the server wrote it
 */
data class AccountProfile(
    val username: String? = null,
    val email: String? = null,
    val displayName: String? = null,
    val vipDaysLeft: Int? = null,
    val vipExpire: String? = null,
)

/** Where the login form is: nothing going on, a request in flight, or the last attempt's Persian error. */
internal sealed interface LoginPhase {
    data object Idle : LoginPhase
    data object Submitting : LoginPhase
    data class Error(val message: String) : LoginPhase
}

/** The «همگام‌سازی دوباره» button: idle, running, or how the last run ended ([Done] clears itself). */
internal enum class SyncPhase { Idle, Syncing, Done, Failed }

/** Everything [ProfileScreen] draws. [loaded] is false until the account state was read once. */
internal data class ProfileUiState(
    val loaded: Boolean = false,
    val loggedIn: Boolean = false,
    /** The signed-in user's profile; null while signed out. */
    val profile: AccountProfile? = null,
    val login: LoginPhase = LoginPhase.Idle,
    val sync: SyncPhase = SyncPhase.Idle,
    /** The «از حساب خارج می‌شوید؟» dialog is open. */
    val confirmingLogout: Boolean = false,
)

internal const val MSG_CREDENTIALS_REQUIRED = "نام کاربری و رمز عبور را وارد کنید."
internal const val MSG_WRONG_CREDENTIALS = "نام کاربری یا رمز عبور اشتباه است."
internal const val MSG_LOGIN_FAILED = "ورود انجام نشد؛ دوباره تلاش کنید."
internal const val MSG_SYNC_FAILED = "همگام‌سازی انجام نشد؛ دوباره تلاش کنید."

/** «همگام شد» (the screen adds the check mark) stays visible this long. */
internal const val SYNC_DONE_VISIBLE_MS = 4_000L

/** «اشتراک ویژه: ۲۳ روز مانده», or null when there is no active plan. */
internal fun vipLabel(daysLeft: Int?): String? =
    daysLeft?.takeIf { it > 0 }?.let { "اشتراک ویژه: ${it.toPersianDigits()} روز مانده" }

/** The VIP status line of the profile card: the days left, or «بدون اشتراک ویژه». */
internal fun vipStatusText(daysLeft: Int?): String = vipLabel(daysLeft) ?: "بدون اشتراک ویژه"

/** The name to greet the user with: the display name, else the username, else the e-mail. */
internal val AccountProfile.shownName: String?
    get() = displayName?.trim()?.takeIf { it.isNotEmpty() }
        ?: username?.trim()?.takeIf { it.isNotEmpty() }
        ?: email?.trim()?.takeIf { it.isNotEmpty() }

/** The letter in the avatar circle: the first letter or digit of [name] (upper-cased), «ب» when there is none. */
internal fun avatarInitial(name: String?): String =
    name?.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: AVATAR_FALLBACK

private const val AVATAR_FALLBACK = "ب"

/**
 * State and actions of [ProfileScreen]: the «پروفایل» tab, where the optional Bolboljan account is signed in and out
 * (docs/auth.md).
 *
 * - **Signed out:** the login form. Login is one request per button press (never retried automatically: unknown lockout
 *   rules). The password goes straight from the text field to [AccountRepository.login]; it is kept in no state.
 *   Becoming signed in syncs My List once (the repository throttles it); it never blocks the UI.
 * - **Signed in:** [profile] details, a manual sync ([syncNow]) and a logout that must be confirmed
 *   ([requestLogout] opens the dialog, [confirmLogout] signs out, [cancelLogout] keeps the session). The local My List
 *   and watch progress stay on the device after logout.
 *
 * [profile] feeds the details (username, e-mail, VIP plan) from the stored session; pass it from the data layer
 * (`DataGraph.accountProfile`, mapped to [AccountProfile]). Create with [factory] (manual DI, docs/decisions.md
 * §Architecture).
 */
class ProfileViewModel(
    private val account: AccountRepository,
    profile: Flow<AccountProfile?>,
) : ViewModel() {

    private val loginPhase = MutableStateFlow<LoginPhase>(LoginPhase.Idle)
    private val syncPhase = MutableStateFlow(SyncPhase.Idle)
    private val confirmingLogout = MutableStateFlow(false)
    private var syncJob: Job? = null
    private var loggingOut = false

    internal val state: StateFlow<ProfileUiState> = combine(
        account.state,
        profile,
        loginPhase,
        syncPhase,
        confirmingLogout,
    ) { accountState, stored, login, sync, confirming ->
        val loggedIn = accountState.loggedIn
        ProfileUiState(
            loaded = true,
            loggedIn = loggedIn,
            profile = if (loggedIn) {
                // The stored profile can lag a moment behind the account state: never show less than the state knows.
                val details = stored ?: AccountProfile()
                if (details.displayName.isNullOrBlank()) details.copy(displayName = accountState.displayName) else details
            } else {
                null
            },
            login = login,
            sync = if (loggedIn) sync else SyncPhase.Idle,
            confirmingLogout = loggedIn && confirming,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ProfileUiState())

    init {
        viewModelScope.launch {
            account.state.map { it.loggedIn }.distinctUntilChanged().collect { loggedIn ->
                // Any change of the session closes a stale dialog and a stale sync result.
                confirmingLogout.value = false
                syncJob?.cancel()
                syncPhase.value = SyncPhase.Idle
                if (loggedIn) launch { syncQuietly() }
            }
        }
    }

    // --- login ---------------------------------------------------------------------------------------------------

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

    // --- sync ----------------------------------------------------------------------------------------------------

    /**
     * «همگام‌سازی دوباره»: syncs My List now and shows how it went ([SyncPhase.Done] clears itself after a few seconds).
     * A press while a sync runs is ignored.
     */
    internal fun syncNow() {
        if (syncPhase.value == SyncPhase.Syncing) return
        syncJob?.cancel()
        syncPhase.value = SyncPhase.Syncing
        syncJob = viewModelScope.launch {
            val result = try {
                account.syncMyList()
                SyncPhase.Done
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                SyncPhase.Failed
            }
            syncPhase.value = result
            if (result == SyncPhase.Done) {
                delay(SYNC_DONE_VISIBLE_MS)
                syncPhase.value = SyncPhase.Idle
            }
        }
    }

    private suspend fun syncQuietly() {
        try {
            account.syncMyList()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The repository never throws by contract; a surprise must not take the screen down.
        }
    }

    // --- logout --------------------------------------------------------------------------------------------------

    /** «خروج از حساب» was pressed: ask first. */
    internal fun requestLogout() {
        confirmingLogout.value = true
    }

    /** «انصراف» / Back: keep the session. */
    internal fun cancelLogout() {
        confirmingLogout.value = false
    }

    /**
     * «بله، خروج»: forgets the token (local My List and watch progress stay) and closes the dialog. Ignored unless the
     * dialog is open, and while a logout is already running.
     */
    internal fun confirmLogout() {
        if (!confirmingLogout.value || loggingOut) return
        loggingOut = true
        viewModelScope.launch {
            try {
                account.logout()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Nothing sensible to show; the state flow keeps the truth.
            } finally {
                loggingOut = false
                confirmingLogout.value = false
            }
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(account: AccountRepository, profile: Flow<AccountProfile?>): ViewModelProvider.Factory =
            viewModelFactory { initializer { ProfileViewModel(account, profile) } }
    }
}
