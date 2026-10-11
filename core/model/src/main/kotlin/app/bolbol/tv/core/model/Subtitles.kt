package app.bolbol.tv.core.model

/** Subtitle look, chosen by the user (global). Spec: docs/subtitles.md. */
data class SubtitleStyle(
    val enabled: Boolean = true,
    /** Multiplier of the base subtitle size: 0.8, 1.0, 1.25, 1.5. */
    val sizeScale: Float = 1.0f,
    val color: SubtitleColor = SubtitleColor.WHITE,
    val background: SubtitleBackground = SubtitleBackground.OUTLINE,
    val bold: Boolean = true,
    val position: SubtitlePosition = SubtitlePosition.LOW,
) {
    companion object {
        val SIZE_STEPS = listOf(0.8f, 1.0f, 1.25f, 1.5f)
        /** Timing offset limits and step for the per-title sync adjustment. */
        const val OFFSET_STEP_MS = 500
        const val OFFSET_MAX_MS = 10_000
    }
}

enum class SubtitleColor { WHITE, YELLOW }

/** OUTLINE = black edge around the letters, no box; BOX = semi-transparent black box behind the text. */
enum class SubtitleBackground { OUTLINE, BOX }

/** Distance from the bottom edge: LOW 8 %, MID 14 %, HIGH 20 % of the screen height. */
enum class SubtitlePosition(val bottomFraction: Float) { LOW(0.08f), MID(0.14f), HIGH(0.20f) }
