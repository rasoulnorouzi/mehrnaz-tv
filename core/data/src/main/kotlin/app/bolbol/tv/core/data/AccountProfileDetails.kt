package app.bolbol.tv.core.data

import app.bolbol.tv.core.network.RemoteProfile

/**
 * The non-secret profile fields of the signed-in user, for the Profile screen (`DataGraph.accountProfile`), as the
 * server's `Profile` (docs/auth.md §0) described them at login and at the last refresh. It never carries the token.
 * Every field is null when the server did not send it.
 *
 * @property displayName the server's display name, else the name the user typed to log in
 * @property vipDaysLeft days left of an active VIP plan, null without one
 * @property vipExpire the plan's end date exactly as the server wrote it
 * @property vipExpirePercent share of the plan already used, 0..100
 * @property vipStatus the plan's status word as sent (for example "active")
 */
data class AccountProfileDetails(
    val username: String?,
    val email: String?,
    val displayName: String?,
    val vipDaysLeft: Int?,
    val vipExpire: String?,
    val vipExpirePercent: Int? = null,
    val vipStatus: String? = null,
)

/** The profile fields of a server answer; [fallbackName] (what the user typed) stands in when it sent no name. */
internal fun RemoteProfile.toDetails(fallbackName: String? = null) = AccountProfileDetails(
    username = username,
    email = email,
    displayName = displayName ?: fallbackName,
    vipDaysLeft = vipDaysLeft,
    vipExpire = vipExpire,
    vipExpirePercent = vipExpirePercent,
    vipStatus = vipStatus,
)
