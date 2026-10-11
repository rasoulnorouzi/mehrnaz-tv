package app.bolbol.tv.core.network

/**
 * The account API's wire format in one place (docs/auth.md section 0, confirmed from the official TV app on
 * 2026-10-11). If the server ever changes, this file plus [AccountParsing] are the only places to touch.
 *
 * Envelope of every answer: `{ "success": bool, "data": T, "message": string, "page"?: int, "per_page"?: int }`.
 * Parsing stays tolerant on purpose (several token/list shapes are accepted), because the server is third-party.
 */
internal object AccountWire {

    // --- login: POST form-urlencoded username, password -> data = Profile ---------------------------

    const val LOGIN_PATH = "login"
    const val FIELD_USERNAME = "username"
    const val FIELD_PASSWORD = "password"

    /** GET with the token -> data = Profile (display name, VIP days). */
    const val PROFILE_PATH = "user/profile"

    // --- "My List" = the site's favourites: GET -> data = [{id,...}], POST form post_id toggles -------

    const val FAVORITES_PATH = "user/favorite-list"
    const val FIELD_POST_ID = "post_id"

    /** Optional query parameter for the next page of a list GET. */
    const val QUERY_PAGE = "page"

    // --- continue watching with position: GET -> data = [{id,title,image,current_time,...}] ---------
    // --- POST multipart post_id, current_time (seconds), season + episode (omitted for movies) -------

    const val PROGRESS_PATH = "user/watch-list"
    const val FIELD_CURRENT_TIME = "current_time"
    const val FIELD_SEASON = "season"
    const val FIELD_EPISODE = "episode"

    // --- token transport: `Authorization: Bearer <token>` on user/ calls ----------------------------------

    const val AUTH_HEADER = "Authorization"
    const val AUTH_SCHEME = "Bearer "

    private const val API_ROOT = "/wp-json/mapi/v1/"

    /** Calls that need the token: everything under `user/`. */
    fun needsToken(encodedPath: String): Boolean = encodedPath.startsWith(API_ROOT + "user/")

    // --- response reading ---------------------------------------------------------------------------

    /** Keys that may hold the login token, in priority order (case-insensitive, shallowest object first; real one: `data.token`). */
    val TOKEN_KEYS: List<String> = listOf(
        "token", "access_token", "accessToken", "api_token", "apiToken", "auth_token", "authToken", "jwt",
    )

    /** Keys that may hold a human name of the signed-in user, in priority order (real one: `data.display_name`). */
    val NAME_KEYS: List<String> = listOf("display_name", "displayName", "name", "nickname", "user_nicename", "user_login", "username")

    /** Keys that may hold the account's username (real one: `data.username`). */
    val USERNAME_KEYS: List<String> = listOf("username", "user_login", "userName", "login")

    /** Keys that may hold the account's e-mail address (real one: `data.email`). */
    val EMAIL_KEYS: List<String> = listOf("email", "user_email", "e-mail", "mail")

    /** Keys inside `vip` that may hold the plan's end date (real one: `vip.expire`). */
    val VIP_EXPIRE_KEYS: List<String> = listOf("expire", "expires", "expire_date", "expiry", "expire_at")

    /** Keys inside `vip` that may hold the share of the plan already used (real one: `vip.expire_percent`). */
    val VIP_PERCENT_KEYS: List<String> = listOf("expire_percent", "expire_percentage", "percent")

    /** Keys of the object that holds a list, in priority order (real one: `data`). */
    val LIST_KEYS: List<String> = listOf("data", "items", "posts", "list", "results", "favorites", "favourites", "watch_list")

    /**
     * Keys that may hold a post id inside one list item. `post_id` goes before `id`: a join-table row carries its
     * own row `id`, while a catalogue post only has `id`.
     */
    val ID_KEYS: List<String> = listOf("post_id", "postId", "postid", "id", "ID", "post")

    /** Keys of the total page count of a paged list. */
    val TOTAL_PAGE_KEYS: List<String> = listOf("total_page", "total_pages", "totalPages", "totalPage")

    /** A 400 message containing this ("... is required") means the server rejected the shape of our request. */
    const val REQUIRED_MARKER = "الزامی"

    /** Hard cap on pages read per sync, so a misparsed page count can never become a crawl. */
    const val MAX_LIST_PAGES = 10

    /** Server progress items read per pull (the server list is "continue watching", normally a few dozen). */
    const val MAX_PROGRESS_ITEMS = 60
}
