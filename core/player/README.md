# :core:player — ExoPlayer core (UI-free)

Media3 1.11 ExoPlayer for progressive H.264/AAC MP4 + side-loaded Persian WebVTT. The UI lives in
`:feature:playback` (`PlayerViewModel`, `PlayerScreen`). Decisions: `docs/decisions.md` §Player.

## Public API (package `app.bolbol.tv.core.player`)
| Name | What |
|---|---|
| `PlayerFactory(context, callFactory, userAgent?)` `.create()` | Builds the ExoPlayer: OkHttp data source (shared client), decoder fallback, explicit `DefaultLoadControl`, movie audio attributes + focus, noisy-audio pause, `WAKE_MODE_NETWORK`, ±10 s increments. |
| `PlayerTuning` | Every number below, with KDoc on why. |
| `PlaybackController(player, watch, prefs, scope, clock)` | One per screen. `start(request, stream, startMs, playWhenReady)`, `switchStream(stream, rememberGlobally)`, `retry()`, `play/pause/togglePlayPause()`, `seekTo/seekBy()`, `setScrubbing()`, `saveSubtitleScale()`, `onStart/onStop/onTrimMemory()`, `release()`. Flows: `status`, `position`, `cues`, `diagnostics`, `error`. |
| `ResumePolicy.decide(saved, requestedStartMs)` → `ResumeDecision` | `StartFromBeginning` / `AskUser(pos)` / `ResumeAt(pos)`. Ask if pos ≥ 30 s, not completed, ≥ 60 s left; explicit start wins. |
| `ProgressSavePolicy` | Pure throttle: every 10 s while playing + forced on pause/stop/error/ended/switch/trim/release; never before duration is known or below 5 s; `completed` at ≥ 95 % or ended; identical saves skipped. |
| `SubtitleCues` | Drops the site-credit cue ("بلبل جان" / "bolboljan"); RLM-prefixes Persian lines whose first strong char is Latin. |
| `SeekSteps`, `SeekKeyRepeater` | D-pad seek: 10 s, 30 s after 1 s held, 60 s after 3 s; repeats rate-limited (120 ms); 1 s end guard. |
| `PlaybackError` | `Kind` = NETWORK / SOURCE_4XX / SOURCE_5XX / SOURCE_FORMAT / DECODER / OTHER (+ HTTP status, code name). |
| `audioTypes() / qualitiesFor() / find() / forAudio() / nextLowerQuality()` | Stream-list helpers for the picker and the error fallback. |

## Tuning (`PlayerTuning`)
| Value | Why |
|---|---|
| bufferForPlayback 1.5 s / after rebuffer 3 s | Fast first frame (TTFF ≤ 2 s); a bit more after a stall so it doesn't flap. |
| min 15 s / max 40 s buffer | Rides out Wi-Fi dips; 40 s of 1080p ≈ 20 MB. |
| target 60 MB, prioritizeTimeOverSize = false | Hard memory cap on a 2 GB TV (playback PSS ≤ 250 MB). |
| back buffer 10 s | "Back 10 s" served from memory, no new range request. |
| progress every 10 s, min 5 s, completed 95 % | Cheap Room writes; peeks aren't progress. |
| resume ask ≥ 30 s in, ≥ 60 s left | No prompt after a quick peek or when only the credits are left. |

## Wiring in `:app` (manual DI)
```kotlin
val playerFactory = PlayerFactory(appContext, okHttpClient)            // AppGraph, once
val vm: PlayerViewModel = viewModel(factory = PlayerViewModel.factory(
    request, createPlayer = { playerFactory.create() }, watchRepository, prefsRepository))
PlayerScreen(vm, onExit = { backStack.removeLast() }, subtitleTypeface = vazirmatn,
    showDiagnostics = settings.showPerformance)
```
The ViewModel owns the single ExoPlayer and releases it in `onCleared()` after the controller's
final progress save. The manifest here adds `WAKE_LOCK` (needed by `setWakeMode`).

## Reading diagnostics
- **Overlay:** `PlayerScreen(showDiagnostics = true)` or DPAD_UP ×3 → TTFF, resolution + bitrate,
  decoder name (MTK hardware vs `c2.android.*` software), buffer ahead, dropped frames, rebuffers,
  mean seek/join time, bandwidth estimate.
- **Logcat** (`adb logcat -s BolbolPerf`): `ttff=1234ms stream=720p/SUBTITLED` once per start
  (start() → first rendered frame; measured even when started paused behind the resume prompt),
  `stream_switch=…ms` after quality/audio changes, and on exit
  `summary rebuffers=0 meanRebufferMs=-1 meanSeekMs=… joinMs=… dropped=… decoder=…` (-1 = none).
- **Debug harness:** `adb shell am start -n app.bolbol.tv/app.bolbol.tv.feature.playback.debug.PlayerTestActivity`
  (needs `:app` to depend on `:feature:playback`). Extras and logs: see `PlayerTestActivity` KDoc;
  progress writes are logged with tag `BolbolTest`.
