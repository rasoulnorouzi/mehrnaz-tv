package app.bolbol.tv.core.lanremote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.bolbol.tv.core.designsystem.BolbolColors
import app.bolbol.tv.core.designsystem.BolbolText
import app.bolbol.tv.core.designsystem.TvButton

private val QrSize = 200.dp

/** Zero-width non-joiner, written as a number so no invisible character sits in the source. */
private val Zwnj = 0x200C.toChar()

/**
 * "Type on your phone" panel for the search screen: shows a QR code and the address while it is composed, and
 * forwards whatever the phone sends to [onText] (replace semantics: each call carries the whole current text).
 *
 * The server runs only while this composable is in the composition: it starts in a [DisposableEffect] and is
 * stopped when the panel leaves (screen closed or search dismissed). Offline (no LAN address) it explains
 * what to do and offers a retry button; otherwise it has no focusable element, so D-pad focus stays on the keyboard.
 */
@Composable
fun PhoneRemotePanel(modifier: Modifier = Modifier, onText: (String) -> Unit) {
    val context = LocalContext.current
    val server = remember(context) { PhoneRemoteServer(context.applicationContext) }
    var attempt by remember { mutableIntStateOf(0) }
    var session by remember { mutableStateOf<PhoneRemoteSession?>(null) }
    val latestOnText by rememberUpdatedState(onText)

    DisposableEffect(server, attempt) {
        session = server.start()
        onDispose { server.stop() }
    }
    LaunchedEffect(server) {
        server.text.collect { latestOnText(it) }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val url = session?.url
        if (url != null) {
            QrCode(content = url, sizeDp = QrSize)
            BolbolText(
                text = "با دوربین گوشی کد را اسکن کنید و تایپ کنید",
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
            )
            BolbolText(
                text = "گوشی و تلویزیون باید به یک شبکه${Zwnj}ی وای${Zwnj}فای وصل باشند",
                style = MaterialTheme.typography.bodySmall,
                color = BolbolColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
            // The address is Latin text: left-to-right paragraph, small, centred under the code.
            Text(
                text = url,
                style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
                color = BolbolColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
        } else {
            BolbolText(
                text = "اتصال به شبکه محلی برقرار نیست. تلویزیون را به وای${Zwnj}فای یا کابل شبکه وصل کنید.",
                style = MaterialTheme.typography.bodyMedium,
                color = BolbolColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
            TvButton(text = "تلاش دوباره", onClick = { attempt++ })
        }
    }
}
