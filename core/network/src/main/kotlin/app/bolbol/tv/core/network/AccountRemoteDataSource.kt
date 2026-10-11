package app.bolbol.tv.core.network

import app.bolbol.tv.core.model.TitleSummary
import app.bolbol.tv.core.model.WatchKey

/**
 * Network side of the optional Bolboljan account (docs/auth.md section 0). Nothing here throws except cancellation:
 * every failure is a value, so a broken or changed server can never crash the app. The token itself is attached to
 * requests by [AuthTokenInterceptor] from a [TokenProvider]; callers only receive it from [login].
 */
interface AccountRemoteDataSource {

    /** One attempt, no retries (unknown lockout rules, docs/auth.md section 8.1). Never logs or stores the password. */
    suspend fun login(username: String, password: String): LoginOutcome

    /** `GET user/profile`: display name, username, e-mail and the VIP plan. */
    suspend fun profile(): RemoteResult<RemoteProfile>

    /** The server favourites (= "My List"): all pages, capped. */
    suspend fun favorites(): RemoteResult<RemoteFavorites>

    /**
     * `POST user/favorite-list post_id`. The server **toggles**: call it only after reading the current state, and
     * read the state again to confirm.
     */
    suspend fun toggleFavorite(postId: Long): RemoteResult<Unit>

    /** `GET user/watch-list`: the server's continue-watching entries with positions. */
    suspend fun progressList(): RemoteResult<List<RemoteProgressItem>>

    /** `POST user/watch-list` (multipart). [season] and [episode] are sent only when both are above 0. */
    suspend fun saveProgress(postId: Long, positionSeconds: Int, season: Int, episode: Int): RemoteResult<Unit>
}

/** Result of [AccountRemoteDataSource.login]. */
sealed interface LoginOutcome {

    data class Success(val token: String, val profile: RemoteProfile) : LoginOutcome {
        /** The token must never reach a log. */
        override fun toString(): String = "Success(token=<redacted>, profile=$profile)"
    }

    /** The server answered and said no. */
    data object WrongCredentials : LoginOutcome

    /** Network or unexpected-response problem; [message] is Persian text safe to show. */
    data class Failed(val message: String) : LoginOutcome
}

/**
 * What the server says about the signed-in user (docs/auth.md §0, `Profile`); every field is null when the answer did
 * not carry it. Never contains the token.
 *
 * @property vipDaysLeft days left of an active VIP plan, null when there is none
 * @property vipExpire the plan's end date exactly as the server wrote it
 * @property vipExpirePercent share of the plan already used, 0..100
 * @property vipStatus the plan's status word as sent (for example "active")
 */
data class RemoteProfile(
    val displayName: String? = null,
    val vipDaysLeft: Int? = null,
    val username: String? = null,
    val email: String? = null,
    val vipExpire: String? = null,
    val vipExpirePercent: Int? = null,
    val vipStatus: String? = null,
)

/** Outcome of an authenticated call. */
sealed interface RemoteResult<out T> {
    data class Ok<T>(val value: T) : RemoteResult<T>

    /** HTTP 401/403 with a token attached: the session has most likely expired. */
    data object Unauthorized : RemoteResult<Nothing>

    /** Network error, server error, or a response that could not be understood. Retry later. */
    data object Failed : RemoteResult<Nothing>
}

/**
 * The server favourites. [complete] is false when more pages may exist than were read; an incomplete list must
 * never be used to conclude that something was removed on the server.
 */
data class RemoteFavorites(val items: List<RemoteFavorite>, val complete: Boolean = true)

/** One server favourite. [summary] is set when the payload carried enough to paint a poster, else only the id is known. */
data class RemoteFavorite(val id: Long, val summary: TitleSummary? = null)

/**
 * One server continue-watching entry. [summary] is null when the payload had no title. [positionMs] comes from
 * `current_time` (seconds). The server's `duration` is unreliable (the official app never reads it), so [durationMs] is
 * 0 (= unknown) unless it parsed to a sane length: longer than the position and between 60 s and 6 h.
 */
data class RemoteProgressItem(
    val key: WatchKey,
    val positionMs: Long,
    val durationMs: Long,
    val summary: TitleSummary?,
) {
    companion object {
        /** Shortest and longest server duration that is believed. */
        const val MIN_DURATION_MS = 60_000L
        const val MAX_DURATION_MS = 6 * 3_600_000L

        /** [durationMs] when it is a believable length for [positionMs] (longer than it, 60 s..6 h), else 0 = unknown. */
        fun saneDurationMs(durationMs: Long, positionMs: Long): Long =
            if (durationMs > positionMs && durationMs in MIN_DURATION_MS..MAX_DURATION_MS) durationMs else 0
    }
}

/** Supplies the login token to the HTTP layer (implemented by the data layer's session store). */
fun interface TokenProvider {
    /** The current token, or null when signed out. Called on OkHttp threads for every matching request. */
    fun token(): String?
}
