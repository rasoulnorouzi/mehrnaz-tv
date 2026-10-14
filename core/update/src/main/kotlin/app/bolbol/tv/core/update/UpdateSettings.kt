package app.bolbol.tv.core.update

import android.content.Context

/** What the repository remembers between launches (SharedPreferences in the app, a map in tests). */
interface UpdateStore {
    /** Wall-clock time of the last successful manifest fetch, 0 = never. */
    var lastCheckMillis: Long

    /** Raw text of the last successfully fetched manifest, used to answer checks made within 24 h. */
    var cachedManifest: String?

    /** Developer override of the manifest URL, blank = [DEFAULT_MANIFEST_URL]. */
    var manifestUrlOverride: String
}

/**
 * SharedPreferences-backed [UpdateStore]. Create once in `Application`; share the instance between
 * [UpdateRepositoryImpl] (via [manifestUrl]) and the developer settings field ([manifestUrlOverride]).
 * Holds no secrets: the manifest is public.
 */
class UpdateSettings(context: Context) : UpdateStore {
    private val prefs = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override var lastCheckMillis: Long
        get() = prefs.getLong(KEY_LAST_CHECK, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_CHECK, value).apply()

    override var cachedManifest: String?
        get() = prefs.getString(KEY_CACHED_MANIFEST, null)
        set(value) = prefs.edit().putString(KEY_CACHED_MANIFEST, value).apply()

    override var manifestUrlOverride: String
        get() = prefs.getString(KEY_URL_OVERRIDE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_URL_OVERRIDE, value.trim()).apply()

    /** The URL to fetch right now: the override when set, else [DEFAULT_MANIFEST_URL]. */
    fun manifestUrl(): String = manifestUrlOverride.ifBlank { DEFAULT_MANIFEST_URL }

    private companion object {
        const val FILE_NAME = "bolbol_update"
        const val KEY_LAST_CHECK = "last_check"
        const val KEY_CACHED_MANIFEST = "cached_manifest"
        const val KEY_URL_OVERRIDE = "manifest_url_override"
    }
}
