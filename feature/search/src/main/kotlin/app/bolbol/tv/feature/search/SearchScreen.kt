package app.bolbol.tv.feature.search

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.layout.layout
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import app.bolbol.tv.core.designsystem.BolbolColors
import app.bolbol.tv.core.designsystem.BolbolSpacing
import app.bolbol.tv.core.designsystem.BolbolText
import app.bolbol.tv.core.designsystem.TvButton
import app.bolbol.tv.core.designsystem.TvButtonStyle
import app.bolbol.tv.core.model.TitleSummary
import kotlinx.coroutines.flow.Flow

/**
 * Search screen (docs/design-system.md §Screens 4): keyboard panel on the start side, results
 * grid on the end side.
 *
 * - **Keyboard:** own Persian / English layouts ([TvKeyboard]), remembers the last layout.
 * - **Voice (M3.4):** mic button -> `RecognizerIntent` (fa-IR, or en-US on the English layout);
 *   hidden when no recogniser exists. The remote's search / voice key starts it too.
 * - **Phone remote:** [externalInput] replaces the query; [phoneRemoteContent] (the QR code) opens
 *   in a dialog from the "تایپ با گوشی" button, which is hidden when the slot is null.
 *
 * Initial focus: the keyboard (or, coming back from details, the title that was opened).
 */
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onOpenTitle: (TitleSummary) -> Unit,
    modifier: Modifier = Modifier,
    externalInput: Flow<String>? = null,
    phoneRemoteContent: (@Composable () -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Voice -------------------------------------------------------------------------------------
    val voiceSupported = remember { canRecognizeSpeech(context) }
    var voiceFailed by remember { mutableStateOf(false) }
    val voiceAvailable = voiceSupported && !voiceFailed
    val language = rememberUpdatedState(state.language)
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) viewModel.onVoiceResult(spoken)
        }
    }
    val startVoice = remember(voiceLauncher) {
        {
            try {
                voiceLauncher.launch(voiceIntent(language.value))
            } catch (_: ActivityNotFoundException) {
                voiceFailed = true // the recogniser disappeared: hide the mic
            } catch (_: SecurityException) {
                voiceFailed = true
            }
        }
    }

    // Phone remote ------------------------------------------------------------------------------
    var showPhone by rememberSaveable { mutableStateOf(false) }
    var phoneEverShown by rememberSaveable { mutableStateOf(false) }
    if (showPhone) phoneEverShown = true
    LaunchedEffect(externalInput) {
        externalInput?.collect { typed ->
            showPhone = false // results are what the person wants to see now
            viewModel.onExternalInput(typed)
        }
    }

    // Focus -------------------------------------------------------------------------------------
    val keyboardFocus = remember { FocusRequester() }
    // The overlay (focused) disappears when the phone sends text: put focus back on the keyboard.
    LaunchedEffect(showPhone) { if (!showPhone && phoneEverShown) keyboardFocus.tryRequestFocus() }
    val micFocus = remember { FocusRequester() }
    // Back from details: focus the title that was opened (if it is still in the results).
    var restoreId by remember(viewModel) {
        mutableStateOf(
            viewModel.consumeOpenedId()?.takeIf { id -> viewModel.state.value.items.any { it.id == id } },
        )
    }
    LaunchedEffect(Unit) {
        if (restoreId == null) keyboardFocus.tryRequestFocus()
    }

    // Callbacks (remembered so the keys and cards can skip recomposition while typing) -----------
    val onKeyAction = remember(viewModel) { { action: KeyAction -> viewModel.onKey(action) } }
    val onOpen = remember(viewModel, onOpenTitle) {
        { item: TitleSummary ->
            viewModel.onResultOpened(item)
            onOpenTitle(item)
        }
    }
    val onFocusedIndex = remember(viewModel) { { index: Int -> viewModel.onResultFocused(index, GRID_COLUMNS) } }
    val onPrediction = remember(viewModel) { { text: String -> viewModel.onPredictionClicked(text) } }
    val onKeyFocused = remember(viewModel) { { id: String -> viewModel.onKeyFocused(id) } }
    val initialKeyId = remember(viewModel) { viewModel.lastKeyId }
    val onRecent = remember(viewModel) { { query: String -> viewModel.onRecentClicked(query) } }
    val onRetry = remember(viewModel) { { viewModel.retry() } }
    val onRestoreDone = remember { { restoreId = null } }
    val onPhone = remember { { showPhone = true } }

    Box(modifier.fillMaxSize()) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(BolbolColors.Background)
            .padding(horizontal = BolbolSpacing.SafeHorizontal, vertical = BolbolSpacing.SafeVertical)
            .onPreviewKeyEvent { event ->
                // The remote's dedicated search / voice button.
                val code = event.nativeKeyEvent.keyCode
                if (voiceAvailable && (code == KEYCODE_SEARCH || code == KEYCODE_VOICE_ASSIST)) {
                    if (event.type == KeyEventType.KeyDown) {
                        micFocus.tryRequestFocus()
                        startVoice()
                    }
                    true
                } else {
                    false
                }
            },
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        KeyboardPanel(
            query = state.query,
            language = state.language,
            onAction = onKeyAction,
            voiceAvailable = voiceAvailable,
            onVoice = startVoice,
            micFocus = micFocus,
            phoneAvailable = phoneRemoteContent != null,
            onPhone = onPhone,
            wrapLeftEdge = !state.hasFocusableResults,
            initialFocus = keyboardFocus,
            predictions = state.predictions,
            onPrediction = onPrediction,
            initialKeyId = initialKeyId,
            onKeyFocused = onKeyFocused,
            modifier = Modifier.weight(1f),
        )
        ResultsPanel(
            phase = state.phase,
            items = state.items,
            loadingMore = state.loadingMore,
            generation = state.generation,
            recents = state.recents,
            error = state.error,
            onOpen = onOpen,
            onFocusedIndex = onFocusedIndex,
            onRecent = onRecent,
            onRetry = onRetry,
            restoreId = restoreId,
            onRestoreDone = onRestoreDone,
            modifier = Modifier.width(ResultsWidth).fillMaxHeight(),
        )
    }

    // The QR panel owns the phone server, so it must stay composed (just invisible) after the first text arrives
    // and the overlay closes; a Dialog would dispose it and stop the server (v1.4-6 root cause).
    if (phoneRemoteContent != null && phoneEverShown) {
        PhoneRemoteOverlay(visible = showPhone, onDismiss = { showPhone = false }, content = phoneRemoteContent)
    }
    }
}

// The remote's dedicated search / voice keys (not importing android.view.KeyEvent: it clashes with Compose's KeyEvent).
private const val KEYCODE_SEARCH = android.view.KeyEvent.KEYCODE_SEARCH
private const val KEYCODE_VOICE_ASSIST = android.view.KeyEvent.KEYCODE_VOICE_ASSIST

private fun voiceIntent(language: KeyboardLanguage): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        // Persian first; the English layout asks for English so Latin titles are recognised.
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (language == KeyboardLanguage.English) "en-US" else "fa-IR")
        putExtra(RecognizerIntent.EXTRA_PROMPT, SearchStrings.VoicePrompt)
    }

/** True when some app handles speech recognition (needs the `<queries>` entry in this module's manifest). */
private fun canRecognizeSpeech(context: Context): Boolean = try {
    voiceIntent(KeyboardLanguage.Persian).resolveActivity(context.packageManager) != null
} catch (_: RuntimeException) {
    false
}

/**
 * Small panel with the app-provided QR code. Back or "بستن" closes it; focus starts on the close button.
 *
 * Not a `Dialog`: when [visible] is false the content stays in the composition (measured to zero size, so
 * it is invisible and unfocusable) so the phone keeps working while the person looks at the results.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun PhoneRemoteOverlay(visible: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val closeFocus = remember { FocusRequester() }
    BackHandler(enabled = visible, onBack = onDismiss)
    if (visible) LaunchedEffect(Unit) { closeFocus.tryRequestFocus() }
    val hidden = Modifier.layout { _, _ -> layout(0, 0) {} }
    Box(
        modifier = if (visible) {
            Modifier
                .fillMaxSize()
                .background(BolbolColors.Background.copy(alpha = 0.92f))
                // Focus cannot leave the panel while it is open.
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup()
        } else {
            hidden
        },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(380.dp)
                .background(BolbolColors.Surface, RoundedCornerShape(12.dp))
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BolbolText(
                text = SearchStrings.Phone,
                style = MaterialTheme.typography.titleMedium,
                color = BolbolColors.TextPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            BolbolText(
                text = SearchStrings.PhoneHint,
                style = MaterialTheme.typography.bodyMedium,
                color = BolbolColors.TextSecondary,
                textAlign = TextAlign.Center,
                maxLines = 3,
            )
            Spacer(Modifier.height(16.dp))
            content()
            Spacer(Modifier.height(20.dp))
            TvButton(
                text = SearchStrings.Close,
                onClick = onDismiss,
                modifier = Modifier.focusRequester(closeFocus),
                style = TvButtonStyle.Secondary,
            )
        }
    }
}
