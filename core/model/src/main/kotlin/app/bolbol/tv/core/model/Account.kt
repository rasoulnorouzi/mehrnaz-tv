package app.bolbol.tv.core.model

import kotlinx.coroutines.flow.Flow

/** Bolboljan account (docs/auth.md). Optional: playback never needs it; it syncs lists. */
data class AccountState(
    val loggedIn: Boolean,
    /** Username or display name when known. */
    val displayName: String? = null,
)

sealed interface LoginResult {
    data object Success : LoginResult
    data object WrongCredentials : LoginResult
    /** Network/server problem; [message] is a Persian text safe to show. */
    data class Failed(val message: String) : LoginResult
}

/**
 * Account + "My List" (لیست من). The local Room list is the source of truth so the UI works
 * offline and without an account; when logged in it is synced best-effort with the site's watch-list.
 */
interface AccountRepository {
    val state: Flow<AccountState>

    /** The user types these on the TV; never log or persist the password. */
    suspend fun login(username: String, password: String): LoginResult

    suspend fun logout()

    /** Local "My List", newest first (works logged out too). */
    fun myList(): Flow<List<TitleSummary>>

    fun isInMyList(postId: Long): Flow<Boolean>

    suspend fun setInMyList(title: TitleSummary, inList: Boolean)

    /** Two-way merge with the site's watch-list when logged in; no-op otherwise. Never throws. */
    suspend fun syncMyList()
}
