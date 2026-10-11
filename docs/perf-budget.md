# Performance budget & how to measure it

Target hardware: Mi TV P1 43": 4× Cortex-A55 @1.5 GHz, Mali-G52 MP2, **2 GB RAM**, Android 10 (API 29).
All numbers are measured on **the real TV**, release build, with the Baseline Profile installed.
The emulator is for screenshots and profile generation only; never quote emulator timings.
(Proof, 2026-10-11: the API 29 TV emulator renders in software, so a trivial screen showed 90 % janky frames
and `Graphics: 0 KB`. Emulator frame stats are meaningless; `tools/tv.sh perf` flags them.)

## Budgets
| Metric | Budget | How it's measured |
|---|---|---|
| Cold start → home rows visible (from cache) | **≤ 1.5 s** | Macrobenchmark `StartupTimingMetric` (TTID) / `am start -W` |
| Warm start | ≤ 0.5 s | `am start -W` |
| Home D-pad scrolling (rows + across rows) | p90 frame CPU ≤ 16 ms, janky frames < 5 % | Macrobenchmark `FrameTimingMetric` (`frameDurationCpuMs`), `dumpsys gfxinfo` |
| Open details (cached / network) | ≤ 0.4 s / ≤ 1.0 s | custom trace section `details_ready` |
| **Time to first video frame** (Play pressed → first frame) | ≤ 2.0 s at 720p on home Wi-Fi | `onRenderedFirstFrame` − play click (logged + overlay) |
| Resume/seek → frame | ≤ 1.0 s | PlaybackStats mean seek time |
| Rebuffers in a 10-min 720p session | 0 | PlaybackStats |
| Dropped video frames | ≈ 0 / min at 1080p | PlaybackStats / `EventLogger` |
| Memory while browsing | total PSS ≤ 180 MB, image memory ≤ 40 MB | `dumpsys meminfo <pkg>` |
| Memory while playing | total PSS ≤ 250 MB, buffer ≤ 60 MB | `dumpsys meminfo`, LoadControl caps |
| APK size (release) | ≤ 15 MB | `ls -l app-release.apk` |
| Main-thread disk/network I/O | 0 | StrictMode in debug builds |

The **official app's numbers** for the same metrics are recorded in M0.6 (`perf/reports/official-baseline.md`).
Our app must beat them on every row.

## How the user can see performance
1. **On the TV (live):** a hidden **debug overlay** (Settings → Developer → "Show performance",
   or press MENU 3× on the home screen). It shows FPS + janky-frame %, Java/native memory,
   and during playback: resolution, bitrate, buffered seconds, dropped frames, and TTFF of the current video.
2. **On the desktop (live):** `scrcpy` mirrors the TV screen to your computer, so you can watch
   while Claude drives it with `tools/tv.sh key …`.
3. **Reports:** `tools/tv.sh perf` runs a standard scenario (cold start ×5, scroll home, open details,
   play 60 s) and writes `perf/reports/<date>-<git-sha>.md`: a table of measured value vs budget vs
   official app, with ✅/❌ per row.
4. **Trend dashboard:** `perf/dashboard.html` is regenerated after each report from all reports
   (charts of startup, jank, TTFF, memory over versions). Open it in a browser.
5. **Deep dives (Claude or you):** Perfetto traces (`tools/tv.sh trace 10s` → open in ui.perfetto.dev),
   Android Studio Profiler over Wi-Fi ADB, LeakCanary notifications in debug builds.

## Measurement commands (wrapped by tools/tv.sh)
```bash
adb connect $TV_IP:5555
adb shell am force-stop $PKG && adb shell am start -W -n $PKG/.MainActivity      # TotalTime
adb shell dumpsys gfxinfo $PKG reset; <drive scroll>; adb shell dumpsys gfxinfo $PKG   # jank %
adb shell dumpsys meminfo $PKG                                                       # PSS
adb exec-out screencap -p > shot.png                                                 # screenshot
adb shell input keyevent KEYCODE_DPAD_RIGHT                                          # remote keys
adb shell setprop persist.traced.enable 1   # once, Android 10, before Perfetto
./gradlew :benchmark:connectedReleaseAndroidTest                                     # Macrobenchmark
```

## Android 10 limitations (know these)
- Macrobenchmark: `timeToFullDisplayMs` may be missing; `frameOverrunMs` needs API 31+. Use `frameDurationCpuMs`.
- **Baseline Profiles can't be generated on this TV** (needs root or API 33+). Generate them on the
  Android TV API 34 emulator, ship them in the APK, and verify the gain on the TV with Macrobenchmark
  (`CompilationMode.None` vs `Partial`).
- Perfetto on API 29: set `persist.traced.enable 1` and pass the config via stdin.

## Standing performance rules
- Images: Coil 3, always sized to the composable (posters ~ 200×300 dp, backdrops ≤ 1280×720),
  hardware bitmaps, RGB_565 for posters is acceptable, memory cache ≤ 15 % of heap.
- Backdrop changes on focus are **debounced (~300 ms)** and crossfade once. No autoplay video.
- Lazy lists: stable `key`, `contentType`, no heavy work in item composition; focus scale via `graphicsLayer`.
- Network: OkHttp disk cache (50 MB) + Room cache of home rows; stale-while-revalidate.
- Player: one ExoPlayer at a time, released on exit; `DefaultLoadControl` ≈ 30–50 s buffer,
  60 MB byte cap, 1.5 s to start / 3 s after rebuffer (tune with data); SurfaceView only.
- Release builds only for measurement: R8 full mode, `debuggable=false`, profile installed.
