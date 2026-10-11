package app.bolbol.tv.core.lanremote

/** Longest text forwarded to the TV (a search query; the 2 KB body limit allows more, we do not). */
internal const val MAX_REMOTE_TEXT_CHARS = 200

private const val ARABIC_YEH = 0x064A
private const val PERSIAN_YEH = 0x06CC
private const val ARABIC_KAF = 0x0643
private const val PERSIAN_KAF = 0x06A9

/** LRM, RLM, BOM and the bidi embedding/override/isolate controls (written as numbers on purpose). */
private fun isBidiOrBom(code: Int): Boolean =
    code == 0xFEFF || code == 0x200E || code == 0x200F || code in 0x202A..0x202E || code in 0x2066..0x2069

/**
 * Cleans text typed on the phone before it reaches the TV search box:
 * - Arabic yeh (U+064A) and kaf (U+0643) become the Persian letters (U+06CC, U+06A9), because phone keyboards
 *   in Arabic mode produce the wrong code points and the catalogue search would not match them;
 * - line breaks and tabs become one space; other control characters and bidi marks (LRM/RLM/embeddings/isolates,
 *   BOM) are dropped; the zero-width non-joiner (U+200C) is kept, Persian needs it;
 * - unpaired surrogates are dropped and the result is cut to [MAX_REMOTE_TEXT_CHARS] without splitting a pair.
 * Whitespace is otherwise kept as typed (replace semantics: the TV shows what the phone shows).
 */
internal fun normalizeRemoteText(raw: String): String {
    val out = StringBuilder(minOf(raw.length, MAX_REMOTE_TEXT_CHARS))
    var i = 0
    while (i < raw.length && out.length < MAX_REMOTE_TEXT_CHARS) {
        val c = raw[i]
        val code = c.code
        when {
            code == ARABIC_YEH -> out.append(PERSIAN_YEH.toChar())
            code == ARABIC_KAF -> out.append(PERSIAN_KAF.toChar())
            c == '\n' || c == '\r' || c == '\t' -> out.append(' ')
            code < 0x20 || code in 0x7F..0x9F -> Unit
            isBidiOrBom(code) -> Unit
            Character.isHighSurrogate(c) -> {
                if (i + 1 < raw.length && Character.isLowSurrogate(raw[i + 1])) {
                    if (out.length + 2 > MAX_REMOTE_TEXT_CHARS) break
                    out.append(c).append(raw[i + 1])
                    i++
                }
            }
            Character.isLowSurrogate(c) -> Unit
            else -> out.append(c)
        }
        i++
    }
    return out.toString()
}
