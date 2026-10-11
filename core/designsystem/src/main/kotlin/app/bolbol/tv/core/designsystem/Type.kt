package app.bolbol.tv.core.designsystem

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Typography

private val CenteredLines = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

/**
 * One style of the scale. Letter spacing is forced to 0: a non-zero value breaks the joining
 * of Arabic-script letters. Line height is ~1.45x because Persian needs room for dots/marks.
 */
private fun vz(weight: FontWeight, size: TextUnit, line: TextUnit) = TextStyle(
    fontFamily = VazirmatnFamily,
    fontWeight = weight,
    fontSize = size,
    lineHeight = line,
    letterSpacing = 0.sp,
    lineHeightStyle = CenteredLines,
)

/**
 * Type scale from docs/design-system.md: Display 36 / Headline 24 / Title 18 / Body 14 / Label 12.
 * The other slots of the Material scale are filled too, so no text falls back to the system font.
 */
val BolbolTypography = Typography(
    displayLarge = vz(FontWeight.Bold, 48.sp, 68.sp),
    displayMedium = vz(FontWeight.Bold, 40.sp, 58.sp),
    displaySmall = vz(FontWeight.Bold, 36.sp, 52.sp),
    headlineLarge = vz(FontWeight.Bold, 32.sp, 46.sp),
    headlineMedium = vz(FontWeight.Bold, 28.sp, 40.sp),
    headlineSmall = vz(FontWeight.Bold, 24.sp, 36.sp),
    titleLarge = vz(FontWeight.Medium, 22.sp, 32.sp),
    titleMedium = vz(FontWeight.Medium, 18.sp, 28.sp),
    titleSmall = vz(FontWeight.Medium, 16.sp, 24.sp),
    bodyLarge = vz(FontWeight.Normal, 16.sp, 24.sp),
    bodyMedium = vz(FontWeight.Normal, 14.sp, 22.sp),
    bodySmall = vz(FontWeight.Normal, 12.sp, 18.sp),
    labelLarge = vz(FontWeight.Medium, 14.sp, 22.sp),
    labelMedium = vz(FontWeight.Medium, 12.sp, 18.sp),
    labelSmall = vz(FontWeight.Medium, 11.sp, 16.sp),
)
