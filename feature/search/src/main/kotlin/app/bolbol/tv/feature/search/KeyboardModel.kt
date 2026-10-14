package app.bolbol.tv.feature.search

import androidx.compose.runtime.Immutable

/**
 * Pure model of the on-screen TV keyboard (no Compose, unit-tested): layouts, key actions and the
 * reducer that applies an action to the query text. docs/design-system.md §Components TvKeyboard.
 */

/** The two keyboard layouts. Both are a hard requirement (user, 2026-10-11). */
internal enum class KeyboardLanguage(val code: String) {
    Persian("fa"),
    English("en"),
    ;

    val other: KeyboardLanguage get() = if (this == Persian) English else Persian

    /** Label of the switch key: it names the layout the key switches **to** ("EN" on the Persian layout). */
    val switchLabel: String get() = if (this == Persian) "EN" else "فا"

    companion object {
        /** Persian is the default (also for unknown or missing stored values). */
        fun fromCode(code: String?): KeyboardLanguage = entries.firstOrNull { it.code == code } ?: Persian
    }
}

internal sealed interface KeyAction {
    /** Appends [text] (a letter, digit or punctuation mark). */
    data class Insert(val text: String) : KeyAction

    data object Space : KeyAction
    data object Backspace : KeyAction
    data object Clear : KeyAction
    data object SwitchLanguage : KeyAction
}

internal enum class KeyKind { Character, Function }

/**
 * One key. [longPress] runs on a long press of OK (alternate letter such as ي for ی); [hint] is
 * the small alternate glyph drawn in the key's corner. [weight] is the relative width in its row.
 */
@Immutable
internal data class KeyboardKey(
    val id: String,
    val label: String,
    val action: KeyAction,
    val kind: KeyKind = KeyKind.Character,
    val weight: Float = 1f,
    val longPress: KeyAction? = null,
    val hint: String? = null,
)

/** Rows of character keys (digits first) plus the function row (switch, space, backspace, clear). */
@Immutable
internal class KeyboardLayout(
    val language: KeyboardLanguage,
    val characterRows: List<List<KeyboardKey>>,
    val functionRow: List<KeyboardKey>,
) {
    val rows: List<List<KeyboardKey>> = characterRows + listOf(functionRow)
}

internal object KeyboardLayouts {
    /** U+200C ZERO WIDTH NON-JOINER: the Persian half-space, common inside titles. */
    val ZWNJ: String = 0x200C.toChar().toString()

    fun of(language: KeyboardLanguage): KeyboardLayout =
        if (language == KeyboardLanguage.Persian) Persian else English

    // Long press: Arabic yeh / kaf (some titles on the site are typed with them).
    private val PersianAlternates = mapOf("ی" to "ي", "ک" to "ك")

    // Ids are layout-independent ("row.column") so a key keeps its Compose identity (and focus) when
    // the layout switches; function keys use fixed names.
    private fun characterRow(row: Int, chars: String, alternates: Map<String, String> = emptyMap()) =
        chars.split(' ').mapIndexed { column, c ->
            val alt = alternates[c]
            KeyboardKey(
                id = "$row.$column",
                label = c,
                action = KeyAction.Insert(c),
                longPress = alt?.let { KeyAction.Insert(it) },
                hint = alt,
            )
        }

    private fun functionKey(id: String, label: String, action: KeyAction, weight: Float, longPress: KeyAction? = null) =
        KeyboardKey(id = id, label = label, action = action, kind = KeyKind.Function, weight = weight, longPress = longPress)

    private fun functionRow(language: KeyboardLanguage): List<KeyboardKey> = buildList {
        add(functionKey("switch", language.switchLabel, KeyAction.SwitchLanguage, 1.4f))
        if (language == KeyboardLanguage.Persian) {
            add(functionKey("zwnj", "نیم${ZWNJ}فاصله", KeyAction.Insert(ZWNJ), 1.8f))
            add(functionKey("space", "فاصله", KeyAction.Space, 2.8f))
        } else {
            add(functionKey("space", "فاصله", KeyAction.Space, 4.6f))
        }
        // Holding backspace clears the whole query (the usual TV-keyboard shortcut).
        add(functionKey("backspace", "", KeyAction.Backspace, 1.2f, longPress = KeyAction.Clear))
        add(functionKey("clear", "پاک کردن", KeyAction.Clear, 1.8f))
    }

    /** Standard Persian letter order, re-flowed to 10 keys per row so keys stay large (Persian digits). */
    val Persian: KeyboardLayout = KeyboardLayout(
        language = KeyboardLanguage.Persian,
        characterRows = listOf(
            characterRow(0, "۱ ۲ ۳ ۴ ۵ ۶ ۷ ۸ ۹ ۰"),
            characterRow(1, "ض ص ث ق ف غ ع ه خ ح", PersianAlternates),
            characterRow(2, "ج چ ش س ی ب ل ا ت ن", PersianAlternates),
            characterRow(3, "م ک گ ظ ط ز ر ذ د پ", PersianAlternates),
            characterRow(4, "و ژ آ ئ ء ؤ ، ؟ ؛ -"),
        ),
        functionRow = functionRow(KeyboardLanguage.Persian),
    )

    /** QWERTY with ASCII digits. */
    val English: KeyboardLayout = KeyboardLayout(
        language = KeyboardLanguage.English,
        characterRows = listOf(
            characterRow(0, "1 2 3 4 5 6 7 8 9 0"),
            characterRow(1, "q w e r t y u i o p"),
            characterRow(2, "a s d f g h j k l '"),
            characterRow(3, "z x c v b n m , . -"),
        ),
        functionRow = functionRow(KeyboardLanguage.English),
    )
}

/** Longest query the keyboard accepts (titles are short; keeps the field readable). */
internal const val MAX_QUERY_LENGTH = 30

internal data class KeyboardState(
    val text: String = "",
    val language: KeyboardLanguage = KeyboardLanguage.Persian,
)

/** Applies [action] to the keyboard state. Pure; never throws. */
internal fun KeyboardState.reduce(action: KeyAction): KeyboardState = when (action) {
    is KeyAction.Insert ->
        if (action.text.isEmpty() || text.length + action.text.length > MAX_QUERY_LENGTH) this
        else copy(text = text + action.text)

    // No leading space and no double spaces.
    KeyAction.Space ->
        if (text.isEmpty() || text.endsWith(' ') || text.length >= MAX_QUERY_LENGTH) this
        else copy(text = "$text ")

    KeyAction.Backspace -> if (text.isEmpty()) this else copy(text = text.dropLastCodePoint())
    KeyAction.Clear -> if (text.isEmpty()) this else copy(text = "")
    KeyAction.SwitchLanguage -> copy(language = language.other)
}

internal fun String.dropLastCodePoint(): String {
    if (isEmpty()) return this
    val n = if (length >= 2 && this[length - 1].isLowSurrogate() && this[length - 2].isHighSurrogate()) 2 else 1
    return dropLast(n)
}
