# TODO: Bolbol TV

The single source of truth for task status. Updated after **every** task or change, and each
update is followed by a short report to the user (format at the bottom).
Task numbers match `docs/plan.md`. Owner: M = manager (Opus) · PE = player-engineer (Opus) ·
UI = tv-ui-engineer (Sonnet) · DE = data-engineer (Sonnet) · QA = qa-device (Sonnet) ·
PA = perf-analyst (Sonnet) · R = researcher (Sonnet) · C = chores (Haiku) · U = you (the user)

Last updated: 2026-10-11 (M0 done on the emulator; TV-only items pending)
📺 = needs the real TV (waits until the TV is available; everything else uses the emulator)

---

## ✅ Done
- [x] 2026-10-11 · Create the project folder `bolboljan-androidtv/` (M)
- [x] 2026-10-11 · Feasibility analysis: found the public JSON API, seekable MP4, VTT subtitles → `docs/feasibility-analysis.md` (M)
- [x] 2026-10-11 · Research the target TV: Mi TV P1 43", MT9611, 2 GB RAM, Android 10 → `docs/device.md` (R)
- [x] 2026-10-11 · Research streaming-app UI, player engines and perf tooling → `docs/decisions.md`, `docs/design-system.md` (R + M)
- [x] 2026-10-11 · API reference → `docs/api.md` (DE, reviewed by M)
- [x] 2026-10-11 · Verify the website's continue-watching service (`tracker.php`) and add it to the plan (task 3.8) (M)
- [x] 2026-10-11 · Project OS: `CLAUDE.md` (router + rules), `MEMORY.md`, `docs/plan.md`, `docs/perf-budget.md`, 7 agent definitions in `.claude/agents/` (M)
- [x] 2026-10-11 · This TODO list + the report-after-every-change rule (M)
- [x] 2026-10-11 · Java 21 installed (U)
- [x] 2026-10-11 · Android command-line tools downloaded to `~/Android/Sdk` (M)
- [x] 2026-10-11 · GitHub CLI installed to `~/.local/bin/gh` (M)
- [x] 2026-10-11 · Local git repository created, first commit `490b59d` (M)
- [x] 2026-10-11 · Private GitHub repo created and pushed: github.com/rasoulnorouzi/bolboljan-androidtv (M)
- [x] 2026-10-11 · 0.1 SDK packages installed: platform-tools, emulator, build-tools 36.1, platforms 36 + 37, Android TV images API 29 + 34 (M)
- [x] 2026-10-11 · 0.1b Virtual TVs created: `BolbolTV_API29` (copy of your TV: Android 10, 2 GB, 4 cores, 1080p) + `TV_API34` (M)
- [x] 2026-10-11 · 0.2 Gradle skeleton: AGP 9.4.1, Gradle 9.8.1, Kotlin 2.4.21, `:app` + `:core:designsystem`, R8 release, recipe in `docs/modules.md` (M)
- [x] 2026-10-11 · 0.3 `tools/tv.sh` written by the Haiku agent and reviewed by M; all subcommands except `trace` tested on the emulator (C + M)
- [x] 2026-10-11 · 0.5 Hello-world Persian RTL app on the virtual TV: right-to-left order, Persian shaping, mixed text and remote focus verified with screenshots (M)
- [x] 2026-10-11 · Requirement recorded: search keyboard in **both Persian and English** (U)

## 🔄 In progress / waiting
- [x] 2026-10-11 · M1 approved by the user ("go for a real watch… do a final product")
- [x] 2026-10-11 · M1.0 Shared contract `:core:model` (domain types, repository interfaces, stream picking; 4 tests pass) + buildable skeletons for all M1 modules (M)
- [ ] 🔄 M1.1 + 1.2 Data layer: network, database, repositories (DE, Sonnet), in progress
- [ ] 🔄 M1.3 Design system components + Vazirmatn font + gallery (UI, Sonnet), in progress
- [ ] 🔄 M1.4 + 1.7 Player core + player screen (PE, Opus), in progress
- [ ] Decision 2026-10-11: **no TV access for now → develop on the Android TV emulator.** Tasks marked 📺 need the real TV and wait until it's available.

## 📋 To do

### M0: Foundations
- [ ] 0.4 📺 Connect to the TV over Wi-Fi ADB + install scrcpy; fill in "Verified on device" in `docs/device.md` (U + M)
- [ ] 0.5b 📺 Repeat the hello-world check on the real TV
- [ ] 0.7 Verify `tools/tv.sh trace` (Perfetto) once there is real UI to trace (M1)
- [ ] 0.6 📺 Measure the official app as the baseline → `perf/reports/official-baseline.md` (PA)

### M1: Watchable MVP
- [ ] 1.1 Network layer: DTOs, lenient JSON, mirror failover, HTTP cache + tests (DE)
- [ ] 1.2 Room database: progress, history, prefs, row cache (DE)
- [ ] 1.3 Design system components + gallery screenshot (UI)
- [ ] 1.4 Player core: ExoPlayer, LoadControl, VTT subtitles, progress saving, metrics (PE)
- [ ] 1.5 Home screen: top bar, hero, rows, Continue Watching, instant paint from cache (UI)
- [ ] 1.6 Movie details: Resume/Play, dub/sub + quality choice (UI)
- [ ] 1.7 Player screen: overlay, fast D-pad seeking, resume prompt (PE + UI)
- [ ] 1.8 Baseline Profile + release build (M); 📺 measure the gain on the TV
- [ ] 1.9 Review gate: code review + QA pass (emulator) + 📺 perf report `perf/reports/M1.md` (M, QA, PA)

### M2: Series experience + search
- [ ] 2.1 Series details: season tabs, episode list with progress, jump to next unwatched (UI + DE)
- [ ] 2.2 Auto-next episode with countdown (PE)
- [ ] 2.3 Remember dub/sub, quality, subtitle size (DE)
- [ ] 2.4 Search: TV keyboard with **both Persian (فارسی) and English layouts + a switch key** (user requirement), instant typeahead (evaluate `search-data.json`), recent searches (UI + DE)
- [ ] 2.5 Category / "view all" pages with paging (UI + DE)
- [ ] 2.6 Mark watched/unwatched, remove from Continue Watching (DE + UI)

### M3: Integrations
- [ ] 3.1 Work out the login/auth flow from the website JS (APK only with your permission) (R → M)
- [ ] 3.2 Login screen + pairing code; secure token storage (DE + UI)
- [ ] 3.3 Watch-list & favourites sync (DE)
- [ ] 3.4 Voice search with the remote's mic (UI)
- [ ] 3.5 Phone/QR remote for typing (DE + UI)
- [ ] 3.6 📺 "Continue Watching" row on the Android TV home screen (DE)
- [ ] 3.7 (if feasible) Receive the website's "play on TV" (R + DE)
- [ ] 3.8 (if feasible) Sync progress with the website's continue-watching (R → DE)

### M4: Performance hardening and polish
- [ ] 4.1 📺 Full benchmark suite + traces of the slowest flows (PA)
- [ ] 4.2 Fix the top regressions until every budget passes (PE / UI)
- [ ] 4.3 📺 Memory/leak pass over a 30-minute session (PA + PE)
- [ ] 4.4 Error states: offline, mirror down, broken stream, expired login (UI)
- [ ] 4.5 App icon, TV banner, splash, final visual polish (UI)
- [ ] 4.6 📺 Final report: our app vs the official app (PA → M)

---

## Report format (sent to the user after every task or change)
```
Report: <task id + title>   (<date>)
Status: ✅ done | ⚠️ partly done | ❌ blocked
What I did:   1–3 lines
What changed: files / features
Verified by:  tests, screenshots (paths), measurements vs budget, or "not verified, because …"
Problems / decisions for you: … (or "none")
Next: <next task id + title>
```
