# :core:player — ExoPlayer core + subtitle engine (UI-free)

Media3 1.11 ExoPlayer for progressive H.264/AAC MP4, plus our own Persian WebVTT engine (ExoPlayer
never sees the subtitles: no `SubtitleConfiguration`, text tracks disabled). The UI lives in
`:feature:playback` (`PlayerViewModel`, `PlayerScreen`). Decisions: `docs/decisions.md` §Player;
subtitle spec: `docs/subtitles.md`.

## Public API (package `app.bolbol.tv.core.player`)
| Name | What |
|---|---|
| `PlayerFactory(context, callFactory, userAgent?)` `.create()` / `.subtitleLoader` | Builds the ExoPlayer: `DefaultDataSource` (http(s) → OkHttp, `file://` saved copies (v1.4) → `FileDataSource`); OkHttp data source on `MediaHttp.mediaClient` (the shared client's pool, no disk cache, 6 s/10 s timeouts) with a `MediaTransferTracker`, `MediaLoadErrorPolicy`, `KeyframeStartMediaSourceFactory`, decoder fallback, explicit `DefaultLoadControl`, movie audio attributes + focus, noisy-audio pause, `WAKE_MODE_NETWORK`, ±10 s increments, `TRACK_TYPE_TEXT` disabled. `subtitleLoader` uses the shared (cached) client. |
| `PlayerTuning` | Every number below, with KDoc on why. |
| `PlaybackController(player, watch, prefs, scope, subtitleLoader?, clock)` | One per screen. `start(request, stream, startMs, playWhenReady)`, `switchStream(stream, rememberGlobally)`, `retry()`, `play/pause/togglePlayPause()`, `seekTo/seekBy()`, `setScrubbing()`, `prewarmOtherStreams()`, `setSubtitleOffset(ms)`, `setSubtitleStyle(style)`, `setSpeed(speed)` (v1.2-1), `markCompleted()`, `onStart/onStop/onTrimMemory()`, `release()`. Flows: `status`, `position`, `subtitleLines`, `subtitleStyle`, `subtitleOffsetMs`, `events` (one-shot `PlayerEvent.SubtitleLoadFailed`), `diagnostics`, `error`, `switchingQuality`, `speed`. |
| `PlaybackSpeeds` / `PlaybackSpeedMemory` | v1.2-1 playback speed: `CHOICES` 0.5 · 0.75 · 1 · 1.25 · 1.5 · 2 (`normalize` snaps, `isNormal`). `PlaybackSpeedMemory.Session` is the in-memory, per-app-session holder every controller reads (next episode, auto-next, quality/audio switch, retry and the background stop/start keep it; re-applied before each `prepare()`); never persisted, so a process restart is back to 1×. Media3 keeps the pitch natural. Seek steps, progress and the auto-next window use media time; the auto-next countdown uses real time. |
| `StreamSwitchPolicy` | Pure switch watchdog (v1.1-2): no first frame 8 s after `switchStream` and no bytes for 2 s → `Reprepare` once (idle connections dropped) → `GiveUp` (error panel); bytes still arriving → `Wait`; 20 s hard limit. `SwitchTimeline.logLine()` = the `BolbolSwitch` breakdown. |
| `KeyframeStart` / `KeyframeSnap` | Tag for `MediaItem.setTag`: the item starts at the keyframe before its start position (`PREVIOUS_SYNC`, first track selection only, ≤ 15 s back). Used by `switchStream` (and the watchdog re-prepare), and since v1.5.1 also by `start` with a saved position and by `onStart` (the screen visible again rebuilds the stream from a keyframe instead of a plain `prepare()`). |
| `MediaHttp`, `MediaRetry`, `MediaLoadErrorPolicy` | Media client derivation; retry delays 0.2/0.5/1/2 s, 4xx fatal except 408/429. |
| `ConnectionPrewarmer` / `PrewarmPlan` | One `Range: bytes=0-0` request per other CDN host of the title (highest quality first, current host skipped, ≤ 1 per host per minute), async on OkHttp's dispatcher. Runs after the first frame of `start()`. |
| `StutterGuard` (v1.5.1) | Self-healing for choppy video: > 30 % of frames dropped in two 4 s windows of real playback → the controller reloads the same stream in place from a keyframe (≥ 60 s apart, ≤ 3 per stream; seeks and pauses restart the window). User report 2026-10-29: after standby/resume the MT9611 played like a slideshow until a quality switch. |
| `ResumePolicy.decide(saved, requestedStartMs)` → `ResumeDecision` | `StartFromBeginning` / `AskUser(pos)` / `ResumeAt(pos)`. Ask if pos ≥ 30 s, not completed, ≥ 60 s left; explicit start wins. |
| `ProgressSavePolicy` | Pure throttle: every 10 s while playing + forced on pause/stop/error/ended/switch/trim/release; never before duration is known or below 5 s; `completed` at ≥ 95 % or ended; identical saves skipped. |
| `AutoNextPolicy.step(state, input, elapsedMs)` → `AutoNextState` | Next-episode card (M2.2): `Waiting` → `Counting(remainingMs, startedAtEnd)` in the last 30 s (episodes ≥ 60 s) or at the end → `PlayNext` after a 10 s countdown (runs while playing or ended; paused/held freezes it) or when the video ends while counting. `dismiss()` → `Dismissed` (comes back once at the end) / `DismissedAtEnd`. Seeking out of the window re-arms. |
| `SeekSteps`, `SeekKeyRepeater` | D-pad seek: 10 s, 30 s after 1 s held, 60 s after 3 s; repeats rate-limited (120 ms); 1 s end guard. |
| `PlaybackError` | `Kind` = NETWORK / SOURCE_4XX / SOURCE_5XX / SOURCE_FORMAT / DECODER / OTHER (+ HTTP status, code name). |
| `audioTypes() / qualitiesFor() / find() / forAudio() / nextLowerQuality()` | Stream-list helpers for the picker and the error fallback. |

## Subtitle engine (package `…core.player.subtitles`, docs/subtitles.md §Engine)
| Name | What |
|---|---|
| `SubtitleLoader(callFactory, userAgent?)` `.load(url)` | OkHttp GET on `Dispatchers.IO` (HTTP cache makes re-opens free), 2 MB cap (Content-Length and streamed), cancellable; throws `SubtitleLoadException` on HTTP errors, oversize, I/O, or zero cues. A `file://` URL (a saved copy's `subtitle.vtt`, v1.4) is read from disk with the same cap, no network. |
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
| media connect 6 s / read 10 s, no disk cache | A stalled CDN connection fails fast and is retried on a fresh one; video bytes are never teed into the API cache. |
| switch watchdog 8 s, stall 2 s, hard limit 20 s, 1 re-prepare | `StreamSwitchPolicy`; slow-but-moving switches are not killed. |
| switch keyframe snap ≤ 15 s | Keyframes are 1-10 s apart in the site's x264 files (perf/reports/v1.1-quality-switch.md). |
| pre-warm ≤ 1 request per host per 60 s | CDN DNS TTL 75-120 s, nginx keep-alive ~1 min. |

## Wiring in `:app` (manual DI)
```kotlin
val playerFactory = PlayerFactory(appContext, okHttpClient)            // AppGraph, once
val vm: PlayerViewModel = viewModel(factory = PlayerViewModel.factory(
    request, playerFactory = playerFactory, watchRepository, prefsRepository,
    downloads = downloadRepository))   // player + subtitle loader; v1.4: plays a saved copy when there is one
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

## Offline playback (v1.4)
With `downloads` (core:model `DownloadRepository`, implemented in `:core:download`), `PlayerViewModel` asks
`offlineMedia(request.key)` before choosing a stream (`PlaybackSource`, unit-tested). A COMPLETED download plays from
`file:///…/video.mp4` with its saved `subtitle.vtt` (works with Wi-Fi off), under the same progress key, so resume,
Continue Watching and auto-next behave as usual. The saved stream is the only one: the settings panel shows
«نسخهٔ ذخیره‌شده: ۷۲۰ · زیرنویس» instead of the audio/quality choices, the error panel has no «کیفیت پایین‌تر», and no CDN
host is pre-warmed. Same ExoPlayer, LoadControl and memory budget as streaming (no cache data source); a local file only
fills the buffer faster. Auto-next to an episode that is not saved streams it as usual (needs internet).

## Reading diagnostics
- **Overlay:** `PlayerScreen(showDiagnostics = true)` or DPAD_UP ×3 → TTFF, resolution + bitrate,
  decoder name (MTK hardware vs `c2.android.*` software), buffer ahead, dropped frames, rebuffers,
  mean seek/join time, bandwidth estimate.
- **Logcat** (`adb logcat -s BolbolPerf`): `ttff=1234ms stream=720p/SUBTITLED` once per start
  (start() → first rendered frame; measured even when started paused behind the resume prompt),
  `stream_switch=…ms` after quality/audio changes, and on exit
  `summary rebuffers=0 meanRebufferMs=-1 meanSeekMs=… joinMs=… dropped=… decoder=…` (-1 = none).
- **Quality switch** (`adb logcat -s BolbolSwitch`): one line per switch,
  `switch 720p/SUBTITLED->1080p/SUBTITLED host=… result=ok firstByte=…ms moov=…ms rangeByte=…ms firstFrame=…ms ready=…ms snap=-1.6s requests=2 mb=4.9 errors=0 reprepares=0`
  (times from the switch; `result` = ok / gave_up / error_KIND / replaced / stopped / released), plus `prewarm host=… http=206 in …ms`
  and watchdog `re-preparing` lines. The overlay's `swtch` line shows the last switch. How to read it: perf/reports/v1.1-quality-switch.md §4.
- **Debug harness:** `adb shell am start -n app.bolbol.tv/app.bolbol.tv.feature.playback.debug.PlayerTestActivity`
  (needs `:app` to depend on `:feature:playback`). `--ez next true` fakes a next episode for the auto-next
  card. Extras and logs: see `PlayerTestActivity` KDoc; progress/prefs writes are logged with tag `BolbolTest`.
