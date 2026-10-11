package app.bolbol.tv.core.data

/**
 * The non-secret profile fields of the signed-in user, for the Profile screen (`DataGraph.accountProfile`). It never
 * carries the token. Every field is null when unknown:
 * - [username] / [email]: the half of the login name the user typed that matches (an "@" means e-mail); the other
 *   half stays null until the profile call returns it.
 * - [displayName]: the server's display name, else the typed login name.
 * - [vipDaysLeft]: days left of an active VIP plan, null without one.
 * - [vipExpire]: the plan's end date exactly as the server wrote it (null while the profile call does not carry it).
 */
data class AccountProfileDetails(
    val username: String?,
    val email: String?,
    val displayName: String?,
    val vipDaysLeft: Int?,
    val vipExpire: String?,
)
