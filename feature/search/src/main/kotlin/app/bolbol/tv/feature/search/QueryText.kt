package app.bolbol.tv.feature.search

/** Pure query-text helpers (unit-tested): normalisation before sending, input clean-up, script detection. */

/** Searches start at this many characters (after normalisation). */
internal const val MIN_QUERY_LENGTH = 2

private const val ZWNJ_CODE = 0x200C // ZERO WIDTH NON-JOINER (Persian half-space), kept

/** Characters that never belong in a title query: tatweel, harakat, and invisible bidi / zero-width marks. */
private fun isNoise(c: Char): Boolean = when (c.code) {
    0x0640 -> true // tatweel
    in 0x064B..0x065F, 0x0670 -> true // Arabic harakat + superscript alef
    0x200B, 0x200D, 0x200E, 0x200F, 0xFEFF -> true // ZWSP, ZWJ, LRM, RLM, BOM (ZWNJ 0x200C is kept)
    in 0x202A..0x202E, in 0x2066..0x2069 -> true // bidi embeddings / isolates
    else -> c.isISOControl() && !c.isWhitespace()
}

/** Arabic yeh (U+064A) and alef maksura (U+0649) become Persian yeh (U+06CC); Arabic kaf (U+0643) becomes Persian keheh (U+06A9). */
private fun foldLetter(c: Char): Char = when (c.code) {
    0x064A, 0x0649 -> 0x06CC.toChar()
    0x0643 -> 0x06A9.toChar()
    else -> c
}

/**
 * The text actually sent to the server: Arabic ي/ى/ك folded to Persian ی/ک, noise characters removed,
 * every whitespace run collapsed to one space, trimmed (also of dangling half-spaces).
 * Digits are left as typed (a few titles do use Persian digits).
 */
internal fun normalizeQuery(raw: String): String {
    val sb = StringBuilder(raw.length)
    var pendingSpace = false
    for (original in raw) {
        if (original.isWhitespace()) {
            pendingSpace = sb.isNotEmpty()
            continue
        }
        if (isNoise(original)) continue
        if (pendingSpace) {
            sb.append(' ')
            pendingSpace = false
        }
        sb.append(foldLetter(original))
    }
    return sb.toString().trim { it == ' ' || it.code == ZWNJ_CODE }
}

internal fun isSearchable(normalizedQuery: String): Boolean = normalizedQuery.length >= MIN_QUERY_LENGTH

/**
 * Cleans text that arrives from outside the keyboard (phone remote, voice) before it becomes the
 * visible query: noise removed, whitespace collapsed (a single trailing space is kept so a phone
 * can type word by word), cut to [MAX_QUERY_LENGTH]. Letters are shown as typed (no ي/ك folding).
 */
internal fun sanitizeInput(raw: String): String {
    val sb = StringBuilder(raw.length)
    var pendingSpace = false
    for (c in raw) {
        if (c.isWhitespace()) {
            pendingSpace = sb.isNotEmpty()
            continue
        }
        if (isNoise(c)) continue
        if (pendingSpace) {
            sb.append(' ')
            pendingSpace = false
        }
        sb.append(c)
    }
    if (pendingSpace) sb.append(' ')
    var out = sb.toString()
    if (out.length > MAX_QUERY_LENGTH) {
        out = out.take(MAX_QUERY_LENGTH)
        if (out.last().isHighSurrogate()) out = out.dropLast(1)
    }
    return out
}

/**
 * Script of the first letter (or Persian digit) in [text]: Persian for Arabic-script, English for
 * Latin, null when there is nothing to decide on (empty, ASCII digits, punctuation).
 */
internal fun detectLanguage(text: String): KeyboardLanguage? {
    for (c in text) {
        val code = c.code
        when {
            code in 0x0600..0x06FF || code in 0x0750..0x077F || code in 0xFB50..0xFDFF || code in 0xFE70..0xFEFF ->
                if (c.isLetter() || code in 0x06F0..0x06F9) return KeyboardLanguage.Persian

            c.isLetter() && code < 0x0250 -> return KeyboardLanguage.English
        }
    }
    return null
}

/** Reading direction of the query field: by the text's script, else by the active layout. */
internal fun isRtlQuery(text: String, layout: KeyboardLanguage): Boolean =
    (detectLanguage(text) ?: layout) == KeyboardLanguage.Persian
