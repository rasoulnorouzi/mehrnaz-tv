package app.bolbol.tv.feature.playback

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.bolbol.tv.core.designsystem.BolbolColors
import app.bolbol.tv.core.designsystem.BolbolSpacing
import app.bolbol.tv.core.designsystem.toPersianDigits
import app.bolbol.tv.core.model.AudioType
import app.bolbol.tv.core.model.StreamOption
import app.bolbol.tv.core.model.SubtitleStyle
import app.bolbol.tv.core.player.PlaybackError
import app.bolbol.tv.core.player.PlaybackSpeeds
import app.bolbol.tv.core.player.audioTypes
import app.bolbol.tv.core.player.qualitiesFor
import app.bolbol.tv.core.player.subtitles.HoldRepeat
import app.bolbol.tv.core.player.subtitles.SubtitleOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Subtitle size choices: label → SubtitleStyle.SIZE_STEPS (کوچک / معمولی / بزرگ / خیلی بزرگ). */
internal val SubtitleSizes = listOf(
    R.string.player_subtitle_small,
    R.string.player_subtitle_normal,
    R.string.player_subtitle_large,
    R.string.player_subtitle_xlarge,
).zip(SubtitleStyle.SIZE_STEPS)

private val DialogShape = RoundedCornerShape(12.dp)

internal fun FocusRequester.tryRequestFocus() {
    try {
        requestFocus()
    } catch (_: IllegalStateException) {
        // Not attached yet (e.g. the target left composition in the same frame); nothing to focus.
    }
}

/** "ادامه از ۴۷:۱۲" (focused) / "از ابتدا", over black, before playback starts. */
@Composable
internal fun ResumeDialog(title: String, positionMs: Long, onResume: () -> Unit, onStartOver: () -> Unit) {
    val resumeFocus = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .widthIn(max = 560.dp)
                .background(BolbolColors.Surface, DialogShape)
                .padding(horizontal = 40.dp, vertical = 32.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = BolbolColors.TextPrimary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.player_resume_title),
                style = MaterialTheme.typography.bodyMedium,
                color = BolbolColors.TextSecondary,
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = onResume, modifier = Modifier.focusRequester(resumeFocus)) {
                    Text(stringResource(R.string.player_resume_from, formatPlaybackTime(positionMs).toPersianDigits()))
                }
                Button(onClick = onStartOver) {
                    Text(stringResource(R.string.player_start_over))
                }
            }
        }
    }
    LaunchedEffect(Unit) { resumeFocus.tryRequestFocus() }
}

/** Error message + "تلاش دوباره" (focused) / lower quality. */
@Composable
internal fun ErrorPanel(error: PlaybackError, canLowerQuality: Boolean, onRetry: () -> Unit, onLowerQuality: () -> Unit) {
    val retryFocus = remember { FocusRequester() }
    MessagePanel(
        message = stringResource(error.messageRes()),
        detail = error.codeName + (error.httpStatus?.let { " · HTTP $it" } ?: ""),
    ) {
        Button(onClick = onRetry, modifier = Modifier.focusRequester(retryFocus)) {
            Text(stringResource(R.string.player_retry))
        }
        if (canLowerQuality) {
            Button(onClick = onLowerQuality) { Text(stringResource(R.string.player_lower_quality)) }
        }
    }
    LaunchedEffect(error) { retryFocus.tryRequestFocus() }
}

@Composable
internal fun NoStreamsPanel(onBack: () -> Unit) {
    val focus = remember { FocusRequester() }
    MessagePanel(message = stringResource(R.string.player_no_streams), detail = null) {
        Button(onClick = onBack, modifier = Modifier.focusRequester(focus)) { Text(stringResource(R.string.player_back)) }
    }
    LaunchedEffect(Unit) { focus.tryRequestFocus() }
}

@Composable
private fun MessagePanel(message: String, detail: String?, buttons: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xE6000000)), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .widthIn(max = 560.dp)
                .background(BolbolColors.Surface, DialogShape)
                .padding(horizontal = 40.dp, vertical = 32.dp),
        ) {
            Text(message, style = MaterialTheme.typography.titleMedium, color = BolbolColors.TextPrimary, textAlign = TextAlign.Center)
            if (detail != null) {
                Spacer(Modifier.height(6.dp))
                Text(detail, style = MaterialTheme.typography.labelMedium, color = BolbolColors.TextSecondary)
            }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { buttons() }
        }
    }
}

private fun PlaybackError.messageRes(): Int = when (kind) {
    PlaybackError.Kind.NETWORK -> R.string.player_error_network
    PlaybackError.Kind.SOURCE_4XX -> R.string.player_error_source_4xx
    PlaybackError.Kind.SOURCE_5XX -> R.string.player_error_source_5xx
    PlaybackError.Kind.SOURCE_FORMAT -> R.string.player_error_format
    PlaybackError.Kind.DECODER -> R.string.player_error_decoder
    PlaybackError.Kind.OTHER -> R.string.player_error_other
}

/**
 * "تنظیمات پخش" side panel at the end edge: audio (دوبله / زیرنویس), quality (۱۰۸۰ / ۷۲۰ / ۴۸۰), playback speed
 * (۰٫۵× ... ۲×, «عادی» = 1×; see [SpeedSection]) and,
 * for subtitled streams, the subtitle quick menu (docs/subtitles.md): on/off, size (4 steps) and sync
 * (±0.5 s steps with hold-to-repeat, «تنظیم مجدد», the current offset up to ±120 s). Changes apply live;
 * colour, box, position and weight live on the Settings page. Focus starts on the current audio.
 * Scrolls vertically when the sections don't fit (focus brings the row into view).
 * A saved copy ([offline], v1.4) has no audio/quality choice: those sections become one «نسخهٔ ذخیره‌شده» line and
 * focus starts on the current speed.
 */
@Composable
internal fun StreamPickerPanel(
    streams: List<StreamOption>,
    current: StreamOption?,
    speed: Float,
    subtitleStyle: SubtitleStyle,
    subtitleOffsetMs: Int,
    onAudio: (AudioType) -> Unit,
    onQuality: (Int) -> Unit,
    onSpeed: (Float) -> Unit,
    onSubtitlesEnabled: (Boolean) -> Unit,
    onSubtitleSize: (Float) -> Unit,
    onSubtitleShift: (deltaMs: Int) -> Unit,
    onSubtitleReset: () -> Unit,
    modifier: Modifier = Modifier,
    offline: Boolean = false,
) {
    val firstFocus = remember { FocusRequester() }
    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(PANEL_WIDTH)
                .background(BolbolColors.Surface)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = BolbolSpacing.SafeVertical),
        ) {
            Text(stringResource(R.string.player_settings), style = MaterialTheme.typography.titleMedium, color = BolbolColors.TextPrimary)

            if (offline) {
                Spacer(Modifier.height(12.dp))
                val savedText = current?.let { stringResource(R.string.player_saved_copy_of, streamLabel(it)) }
                    ?: stringResource(R.string.player_saved_copy)
                Text(savedText, style = MaterialTheme.typography.labelMedium, color = BolbolColors.TextSecondary)
            } else PickerSection(stringResource(R.string.player_audio)) {
                for (audio in streams.audioTypes()) {
                    val selected = audio == current?.audio
                    OptionButton(
                        label = audioLabel(audio),
                        selected = selected,
                        onClick = { onAudio(audio) },
                        modifier = if (selected) Modifier.focusRequester(firstFocus) else Modifier,
                    )
                }
            }
            if (current != null && !offline) {
                PickerSection(stringResource(R.string.player_quality)) {
                    for (quality in streams.qualitiesFor(current.audio)) {
                        OptionButton(
                            label = quality.toPersianDigits(),
                            selected = quality == current.quality,
                            onClick = { onQuality(quality) },
                        )
                    }
                }
            }
            SpeedSection(speed, onSpeed, focusSelected = if (offline) firstFocus else null)
            if (current?.subtitleUrl != null) {
                SubtitleSection(subtitleStyle, subtitleOffsetMs, onSubtitlesEnabled, onSubtitleSize, onSubtitleShift, onSubtitleReset)
            }
        }
    }
    LaunchedEffect(Unit) { firstFocus.tryRequestFocus() }
}

/**
 * «سرعت پخش» (v1.2-1): six equal-width chips in two rows of three, slowest first (so «۰٫۵×» is at the
 * start/right in RTL): ۰٫۵× · ۰٫۷۵× · عادی / ۱٫۲۵× · ۱٫۵× · ۲×. Equal widths keep UP/DOWN straight between the
 * rows and LEFT/RIGHT inside them, and the panel never scrolls sideways.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeedSection(speed: Float, onSpeed: (Float) -> Unit, focusSelected: FocusRequester? = null) {
    Spacer(Modifier.height(20.dp))
    Text(stringResource(R.string.player_speed), style = MaterialTheme.typography.labelMedium, color = BolbolColors.TextSecondary)
    Spacer(Modifier.height(8.dp))
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = SPEED_CHIPS_PER_ROW,
    ) {
        for (choice in PlaybackSpeeds.CHOICES) {
            val selected = abs(choice - speed) < 0.001f
            OptionButton(
                label = if (PlaybackSpeeds.isNormal(choice)) stringResource(R.string.player_speed_normal) else formatSpeedMultiplier(choice),
                selected = selected,
                onClick = { onSpeed(choice) },
                modifier = if (selected && focusSelected != null) Modifier.focusRequester(focusSelected) else Modifier,
            )
        }
    }
}

private const val SPEED_CHIPS_PER_ROW = 3

@Composable
private fun SubtitleSection(
    style: SubtitleStyle,
    offsetMs: Int,
    onEnabled: (Boolean) -> Unit,
    onSize: (Float) -> Unit,
    onShift: (deltaMs: Int) -> Unit,
    onReset: () -> Unit,
) {
    Spacer(Modifier.height(24.dp))
    Text(stringResource(R.string.player_subtitles), style = MaterialTheme.typography.titleSmall, color = BolbolColors.TextPrimary)
    Spacer(Modifier.height(8.dp))
    OptionRow {
        OptionButton(stringResource(R.string.player_subtitles_on), selected = style.enabled, onClick = { onEnabled(true) })
        OptionButton(stringResource(R.string.player_subtitles_off), selected = !style.enabled, onClick = { onEnabled(false) })
    }
    if (!style.enabled) return
    PickerSection(stringResource(R.string.player_subtitle_size)) {
        for ((label, scale) in SubtitleSizes) {
            OptionButton(
                label = stringResource(label),
                selected = abs(scale - style.sizeScale) < 0.01f,
                onClick = { onSize(scale) },
            )
        }
    }
    // «همگام‌سازی: +۱۲٫۵ ثانیه», then one row (earlier at the start/right, later at the end/left) and reset:
    //   زودتر ۰٫۵ | دیرتر ۰٫۵
    //   تنظیم مجدد (full width)
    // Equal-width columns + a full-width reset keep LEFT/RIGHT between the steps and UP/DOWN straight
    // between the rows (nothing unreachable). Holding OK repeats a step. (5 s steps removed 2026-10-11.)
    val offsetText = stringResource(R.string.player_subtitle_seconds, formatSubtitleOffset(offsetMs))
    Spacer(Modifier.height(20.dp))
    Text(stringResource(R.string.player_subtitle_sync, offsetText), style = MaterialTheme.typography.labelMedium, color = BolbolColors.TextSecondary)
    Spacer(Modifier.height(8.dp))
    val small = SubtitleOffset.SMALL_STEP_MS
    StepRow(
        R.string.player_subtitle_earlier to -small,
        R.string.player_subtitle_later to small,
        onShift = onShift,
    )
    Spacer(Modifier.height(8.dp))
    OptionButton(stringResource(R.string.player_subtitle_reset), selected = false, onClick = onReset, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun StepRow(first: Pair<Int, Int>, second: Pair<Int, Int>, onShift: (deltaMs: Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((label, delta) in listOf(first, second)) {
            RepeatStepButton(stringResource(label), onStep = { onShift(delta) }, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * A sync step button: OK steps once at key-down; **holding OK** repeats the step on our own timer
 * ([HoldRepeat]: first repeat after 400 ms, then every 120 ms) until key-up or focus loss. The remote's
 * own auto-repeat key-downs are ignored, so the speed is the same on every remote.
 */
@Composable
private fun RepeatStepButton(label: String, onStep: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val currentOnStep by rememberUpdatedState(onStep)
    val stepper = remember(scope) { HoldStepper(scope) { currentOnStep() } }
    DisposableEffect(stepper) { onDispose { stepper.release() } }
    OptionButton(
        label = label,
        selected = false,
        onClick = { currentOnStep() }, // pointer clicks only: OK key events are consumed below
        modifier = modifier
            .onFocusChanged { if (!it.isFocused) stepper.release() }
            .onPreviewKeyEvent { event ->
                if (!event.key.isConfirmKey()) return@onPreviewKeyEvent false
                when (event.type) {
                    KeyEventType.KeyDown -> stepper.press()
                    KeyEventType.KeyUp -> stepper.release()
                }
                true
            },
    )
}

private class HoldStepper(private val scope: CoroutineScope, private val step: () -> Unit) {
    private var job: Job? = null

    fun press() {
        if (job != null) return // a remote auto-repeat key-down: our timer is already running
        step() // synchronously, so a quick down+up (adb keyevent) still steps exactly once
        job = scope.launch {
            for (i in 1 until HoldRepeat.MAX_STEPS) {
                delay(HoldRepeat.delayBefore(i))
                step()
            }
        }
    }

    fun release() {
        job?.cancel()
        job = null
    }
}

private fun Key.isConfirmKey() = this == Key.DirectionCenter || this == Key.Enter || this == Key.NumPadEnter

@Composable
private fun PickerSection(title: String, options: @Composable () -> Unit) {
    Spacer(Modifier.height(20.dp))
    Text(title, style = MaterialTheme.typography.labelMedium, color = BolbolColors.TextSecondary)
    Spacer(Modifier.height(8.dp))
    OptionRow(options)
}

/** Buttons flow start→end and wrap when the labels don't fit the panel width. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionRow(options: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { options() }
}

internal val PANEL_WIDTH = 400.dp


@Composable
private fun OptionButton(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        colors = ButtonDefaults.colors(
            containerColor = if (selected) BolbolColors.Accent else BolbolColors.SurfaceHigh,
            contentColor = if (selected) BolbolColors.OnAccent else BolbolColors.TextPrimary,
            focusedContainerColor = BolbolColors.FocusRing,
            focusedContentColor = BolbolColors.OnAccent,
        ),
    ) {
        if (selected) {
            val dot = LocalContentColor.current
            Canvas(Modifier.size(6.dp)) { drawCircle(dot) }
            Spacer(Modifier.width(6.dp))
        }
        Text(label)
    }
}
