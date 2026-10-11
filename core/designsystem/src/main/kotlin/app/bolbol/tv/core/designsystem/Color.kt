package app.bolbol.tv.core.designsystem

import androidx.compose.ui.graphics.Color

/** Colour tokens from docs/design-system.md (dark theme only). */
object BolbolColors {
    val Background = Color(0xFF0B0D12)
    val Surface = Color(0xFF151922)
    val SurfaceHigh = Color(0xFF1E2430)
    val TextPrimary = Color(0xFFF2F4F8)
    val TextSecondary = Color(0xFFA9B0BD)
    val Accent = Color(0xFFFFC93C)
    val OnAccent = Color(0xFF14161C)
    val FocusRing = Color(0xFFFFFFFF)
    val Error = Color(0xFFFF5A5F)
    /** Subtitle "yellow" option: warm, high-contrast on video. */
    val SubtitleYellow = Color(0xFFFFE14D)
    /** Subtitle "cyan" (فیروزه‌ای) option. */
    val SubtitleCyan = Color(0xFF7FDBFF)
    /** Subtitle "light green" (سبز روشن) option. */
    val SubtitleGreen = Color(0xFFA6F28F)
    /** Subtitle "pink" (صورتی) option. */
    val SubtitlePink = Color(0xFFFFB3D1)

    // Additions used by components (solid colours only, no alpha stacks).
    /** 1 dp outline of secondary buttons / unfocused outlined elements. */
    val Outline = Color(0xFF3A4252)

    /** Unfilled part of a progress bar. */
    val ProgressTrack = Color(0xFF2B3140)
}
