package app.bolbol.tv.feature.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.bolbol.tv.core.designsystem.BolbolColors
import app.bolbol.tv.core.designsystem.BolbolIcons
import app.bolbol.tv.core.designsystem.BolbolSpacing
import app.bolbol.tv.core.designsystem.BolbolText
import app.bolbol.tv.core.designsystem.TvButton
import app.bolbol.tv.core.designsystem.TvButtonStyle

private val ProfileCardMaxWidth = 640.dp
private val LoginCardWidth = 500.dp
private val AvatarSize = 88.dp
private val DialogShape = RoundedCornerShape(12.dp)

private const val LOCAL_DATA_NOTE = "لیست و تاریخچهٔ تماشا روی این تلویزیون می‌ماند"

/**
 * The «پروفایل» tab: where the optional Bolboljan account is signed in and out (docs/auth.md).
 *
 * **Signed out:** the screen's title and a short explanation at the start, the login card at the end (username or
 * e-mail, masked password, «ورود»; typing opens the system TV keyboard, see [TvTextField]). Focus starts on the
 * username field.
 *
 * **Signed in:** a profile card: avatar with the first letter of the name, the name, username and e-mail, the VIP
 * status, «همگام‌سازی دوباره» and «خروج از حساب». Focus starts on «همگام‌سازی دوباره», and moves there again right
 * after a login. «خروج از حساب» opens a small dialog («از حساب خارج می‌شوید؟») whose «انصراف» is focused by default;
 * «بله، خروج» signs out and the login card comes back. Local My List and watch progress stay on the device.
 *
 * Draws its own background; apply BolbolTheme (RTL) above it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ProfileScreen(viewModel: ProfileViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Kept here so recomposition (or switching between the two views) never loses what was typed while signed out.
    // The password lives only in this composition: never saved, never in the view model.
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }
    val usernameFocus = remember { FocusRequester() }
    val syncFocus = remember { FocusRequester() }
    val logoutFocus = remember { FocusRequester() }

    LaunchedEffect(state.loggedIn) {
        if (state.loggedIn) {
            username = ""
            password = ""
            revealed = false
        }
    }
    // Signed in: first button. Signed out: the username field. Runs again after every login and logout.
    LaunchedEffect(state.loaded, state.loggedIn) {
        if (state.loaded) (if (state.loggedIn) syncFocus else usernameFocus).requestFocusWhenAttached()
    }
    // The dialog closed without signing out (انصراف / Back): back onto the button that opened it.
    var dialogWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(state.confirmingLogout) {
        if (dialogWasOpen && !state.confirmingLogout && state.loggedIn) logoutFocus.requestFocusWhenAttached()
        dialogWasOpen = state.confirmingLogout
    }
    BackHandler(enabled = state.confirmingLogout) { viewModel.cancelLogout() }

    Box(modifier.fillMaxSize().background(BolbolColors.Background)) {
        if (!state.loaded) return@Box
        val profile = state.profile
        if (state.loggedIn && profile != null) {
            SignedInContent(
                profile = profile,
                sync = state.sync,
                onSync = viewModel::syncNow,
                onLogout = viewModel::requestLogout,
                syncFocus = syncFocus,
                logoutFocus = logoutFocus,
            )
        } else {
            SignedOutContent(
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
        if (state.confirmingLogout) {
            LogoutDialog(onConfirm = viewModel::confirmLogout, onCancel = viewModel::cancelLogout)
        }
    }
}

// --- signed out -------------------------------------------------------------------------------------------------

@Composable
private fun SignedOutContent(
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
    Box(
        Modifier
            .fillMaxSize()
            .padding(horizontal = BolbolSpacing.SafeHorizontal, vertical = BolbolSpacing.SafeVertical),
        contentAlignment = Alignment.Center,
    ) {
        // Scrolls (instead of clipping) if a large font or a short screen ever makes the card taller than the screen.
        Row(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BolbolText(
                    text = "پروفایل",
                    style = MaterialTheme.typography.headlineLarge,
                    color = BolbolColors.TextPrimary,
                    maxLines = 1,
                )
                BolbolText(
                    text = "با ورود به حساب، لیست من و ادامهٔ تماشا با سایت و گوشی همگام می‌شود",
                    style = MaterialTheme.typography.bodyLarge,
                    color = BolbolColors.TextPrimary,
                    maxLines = 4,
                )
                BolbolText(
                    text = "ورود اختیاری است؛ تماشا بدون حساب هم کار می‌کند.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BolbolColors.TextSecondary,
                    maxLines = 3,
                )
            }
            LoginCard(
                username = username,
                onUsernameChange = onUsernameChange,
                password = password,
                onPasswordChange = onPasswordChange,
                revealed = revealed,
                onRevealedChange = onRevealedChange,
                phase = phase,
                onLogin = onLogin,
                usernameFocus = usernameFocus,
                modifier = Modifier.width(LoginCardWidth),
            )
        }
    }
}

// --- signed in --------------------------------------------------------------------------------------------------

@Composable
private fun SignedInContent(
    profile: AccountProfile,
    sync: SyncPhase,
    onSync: () -> Unit,
    onLogout: () -> Unit,
    syncFocus: FocusRequester,
    logoutFocus: FocusRequester,
) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(horizontal = BolbolSpacing.SafeHorizontal, vertical = BolbolSpacing.SafeVertical),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = ProfileCardMaxWidth).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BolbolText(
                text = "پروفایل",
                style = MaterialTheme.typography.headlineMedium,
                color = BolbolColors.TextPrimary,
                maxLines = 1,
            )
            ProfileCard(
                profile = profile,
                sync = sync,
                onSync = onSync,
                onLogout = onLogout,
                syncFocus = syncFocus,
                logoutFocus = logoutFocus,
            )
        }
    }
}

@Composable
private fun ProfileCard(
    profile: AccountProfile,
    sync: SyncPhase,
    onSync: () -> Unit,
    onLogout: () -> Unit,
    syncFocus: FocusRequester,
    logoutFocus: FocusRequester,
) {
    val name = profile.shownName
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BolbolColors.Surface, CardShape)
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Avatar(initial = avatarInitial(name))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BolbolText(
                    text = name ?: "کاربر بلبل جان",
                    style = MaterialTheme.typography.headlineSmall,
                    color = BolbolColors.TextPrimary,
                    maxLines = 1,
                )
                profile.username?.takeIf { it.isNotBlank() }?.let { InfoRow(label = "نام کاربری", value = it) }
                profile.email?.takeIf { it.isNotBlank() }?.let { InfoRow(label = "ایمیل", value = it) }
            }
        }

        VipStatus(daysLeft = profile.vipDaysLeft, expire = profile.vipExpire)

        Box(Modifier.fillMaxWidth().height(1.dp).background(BolbolColors.Outline))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(
                text = if (sync == SyncPhase.Syncing) "در حال همگام‌سازی…" else "همگام‌سازی دوباره",
                onClick = onSync,
                // A fixed minimum width, so the label change while syncing never moves the next button.
                modifier = Modifier.widthIn(min = 200.dp).focusRequester(syncFocus),
                style = TvButtonStyle.Primary,
            )
            TvButton(
                text = "خروج از حساب",
                onClick = onLogout,
                modifier = Modifier.focusRequester(logoutFocus),
                style = TvButtonStyle.Secondary,
            )
            SyncResult(sync, Modifier.weight(1f))
        }
        BolbolText(
            text = LOCAL_DATA_NOTE,
            style = MaterialTheme.typography.bodyMedium,
            color = BolbolColors.TextSecondary,
            maxLines = 2,
        )
    }
}

/** Circle on the accent colour with the first letter of the name. */
@Composable
private fun Avatar(initial: String) {
    Box(
        Modifier.size(AvatarSize).background(BolbolColors.Accent, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        BolbolText(
            text = initial,
            style = MaterialTheme.typography.headlineLarge,
            color = BolbolColors.OnAccent,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/** «نام کاربری  ali_r»: a label and a left-to-right value (user names and e-mails are Latin data). */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BolbolText(
            text = label,
            modifier = Modifier.width(84.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = BolbolColors.TextSecondary,
            maxLines = 1,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            color = BolbolColors.TextPrimary,
            style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** «اشتراک ویژه: ۲۳ روز مانده» (accent) or «بدون اشتراک ویژه», plus the end date when the server sent one. */
@Composable
private fun VipStatus(daysLeft: Int?, expire: String?) {
    val active = (daysLeft ?: 0) > 0
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .background(BolbolColors.SurfaceHigh, RoundedCornerShape(50))
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            BolbolText(
                text = vipStatusText(daysLeft),
                style = MaterialTheme.typography.titleSmall,
                color = if (active) BolbolColors.Accent else BolbolColors.TextSecondary,
                maxLines = 1,
            )
        }
        if (active && !expire.isNullOrBlank()) {
            BolbolText(
                text = "تا $expire",
                style = MaterialTheme.typography.bodyMedium,
                color = BolbolColors.TextSecondary,
                maxLines = 1,
            )
        }
    }
}

/** How the last manual sync ended: «همگام شد ✓» (the check is a vector, not a font glyph) or the error. */
@Composable
private fun SyncResult(phase: SyncPhase, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.CenterStart) {
        when (phase) {
            SyncPhase.Done -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = BolbolIcons.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = BolbolColors.Accent,
                )
                BolbolText(
                    text = "همگام شد",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BolbolColors.Accent,
                    maxLines = 1,
                )
            }
            SyncPhase.Failed -> BolbolText(
                text = MSG_SYNC_FAILED,
                style = MaterialTheme.typography.bodyMedium,
                color = BolbolColors.Error,
                maxLines = 2,
            )
            SyncPhase.Idle, SyncPhase.Syncing -> Unit
        }
    }
}

// --- logout confirmation ----------------------------------------------------------------------------------------

/**
 * «از حساب خارج می‌شوید؟» over the profile: «انصراف» has the initial focus (yellow, the safe choice), D-pad cannot leave
 * the panel, Back cancels (the caller's BackHandler). Drawn in the same window, so focus handling is fully ours.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun LogoutDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { cancelFocus.requestFocusWhenAttached() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BolbolColors.Background.copy(alpha = 0.82f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .background(BolbolColors.Surface, DialogShape)
                .padding(24.dp)
                // Focus cannot leave the panel in any direction.
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            BolbolText(
                text = "از حساب خارج می‌شوید؟",
                style = MaterialTheme.typography.titleLarge,
                color = BolbolColors.TextPrimary,
                maxLines = 2,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton(
                    text = "بله، خروج",
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    style = TvButtonStyle.Secondary,
                )
                TvButton(
                    text = "انصراف",
                    onClick = onCancel,
                    modifier = Modifier.weight(1f).focusRequester(cancelFocus),
                    style = TvButtonStyle.Primary,
                )
            }
        }
    }
}
