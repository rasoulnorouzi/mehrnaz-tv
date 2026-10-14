package app.bolbol.tv.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private val DialogShape = RoundedCornerShape(12.dp)

/**
 * Modal panel in its own dialog window: the window dims the screen behind it (no blur), keeps D-pad
 * focus inside, hands focus back to the element that had it when it closes, and Back calls [onDismiss].
 * The caller puts the initial focus on one of its children (`FocusRequester` + `LaunchedEffect`).
 */
@Composable
fun TvDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 560.dp,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .width(width)
                    .background(BolbolColors.Surface, DialogShape)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content()
            }
        }
    }
}
