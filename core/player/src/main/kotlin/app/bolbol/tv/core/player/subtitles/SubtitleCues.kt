package app.bolbol.tv.core.player.subtitles

/**
 * Pure text rules for Persian WebVTT cue lines (no Android types, unit-tested).
 *
 * - Every Bolboljan .vtt carries site-credit cues ("..:: ارائه شده توسط وب سايت بلبل جان ::.." +
 *   "www.bolboljan.net", the Telegram/Instagram plug, a bare "بلبل جان"). [isSiteCredit] finds them.
 * - [prepareLine] makes each line display right-to-left the way the translator meant it.
 */
object SubtitleCues {

    // "bollbolljan" is the Telegram/Instagram handle, sometimes in a cue of its own.
    private val CREDIT_MARKERS = listOf("بلبلجان", "bolboljan", "bollbolljan")

    private const val RLM = '\u200F'

    /** True for a site-credit cue, tolerant of spaces, ZWNJ, bidi marks and letter case. */
    fun isSiteCredit(text: CharSequence?): Boolean {
        if (text.isNullOrEmpty()) return false
        val normalized = StringBuilder(text.length)
        for (c in text) {
            if (c.isWhitespace() || isFormatChar(c) || c == 'ـ' /* tatweel */) continue
            normalized.append(c.lowercaseChar())
        }
        return CREDIT_MARKERS.any { normalized.contains(it) }
    }

    /** One cue line, ready for `SubtitleText` (RTL paragraph): [repairVisualOrder], then [forceRtl]. */
    fun prepareLine(line: String): String = forceRtl(repairVisualOrder(line))

    /**
     * Undoes "visual order" authoring. Many Persian subtitle lines are typed for players that lay
     * every line out left-to-right: the sentence's final punctuation is typed *first* (".راست
     * می‌گفتی ها", "!خدایی بازم؟", "...ولی") and the speaker dash *last* ("عه -"), so that it lands
     * on the correct side in an LTR paragraph. We render RTL, so we move them back:
     * leading `. ! ? , : ; ، …` go to the end and a trailing `-` goes to the front.
     *
     * Only lines that contain Arabic-script letters and do not start with an explicit bidi
     * control (the site's correctly authored lines start with U+202B RLE) are touched.
     */
    fun repairVisualOrder(line: String): String {
        if (line.isEmpty() || startsWithBidiControl(line) || line.none(::isArabicScript)) return line
        var start = 0
        var end = line.length
        // Trailing speaker dash: "...متن -" (but not a line that already starts with a dash).
        var dash = false
        run {
            var e = end
            while (e > start && line[e - 1] == ' ') e--
            if (e > start && isDash(line[e - 1]) && !isDash(line.trimStart().first())) {
                var before = e - 1
                while (before > start && line[before - 1] == ' ') before--
                if (before > start && !isDash(line[before - 1])) {
                    dash = true
                    end = before
                }
            }
        }
        // Leading sentence punctuation directly followed by text (".راست…", "!خدایی…", ".FBI …").
        var lead = start
        while (lead < end && (isMovablePunctuation(line[lead]) || line[lead] == ' ')) lead++
        val leadingRun = line.substring(start, lead).filterNot { it == ' ' }
        val moveLeading = leadingRun.isNotEmpty() && lead < end && line[lead].isLetterOrDigit()
        if (moveLeading) start = lead
        if (!dash && !moveLeading) return line
        val body = line.substring(start, end).trim()
        return buildString(line.length + 2) {
            if (dash) append("- ")
            append(body)
            if (moveLeading) append(leadingRun)
        }
    }

    /**
     * Indices where an RLM must be inserted so each Persian line is laid out right-to-left.
     *
     * Android picks a paragraph's direction from its first strong character, so a Persian line
     * that starts with Latin text ("OK باشه") would render LTR with its punctuation on the wrong
     * side. For each paragraph (split on '\n') that contains Arabic-script letters but whose first
     * strong character is LTR, we return the paragraph start. Paragraphs that are already
     * RTL-first (the common case) or start with an explicit bidi control (U+202B RLE in the site's
     * files) are left alone.
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

    /** Inserts the RLMs from [rtlMarkInsertionPoints]. Returns [text] itself when none is needed. */
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
        var seenNonSpace = false
        for (i in start until end) {
            val c = text[i]
            if (!seenNonSpace && !c.isWhitespace()) {
                seenNonSpace = true
                if (isBidiControl(c)) return false // the author chose the direction explicitly
            }
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

    private fun startsWithBidiControl(line: String): Boolean {
        val first = line.firstOrNull { !it.isWhitespace() } ?: return false
        return isBidiControl(first)
    }

    /** LRM, RLM, ALM, LRE/RLE/PDF/LRO/RLO and the isolates. */
    private fun isBidiControl(c: Char): Boolean =
        c == '\u200E' || c == '\u200F' || c == '\u061C' || c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069'

    /** Weak/neutral sentence punctuation a visual-order line puts first. Not quotes, not "-". */
    private fun isMovablePunctuation(c: Char): Boolean =
        c == '.' || c == '!' || c == '?' || c == ',' || c == ':' || c == ';' || c == '،' || c == '…'

    private fun isDash(c: Char): Boolean = c == '-' || c == '–' || c == '—'

    internal fun isArabicScript(c: Char): Boolean =
        c in '\u0600'..'ۿ' || c in 'ݐ'..'ݿ' || c in 'ࢠ'..'ࣿ' ||
            c in 'ﭐ'..'﷿' || c in 'ﹰ'..'ﻼ'

    /** Zero-width and bidi control characters. */
    private fun isFormatChar(c: Char): Boolean =
        c in '\u200B'..'\u200F' || c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069' || c == '\uFEFF'

    private val EMPTY = IntArray(0)
}
