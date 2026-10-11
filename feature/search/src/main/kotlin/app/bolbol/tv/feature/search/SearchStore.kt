package app.bolbol.tv.feature.search

import android.content.Context
import android.content.SharedPreferences

/** Persistence of the last keyboard layout and the recent searches (the module's own SharedPreferences). */
internal interface SearchStore {
    fun loadLanguage(): KeyboardLanguage
    fun saveLanguage(language: KeyboardLanguage)
    fun loadRecents(): List<String>
    fun saveRecents(recents: List<String>)
}

/** Blocking calls: invoke off the main thread (the first access reads the file). */
internal class SharedPrefsSearchStore(context: Context) : SearchStore {
    private val appContext = context.applicationContext ?: context
    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun loadLanguage(): KeyboardLanguage =
        KeyboardLanguage.fromCode(prefs.getString(KEY_LANGUAGE, null))

    override fun saveLanguage(language: KeyboardLanguage) {
        prefs.edit().putString(KEY_LANGUAGE, language.code).apply()
    }

    override fun loadRecents(): List<String> = decodeRecents(prefs.getString(KEY_RECENTS, null))

    override fun saveRecents(recents: List<String>) {
        prefs.edit().putString(KEY_RECENTS, encodeRecents(recents)).apply()
    }

    private companion object {
        const val PREFS_NAME = "bolbol_search"
        const val KEY_LANGUAGE = "keyboard_language"
        const val KEY_RECENTS = "recent_searches"
    }
}
