package app.bolbol.tv.feature.tvhome

import android.net.Uri
import app.bolbol.tv.core.model.WatchKey

/**
 * Deep link used by the launcher's Watch Next cards: `mehrnaz://play/{postId}/{season}/{episode}`
 * (movies: season = 0, episode = 0). The app's manifest needs an intent filter for it (see README.md).
 *
 * The string forms are separate so the parsing rules are testable on a plain JVM (android.net.Uri is a stub there).
 */
object DeepLinks {
    const val SCHEME = "mehrnaz"
    private const val HOST_PLAY = "play"

    fun play(key: WatchKey): Uri = Uri.parse(playString(key))

    /** Inverse of [play]; null for anything that is not exactly a play link with three non-negative numbers. */
    fun parse(uri: Uri?): WatchKey? = parseString(uri?.toString())

    internal fun playString(key: WatchKey): String = "$SCHEME://$HOST_PLAY/${key.postId}/${key.season}/${key.episode}"

    internal fun parseString(value: String?): WatchKey? {
        if (value == null) return null
        val prefix = "$SCHEME://$HOST_PLAY/"
        if (!value.startsWith(prefix, ignoreCase = true)) return null
        val parts = value.substring(prefix.length).split('/')
        if (parts.size != 3) return null
        val postId = parts[0].toAsciiLongOrNull() ?: return null
        val season = parts[1].toAsciiLongOrNull() ?: return null
        val episode = parts[2].toAsciiLongOrNull() ?: return null
        if (postId <= 0 || season > Int.MAX_VALUE || episode > Int.MAX_VALUE) return null
        return WatchKey(postId, season.toInt(), episode.toInt())
    }

    /** Plain ASCII digits only: rejects signs, spaces, Persian digits, empty strings and overflow. */
    private fun String.toAsciiLongOrNull(): Long? =
        if (isNotEmpty() && all { it in '0'..'9' }) toLongOrNull() else null
}
