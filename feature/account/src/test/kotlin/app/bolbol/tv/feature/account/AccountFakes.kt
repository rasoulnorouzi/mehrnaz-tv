package app.bolbol.tv.feature.account

import app.bolbol.tv.core.model.AccountRepository
import app.bolbol.tv.core.model.AccountState
import app.bolbol.tv.core.model.LoginResult
import app.bolbol.tv.core.model.TitleSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

internal fun title(id: Long) = TitleSummary(
    id = id, type = null, title = "Title $id", faTitle = "", posterUrl = null, backdropUrl = null,
    year = null, imdbRating = null, hasDub = false, hasSubtitle = false,
)

/** In-memory [AccountRepository] that records every call and lets a test gate or fail login, logout and sync. */
internal class FakeAccountRepo(signedIn: Boolean = false) : AccountRepository {
    val accountState = MutableStateFlow(AccountState(signedIn, if (signedIn) "Ali" else null))
    val list = MutableStateFlow<List<TitleSummary>>(emptyList())
    var loginResult: LoginResult = LoginResult.Success
    var loginGate: CompletableDeferred<Unit>? = null
    var loginThrows: Throwable? = null
    var logoutThrows: Throwable? = null
    var syncGate: CompletableDeferred<Unit>? = null
    var syncThrows: Throwable? = null
    val logins = mutableListOf<Pair<String, String>>()
    var syncs = 0
    var logouts = 0

    override val state: Flow<AccountState> = accountState

    override suspend fun login(username: String, password: String): LoginResult {
        logins += username to password
        loginGate?.await()
        loginThrows?.let { throw it }
        if (loginResult == LoginResult.Success) accountState.value = AccountState(true, username)
        return loginResult
    }

    override suspend fun logout() {
        logouts++
        logoutThrows?.let { throw it }
        accountState.value = AccountState(false)
    }

    override fun myList(): Flow<List<TitleSummary>> = list
    override fun isInMyList(postId: Long): Flow<Boolean> = flowOf(false)
    override suspend fun setInMyList(title: TitleSummary, inList: Boolean) {}

    override suspend fun syncMyList() {
        syncs++
        syncGate?.await()
        syncThrows?.let { throw it }
    }
}
