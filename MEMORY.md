# MEMORY: Bolbol TV project

Living memory for this project. Update it at the end of every session or milestone.
Newest entries go at the top of each section. Dates are absolute (YYYY-MM-DD).

## ▶ Current status
- **Milestone:** M0 done on the emulator (TV-only items 0.4/0.5b/0.6 wait for the TV). M1 next, pending the user's go-ahead.
- **Next task:** M1.1 network layer (DE) + M1.3 design system (UI) + M1.4 player core (PE), in parallel on disjoint modules.
- **Blockers:** none. **No TV access for now**: develop on the emulator; 📺 tasks wait.

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
- 2026-10-11: The search keyboard **must support both Persian and English** (user requirement).
- 2026-10-11: The user has no TV access for now, so we use the Android TV emulator; performance numbers wait for the real TV. They asked for a **private GitHub repo** (approved pushing to it).
- 2026-10-11: The user found the technical instructions confusing ("clearly guide me"). Give the user **plain-language, numbered, click-by-click steps** with exact commands; avoid jargon.
- 2026-10-11: The user asked for a TODO list (done / doing / to do) and **a report after every task or
  change**. Implemented as `TODO.md` + hard rule 10 in CLAUDE.md. Always follow it.
- 2026-10-11: The user confirmed the feasibility analysis and wants to proceed to planning with
  an agent team. No corrections so far.

## Progress log
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
