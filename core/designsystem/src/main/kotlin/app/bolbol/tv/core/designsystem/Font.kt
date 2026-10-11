package app.bolbol.tv.core.designsystem

import android.content.Context
import android.graphics.Typeface
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.res.ResourcesCompat

/**
 * Vazirmatn (SIL OFL 1.1, see core/designsystem/FONT-LICENSE.txt), static TTFs v33.003:
 * Regular 400, Medium 500, Bold 700. Covers Persian + Latin. Numerals are Latin glyphs, so
 * convert with [toPersianDigits] where the design calls for Persian numerals.
 */
val VazirmatnFamily: FontFamily = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_bold, FontWeight.Bold),
)

/**
 * Android [Typeface] of the bundled Vazirmatn for non-Compose views (e.g. the player's
 * SubtitleView). [weight] snaps to the nearest bundled weight (400 / 500 / 700).
 */
fun vazirmatnTypeface(context: Context, weight: FontWeight = FontWeight.Normal): Typeface {
    val res = when {
        weight.weight >= 600 -> R.font.vazirmatn_bold
        weight.weight >= 450 -> R.font.vazirmatn_medium
        else -> R.font.vazirmatn_regular
    }
    return ResourcesCompat.getFont(context, res) ?: Typeface.DEFAULT
}
