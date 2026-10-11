package app.bolbol.tv.feature.account

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import kotlinx.coroutines.launch
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import app.bolbol.tv.core.designsystem.BolbolColors
import app.bolbol.tv.core.designsystem.BolbolSpacing
import app.bolbol.tv.core.designsystem.BolbolText

private val FieldShape = RoundedCornerShape(BolbolSpacing.CardRadius)

/**
 * A single-line text field for the TV (username, password), built on [BasicTextField] so the **system TV keyboard
 * (IME)** types any character, including every password symbol.
 *
 * Two layers, because a text field that takes focus from the D-pad would pop the keyboard up while the user is
 * merely moving past it:
 * 1. the **frame** is an ordinary D-pad focus stop (white 2 dp ring when focused); OK on it starts editing;
 * 2. the inner text field is only focusable while editing: it is focused programmatically and the IME is shown.
 *    While it is focused with the IME hidden, Up/Down leave the field, OK shows the keyboard again and Back returns
 *    to the frame (when the IME is visible it consumes these keys itself).
 *
 * Content is left-to-right and aligned to the end edge (the right, next to the Persian label); [masked] hides it
 * with bullets and marks the field as a password for accessibility. [onImeAction] runs on the IME's Next/Done key.
 * [containerFocus] focuses the frame (for the initial focus of a screen).
 */
/** Lets a screen put a [TvTextField] straight into typing mode (e.g. Tab/Next from the previous field). */
internal class TvTextFieldController {
    internal var requests by mutableIntStateOf(0)
        private set

    fun startEditing() {
        requests++
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    containerFocus: FocusRequester = remember { FocusRequester() },
    masked: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
    hint: String = "برای نوشتن، دکمهٔ تأیید را بزنید",
    controller: TvTextFieldController? = null,
) {
    val fieldFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var editing by remember { mutableStateOf(false) }
    var frameFocused by remember { mutableStateOf(false) }
    var fieldFocused by remember { mutableStateOf(false) }
    val ringed = frameFocused || fieldFocused

    // Programmatic "start typing here" (Tab / Next from the previous field).
    val requests = controller?.requests ?: 0
    LaunchedEffect(requests) {
        if (requests == 0) return@LaunchedEffect
        editing = true
        withFrameNanos { } // let focusProperties(canFocus = editing) apply before requesting focus
        fieldFocus.requestFocus()
        keyboard?.show()
    }
    val scope = rememberCoroutineScope()

    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        color = BolbolColors.TextPrimary,
        textDirection = TextDirection.Ltr,
        textAlign = TextAlign.End,
    )

    Box(
        modifier = modifier
            .focusRequester(containerFocus)
            .onFocusChanged { frameFocused = it.isFocused }
            .onPreviewKeyEvent { event ->
                // A physical keyboard (or the emulator's) types straight into the field: the first printable
                // key starts editing and is kept, no OK press needed. D-pad/Back/Enter keep their TV meaning.
                val code = event.utf16CodePoint
                if (!editing && event.type == KeyEventType.KeyDown && code > 31 && code != 127 && event.key != Key.Tab) {
                    onValueChange(value + code.toChar())
                    editing = true
                    scope.launch {
                        withFrameNanos { }
                        fieldFocus.requestFocus()
                    }
                    true
                } else if (!editing && event.type == KeyEventType.KeyDown && event.key == Key.Tab) {
                    onImeAction()
                    true
                } else {
                    false
                }
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                editing = true
                fieldFocus.requestFocus()
                keyboard?.show()
            }
            .background(BolbolColors.SurfaceHigh, FieldShape)
            .border(
                width = if (ringed) BolbolSpacing.FocusBorder else 1.dp,
                color = if (ringed) BolbolColors.FocusRing else BolbolColors.Outline,
                shape = FieldShape,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(fieldFocus)
                // Read when focus is requested or searched, so the field is a D-pad stop only while editing.
                .focusProperties { canFocus = editing }
                .onFocusChanged { state ->
                    fieldFocused = state.isFocused
                    if (!state.isFocused && editing) {
                        editing = false
                        keyboard?.hide()
                    }
                }
                .onPreviewKeyEvent { event ->
                    when (event.key) {
                        Key.DirectionUp, Key.DirectionDown -> {
                            if (event.type == KeyEventType.KeyDown) {
                                focusManager.moveFocus(if (event.key == Key.DirectionUp) FocusDirection.Up else FocusDirection.Down)
                            }
                            true
                        }
                        Key.DirectionCenter -> {
                            if (event.type == KeyEventType.KeyUp) keyboard?.show()
                            true
                        }
                        // Physical keyboard: Tab / Enter = Next (username) or Done (password).
                        Key.Tab, Key.Enter, Key.NumPadEnter -> {
                            if (event.type == KeyEventType.KeyDown) onImeAction()
                            true
                        }
                        Key.Back -> {
                            if (event.type == KeyEventType.KeyUp) containerFocus.requestFocus()
                            true
                        }
                        else -> false
                    }
                }
                .then(if (masked) Modifier.semantics { password() } else Modifier),
            textStyle = textStyle,
            singleLine = true,
            cursorBrush = SolidColor(BolbolColors.Accent),
            visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = if (masked) KeyboardType.Password else KeyboardType.Text,
                imeAction = imeAction,
            ),
            keyboardActions = KeyboardActions(
                onNext = { onImeAction() },
                onDone = {
                    keyboard?.hide()
                    onImeAction()
                },
                onGo = { onImeAction() },
            ),
            decorationBox = { innerTextField ->
                Box(Modifier.fillMaxWidth().heightIn(min = 28.dp), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty() && frameFocused && !editing) {
                        BolbolText(
                            text = hint,
                            style = MaterialTheme.typography.bodyMedium,
                            color = BolbolColors.TextSecondary,
                            maxLines = 1,
                        )
                    }
                    innerTextField()
                }
            },
        )
    }
}
