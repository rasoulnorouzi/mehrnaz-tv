package app.bolbol.tv.core.data

import android.content.Context
import app.bolbol.tv.core.model.AccountState
import app.bolbol.tv.core.network.TokenProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What is kept of a login: the token the server issued plus the non-secret profile fields shown on the Profile screen
 * (name, the login name split into [username] / [email], VIP days and expiry; display only). The password is never stored.
 */
internal data class StoredSession(
    val token: String,
    val displayName: String?,
    val vipDaysLeft: Int? = null,
    val username: String? = null,
    val email: String? = null,
    val vipExpire: String? = null,
) {
    /** The profile fields without the token: safe to hand to the UI. */
    fun toProfile(): AccountProfileDetails = AccountProfileDetails(
        username = username,
        email = email,
        displayName = displayName,
        vipDaysLeft = vipDaysLeft,
        vipExpire = vipExpire,
    )

    /** The token must never reach a log. */
    override fun toString(): String =
        "StoredSession(token=<redacted>, displayName=$displayName, vipDaysLeft=$vipDaysLeft, " +
            "username=$username, email=$email, vipExpire=$vipExpire)"
}

/**
 * What the login form's first field says about the user: a text with an "@" is the e-mail address, anything else is
 * the username. Returns (username, email); the other half stays unknown until the server's profile call carries it.
 */
internal fun identityOf(loginName: String?): Pair<String?, String?> {
    val name = loginName?.trim()?.takeIf { it.isNotEmpty() } ?: return null to null
    return if ('@' in name) null to name else name to null
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
        val vip = if (prefs.contains(KEY_VIP_DAYS)) prefs.getInt(KEY_VIP_DAYS, 0) else null
        return StoredSession(
            token = token,
            displayName = prefs.getString(KEY_NAME, null),
            vipDaysLeft = vip,
            username = prefs.getString(KEY_USERNAME, null),
            email = prefs.getString(KEY_EMAIL, null),
            vipExpire = prefs.getString(KEY_VIP_EXPIRE, null),
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
        }.commit()
    }

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

    /**
     * [loginName] is what the user typed in the login form (username or e-mail); it fills whichever of
     * [username] / [email] the server did not send. [vipExpire] is the plan's end date as the server wrote it.
     */
    fun signIn(
        token: String,
        displayName: String?,
        vipDaysLeft: Int?,
        loginName: String? = null,
        username: String? = null,
        email: String? = null,
        vipExpire: String? = null,
    ) {
        ensureLoaded()
        synchronized(lock) {
            val (typedUsername, typedEmail) = identityOf(loginName)
            val next = StoredSession(
                token = token,
                displayName = displayName,
                vipDaysLeft = vipDaysLeft,
                username = username ?: typedUsername,
                email = email ?: typedEmail,
                vipExpire = vipExpire,
            )
            storage.save(next)
            publish(next)
        }
    }

    /**
     * Refreshes the name and VIP plan of the current session (days and expiry come together: null = no plan); a null
     * [displayName], [username] or [email] keeps what is stored. Ignored when signed out meanwhile.
     */
    fun updateProfile(
        displayName: String?,
        vipDaysLeft: Int?,
        vipExpire: String? = null,
        username: String? = null,
        email: String? = null,
    ) {
        ensureLoaded()
        synchronized(lock) {
            val current = session ?: return
            val next = current.copy(
                displayName = displayName ?: current.displayName,
                vipDaysLeft = vipDaysLeft,
                vipExpire = vipExpire,
                username = username ?: current.username,
                email = email ?: current.email,
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
