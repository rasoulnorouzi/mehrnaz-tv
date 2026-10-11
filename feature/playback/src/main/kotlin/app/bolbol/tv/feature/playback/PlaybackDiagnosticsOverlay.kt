package app.bolbol.tv.feature.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.bolbol.tv.core.player.PlaybackDiagnostics

private val DiagStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 11.sp,
    lineHeight = 14.sp,
    color = Color(0xFF8CF5A0),
)

/**
 * Hidden debug box (TTFF, resolution, bitrate, decoder, buffer, dropped frames, rebuffers,
 * seek/join times). Shown by PlayerScreen(showDiagnostics = true) or 3 quick DPAD_UP presses.
 * Left-to-right with Latin digits on purpose: it is data for us, not UI chrome.
 */
@Composable
fun PlaybackDiagnosticsOverlay(diagnostics: PlaybackDiagnostics, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            modifier
                .background(Color(0xB3000000), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            for (line in diagnostics.lines()) Text(line, style = DiagStyle)
        }
    }
}
