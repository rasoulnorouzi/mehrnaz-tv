package app.bolbol.tv.core.player

/**
 * Pure text rules for Persian WebVTT cues (no Android types, unit-tested).
 *
 * Every Bolboljan .vtt starts with a site-credit cue (00:00:01 → 00:00:16):
 * "..:: ارائه شده توسط وب سايت بلبل جان ::.." + "www.bolboljan.net". We drop it.
 */
object SubtitleCues {

    private val CREDIT_MARKERS = listOf("بلبلجان", "bolboljan")

    private const val RLM = '\u200F'

    /** True for the site-credit cue, tolerant of spaces, ZWNJ, bidi marks and letter case. */
    fun isSiteCredit(text: CharSequence?): Boolean {
        if (text.isNullOrEmpty()) return false
        val normalized = StringBuilder(text.length)
        for (c in text) {
            if (c.isWhitespace() || isFormatChar(c) || c == '\u0640' /* tatweel */) continue
            normalized.append(c.lowercaseChar())
        }
        return CREDIT_MARKERS.any { normalized.contains(it) }
    }

    /** Drops credit cues; returns [cues] itself (no allocation) when there is nothing to drop. */
    fun <T> withoutCredits(cues: List<T>, textOf: (T) -> CharSequence?): List<T> =
        if (cues.none { isSiteCredit(textOf(it)) }) cues else cues.filterNot { isSiteCredit(textOf(it)) }

    /**
     * Indices where an RLM must be inserted so each Persian line is laid out right-to-left.
     *
     * Android's StaticLayout picks a paragraph's direction from its first strong character, so a
     * Persian line that starts with Latin text ("OK باشه") would render LTR with its punctuation
     * on the wrong side. For each paragraph (split on '\n') that contains Arabic-script letters
     * but whose first strong character is LTR, we return the paragraph start. Paragraphs that are
     * already RTL-first (the common case) need nothing, so most cues are left untouched.
     */
    fun rtlMarkInsertionPoints(text: CharSequence): IntArray {
        var result: MutableList<Int>? = null
        var start = 0
        while (start <= text.length) {
            var end = text.indexOf('\n', start)
            if (end < 0) end = text.length
            if (needsRtlMark(text, start, end)) {
                if (result == null) result = ArrayList(2)
                result.add(start)
            }
            start = end + 1
        }
        return result?.toIntArray() ?: EMPTY
    }

    /** Convenience for plain strings (tests, logging): inserts the RLMs. */
    fun forceRtl(text: String): String {
        val points = rtlMarkInsertionPoints(text)
        if (points.isEmpty()) return text
        val sb = StringBuilder(text)
        for (i in points.indices.reversed()) sb.insert(points[i], RLM)
        return sb.toString()
    }

    private fun needsRtlMark(text: CharSequence, start: Int, end: Int): Boolean {
        var hasArabic = false
        var firstStrongIsLtr: Boolean? = null
        for (i in start until end) {
            val c = text[i]
            if (isArabicScript(c)) hasArabic = true
            if (firstStrongIsLtr == null) {
                when (Character.getDirectionality(c)) {
                    Character.DIRECTIONALITY_LEFT_TO_RIGHT -> firstStrongIsLtr = true
                    Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                    Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> firstStrongIsLtr = false
                }
            }
            if (hasArabic && firstStrongIsLtr != null) break
        }
        return hasArabic && firstStrongIsLtr == true
    }

    private fun isArabicScript(c: Char): Boolean =
        c in '\u0600'..'\u06FF' || c in '\u0750'..'\u077F' || c in '\u08A0'..'\u08FF' ||
            c in '\uFB50'..'\uFDFF' || c in '\uFE70'..'\uFEFC'

    /** Zero-width and bidi control characters. */
    private fun isFormatChar(c: Char): Boolean =
        c in '\u200B'..'\u200F' || c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069' || c == '\uFEFF'

    private val EMPTY = IntArray(0)
}
