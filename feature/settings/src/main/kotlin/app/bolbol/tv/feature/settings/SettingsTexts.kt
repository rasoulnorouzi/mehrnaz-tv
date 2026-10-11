package app.bolbol.tv.feature.settings

/**
 * Description of «نمایش اطلاعات عملکرد» (Settings → توسعه‌دهنده).
 *
 * It must **start with a Persian word**: `BolbolText` wraps its text with `BidiFormatter`, which takes the paragraph
 * direction from the first strong character. A sentence that opens with a Latin run ("FPS، …") is wrapped as left to
 * right, so the full stop jumps to the other end and the comma sticks to the Latin word (QA-07). Latin terms inside a
 * Persian sentence are fine.
 */
internal const val PERFORMANCE_DESCRIPTION = "نمایش FPS، مصرف حافظه و آمار پخش‌کننده روی صفحه."

/**
 * True when the first strong (letter-like) character of [text] reads right to left, i.e. a bidi-aware text view lays
 * the sentence out as a Persian paragraph. Digits, punctuation, spaces and format marks are skipped; a text without
 * any strong character counts as right to left (nothing to flip).
 */
internal fun startsWithRtl(text: String): Boolean {
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        when (Character.getDirectionality(cp)) {
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
            else -> Unit
        }
        i += Character.charCount(cp)
    }
    return true
}
