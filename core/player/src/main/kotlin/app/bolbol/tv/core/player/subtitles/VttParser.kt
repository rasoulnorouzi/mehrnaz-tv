package app.bolbol.tv.core.player.subtitles

/**
 * Minimal, lenient WebVTT parser for the site's Persian soft subtitles (docs/subtitles.md
 * §Engine 2). Pure Kotlin, no Android types.
 *
 * - Strips the BOM; the "WEBVTT" header is optional (SRT-style "00:00:01,000" times also parse).
 * - Times: `HH:MM:SS.mmm` or `MM:SS.mmm` (hours may exceed 99); cue settings after the end time
 *   are ignored. NOTE / STYLE / REGION blocks and cue identifiers are skipped.
 * - Text: tags removed but their text kept (`<c.colorff9800>`, `<i>`, `<b>`, `<u>`, `<v Name>`,
 *   `<ruby>`, inline timestamps…); `&amp; &lt; &gt; &nbsp; &lrm; &rlm; &quot; &apos;` and numeric
 *   entities decoded; blank lines dropped; each line passed through [SubtitleCues.prepareLine].
 * - Site-credit cues ("بلبل جان" / "bolboljan") and empty or zero-length cues are dropped.
 * - The result is sorted by start time.
 */
object VttParser {

    fun parse(text: String): SubtitleTrack {
        val lines = text.removePrefix("\uFEFF").lines()
        val cues = ArrayList<SubtitleCue>(lines.size / 4)
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) {
                i++
                continue
            }
            // The header (and its metadata lines) ends at a blank line, or at a timing line in sloppy files.
            if (i == 0 && line.startsWith("WEBVTT")) {
                i++
                while (i < lines.size && lines[i].isNotBlank() && !lines[i].contains("-->")) i++
                continue
            }
            if (isSkippedBlock(line)) {
                i = nextBlank(lines, i)
                continue
            }
            // A cue: optional identifier line, then the timing line.
            val timingIndex = when {
                line.contains("-->") -> i
                i + 1 < lines.size && lines[i + 1].contains("-->") -> i + 1
                else -> -1
            }
            if (timingIndex < 0) {
                i = nextBlank(lines, i)
                continue
            }
            val timing = parseTiming(lines[timingIndex])
            var j = timingIndex + 1
            val body = ArrayList<String>(3)
            while (j < lines.size && lines[j].isNotBlank() && !lines[j].contains("-->")) {
                body.add(lines[j])
                j++
            }
            i = j
            if (timing != null) buildCue(timing.first, timing.second, body)?.let(cues::add)
        }
        return SubtitleTrack(cues)
    }

    /** `"01:02:03.456 --> 01:02:05.000 align:start"` → (3723456, 3725000), or null. */
    internal fun parseTiming(line: String): Pair<Long, Long>? {
        val arrow = line.indexOf("-->")
        if (arrow < 0) return null
        val start = parseTimestamp(line.substring(0, arrow).trim()) ?: return null
        val rest = line.substring(arrow + 3).trim()
        val endToken = rest.substringBefore(' ').substringBefore('\t')
        val end = parseTimestamp(endToken) ?: return null
        return start to end
    }

    /** `HH:MM:SS.mmm`, `MM:SS.mmm` (also `,` as the decimal separator) → ms, or null. */
    fun parseTimestamp(value: String): Long? {
        val parts = value.split(':')
        if (parts.size !in 2..3) return null
        val hours = if (parts.size == 3) parts[0].toLongStrict() ?: return null else 0L
        val minutes = parts[parts.size - 2].toLongStrict() ?: return null
        val secondsPart = parts.last()
        val sep = secondsPart.indexOfFirst { it == '.' || it == ',' }
        val seconds = (if (sep < 0) secondsPart else secondsPart.substring(0, sep)).toLongStrict() ?: return null
        val millis = if (sep < 0) {
            0L
        } else {
            val frac = secondsPart.substring(sep + 1)
            if (frac.isEmpty() || frac.any { it !in '0'..'9' }) return null
            frac.padEnd(3, '0').take(3).toLong()
        }
        if (minutes >= 60 || seconds >= 60) return null
        return ((hours * 60 + minutes) * 60 + seconds) * 1000 + millis
    }

    /** Removes `<…>` tags (keeping their text) and decodes entities. */
    internal fun cleanLine(raw: String): String {
        if (raw.indexOf('<') < 0 && raw.indexOf('&') < 0) return raw.trim()
        val noTags = StringBuilder(raw.length)
        var k = 0
        while (k < raw.length) {
            val c = raw[k]
            if (c == '<') {
                val close = raw.indexOf('>', k + 1)
                if (close < 0) break // unterminated tag: drop the rest
                k = close + 1
            } else {
                noTags.append(c)
                k++
            }
        }
        return decodeEntities(noTags).trim()
    }

    private fun decodeEntities(text: CharSequence): String {
        if (text.indexOf('&') < 0) return text.toString()
        val out = StringBuilder(text.length)
        var k = 0
        while (k < text.length) {
            val c = text[k]
            if (c == '&') {
                val semi = text.indexOf(';', k + 1)
                if (semi in (k + 2)..(k + 10)) {
                    val decoded = decodeEntity(text.substring(k + 1, semi))
                    if (decoded != null) {
                        out.append(decoded)
                        k = semi + 1
                        continue
                    }
                }
            }
            out.append(c)
            k++
        }
        return out.toString()
    }

    private fun decodeEntity(name: String): String? = when (name) {
        "amp" -> "&"
        "lt" -> "<"
        "gt" -> ">"
        "nbsp" -> "\u00A0"
        "lrm" -> "\u200E"
        "rlm" -> "\u200F"
        "quot" -> "\""
        "apos" -> "'"
        else -> when {
            name.startsWith("#x") || name.startsWith("#X") -> name.substring(2).toIntOrNull(16)?.toCodePointString()
            name.startsWith("#") -> name.substring(1).toIntOrNull()?.toCodePointString()
            else -> null
        }
    }

    private fun Int.toCodePointString(): String? =
        if (this in 1..0x10FFFF && this !in 0xD800..0xDFFF) String(Character.toChars(this)) else null

    private fun buildCue(startMs: Long, endMs: Long, body: List<String>): SubtitleCue? {
        if (endMs <= startMs || body.isEmpty()) return null
        val cleaned = body.map(::cleanLine).filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return null
        if (SubtitleCues.isSiteCredit(cleaned.joinToString("\n"))) return null
        return SubtitleCue(startMs, endMs, cleaned.map(SubtitleCues::prepareLine))
    }

    private fun isSkippedBlock(line: String): Boolean =
        !line.contains("-->") && (line.startsWith("NOTE") || line.startsWith("STYLE") || line.startsWith("REGION"))

    private fun nextBlank(lines: List<String>, from: Int): Int {
        var k = from
        while (k < lines.size && lines[k].isNotBlank()) k++
        return k
    }

    /** Digits only (no sign, no spaces), else null. */
    private fun String.toLongStrict(): Long? =
        if (isEmpty() || length > 9 || any { it !in '0'..'9' }) null else toLong()
}
