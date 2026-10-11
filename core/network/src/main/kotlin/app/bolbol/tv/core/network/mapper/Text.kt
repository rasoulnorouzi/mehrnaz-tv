package app.bolbol.tv.core.network.mapper

import app.bolbol.tv.core.model.AudioType

/*
 * Pure text/number helpers used by the DTO mappers. No Android dependencies, so they run in
 * plain JVM unit tests. Rules come from docs/api.md quirks 2, 8, 11, 12 and section 8.4.
 */

/** Blank -> null, otherwise trimmed. */
internal fun String?.nullIfBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/** Persian (U+06F0..) and Arabic-Indic (U+0660..) digits -> ASCII digits. */
internal fun String.normalizeDigits(): String {
    if (none { it in '٠'..'٩' || it in '۰'..'۹' }) return this
    return map {
        when (it) {
            in '٠'..'٩' -> '0' + (it - '٠')
            in '۰'..'۹' -> '0' + (it - '۰')
            else -> it
        }
    }.joinToString("")
}

// ---------------------------------------------------------------------------------------------
// HTML entities and titles
// ---------------------------------------------------------------------------------------------

private val ENTITY = Regex("&(#[xX][0-9a-fA-F]{1,6}|#[0-9]{1,7}|[A-Za-z][A-Za-z0-9]{1,9});")

private val NAMED_ENTITIES = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
    "ndash" to "–", "mdash" to "—", "hellip" to "…",
    "lsquo" to "‘", "rsquo" to "’", "sbquo" to "‚",
    "ldquo" to "“", "rdquo" to "”", "bdquo" to "„",
    "laquo" to "«", "raquo" to "»", "middot" to "·", "bull" to "•",
    "copy" to "©", "reg" to "®", "trade" to "™", "deg" to "°", "times" to "×",
    "frac12" to "½", "frac14" to "¼", "frac34" to "¾",
    "agrave" to "à", "aacute" to "á", "acirc" to "â", "atilde" to "ã", "auml" to "ä",
    "aring" to "å", "aelig" to "æ", "ccedil" to "ç",
    "egrave" to "è", "eacute" to "é", "ecirc" to "ê", "euml" to "ë",
    "igrave" to "ì", "iacute" to "í", "icirc" to "î", "iuml" to "ï",
    "ntilde" to "ñ", "ograve" to "ò", "oacute" to "ó", "ocirc" to "ô",
    "otilde" to "õ", "ouml" to "ö", "oslash" to "ø",
    "ugrave" to "ù", "uacute" to "ú", "ucirc" to "û", "uuml" to "ü",
    "szlig" to "ß", "Eacute" to "É", "Aacute" to "Á", "Uuml" to "Ü", "Ouml" to "Ö",
)

/** Decodes numeric (`&#8217;`, `&#x2019;`) and common named (`&amp;`, `&quot;`) entities in one pass. */
internal fun String.decodeHtmlEntities(): String {
    if (indexOf('&') < 0) return this
    return ENTITY.replace(this) { match ->
        val body = match.groupValues[1]
        if (body[0] == '#') {
            val codePoint = if (body[1] == 'x' || body[1] == 'X') {
                body.substring(2).toIntOrNull(16)
            } else {
                body.substring(1).toIntOrNull()
            }
            if (codePoint != null && codePoint in 1..0x10FFFF && codePoint !in 0xD800..0xDFFF) {
                String(Character.toChars(codePoint))
            } else {
                match.value
            }
        } else {
            NAMED_ENTITIES[body] ?: match.value
        }
    }
}

/** Persian type labels the server prepends to titles (docs/api.md quirk 8). Longest first. */
private val TYPE_LABELS: List<String> = listOf(
    "فیلم ترکی",
    "فیلم هندی",
    "فیلم",
    "سریال ترکی",
    "سریال کره ای",
    "سریال کره‌ای", // with ZWNJ
    "سریال",
    "انیمه",
    "انیمیشن",
).sortedByDescending { it.length }

/**
 * Display title: entities decoded, trimmed, and the leading Persian type label (plus the space
 * after it) removed. Titles without a label are returned unchanged. Never use the result as identity.
 */
internal fun cleanTitle(raw: String): String {
    val decoded = raw.decodeHtmlEntities().trim()
    for (label in TYPE_LABELS) {
        if (decoded.length > label.length && decoded.startsWith(label) && decoded[label.length].isWhitespace()) {
            return decoded.substring(label.length).trim()
        }
    }
    return decoded
}

// ---------------------------------------------------------------------------------------------
// Numbers
// ---------------------------------------------------------------------------------------------

private val FOUR_DIGITS = Regex("\\d{4}")
private val DIGIT_RUN = Regex("\\d+")
private val QUALITY_P = Regex("(\\d{3,4})\\s*p", RegexOption.IGNORE_CASE)
private val QUALITY_BARE = Regex("\\b(\\d{3,4})\\b")

/** `"2025"` / `2025` -> 2025; blank, `"0"` or garbage -> null. */
internal fun parseYear(raw: String): Int? =
    FOUR_DIGITS.find(raw.normalizeDigits())?.value?.toIntOrNull()?.takeIf { it in 1870..2200 }

/** `"6.3"` -> 6.3f; `"0.0"`, blank, garbage -> null. */
internal fun parseImdb(raw: String): Float? =
    raw.trim().replace(',', '.').toFloatOrNull()?.takeIf { it > 0f && it <= 10f }

/** `"64%"` / `"64"` -> 64; `"0%"`, blank -> null. */
internal fun parseMetacritic(raw: String): Int? =
    DIGIT_RUN.find(raw.normalizeDigits())?.value?.toIntOrNull()?.takeIf { it in 1..100 }

/** `"85 دقیقه"` -> 85 (first run of digits); blank -> null. */
internal fun parseRuntimeMinutes(raw: String): Int? =
    DIGIT_RUN.find(raw.normalizeDigits())?.value?.toIntOrNull()?.takeIf { it > 0 }

/** `"1080"` / `"BluRay 1080p"` / `"WEB-DL 720p"` -> 1080 / 1080 / 720; `"4K"` -> 2160; else null. */
internal fun parseQuality(raw: String): Int? {
    val text = raw.normalizeDigits()
    QUALITY_P.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
    QUALITY_BARE.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
    return if (text.contains("4k", ignoreCase = true)) 2160 else null
}

/** First number in `"Season 1"` / `"Episode 12"`; null when there is none. */
internal fun parseOrdinal(raw: String): Int? =
    DIGIT_RUN.find(raw.normalizeDigits())?.value?.toIntOrNull()?.takeIf { it > 0 }

/** `"dub"` -> DUBBED, anything else (`"sub"`, blank) -> SUBTITLED. */
internal fun parseAudio(raw: String): AudioType =
    if (raw.contains("dub", ignoreCase = true)) AudioType.DUBBED else AudioType.SUBTITLED

private val SIZE_UNITS = listOf(
    Regex("\\s*tera\\s*bytes?", RegexOption.IGNORE_CASE) to " TB",
    Regex("\\s*giga\\s*bytes?", RegexOption.IGNORE_CASE) to " GB",
    Regex("\\s*mega\\s*bytes?", RegexOption.IGNORE_CASE) to " MB",
    Regex("\\s*kilo\\s*bytes?", RegexOption.IGNORE_CASE) to " KB",
)

/** `"2.42 GigaByte "` -> `"2.42 GB"`, `"780 MegaByte "` -> `"780 MB"`, blank -> null. */
internal fun formatSize(raw: String): String? {
    var text = raw.normalizeDigits().trim()
    if (text.isEmpty()) return null
    for ((regex, unit) in SIZE_UNITS) text = regex.replace(text, unit)
    return text.replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() }
}
