package app.bolbol.tv.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

private val BolbolColorScheme = darkColorScheme(
    primary = BolbolColors.Accent,
    onPrimary = BolbolColors.OnAccent,
    background = BolbolColors.Background,
    onBackground = BolbolColors.TextPrimary,
    surface = BolbolColors.Surface,
    onSurface = BolbolColors.TextPrimary,
    surfaceVariant = BolbolColors.SurfaceHigh,
    onSurfaceVariant = BolbolColors.TextSecondary,
    border = BolbolColors.FocusRing,
    error = BolbolColors.Error,
)

/**
 * Root theme. Forces right-to-left layout for the whole app (Persian UI),
 * regardless of the TV's system language, and sets the default content colour so
 * a bare `Text` outside any Surface is readable on the dark background.
 */
@Composable
fun BolbolTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = BolbolColorScheme,
            typography = BolbolTypography,
        ) {
            CompositionLocalProvider(LocalContentColor provides BolbolColors.TextPrimary) {
                content()
            }
        }
    }
}
