package app.bolbol.tv.core.network

import app.bolbol.tv.core.model.TitleSummary
import app.bolbol.tv.core.model.TitleType
import app.bolbol.tv.core.model.WatchKey
import app.bolbol.tv.core.network.dto.SummaryDto
import app.bolbol.tv.core.network.mapper.ImageUrls
import app.bolbol.tv.core.network.mapper.cleanTitle
import app.bolbol.tv.core.network.mapper.normalizeDigits
import app.bolbol.tv.core.network.mapper.toSummary
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Tolerant readers for the account responses (docs/auth.md section 0 gives the confirmed shapes; the readers also
 * accept plausible variants because the server is third-party and changes without notice). Pure functions on parsed
 * JSON so they are unit-tested with fake payloads. All of them return null (or a [LoginOutcome.Failed]) instead of
 * throwing.
 */
internal object AccountParsing {

    // Persian texts that may be shown to the user.
    const val MSG_OFFLINE = "اتصال اینترنت برقرار نیست؛ دوباره تلاش کنید."
    const val MSG_SERVER = "سرور پاسخ نمی‌دهد؛ کمی بعد دوباره تلاش کنید."
    const val MSG_BAD_RESPONSE = "پاسخ نامعتبر؛ دوباره تلاش کنید."
    const val MSG_REJECTED = "سرور درخواست ورود را نپذیرفت؛ شاید نسخهٔ برنامه با سایت سازگار نیست."
    const val MSG_TOO_MANY = "تعداد تلاش‌ها زیاد است؛ چند دقیقه بعد دوباره امتحان کنید."

    private val BOM = Char(0xFEFF)
    private val ZWNJ = Char(0x200C).toString()
    private const val MAX_NODES = 400
    private const val MAX_LIST_DEPTH = 4
    private const val MAX_NAME_LENGTH = 64
    private const val MAX_EMAIL_LENGTH = 128
    private const val MAX_SHAPE_DEPTH = 4
    private const val MAX_SHAPE_KEYS = 14

    /** Parses [text] when it is a JSON object or array (HTML pages, plain text and empty bodies give null). */
    fun parseJson(text: String): JsonElement? {
        val trimmed = text.trimStart { it.isWhitespace() || it == BOM }
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return null
        return try {
            BolboljanJson.parseToJsonElement(trimmed)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    // --- login and profile ---------------------------------------------------------------------------

    /**
     * Maps an HTTP status and body of `POST login` to a result.
     * - token found in a 2xx body (`data.token`): success, with the profile fields found next to it;
     * - 2xx without a token: `success:false` means refused credentials, anything else is an unexpected shape;
     * - 400/401/403 with a JSON body: wrong credentials, unless the message says a field is "required"
     *   (then our request shape was refused, which is an app/server mismatch and not the user's fault);
     * - 429, 5xx, 404/405 and non-JSON bodies: failures, never "wrong password".
     */
    fun classifyLogin(httpCode: Int, body: String): LoginOutcome {
        val json = parseJson(body)
        if (httpCode in 200..299) {
            val token = json?.let(::findToken)
            if (token != null) return LoginOutcome.Success(token, parseProfile(json, token))
            return if (json != null && successFlag(json) == false) LoginOutcome.WrongCredentials else failed(MSG_BAD_RESPONSE)
        }
        return when {
            httpCode == 429 -> failed(MSG_TOO_MANY)
            httpCode in 500..599 -> failed(MSG_SERVER)
            json == null -> failed(MSG_BAD_RESPONSE)
            messageOf(json)?.contains(AccountWire.REQUIRED_MARKER) == true -> failed(MSG_REJECTED)
            httpCode == 400 || httpCode == 401 || httpCode == 403 -> LoginOutcome.WrongCredentials
            else -> failed(MSG_BAD_RESPONSE)
        }
    }

    private fun failed(message: String) = LoginOutcome.Failed(message)

    /** The token: the first of [AccountWire.TOKEN_KEYS] found as a non-blank string, searching shallow objects first. */
    fun findToken(root: JsonElement): String? = breadthFirst(root) { obj ->
        AccountWire.TOKEN_KEYS.firstNotNullOfOrNull { obj.stringValue(it) }
    }

    /**
     * The non-secret fields of a login or `user/profile` answer: display name (`display_name`, else `username`...),
     * `username`, `email` and the VIP plan (`vip.days_left`, `expire`, `expire_percent`, `status`). Strings and numbers
     * are both accepted; the first object (shallowest first) that has a field wins. [token] is excluded from every text
     * candidate, so a token under a name-like key can never end up in the profile.
     */
    fun parseProfile(root: JsonElement, token: String? = null): RemoteProfile {
        fun text(keys: List<String>, maxLength: Int): String? = breadthFirst(root) { obj ->
            keys.firstNotNullOfOrNull { key -> obj.stringOrNumber(key)?.takeIf { it != token && it.length <= maxLength } }
        }
        val name = breadthFirst(root) { obj ->
            AccountWire.NAME_KEYS.firstNotNullOfOrNull { key ->
                obj.stringValue(key)?.takeIf { it != token && it.length <= MAX_NAME_LENGTH }
            }
        }
        val vip = breadthFirst(root) { obj -> obj.entryIgnoreCase("vip") as? JsonObject }
        return RemoteProfile(
            displayName = name,
            vipDaysLeft = vip?.let(::vipDaysLeft),
            username = text(AccountWire.USERNAME_KEYS, MAX_NAME_LENGTH),
            email = text(AccountWire.EMAIL_KEYS, MAX_EMAIL_LENGTH),
            vipExpire = vip?.let { v -> AccountWire.VIP_EXPIRE_KEYS.firstNotNullOfOrNull { v.stringOrNumber(it) } },
            vipExpirePercent = vip?.let(::vipExpirePercent),
            vipStatus = vip?.stringOrNumber("status"),
        )
    }

    /** The share of the plan already used, 0..100, from a number or numeric string (Persian digits and "%" too). */
    internal fun vipExpirePercent(vip: JsonObject): Int? {
        val raw = AccountWire.VIP_PERCENT_KEYS.firstNotNullOfOrNull { vip.stringOrNumber(it) }
            ?.normalizeDigits()?.trim()?.removeSuffix("%")?.trim()
            ?: return null
        return (raw.toIntOrNull() ?: raw.toDoubleOrNull()?.toInt())?.coerceIn(0, 100)
    }

    /**
     * Days left of an active plan, or null for "no plan / expired / zero days". `status` words that clearly mean
     * "not active" win over a stale `days_left`.
     */
    internal fun vipDaysLeft(vip: JsonObject): Int? {
        val status = vip.stringOrNumber("status")?.lowercase()
        if (status in INACTIVE_STATUSES) return null
        val days = (vip.entryIgnoreCase("days_left") as? JsonPrimitive)?.takeIf { it !is JsonNull }
            ?.content?.normalizeDigits()?.trim()?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }
        return days?.takeIf { it > 0 }
    }

    private val INACTIVE_STATUSES = setOf("expired", "inactive", "disabled", "none", "false", "0", "no", "غیرفعال", "منقضی")

    /** `success` as a boolean when present (true/false/"1"/"0"/"on"), else null. */
    fun successFlag(root: JsonElement): Boolean? {
        val value = (root as? JsonObject)?.entryIgnoreCase("success") as? JsonPrimitive ?: return null
        if (value is JsonNull) return null
        value.booleanOrNull?.let { return it }
        return when (value.content.trim().lowercase()) {
            "1", "on", "yes", "true" -> true
            "0", "", "off", "no", "false" -> false
            else -> null
        }
    }

    fun messageOf(root: JsonElement): String? = (root as? JsonObject)?.stringValue("message")

    // --- favourites (= My List) ------------------------------------------------------------------------

    /**
     * The entries of a favourites response, or null when no list could be found or its entries carry no readable id
     * (an unknown shape is a failure, never an empty list: an empty result would delete local items).
     * Understands a bare array, `{data:[...]}`, a paged envelope, `{data:{items|posts|...:[...]}}`, an object keyed by
     * post id, entries that are numbers, numeric strings, post-shaped objects, `{post_id,...}` rows or `{post:{...}}`.
     */
    fun extractFavorites(root: JsonElement, images: ImageUrls = ImageUrls.Default): List<RemoteFavorite>? {
        val elements = findItemElements(root, depth = 0) ?: return null
        val items = elements.mapNotNull { (fallbackId, element) ->
            val (id, source) = idAndSource(element, fallbackId) ?: return@mapNotNull null
            RemoteFavorite(id, source?.let { summaryOf(it, id, images) })
        }.distinctBy { it.id }
        if (elements.isNotEmpty() && items.isEmpty()) return null
        return items
    }

    /** `total_page` of a paged list (top level or inside `data`), or null when the list does not say. */
    fun totalPages(root: JsonElement): Int? {
        val top = root as? JsonObject ?: return null
        for (candidate in listOf(top, top.entryIgnoreCase("data") as? JsonObject)) {
            candidate ?: continue
            for (key in AccountWire.TOTAL_PAGE_KEYS) {
                val n = (candidate.entryIgnoreCase(key) as? JsonPrimitive)?.content?.trim()?.toIntOrNull()
                if (n != null && n > 0) return n
            }
        }
        return null
    }

    /** `per_page` of a paged list envelope, or null. */
    fun perPage(root: JsonElement): Int? =
        ((root as? JsonObject)?.entryIgnoreCase("per_page") as? JsonPrimitive)?.content?.trim()?.toIntOrNull()?.takeIf { it > 0 }

    // --- continue watching with position (`user/watch-list`) -----------------------------------------------

    /**
     * Server progress entries: `{id, title, image, current_time, duration, season, episode}` with numbers or numeric
     * strings. `current_time` is **seconds** (the official app seeks to `current_time * 1000`). `duration` is not
     * trusted (the official app never reads it; unit and format are unknown): [RemoteProgressItem.durationMs] is 0
     * unless [saneDurationSeconds] can prove a value. Null when no list was found; entries without a readable id are
     * skipped.
     */
    fun extractProgress(root: JsonElement, images: ImageUrls = ImageUrls.Default): List<RemoteProgressItem>? {
        val elements = findItemElements(root, depth = 0) ?: return null
        val items = elements.mapNotNull { (fallbackId, element) ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = idOf(obj) ?: fallbackId?.takeIf { it > 0 } ?: return@mapNotNull null
            val season = intField(obj, "season") ?: 0
            val episode = intField(obj, "episode") ?: 0
            val key = if (season > 0 && episode > 0) WatchKey(id, season, episode) else WatchKey(id)
            val positionSec = secondsField(obj, "current_time") ?: 0L
            val durationSec = saneDurationSeconds(obj.stringOrNumber("duration"), positionSec)
            val title = cleanTitle(obj.stringOrNumber("title").orEmpty())
            val summary = if (title.isBlank()) null else TitleSummary(
                id = id,
                type = if (key.isEpisode) TitleType.SERIES else null,
                title = title,
                faTitle = "",
                posterUrl = images.normalize(obj.stringOrNumber("image") ?: obj.stringOrNumber("thumbnail")),
                backdropUrl = null,
                year = null,
                imdbRating = null,
                hasDub = false,
                hasSubtitle = false,
            )
            RemoteProgressItem(key, positionSec * 1000, durationSec * 1000, summary)
        }.distinctBy { it.key }
        if (elements.isNotEmpty() && items.isEmpty()) return null
        return items
    }

    /**
     * The server's `duration` field is unreliable (unknown unit and format; the official app never reads it), so it is
     * accepted only when it provably is a length in seconds: a plain number of seconds, `H:MM:SS` / `MM:SS`, or a
     * number followed by an explicit unit word (`85 دقیقه`, `85 min`, `2 ساعت`), that is also longer than
     * [positionSec] and between 60 s and 6 h ([RemoteProgressItem.saneDurationMs]). A bare number is never guessed to
     * be minutes. Anything else (missing, `0`, text, too short, too long, shorter than the position) gives 0 = unknown.
     */
    internal fun saneDurationSeconds(raw: String?, positionSec: Long): Long {
        val seconds = parseDurationSeconds(raw) ?: return 0
        return RemoteProgressItem.saneDurationMs(seconds * 1000, positionSec * 1000) / 1000
    }

    private val UNIT_SECONDS = mapOf(
        "ثانیه" to 1L, "sec" to 1L, "secs" to 1L, "second" to 1L, "seconds" to 1L, "s" to 1L,
        "دقیقه" to 60L, "min" to 60L, "mins" to 60L, "minute" to 60L, "minutes" to 60L, "m" to 60L,
        "ساعت" to 3600L, "h" to 3600L, "hr" to 3600L, "hrs" to 3600L, "hour" to 3600L, "hours" to 3600L,
    )
    private val NUMBER_WITH_UNIT = Regex("""^(\d+(?:\.\d+)?)\s*([^\d\s:.]+)\.?$""")

    /** Seconds from a plain number, a clock string or a number with a unit word; null when it is none of those. */
    private fun parseDurationSeconds(raw: String?): Long? {
        val text = raw?.normalizeDigits()?.replace('٫', '.')?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        parseSeconds(text)?.let { return it }
        val match = NUMBER_WITH_UNIT.matchEntire(text) ?: return null
        val unit = match.groupValues[2].replace(ZWNJ, "").lowercase()
        val factor = UNIT_SECONDS[unit] ?: return null
        val amount = match.groupValues[1].toDoubleOrNull() ?: return null
        return (amount * factor).toLong()
    }

    private fun intField(obj: JsonObject, key: String): Int? =
        obj.stringOrNumber(key)?.normalizeDigits()?.trim()?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }?.takeIf { it >= 0 }

    /** Seconds from `1234`, `"1234"`, `"1234.5"` or `"1:02:03"` / `"02:03"`; null when absent or unreadable. */
    private fun secondsField(obj: JsonObject, key: String): Long? =
        parseSeconds(obj.stringOrNumber(key)?.normalizeDigits()?.trim() ?: return null)

    private fun parseSeconds(raw: String): Long? {
        if (':' in raw) {
            val parts = raw.split(':').map { it.trim().toDoubleOrNull() ?: return null }
            if (parts.size !in 2..3 || parts.any { it < 0 }) return null
            return parts.fold(0.0) { acc, p -> acc * 60 + p }.toLong()
        }
        return (raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong())?.takeIf { it >= 0 }
    }

    // --- shared list reading ---------------------------------------------------------------------------------

    private fun findItemElements(node: JsonElement, depth: Int): List<Pair<Long?, JsonElement>>? {
        if (depth > MAX_LIST_DEPTH) return null
        return when (node) {
            is JsonArray -> node.map { null to it }
            is JsonObject -> {
                idKeyedEntries(node)?.let { return it }
                for (key in AccountWire.LIST_KEYS) {
                    val value = node.entryIgnoreCase(key)
                    if (value is JsonArray || value is JsonObject) findItemElements(value, depth + 1)?.let { return it }
                }
                // Last resort: the first array of post-like entries under any other key.
                node.values.firstOrNull { it is JsonArray && it.firstOrNull().looksLikeItem() }
                    ?.let { findItemElements(it, depth + 1) }
            }
            else -> null
        }
    }

    /** PHP encodes sparse id lists as `{"353371":{...},"1234":{...}}`; the key is then the post id. */
    private fun idKeyedEntries(obj: JsonObject): List<Pair<Long?, JsonElement>>? {
        if (obj.isEmpty() || !obj.keys.all { key -> key.isNotEmpty() && key.all(Char::isDigit) }) return null
        return obj.entries.map { (key, value) -> key.toLongOrNull() to value }
    }

    private fun JsonElement?.looksLikeItem(): Boolean = when (this) {
        is JsonObject -> AccountWire.ID_KEYS.any { entryIgnoreCase(it) != null }
        is JsonPrimitive -> content.trim().toLongOrNull() != null
        else -> false
    }

    /** The post id of one list entry and the object that describes it (null for a bare id). */
    private fun idAndSource(element: JsonElement, fallbackId: Long?): Pair<Long, JsonObject?>? = when (element) {
        is JsonPrimitive -> if (element is JsonNull) null else positiveLong(element.content)?.let { it to null }
        is JsonObject -> {
            // `{post: {...}}` wraps the real post; `{post: 123}` is handled by the id keys.
            val source = element.entryIgnoreCase("post") as? JsonObject ?: element
            val id = idOf(source) ?: idOf(element) ?: fallbackId?.takeIf { it > 0 }
            id?.let { it to source }
        }
        else -> null
    }

    private fun idOf(obj: JsonObject): Long? = AccountWire.ID_KEYS.firstNotNullOfOrNull { key ->
        (obj.entryIgnoreCase(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { positiveLong(it.content) }
    }

    private fun positiveLong(raw: String): Long? {
        val text = raw.normalizeDigits().trim()
        return (text.toLongOrNull() ?: text.toDoubleOrNull()?.toLong())?.takeIf { it > 0 }
    }

    /**
     * A poster-ready summary when the entry looks like a catalogue item (it has a title), else null (id only).
     * `image` is accepted as the poster when the usual `thumbnail` is absent (the watch-list item names it so).
     */
    private fun summaryOf(source: JsonObject, id: Long, images: ImageUrls): TitleSummary? = try {
        val dto = BolboljanJson.decodeFromJsonElement(SummaryDto.serializer(), source)
        val poster = dto.thumbnail.ifBlank { source.stringOrNumber("image").orEmpty() }
        dto.copy(id = id, thumbnail = poster).toSummary(fallbackType = null, images = images)
            ?.takeIf { it.title.isNotBlank() || it.faTitle.isNotBlank() }
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    // --- diagnostics -------------------------------------------------------------------------------------------

    /**
     * Key names and JSON types only, never values: `{success:bool,data:{token:str,vip:{days_left:num}}}`. This is the
     * only thing the account code ever logs (docs/auth.md section 8.3).
     */
    fun describeShape(element: JsonElement?, depth: Int = 0): String = when (element) {
        null -> "unparsable"
        is JsonNull -> "null"
        is JsonPrimitive -> if (element.isString) "str" else if (element.booleanOrNull != null) "bool" else "num"
        is JsonArray -> when {
            element.isEmpty() -> "[]"
            depth >= MAX_SHAPE_DEPTH -> "[...]"
            else -> "[${element.size}x ${describeShape(element.first(), depth + 1)}]"
        }
        is JsonObject -> when {
            element.isEmpty() -> "{}"
            depth >= MAX_SHAPE_DEPTH -> "{...}"
            else -> {
                val shown = element.entries.take(MAX_SHAPE_KEYS)
                    .joinToString(",") { (key, value) -> "$key:${describeShape(value, depth + 1)}" }
                val more = if (element.size > MAX_SHAPE_KEYS) ",+${element.size - MAX_SHAPE_KEYS}" else ""
                "{$shown$more}"
            }
        }
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    private fun JsonObject.entryIgnoreCase(key: String): JsonElement? =
        this[key] ?: entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value

    private fun JsonObject.stringValue(key: String): String? =
        (entryIgnoreCase(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

    /** A string or number primitive as text (the server types the same field both ways). */
    private fun JsonObject.stringOrNumber(key: String): String? =
        (entryIgnoreCase(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() }

    /** Visits objects shallowest first (so `{token}` beats `{data:{user:{token}}}`) and returns the first pick. */
    private inline fun <T : Any> breadthFirst(root: JsonElement, pick: (JsonObject) -> T?): T? {
        val queue = ArrayDeque<JsonElement>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            when (node) {
                is JsonObject -> {
                    pick(node)?.let { return it }
                    node.values.filterTo(queue) { it is JsonObject || it is JsonArray }
                }
                is JsonArray -> node.filterTo(queue) { it is JsonObject || it is JsonArray }
                else -> Unit
            }
        }
        return null
    }
}
