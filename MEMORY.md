# MEMORY: Bolbol TV project

Living memory for this project. Update it at the end of every session or milestone.
Newest entries go at the top of each section. Dates are absolute (YYYY-MM-DD).

## ▶ Current status
- **v1.1.0 released 2026-10-11** (versionCode 2; GitHub release; APK `dist/mehrnaz-tv-v1.1.0.apk` sent to the user). v1.0.0 before it.
- **Next:** whatever the user reports from the TV; 📺 items (quality-switch measurement on the TV, perf vs the official app,
  Watch Next on PatchWall). Any update: bump versionCode/versionName, DB migrations (no in-place schema edits), `tools/release.sh`.
- **Keep in mind:** the signing key is in `~/.bolboltv/` (back it up); losing it means the user must uninstall to update.

## User profile & preferences
- Persian speaker; communicates in English (voice-typed, so expect transcription noise:
  "cloud.md" means CLAUDE.md).
- Owns a Xiaomi Mi TV P1 43" (ELA4584EU, 2021). This app is for **private, personal use only**.
- Has a Bolboljan account (username/password). They will type the credentials on the TV;
  don't ask for them in chat.
- Wants: maximum speed/efficiency, the best player engine, a fully TV-native UI, and for Claude to
  check the UI with screenshots and drive it remotely.
- Wants token economy: simple tasks go to Sonnet/Haiku, complex ones to Opus. Claude acts as project
  manager. Never more than 10 agents at once.
- Wants CLAUDE.md as a router and this file as the learning log.
- Wants `TODO.md` kept current and a short report after every task or change.

## Decisions (see docs/decisions.md for full rationale)
- 2026-10-11: UI in **Persian RTL** (user choice).
- 2026-10-11: **No profiles**, a single shared watch history (user choice; could be added later).
- 2026-10-11: Scope includes **account login + watch-list/favorites sync, Android TV home row,
  voice search, phone/QR remote** (user choice).
- 2026-10-11: Stack is Kotlin, Compose for TV (tv-material 1.1.0), **Media3 ExoPlayer 1.11.x**
  (SurfaceView), Room, Hilt, Retrofit + kotlinx.serialization, Coil 3. libVLC/mpv rejected
  (heavy, worse RTL subtitles); FFmpeg extension skipped (H.264 + AAC needs no extra decoder).
- 2026-10-11: Progress is stored **locally** (`mapi/v1` has no progress endpoint). Key = `postId:season:episode`. Optional website sync via tracker.php later (M3.8).

## Feedback log
- 2026-10-11: (fa) Add playback speed, slower and up to 2×, then publish → v1.2-1 / v1.2.0. Decision (M): speed is kept for the app session, not saved to disk.
- 2026-10-11: "The video lag resolves after a few seconds, don't bother too much; improve only if there's room." v1.1-2 fix already shipped; no more work on it unless the TV shows a problem.
- 2026-10-11: (fa) Remove the «زودتر/دیرتر ۵ ثانیه» subtitle-sync buttons; 0.5 s steps are enough (v1.1-6). Supersedes the earlier "5 s steps" wish.
- 2026-10-11: "Go ahead for implementation and production, assign tasks to agents": v1.1 started with 4 parallel agents.
- 2026-10-11: Plan-only additions to v1.1 (do not implement until asked): an **in-player episodes/seasons button** for series
  (pick another episode while watching) and a **«رفتن به صفحهٔ فیلم/سریال»** option in the Continue Watching long-press menu.
- 2026-10-11: **The user installed v1.0.0 on the real Mi TV by themselves; "everything's working pretty well."** Next wishes
  (do not implement until asked, they're in TODO v1.1): previous/next episode buttons in the player; a long stall when
  switching to a higher quality (lower then back up fixes it); 3 more subtitle colours. The user said "don't do anything now,
  only add to the plan": respect plan-only requests strictly.
- 2026-10-11: (fa) "Some films also have dubbing and quality selection, watch out for these." Led to a real bug: details can lack links, so streams now come from `post/player-links` (like the official app), with dub blocks per episode.
- 2026-10-11: (fa) The app's name and logo must differ from the original site: **rebrand to «مهرناز» (Mehrnaz)**. Logo = sun («مهر» = love/sun) + play. applicationId `app.mehrnaz.tv` (Kotlin packages stay `app.bolbol.tv.*` internally). The dedication "Made with ❤ for Mehi by Rasoul" stays.
- 2026-10-11: (fa) Subtitle sync must be open-ended: 0.5 s steps but any amount, e.g. ±50 s → ±120 s, plus 5 s steps and hold-to-repeat.
- 2026-10-11: The user wants a GitHub release + the APK file sent to them, "100% working on my Mi TV": verify installability carefully before release.
- 2026-10-11: User asked for a **profile section for login/logout** and the visible line **"Made with love for Mehi by Rasoul"** (personal dedication; keep it on Home + About in every future redesign).
- 2026-10-11: "Do the rest and check the app is working properly": a full QA pass (search, UI, pages, bookmarking/listing, etc.) before calling it done.
- 2026-10-11: Login: the user couldn't type into the password field after Tab (the field required OK to start editing). Fixed: typing/Tab/Enter work like a normal form. Lesson: test text-input flows with a hardware keyboard (emulator) AND the remote. The user then logged in successfully.
- 2026-10-11: The user gave permission to download and analyse the official TV APK ("please download yourself"). Done locally in the scratchpad; the APK and decompiled code are NOT committed, only the facts (docs/auth.md §0).
- 2026-10-11: "Use a few more agents, you have a limit of 10 in parallel": scaled to 9 parallel agents. The real constraint is RAM (16 GB, about 5 GB free), so builds go through the `tools/gw.sh` queue (2 slots, no daemon).
- 2026-10-11: "Let's consider the subtitle and its settings, because this app has subtitles with movies": subtitles are a core feature. Wrote docs/subtitles.md: own engine (timing offset, bidi, styling) + settings in the player and on a Settings page. Added as M1.10.
- 2026-10-11: The search keyboard **must support both Persian and English** (user requirement).
- 2026-10-11: The user has no TV access for now, so we use the Android TV emulator; performance numbers wait for the real TV. They asked for a **private GitHub repo** (approved pushing to it).
- 2026-10-11: The user found the technical instructions confusing ("clearly guide me"). Give the user **plain-language, numbered, click-by-click steps** with exact commands; avoid jargon.
- 2026-10-11: The user asked for a TODO list (done / doing / to do) and **a report after every task or
  change**. Implemented as `TODO.md` + hard rule 10 in CLAUDE.md. Always follow it.
- 2026-10-11: The user confirmed the feasibility analysis and wants to proceed to planning with
  an agent team. No corrections so far.

## Progress log
- 2026-10-11: v1.1.0 released. 5 agents in worktrees (Opus: player episodes nav, quality switch; Sonnet: colours, CW menu, sync
  buttons); merges were conflict-free because each brief listed disjoint files (MainNavigation split by entry block).
  Lessons: (1) do the shared `core:model` contract change BEFORE spawning, so agents build against it; (2) test the update path
  by installing the signed release OVER the previous release with real history (resume survived); (3) QA on real series found a
  pre-existing data bug: the site sends `She\s` for "She's" in file URLs (404) → `mediaUrlOrNull` repair; (4) home focus starts
  on the Continue Watching row: one DPAD_DOWN skips it, so take the home screenshot before pressing keys.
- 2026-10-11: v1.0.0 released (Mehrnaz). QA pass + fix wave done; dubbing and quality switching verified on the Android 11 emulator; release APK v2+v3 signed with the permanent key.
- 2026-10-11: The official app's auth is confirmed: login form `username`/`password` → `data.token`; `Authorization: Bearer`; favorite-list (toggle `post_id`); watch-list with `current_time` in seconds (multipart). This enables cross-device progress sync. Mirrors playgen.ir and arzanycenter.ir also work.
- 2026-10-11: MILESTONE: end-to-end on the emulator. Home (real data) → details → 720p playback with Persian subs → resume «ادامه از ۱:۰۷» + Continue Watching row. CDN throughput from the dev machine is about 45 Mbit/s with 0.4 s to first byte; emulator TTFF of 15.5 s is the software decoder, not the network.
- 2026-10-11: Lesson: `gradlew --stop` kills ALL daemons machine-wide (it broke the manager's build). Banned; use tools/gw.sh (flock slots + --no-daemon) and the daemon idle timeout.
- 2026-10-11: Learned: the API's image URLs on euploadi.com/uuploadi.ir 404; images live on the CDN (majnoonbazar.ir, seo2024.ir). Fixed in core:network with host rewrite + failover.
- 2026-10-11: Learned: in RTL LazyRows, BringIntoViewSpec gets physical left-based offsets (positive = content moves left). The UI agent's StartPivot formula is correct; short rows clamp at the end (looks "stuck" in a 9-item gallery).
- 2026-10-11: M0 done (emulator). GitHub repo private at rasoulnorouzi/bolboljan-androidtv (gh logged in via keyring).
  SDK at ~/Android/Sdk via the new `android` CLI (`android --sdk=$HOME/Android/Sdk sdk install …`; no license prompt).
  compileSdk had to be 37 (AndroidX requires it). Haiku wrote tools/tv.sh (good quality). Hello screen verified by
  screenshots (RTL, shaping, bidi, focus). Emulator perf numbers are invalid (software rendering).
  Lesson: project agent types load only when a session starts in the project folder; use general-purpose + model meanwhile.
  Lesson: the user's `!`-launched interactive commands (gh auth login) spawned several tabs; for interactive steps,
  launch the command myself with run_in_terminal and tell the user exactly what to press.
- 2026-10-11: Java 21 installed (user). Android cmdline-tools in `~/Android/Sdk` (new "Android CLI" 1.0; sdkmanager still works but is deprecated). gh 2.102.0 in `~/.local/bin`. Local git repo, first commit 490b59d.
- 2026-10-11: Created the project folder. Feasibility study done (`docs/feasibility-analysis.md`):
  public JSON API `/wp-json/mapi/v1`, seekable MP4, VTT subtitles. Researched the device and the
  UI/player options. Wrote CLAUDE.md, MEMORY.md, docs/plan.md, decisions, design system,
  perf budget, device notes, and agent definitions.

## Open questions / to verify
- The website has a logged-in "continue watching" strip via `majnoonbazar.ir/tracker.php` (found 2026-10-11 in the site JS). Could we sync TV progress with it after login? (plan task 3.8)
- Is the static search index `wp-content/uploads/search-data.json` small enough for instant on-device search? (task 2.4)
- Does the TV expose network ADB without a USB session? (try `adb connect IP:5555` first)
- Will Xiaomi's PatchWall launcher show the Watch Next row, or only the stock Android TV home?
- Login/login-code/watch-list request formats: inspect the website's JS first. Decompile the
  official TV APK only if the user permits downloading it.
- How does the website's "play on TV" (`POST /post/tv`) reach the TV app: polling or push?
