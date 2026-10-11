package app.bolbol.tv.feature.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import app.bolbol.tv.core.designsystem.BolbolColors
import app.bolbol.tv.core.designsystem.BolbolSpacing
import app.bolbol.tv.core.designsystem.BolbolText
import app.bolbol.tv.core.designsystem.SelectableChip
import app.bolbol.tv.core.designsystem.TvButton
import app.bolbol.tv.core.designsystem.TvButtonStyle

internal val CardShape = RoundedCornerShape(BolbolSpacing.CardRadius)

/**
 * The login form of the Profile screen: username or e-mail, masked password with «نمایش رمز», «ورود».
 *
 * Keyboard behaviour (see [TvTextField]): a hardware keyboard types straight into the focused field; Tab / Enter in
 * the username field starts typing the password; Enter in the password field logs in; OK on a field opens the TV
 * keyboard. The state of the fields lives in the caller so scrolling or recomposing never loses what was typed.
 */
@Composable
internal fun LoginCard(
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    revealed: Boolean,
    onRevealedChange: (Boolean) -> Unit,
    phase: LoginPhase,
    onLogin: () -> Unit,
    usernameFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .background(BolbolColors.Surface, CardShape)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BolbolText(
            text = "ورود به حساب بلبل جان",
            style = MaterialTheme.typography.titleLarge,
            color = BolbolColors.TextPrimary,
            maxLines = 1,
        )
        Spacer(Modifier.height(8.dp))

        val passwordEditor = remember { TvTextFieldController() }
        FieldLabel("نام کاربری یا ایمیل")
        TvTextField(
            value = username,
            onValueChange = onUsernameChange,
            modifier = Modifier.fillMaxWidth(),
            containerFocus = usernameFocus,
            imeAction = ImeAction.Next,
            // Next / Tab / Enter: go straight into typing the password.
            onImeAction = { passwordEditor.startEditing() },
        )

        Spacer(Modifier.height(4.dp))
        FieldLabel("رمز عبور")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvTextField(
                value = password,
                onValueChange = onPasswordChange,
                modifier = Modifier.weight(1f),
                masked = !revealed,
                imeAction = ImeAction.Done,
                controller = passwordEditor,
                // Done / Enter in the password field logs in.
                onImeAction = onLogin,
            )
            SelectableChip(text = "نمایش رمز", selected = revealed, onClick = { onRevealedChange(!revealed) })
        }

        // The error line keeps its space, so showing or clearing an error never moves the fields.
        Box(Modifier.fillMaxWidth().height(44.dp).padding(top = 8.dp)) {
            if (phase is LoginPhase.Error) {
                BolbolText(
                    text = phase.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = BolbolColors.Error,
                    maxLines = 2,
                )
            }
        }
        TvButton(
            text = if (phase == LoginPhase.Submitting) "در حال ورود…" else "ورود",
            onClick = onLogin,
            modifier = Modifier.fillMaxWidth(),
            style = TvButtonStyle.Primary,
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    BolbolText(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = BolbolColors.TextSecondary,
        maxLines = 1,
    )
}
