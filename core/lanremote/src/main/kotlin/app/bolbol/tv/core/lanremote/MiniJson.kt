package app.bolbol.tv.core.lanremote

/**
 * Strict parser for the only JSON the phone page sends: a flat object whose values are all strings, e.g.
 * `{"text":"..."}`. Anything else (nesting, numbers, trailing garbage, raw control characters in strings, bad
 * escapes) yields `null`. Written by hand so the module has no `org.json` dependency (it is stubbed in JVM tests).
 */
internal object MiniJson {
    fun parseStringMap(source: String): Map<String, String>? = Parser(source).parse()

    private class Parser(private val s: String) {
        private var i = 0

        fun parse(): Map<String, String>? {
            skipWhitespace()
            if (!consume('{')) return null
            val result = LinkedHashMap<String, String>()
            skipWhitespace()
            if (consume('}')) return finish(result)
            while (true) {
                skipWhitespace()
                val key = readString() ?: return null
                skipWhitespace()
                if (!consume(':')) return null
                skipWhitespace()
                val value = readString() ?: return null
                result[key] = value
                skipWhitespace()
                if (consume(',')) continue
                if (consume('}')) return finish(result)
                return null
            }
        }

        private fun finish(result: Map<String, String>): Map<String, String>? {
            skipWhitespace()
            return if (i == s.length) result else null
        }

        private fun consume(c: Char): Boolean {
            if (i < s.length && s[i] == c) {
                i++
                return true
            }
            return false
        }

        private fun skipWhitespace() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        private fun readString(): String? {
            if (!consume('"')) return null
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c < ' ' -> return null
                    c != '\\' -> sb.append(c)
                    else -> {
                        if (i >= s.length) return null
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) return null
                                var code = 0
                                repeat(4) {
                                    val d = Character.digit(s[i++], 16)
                                    if (d < 0) return null
                                    code = code * 16 + d
                                }
                                sb.append(code.toChar())
                            }
                            else -> return null
                        }
                    }
                }
            }
            return null
        }
    }
}
