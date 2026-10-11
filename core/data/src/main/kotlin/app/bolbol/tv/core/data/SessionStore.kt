package app.bolbol.tv.core.data

import android.content.Context
import app.bolbol.tv.core.model.AccountState
import app.bolbol.tv.core.network.TokenProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What is kept of a login: the token the server issued, a name to greet the user with and the VIP days (display only).
 * The password is never stored.
 */
internal data class StoredSession(
    val token: String,
    val displayName: String?,
    val vipDaysLeft: Int? = null,
) {
    /** The token must never reach a log. */
    override fun toString(): String = "StoredSession(token=<redacted>, displayName=$displayName, vipDaysLeft=$vipDaysLeft)"
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
        return StoredSession(token, prefs.getString(KEY_NAME, null), vip)
    }

    override fun save(session: StoredSession) {
        prefs.edit().apply {
            putString(KEY_TOKEN, session.token)
            putString(KEY_NAME, session.displayName)
            if (session.vipDaysLeft != null) putInt(KEY_VIP_DAYS, session.vipDaysLeft) else remove(KEY_VIP_DAYS)
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

    /** Valid after [ensureLoaded]. */
    val state: StateFlow<AccountState> = _state.asStateFlow()
    val vipDaysLeft: StateFlow<Int?> = _vipDaysLeft.asStateFlow()

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

    fun signIn(token: String, displayName: String?, vipDaysLeft: Int?) {
        ensureLoaded()
        synchronized(lock) {
            val next = StoredSession(token, displayName, vipDaysLeft)
            storage.save(next)
            publish(next)
        }
    }

    /** Refreshes the name and VIP days of the current session; ignored when signed out meanwhile. */
    fun updateProfile(displayName: String?, vipDaysLeft: Int?) {
        ensureLoaded()
        synchronized(lock) {
            val current = session ?: return
            val next = current.copy(displayName = displayName ?: current.displayName, vipDaysLeft = vipDaysLeft)
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
    }
}
