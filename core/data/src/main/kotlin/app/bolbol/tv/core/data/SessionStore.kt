package app.bolbol.tv.core.data

import android.content.Context
import android.content.SharedPreferences
import app.bolbol.tv.core.model.AccountState
import app.bolbol.tv.core.network.TokenProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What is kept of a login: the token the server issued plus the non-secret profile fields shown on the Profile screen
 * (name, username, e-mail and the VIP plan; display only). The password is never stored.
 */
internal data class StoredSession(
    val token: String,
    val displayName: String?,
    val vipDaysLeft: Int? = null,
    val username: String? = null,
    val email: String? = null,
    val vipExpire: String? = null,
    val vipExpirePercent: Int? = null,
    val vipStatus: String? = null,
) {
    /** The profile fields without the token: safe to hand to the UI. */
    fun toProfile(): AccountProfileDetails = AccountProfileDetails(
        username = username,
        email = email,
        displayName = displayName,
        vipDaysLeft = vipDaysLeft,
        vipExpire = vipExpire,
        vipExpirePercent = vipExpirePercent,
        vipStatus = vipStatus,
    )

    /** The token must never reach a log. */
    override fun toString(): String =
        "StoredSession(token=<redacted>, displayName=$displayName, vipDaysLeft=$vipDaysLeft, username=$username, " +
            "email=$email, vipExpire=$vipExpire, vipExpirePercent=$vipExpirePercent, vipStatus=$vipStatus)"
}

/** Persistence of the single [StoredSession]. Calls may block briefly (disk): never call from the main thread. */
internal interface SessionStorage {
    fun load(): StoredSession?
    fun save(session: StoredSession)
    fun clear()
}

/**
 * The session lives in a private SharedPreferences file (`shared_prefs/account_session.xml`) of the app's sandbox.
 * This is a private, sideloaded app on one TV, so the Android app sandbox is the protection: no other app can read
 * the file, `android:allowBackup` is false (no cloud/adb backup) and the token only grants access to the user's own
 * favourites list on a movie site. No Keystore encryption on purpose (docs/auth.md section 8.4 recommended one;
 * decided against it for this threat model, see docs/decisions.md). A rooted device or a stolen TV can read it.
 */
internal class SharedPreferencesSessionStorage(context: Context) : SessionStorage {
    private val prefs by lazy { context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE) }

    override fun load(): StoredSession? {
        val token = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotEmpty() } ?: return null
        return StoredSession(
            token = token,
            displayName = prefs.getString(KEY_NAME, null),
            vipDaysLeft = prefs.optionalInt(KEY_VIP_DAYS),
            username = prefs.getString(KEY_USERNAME, null),
            email = prefs.getString(KEY_EMAIL, null),
            vipExpire = prefs.getString(KEY_VIP_EXPIRE, null),
            vipExpirePercent = prefs.optionalInt(KEY_VIP_PERCENT),
            vipStatus = prefs.getString(KEY_VIP_STATUS, null),
        )
    }

    override fun save(session: StoredSession) {
        prefs.edit().apply {
            putString(KEY_TOKEN, session.token)
            putString(KEY_NAME, session.displayName)
            if (session.vipDaysLeft != null) putInt(KEY_VIP_DAYS, session.vipDaysLeft) else remove(KEY_VIP_DAYS)
            putString(KEY_USERNAME, session.username)
            putString(KEY_EMAIL, session.email)
            putString(KEY_VIP_EXPIRE, session.vipExpire)
            if (session.vipExpirePercent != null) putInt(KEY_VIP_PERCENT, session.vipExpirePercent) else remove(KEY_VIP_PERCENT)
            putString(KEY_VIP_STATUS, session.vipStatus)
        }.commit()
    }

    private fun SharedPreferences.optionalInt(key: String): Int? = if (contains(key)) getInt(key, 0) else null

    override fun clear() {
        prefs.edit().clear().commit()
    }

    private companion object {
        const val FILE_NAME = "account_session"
        const val KEY_TOKEN = "token"
        const val KEY_NAME = "name"
        const val KEY_VIP_DAYS = "vip_days"
        const val KEY_USERNAME = "username"
        const val KEY_EMAIL = "email"
        const val KEY_VIP_EXPIRE = "vip_expire"
        const val KEY_VIP_PERCENT = "vip_percent"
        const val KEY_VIP_STATUS = "vip_status"
    }
}

/**
 * In-memory copy of the session, shared by the HTTP layer (as a [TokenProvider], read on OkHttp threads for every
 * `user/` call) and the account repository. Loaded lazily on first use so constructing it does no I/O.
 */
internal class SessionStore(private val storage: SessionStorage) : TokenProvider {
    private val lock = Any()
    @Volatile private var loaded = false
    @Volatile private var session: StoredSession? = null
    private val _state = MutableStateFlow(AccountState(loggedIn = false))
    private val _vipDaysLeft = MutableStateFlow<Int?>(null)
    private val _profile = MutableStateFlow<AccountProfileDetails?>(null)

    /** Valid after [ensureLoaded]. */
    val state: StateFlow<AccountState> = _state.asStateFlow()
    val vipDaysLeft: StateFlow<Int?> = _vipDaysLeft.asStateFlow()

    /** The non-secret profile of the signed-in user, null when signed out. Never carries the token. */
    val profile: StateFlow<AccountProfileDetails?> = _profile.asStateFlow()

    /** Reads the stored session once (blocking disk read). */
    fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            publish(storage.load())
            loaded = true
        }
    }

    override fun token(): String? {
        ensureLoaded()
        return session?.token
    }

    val isLoggedIn: Boolean get() = token() != null

    /** Starts a session with [token] and the [profile] the login answer carried. */
    fun signIn(token: String, profile: AccountProfileDetails) {
        ensureLoaded()
        synchronized(lock) {
            val next = StoredSession(
                token = token,
                displayName = profile.displayName,
                vipDaysLeft = profile.vipDaysLeft,
                username = profile.username,
                email = profile.email,
                vipExpire = profile.vipExpire,
                vipExpirePercent = profile.vipExpirePercent,
                vipStatus = profile.vipStatus,
            )
            storage.save(next)
            publish(next)
        }
    }

    /**
     * Refreshes the profile of the current session. The VIP plan (days, expiry, percent, status) is replaced as a group
     * (nothing there = no plan); a null [AccountProfileDetails.displayName], [AccountProfileDetails.username] or
     * [AccountProfileDetails.email] keeps what is stored, so an answer that misses a field never erases it. Ignored when
     * signed out meanwhile; no disk write when nothing changed.
     */
    fun updateProfile(profile: AccountProfileDetails) {
        ensureLoaded()
        synchronized(lock) {
            val current = session ?: return
            val next = current.copy(
                displayName = profile.displayName ?: current.displayName,
                vipDaysLeft = profile.vipDaysLeft,
                username = profile.username ?: current.username,
                email = profile.email ?: current.email,
                vipExpire = profile.vipExpire,
                vipExpirePercent = profile.vipExpirePercent,
                vipStatus = profile.vipStatus,
            )
            if (next == current) return
            storage.save(next)
            publish(next)
        }
    }

    fun signOut() {
        ensureLoaded()
        synchronized(lock) {
            storage.clear()
            publish(null)
        }
    }

    private fun publish(value: StoredSession?) {
        session = value
        _state.value = AccountState(loggedIn = value != null, displayName = value?.displayName)
        _vipDaysLeft.value = value?.vipDaysLeft
        _profile.value = value?.toProfile()
    }
}
