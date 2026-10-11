package app.bolbol.tv.feature.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The two calls [AppSettings] needs from a key-value store (SharedPreferences in the app, a map in tests). */
internal interface KeyValueStore {
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
}

internal class SharedPreferencesStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)

    /** `apply()`: the in-memory value changes at once, the disk write happens off the main thread. */
    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }
}

/**
 * App-level switches that are not playback preferences (so they do not live in Room): currently
 * only the performance overlay. SharedPreferences-backed; create it once in `Application`
 * (the small file is read on first use) and share the instance with the screens that need it.
 */
class AppSettings internal constructor(private val store: KeyValueStore) {

    constructor(context: Context) : this(
        SharedPreferencesStore(
            context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE),
        ),
    )

    private val _showPerformance = MutableStateFlow(store.getBoolean(KEY_SHOW_PERFORMANCE, false))

    /** FPS / memory / player stats overlay (debug aid). Off by default. */
    val showPerformance: StateFlow<Boolean> = _showPerformance.asStateFlow()

    fun setShowPerformance(value: Boolean) {
        if (_showPerformance.value == value) return
        store.putBoolean(KEY_SHOW_PERFORMANCE, value)
        _showPerformance.value = value
    }

    internal companion object {
        const val FILE_NAME = "bolbol_app_settings"
        const val KEY_SHOW_PERFORMANCE = "show_performance"
    }
}
