# Plan: Bolbol TV

**Goal:** the fastest, most TV-native way to watch the Bolboljan catalogue on the user's Xiaomi Mi TV
P1 43", with Continue Watching, resume and auto-next built in. The app must beat the official app on
startup time, scroll smoothness, and time-to-first-frame (see `perf-budget.md`).

Agent legend: **M** = manager (Opus, main session) · **PE** = player-engineer (Opus) ·
**UI** = tv-ui-engineer (Sonnet) · **DE** = data-engineer (Sonnet) · **QA** = qa-device (Sonnet) ·
**PA** = perf-analyst (Sonnet) · **R** = researcher (Sonnet) · **C** = chores (Haiku)

## Architecture
```
app/                  Application, MainActivity (single activity), nav graph, Hilt wiring
core/model            domain models: TitleSummary, Title, Season, Episode, StreamOption, Progress
core/network          Retrofit + OkHttp + kotlinx.serialization, MirrorInterceptor (failover), DTOs → domain
core/database         Room: progress, history, prefs, home-row cache, my-list
core/designsystem     theme tokens, Vazirmatn, RTL, PosterCard, focus modifiers, shimmer
core/player           ExoPlayer factory, LoadControl, subtitle style, ProgressTracker, PlaybackStats
feature/home          top bar, hero (static backdrop), rows, Continue Watching
feature/details       movie/series details, seasons tabs + episode list with progress
feature/playback      player screen + overlay, quality/audio/subtitle picker, auto-next
feature/search        TV keyboard, voice search, phone/QR remote (tiny LAN HTTP server)
feature/account       login (password + login-code), watch-list/favourites sync
feature/tvhome        Watch Next publishing (androidx.tvprovider)
baselineprofile/      Baseline + Startup profile generator (runs on an API 33+ TV emulator)
benchmark/            Macrobenchmark: startup, home scroll, open details, time-to-first-frame
tools/tv.sh           adb wrapper: connect/install/shot/key/perf/trace/logs
```
Patterns: single-activity app with Compose Navigation; MVVM with immutable `UiState`;
`StateFlow` collected with `collectAsStateWithLifecycle`; repository layer with an in-memory +
Room cache (stale-while-revalidate for home rows, so the home screen paints instantly from cache).

## M0: Foundations (target: "hello TV" + full tooling loop)
| # | Task | Agent | Acceptance |
|---|---|---|---|
| 0.1 | Install JDK 21, Android cmdline-tools, platform-tools, build-tools, API 36 + **Android TV API 34 system image**, emulator, scrcpy | M (+ user's sudo) | `adb version`, `sdkmanager --list_installed` OK |
| 0.2 | `git init`, `.gitignore`, Gradle version catalog, multi-module skeleton, Kotlin 2.x, AGP 8.x, R8, minSdk 29 / targetSdk 36 | M | `./gradlew assembleDebug` green |
| 0.3 | `tools/tv.sh` (connect, install, shot, key, text, logs, perf, trace) | C (+ M review) | each subcommand works on the emulator |
| 0.4 | Enable developer options + ADB debugging on the TV; `adb connect IP:5555` | user + M | `adb devices` lists the TV |
| 0.5 | Hello-world Compose for TV app installed on the TV; screenshot taken | QA | screenshot in `shots/` shows RTL Persian text |
| 0.6 | Measure the **official app** baseline (startup, memory, frame stats) for comparison | PA | `perf/reports/official-baseline.md` |

## M1: Watchable MVP
| # | Task | Agent | Acceptance |
|---|---|---|---|
| 1.1 | `core/model` + `core/network`: DTOs, lenient JSON, map-or-array serializers, mirror failover, OkHttp disk cache | DE | unit tests parse every `research/webapp/api/*.json`; failover test |
| 1.2 | `core/database`: Room entities (progress, history, prefs, row cache), DAOs, migrations | DE | DAO tests |
| 1.3 | `core/designsystem`: tokens, typography (Vazirmatn subset), PosterCard, focus scale via `graphicsLayer`, RTL | UI | screenshot of a component gallery screen |
| 1.4 | `core/player`: ExoPlayer singleton factory, tuned `DefaultLoadControl`, SurfaceView, side-loaded VTT, Persian caption style, `ProgressTracker` (save every 10 s + pause/stop), PlaybackStats → debug overlay | **PE** | plays the sample movie on the TV; TTFF logged; resume after app kill works |
| 1.5 | `feature/home`: top bar, static hero, lazy rows from `post/all`, Continue Watching row from Room, cached first paint | UI | budget: cold start ≤ 1.5 s to first rows from cache; no dropped frames on D-pad scroll (gfxinfo) |
| 1.6 | `feature/details` (movie): backdrop, metadata, **Resume/Play** focused by default, audio (dub/sub) + quality choice | UI | screenshots; Resume label shows the saved time |
| 1.7 | `feature/playback`: overlay (auto-hide), D-pad seek ±10 s with acceleration, scrubbing mode, resume prompt | PE + UI | seek latency within budget; screenshots of the overlay |
| 1.10 | **Subtitles first-class** (`docs/subtitles.md`): own VTT engine with timing offset, Persian bidi, styling (size/colour/box/position/bold), in-player menu + Settings page with live preview | PE + DE + UI | parser tests on real files; screenshots of each style; offset works |
| 1.8 | Baseline Profile + Startup Profile generated on the TV emulator; release build | M | Macrobenchmark shows the improvement vs no profile on the real TV |
| 1.9 | Review gate: code review + device QA pass + perf report | M, QA, PA | `perf/reports/M1.md` within budget |

## M2: Series experience + search
| # | Task | Agent | Acceptance |
|---|---|---|---|
| 2.1 | Series details: season tabs + episode list with progress bars/watched ticks; opens at the next unwatched episode | UI + DE | screenshots; logic unit-tested |
| 2.2 | Auto-next: countdown card near the end, "next episode" button, honours remembered audio/quality | PE | plays S01E01 → E02 hands-free |
| 2.3 | Remembered preferences per title + global (dub/sub, quality, subtitle size) | DE | prefs persist across restarts |
| 2.4 | Search: on-screen keyboard with **both Persian and English layouts** (switch key; remembers the last layout; user requirement), debounced query, results grid, recent searches. Evaluate the site's static `search-data.json` index (size/freshness) for instant local typeahead; fall back to `/post/search` | UI + DE | search "friends" works with the remote only; results < 300 ms |
| 2.5 | Category pages (movies/series/cartoons/docs, "view all" from rows) with paging | UI + DE | infinite scroll with no jank |
| 2.6 | Mark watched/unwatched, remove from Continue Watching | DE + UI | long-press menu works |

## M3: Integrations
| # | Task | Agent | Acceptance |
|---|---|---|---|
| 3.1 | Reverse-engineer auth: website sign-in JS for `/login`, `/login-code`, `X-Token`. Decompile the official TV APK only with the user's permission | R → M | `docs/api.md` auth section complete |
| 3.2 | `feature/account`: login screen (user types credentials on the TV) + login-code pairing; token in encrypted storage | DE + UI | user logs in successfully on the TV |
| 3.3 | Watch-list & favourites sync (two-way, offline-tolerant) | DE | items added on the website appear on the TV |
| 3.4 | Voice search via `RecognizerIntent` (fa-IR first, en fallback) + mic button | UI | spoken query fills the search |
| 3.5 | Phone/QR remote: NanoHTTPD on the LAN only while search is open, random token in the QR | DE + UI | typing on the phone appears on the TV |
| 3.6 | Watch Next row via `androidx.tvprovider` (Continue Watching + next episode) | DE | visible on the stock Android TV home; PatchWall behaviour documented |
| 3.7 | (if feasible) receive the website's "play on TV" (`/post/tv`) | R + DE | depends on 3.1 findings |
| 3.8 | (if feasible) sync progress with the website's logged-in "continue watching" (`majnoonbazar.ir/tracker.php`, see `api.md` caveat). Room stays the source of truth | R → DE | something watched on the TV shows on the website, and vice versa |

## M4: Performance hardening and polish
| # | Task | Agent | Acceptance |
|---|---|---|---|
| 4.1 | Full Macrobenchmark suite on the TV; Perfetto traces of the 3 slowest flows | PA | `perf/reports/M4.md` |
| 4.2 | Fix the top regressions found (recomposition, image sizes, main-thread I/O) | PE / UI | every budget met |
| 4.3 | Memory: `dumpsys meminfo` under a 30-minute session, LeakCanary in debug, trim on `onTrimMemory` | PA + PE | PSS within budget, no leaks |
| 4.4 | Error states: offline, mirror down, 404 stream, expired token. Friendly Persian messages + retry | UI | screenshots of each state |
| 4.5 | App icon/banner (320×180 TV banner), splash, final visual polish pass | UI | screenshots |
| 4.6 | Final comparison report: our app vs the official app | PA → M | published to the user |

## v1.1: from the user's first days on the real TV (✅ released as v1.1.0 on 2026-10-11)
| # | Task | Agent | Acceptance |
|---|---|---|---|
| v1.1-1 | Player «قسمت قبل» / «قسمت بعد» buttons (+ MEDIA_PREVIOUS/NEXT keys) for series | PE + UI | switching saves progress, works at season boundaries, hidden for movies |
| v1.1-2 | Fix the stall when switching to a higher quality (measure on the TV first; see TODO for causes and candidate fixes) | PA → PE | 720→1080 shows a picture within 3 s on the TV |
| v1.1-3 | 3 more subtitle colours (cyan, light green, pink, final after preview) | UI + DE | preview + player; DB migration v1→v2 tested |
| v1.1-4 | In-player «قسمت‌ها» button: side panel with seasons + episodes (progress, ✓, dub badge) while playing; pick → switch in place | PE + UI | switch saves progress; current episode focused; Back closes panel |
| v1.1-6 | Subtitle sync: remove the 5 s buttons (0.5 s steps + hold-to-repeat are enough; user request) | UI | panel shows only 0.5 s + reset |
| v1.1-5 | Continue Watching long-press menu: «رفتن به صفحهٔ فیلم/سریال» → details (series: that season + episode focused) | UI | option present for movies and series; focus lands on the episode |

## Later: «فایل‌های من», play movies and series from a USB stick (⏸ postponed by the user 2026-10-14; planned 2026-10-12)
**Goal:** the user plugs a USB stick with their own movies and series into the TV and watches them inside the app, with
the same player, subtitle styling and sync, speed, resume, Continue Watching, auto-next and episodes panel as online titles.

**Folder rules** (no renaming needed; pure-Kotlin detector with unit tests over many naming styles):
- Each folder at the stick's root is a title. A folder with one video, or a video directly at the root, is a **movie**.
- A folder with videos directly inside is a **series, season 1**. A folder with sub-folders is a **series**; each
  sub-folder is a season, numbered from its name (`Season 2`, `S02`, `فصل ۲`, `2`), else name order.
- Episode number from `S01E03`, `E03`, `Episode 3`, `قسمت ۳` or a leading number, else **natural** name order (ep2 < ep10).
- Skip `sample` files, very short clips and hidden/system files. Example the user has: `Island Stories/Season 1/E01.mkv`.
- Poster: `poster.jpg` / `folder.jpg` in the folder → else a best-effort match against the catalog → else a text card.

**How it fits the existing code:**
- Local titles get **negative ids** (a new `local_title` table), so `progress`, `title_snapshot`, `prefs` and
  `subtitle_offset` (all keyed by `postId`) work unchanged. A local key = USB volume UUID + relative path, so
  re-plugging or another port keeps progress (renaming a folder starts that title fresh).
- **DB v1 → v2**, additive only: `local_title` + `local_episode` tables, a tested `Migration`, new schema JSON (hard rule 11).
- Player: `DefaultDataSource` wrapping the OkHttp factory (file/content URIs), a smaller buffer for local files.
- Access: `MANAGE_EXTERNAL_STORAGE`, granted once with `tools/tv.sh` (appops over ADB) or TV Settings; Storage Access
  Framework folder picker as the fallback (often missing/awkward on TVs). Drives found with `StorageManager`, refreshed on plug-in.

**Subtitles and audio (all three cases the user has):** none → «زیرنویس ندارد»; sidecar `.srt` (`E02.srt`, `E02.fa.srt`,
`E02.en.srt`) with a new SRT parser + Windows-1256 detection; **embedded soft subtitles** in MKV/MP4 (SRT/ASS/mov_text) take
their text from ExoPlayer and draw it with **our own renderer**, so style/sync settings apply (ASS styling dropped, text kept);
image subtitles (PGS/VobSub) shown as they are. One menu lists off + sidecar + embedded tracks by language, Persian first,
choice remembered per title. MKV audio tracks («دوبله فارسی» / «زبان اصلی») get a picker, remembered per title.
DTS/other audio: add the Media3 FFmpeg audio extension only if the user's files need it (docs/decisions.md §Player revisit-if).

**Continue Watching: build both, the user picks on the TV:** (A) one shared «ادامه تماشا» row with a USB badge on local
cards; (B) a separate «ادامه از فلش» row under it. Settings switch «ادامه تماشا: یکجا / جدا» for the trial; after the user
chooses, remove the other option and the switch. A local card whose stick is unplugged stays and says «فلش "…" را وصل کنید».
Local titles also go to the Android TV Watch Next row.

| # | Task | Agent | Acceptance |
|---|---|---|---|
| usb-1 | `core:model` contract: local title/episode types, `LocalMediaRepository`, negative-id rules (before spawning) | M | builds; agents code against it |
| usb-2 | USB scan (off the main thread) + folder/season/episode detector + DB v1→v2 (2 tables) + repository | DE | detector tests on many naming styles; migration test keeps history + My List |
| usb-3 | Local playback: data source, local buffer, SRT parser + encoding detection, sidecar loading, embedded text subs via our renderer, image subs, subtitle + audio menus, FFmpeg audio ext. if needed | PE | `.srt` (UTF-8 and 1256), embedded MKV/MP4 subs and dual-audio MKV play on the emulator; unit tests for parser/encoding |
| usb-4 | «فایل‌های من» entry on home, title grid, series page (seasons + episodes with progress/✓) and movie page, reusing current components | UI | screenshots: RTL, focus, empty "no USB" state |
| usb-5 | Continue Watching option A + option B + Settings switch, unplugged-stick message, Watch Next for local titles | UI + M | screenshots of both options sent to the user |
| usb-6 | Wiring (routing negative ids to local pages), `tools/tv.sh` access grant, emulator QA with a virtual USB shaped like the user's, release | M + QA | install over v1.2.0 with history kept; 📺 then on the TV |

📺 **Check on the real TV:** USB format (FAT32 / exFAT / NTFS), whether Xiaomi shows the "all files access" setting,
AC3/E-AC3/DTS playback from the user's own files, 4K HEVC from USB vs `docs/perf-budget.md`.

## Out of scope (for now)
Profiles, downloads/offline playback (16 GB storage), trailer autoplay, seek-preview thumbnails
(the server provides no sprites; generating them on a 2 GB TV is too costly), Chromecast, Play Store.

## How a typical task flows
1. M picks the next task from this file and writes a brief (goal, modules, docs, acceptance, verify).
2. The agent implements it in its own worktree/module and runs unit tests.
3. QA installs it on the TV, drives the flow with `tools/tv.sh key …`, and screenshots it. PA measures if the task is perf-relevant.
4. M reviews the diff and screenshots, merges, and updates `MEMORY.md` (progress + any user feedback).
