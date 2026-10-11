package app.bolbol.tv

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Navigation 3 destinations. Serializable so the back stack survives process death. */
@Serializable data object HomeRoute : NavKey

@Serializable data class DetailsRoute(val titleId: Long) : NavKey

/**
 * Player for one movie (season = episode = 0) or episode.
 * [startPositionMs] >= 0 starts exactly there; [ASK] lets the player offer "resume / from start".
 */
@Serializable data class PlayerRoute(
    val postId: Long,
    val season: Int = 0,
    val episode: Int = 0,
    val startPositionMs: Long = ASK,
) : NavKey {
    companion object { const val ASK = -1L }
}

/** Category / "view all" grid. [category] is a [app.bolbol.tv.core.model.Category] name. */
@Serializable data class BrowseRoute(
    val title: String,
    val category: String,
    val country: Int? = null,
    val genre: Int? = null,
    val imdbTop250: Boolean = false,
) : NavKey

@Serializable data object SettingsRoute : NavKey

@Serializable data object SearchRoute : NavKey

@Serializable data object AccountRoute : NavKey

/** «پروفایل»: sign in / out and the signed-in user's details. */
@Serializable data object ProfileRoute : NavKey

/** Not-yet-built sections (M2/M3) show a placeholder. */
@Serializable data class ComingSoonRoute(val title: String) : NavKey
