package app.bolbol.tv.feature.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import app.bolbol.tv.core.designsystem.BolbolColors
import app.bolbol.tv.core.designsystem.BolbolSpacing
import app.bolbol.tv.core.designsystem.BolbolText
import app.bolbol.tv.core.designsystem.EmptyState
import app.bolbol.tv.core.designsystem.PosterCard
import app.bolbol.tv.core.designsystem.SelectableChip
import app.bolbol.tv.core.designsystem.TvButton
import app.bolbol.tv.core.designsystem.TvButtonStyle
import app.bolbol.tv.core.designsystem.toPersianDigits
import app.bolbol.tv.core.model.TitleSummary

/** Grid columns of "My List" (docs/design-system.md: 5 posters of 140 dp across the 864 dp safe width). */
internal const val GRID_COLUMNS = 5

private val CardShape = RoundedCornerShape(BolbolSpacing.CardRadius)

/** Room above the first row and below the last for the 1.08x focus scale and the focus border. */
private val GridEdgePadding = 12.dp

/**
 * The «حساب» / «لیست من» screen (docs/auth.md).
 *
 * Signed out: a login card (username or email, masked password, «ورود»; typing opens the system TV keyboard, see
 * [TvTextField]) above the local My List. Signed in: «سلام، {name}», the VIP days when the plan is active and a
 * «خروج» button above the same list. The list is a 5-column poster grid; empty it shows «لیست شما خالی است».
 * Everything scrolls as one grid so the D-pad flows from the form down into the posters.
 *
 * Focus starts on the username field (signed out) or on «خروج» (signed in) and moves there again after a login
 * or logout, so the remote never loses its place. Draws its own background; apply BolbolTheme (RTL) above it.
 */
@Composable
fun AccountScreen(
    viewModel: AccountViewModel,
    onOpenTitle: (TitleSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Kept here (not in the lazy items) so scrolling the form out of view never loses what was typed.
    // The password lives only in this composition: never saved, never in the view model.
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }
    val usernameFocus = remember { FocusRequester() }
    val logoutFocus = remember { FocusRequester() }

    LaunchedEffect(state.loggedIn) {
        if (state.loggedIn) {
            password = ""
            revealed = false
        }
    }
    LaunchedEffect(state.loaded, state.loggedIn) {
        if (state.loaded) (if (state.loggedIn) logoutFocus else usernameFocus).requestFocusWhenAttached()
    }

    Box(modifier.fillMaxSize().background(BolbolColors.Background)) {
        if (!state.loaded) return@Box
        LazyVerticalGrid(
            columns = GridCells.Fixed(GRID_COLUMNS),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = BolbolSpacing.SafeHorizontal,
                end = BolbolSpacing.SafeHorizontal,
                top = BolbolSpacing.SafeVertical + GridEdgePadding,
                bottom = BolbolSpacing.SafeVertical + GridEdgePadding,
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = KEY_ACCOUNT, span = { GridItemSpan(maxLineSpan) }, contentType = "account") {
                if (state.loggedIn) {
                    AccountHeader(
                        displayName = state.displayName,
                        vipText = vipLabel(state.vipDaysLeft),
                        onLogout = viewModel::logout,
                        logoutFocus = logoutFocus,
                    )
                } else {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        LoginCard(
                            username = username,
                            onUsernameChange = {
                                username = it
                                viewModel.onInputChanged()
                            },
                            password = password,
                            onPasswordChange = {
                                password = it
                                viewModel.onInputChanged()
                            },
                            revealed = revealed,
                            onRevealedChange = { revealed = it },
                            phase = state.login,
                            onLogin = { viewModel.login(username, password) },
                            usernameFocus = usernameFocus,
                        )
                    }
                }
            }
            item(key = KEY_TITLE, span = { GridItemSpan(maxLineSpan) }, contentType = "title") {
                ListTitle(count = state.myList.size)
            }
            if (state.myList.isEmpty()) {
                item(key = KEY_EMPTY, span = { GridItemSpan(maxLineSpan) }, contentType = "empty") {
                    EmptyState(message = "لیست شما خالی است", modifier = Modifier.height(180.dp))
                }
            } else {
                items(items = state.myList, key = { it.id }, contentType = { "poster" }) { item ->
                    // 864 dp / 5 = 172.8 dp cells: centre each 140 dp card so the slack is shared evenly.
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        PosterCard(item = item, onClick = { onOpenTitle(item) })
                    }
                }
            }
        }
    }
}

private const val KEY_ACCOUNT = "account"
private const val KEY_TITLE = "title"
private const val KEY_EMPTY = "empty"

@Composable
private fun LoginCard(
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    revealed: Boolean,
    onRevealedChange: (Boolean) -> Unit,
    phase: LoginPhase,
    onLogin: () -> Unit,
    usernameFocus: FocusRequester,
) {
    val focusManager = LocalFocusManager.current
    Column(
        modifier = Modifier
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
        BolbolText(
            text = "ورود اختیاری است؛ فقط برای همگام‌سازی لیست من",
            style = MaterialTheme.typography.bodyMedium,
            color = BolbolColors.TextSecondary,
            maxLines = 2,
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

@Composable
private fun AccountHeader(
    displayName: String?,
    vipText: String?,
    onLogout: () -> Unit,
    logoutFocus: FocusRequester,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BolbolColors.Surface, CardShape)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BolbolText(
                text = if (displayName.isNullOrBlank()) "سلام" else "سلام، $displayName",
                style = MaterialTheme.typography.headlineSmall,
                color = BolbolColors.TextPrimary,
                maxLines = 1,
            )
            if (vipText != null) {
                BolbolText(
                    text = vipText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = BolbolColors.Accent,
                    maxLines = 1,
                )
            }
        }
        TvButton(
            text = "خروج",
            onClick = onLogout,
            modifier = Modifier.focusRequester(logoutFocus),
            style = TvButtonStyle.Secondary,
        )
    }
}

@Composable
private fun ListTitle(count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BolbolText(
            text = "لیست من",
            style = MaterialTheme.typography.titleLarge,
            color = BolbolColors.TextPrimary,
            maxLines = 1,
        )
        if (count > 0) {
            BolbolText(
                text = "${count.toPersianDigits()} عنوان",
                style = MaterialTheme.typography.bodyMedium,
                color = BolbolColors.TextSecondary,
                maxLines = 1,
            )
        }
    }
}

/**
 * Requests focus once the target is attached: right after the grid first composes the item may not be laid out yet
 * (requestFocus then throws), so retry over the next few frames.
 */
private suspend fun FocusRequester.requestFocusWhenAttached() {
    repeat(FOCUS_ATTEMPTS) {
        try {
            requestFocus()
            return
        } catch (_: IllegalStateException) {
            withFrameNanos { }
        }
    }
}

private const val FOCUS_ATTEMPTS = 5
