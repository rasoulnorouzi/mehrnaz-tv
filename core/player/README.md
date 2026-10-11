# :core:player — ExoPlayer core + subtitle engine (UI-free)

Media3 1.11 ExoPlayer for progressive H.264/AAC MP4, plus our own Persian WebVTT engine (ExoPlayer
never sees the subtitles: no `SubtitleConfiguration`, text tracks disabled). The UI lives in
`:feature:playback` (`PlayerViewModel`, `PlayerScreen`). Decisions: `docs/decisions.md` §Player;
subtitle spec: `docs/subtitles.md`.

## Public API (package `app.bolbol.tv.core.player`)
| Name | What |
|---|---|
| `PlayerFactory(context, callFactory, userAgent?)` `.create()` / `.subtitleLoader` | Builds the ExoPlayer: OkHttp data source (shared client), decoder fallback, explicit `DefaultLoadControl`, movie audio attributes + focus, noisy-audio pause, `WAKE_MODE_NETWORK`, ±10 s increments, `TRACK_TYPE_TEXT` disabled. `subtitleLoader` shares the same client. |
| `PlayerTuning` | Every number below, with KDoc on why. |
| `PlaybackController(player, watch, prefs, scope, subtitleLoader?, clock)` | One per screen. `start(request, stream, startMs, playWhenReady)`, `switchStream(stream, rememberGlobally)`, `retry()`, `play/pause/togglePlayPause()`, `seekTo/seekBy()`, `setScrubbing()`, `setSubtitleOffset(ms)`, `setSubtitleStyle(style)`, `markCompleted()`, `onStart/onStop/onTrimMemory()`, `release()`. Flows: `status`, `position`, `subtitleLines`, `subtitleStyle`, `subtitleOffsetMs`, `events` (one-shot `PlayerEvent.SubtitleLoadFailed`), `diagnostics`, `error`. |
| `ResumePolicy.decide(saved, requestedStartMs)` → `ResumeDecision` | `StartFromBeginning` / `AskUser(pos)` / `ResumeAt(pos)`. Ask if pos ≥ 30 s, not completed, ≥ 60 s left; explicit start wins. |
| `ProgressSavePolicy` | Pure throttle: every 10 s while playing + forced on pause/stop/error/ended/switch/trim/release; never before duration is known or below 5 s; `completed` at ≥ 95 % or ended; identical saves skipped. |
| `AutoNextPolicy.step(state, input, elapsedMs)` → `AutoNextState` | Next-episode card (M2.2): `Waiting` → `Counting(remainingMs, startedAtEnd)` in the last 30 s (episodes ≥ 60 s) or at the end → `PlayNext` after a 10 s countdown (runs while playing or ended; paused/held freezes it) or when the video ends while counting. `dismiss()` → `Dismissed` (comes back once at the end) / `DismissedAtEnd`. Seeking out of the window re-arms. |
| `SeekSteps`, `SeekKeyRepeater` | D-pad seek: 10 s, 30 s after 1 s held, 60 s after 3 s; repeats rate-limited (120 ms); 1 s end guard. |
| `PlaybackError` | `Kind` = NETWORK / SOURCE_4XX / SOURCE_5XX / SOURCE_FORMAT / DECODER / OTHER (+ HTTP status, code name). |
| `audioTypes() / qualitiesFor() / find() / forAudio() / nextLowerQuality()` | Stream-list helpers for the picker and the error fallback. |

## Subtitle engine (package `…core.player.subtitles`, docs/subtitles.md §Engine)
| Name | What |
|---|---|
| `SubtitleLoader(callFactory, userAgent?)` `.load(url)` | OkHttp GET on `Dispatchers.IO` (HTTP cache makes re-opens free), 2 MB cap (Content-Length and streamed), cancellable; throws `SubtitleLoadException` on HTTP errors, oversize, I/O, or zero cues. |
| `VttParser.parse(text)` → `SubtitleTrack` | Pure, lenient WebVTT: BOM, optional header, `HH:MM:SS.mmm`/`MM:SS.mmm` (also `,`), cue settings ignored, NOTE/STYLE/REGION and identifiers skipped, tags stripped (text kept), entities decoded (`&amp; &lt; &gt; &nbsp; &lrm; &rlm;`, numeric), credit cues dropped, sorted. |
| `SubtitleTrack(cues)` `.linesAt(ms)` | Immutable, start-sorted `SubtitleCue(startMs, endMs, lines)`; binary search + running max end (overlaps stack in start order); returns the cue's own list when one cue is active (no allocation). |
| `SubtitleSync(scope, positionMs)` | `lines: StateFlow<List<String>>` at `position − offsetMs`; 100 ms ticker only while playing with a track and subtitles on; `update()` on seek/discontinuity/pause; emits only on change. |
| `SubtitleOffset.normalize/shift`, `HoldRepeat` | ±120 s clamp on a 0.5 s grid; `shift(current, ±500 or ±5000)` (positive = subtitles later). Saved per title (`PrefsRepository.saveSubtitleOffsetMs`, debounced 400 ms). `HoldRepeat`: hold-OK timing of the sync buttons (step at once, first repeat 400 ms, then every 120 ms). |
| `SubtitleCues` | `isSiteCredit` ("بلبل جان", "bolboljan", "@bollbolljan" handle; spaces/ZWNJ/bidi marks ignored). `prepareLine` = `repairVisualOrder` (lines typed for LTR players: leading `. ! ? , : ; ، …` moved to the end, trailing speaker `-` moved to the front; only lines with Persian text and no leading bidi control) then `forceRtl` (RLM before a Persian line whose first strong char is Latin). Lines starting with U+202B RLE (the site's correctly authored ones) are kept verbatim. |

Behaviour: `start()`/`switchStream()` load the stream's `.vtt` (skipped when the URL is unchanged; none for
dubbed streams), `start()` reads the title's saved offset once per title; the global `SubtitleStyle` is
collected from `PrefsRepository.subtitleStyle()` and `setSubtitleStyle` saves it. Load failure → one
`PlayerEvent.SubtitleLoadFailed` (the UI toasts "زیرنویس بارگذاری نشد"), playback continues.
Logcat `BolbolPlayer`: `subtitles: 1328 cues in 85ms` / `subtitle load failed: …`.

## Tuning (`PlayerTuning`)
| Value | Why |
|---|---|
| bufferForPlayback 1.5 s / after rebuffer 3 s | Fast first frame (TTFF ≤ 2 s); a bit more after a stall so it doesn't flap. |
| min 15 s / max 40 s buffer | Rides out Wi-Fi dips; 40 s of 1080p ≈ 20 MB. |
| target 60 MB, prioritizeTimeOverSize = false | Hard memory cap on a 2 GB TV (playback PSS ≤ 250 MB). |
| back buffer 10 s | "Back 10 s" served from memory, no new range request. |
| progress every 10 s, min 5 s, completed 95 % | Cheap Room writes; peeks aren't progress. |
| resume ask ≥ 30 s in, ≥ 60 s left | No prompt after a quick peek or when only the credits are left. |
| subtitle tick 100 ms (`SubtitleSync.TICK_MS`) | A cue shows ≤ 0.1 s late; one binary search per tick, recomposition only per cue. |
| auto-next window 30 s, countdown 10 s | `AutoNextPolicy`; the VM polls 1 Hz outside the countdown, 5 Hz during it. |

## Wiring in `:app` (manual DI)
```kotlin
val playerFactory = PlayerFactory(appContext, okHttpClient)            // AppGraph, once
val vm: PlayerViewModel = viewModel(factory = PlayerViewModel.factory(
    request, playerFactory = playerFactory, watchRepository, prefsRepository))   // player + subtitle loader
PlayerScreen(vm, onExit = { backStack.removeLast() },
    onPlayNext = { next -> /* replace this player entry with PlayerRoute(next) */ },
    showDiagnostics = settings.showPerformance)
```
The older `factory(request, createPlayer = { … }, watch, prefs, subtitleLoader = playerFactory.subtitleLoader)`
still works; without `subtitleLoader` a private fallback OkHttpClient is used (logged as a warning).
`PlayerScreen(subtitleTypeface = …)` is ignored now (SubtitleText uses Vazirmatn) and can be dropped.
The ViewModel owns the single ExoPlayer and releases it in `onCleared()` after the controller's
final progress save. `onPlayNext` is called after the episode was saved as completed and paused; replace
the back-stack entry so this ViewModel (and its ExoPlayer) is cleared before the next player is created.
The manifest here adds `WAKE_LOCK` (needed by `setWakeMode`).

## Reading diagnostics
- **Overlay:** `PlayerScreen(showDiagnostics = true)` or DPAD_UP ×3 → TTFF, resolution + bitrate,
  decoder name (MTK hardware vs `c2.android.*` software), buffer ahead, dropped frames, rebuffers,
  mean seek/join time, bandwidth estimate.
- **Logcat** (`adb logcat -s BolbolPerf`): `ttff=1234ms stream=720p/SUBTITLED` once per start
  (start() → first rendered frame; measured even when started paused behind the resume prompt),
  `stream_switch=…ms` after quality/audio changes, and on exit
  `summary rebuffers=0 meanRebufferMs=-1 meanSeekMs=… joinMs=… dropped=… decoder=…` (-1 = none).
- **Debug harness:** `adb shell am start -n app.bolbol.tv/app.bolbol.tv.feature.playback.debug.PlayerTestActivity`
  (needs `:app` to depend on `:feature:playback`). `--ez next true` fakes a next episode for the auto-next
  card. Extras and logs: see `PlayerTestActivity` KDoc; progress/prefs writes are logged with tag `BolbolTest`.
