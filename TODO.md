# TODO: Bolbol TV

The single source of truth for task status. Updated after **every** task or change, and each
update is followed by a short report to the user (format at the bottom).
Task numbers match `docs/plan.md`. Owner: M = manager (Opus) · PE = player-engineer (Opus) ·
UI = tv-ui-engineer (Sonnet) · DE = data-engineer (Sonnet) · QA = qa-device (Sonnet) ·
PA = perf-analyst (Sonnet) · R = researcher (Sonnet) · C = chores (Haiku) · U = you (the user)

Last updated: 2026-10-11 (end-to-end flow works on the virtual TV)
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
- [x] 2026-10-11 · M1.1 + 1.2 Data layer merged: network (mirror failover, lenient JSON), Room, repositories; 165 unit tests (DE)
- [x] 2026-10-11 · Fix: image URLs rewritten to the image CDN (majnoonbazar.ir → seo2024.ir failover); the API's own hosts 404 for images (found on the emulator by M, fixed by DE)
- [x] 2026-10-11 · M1.3 Design system merged: Vazirmatn, PosterCard, WideCard, ContentRow, HeroBackdrop, buttons, chips, TopBar, states, gallery; verified with screenshots, RTL row scrolling confirmed correct (UI + M)
- [x] 2026-10-11 · M1.4 + 1.7 Player core + player screen merged: tuned ExoPlayer, resume prompt, LTR seek bar with accelerating D-pad seek, stream picker, diagnostics overlay, progress saving; 39 tests (PE)
- [x] 2026-10-11 · M1.10a Subtitle settings contract (M) + M1.10c storage of style and per-title sync offset; 53 tests (DE)
- [x] 2026-10-11 · App wiring: Application graph, image loader on the shared client, Navigation 3, player route (M)
- [x] 2026-10-11 · Build queue `tools/gw.sh` + skeletons for search/browse/settings/tvhome/lanremote + shared `SubtitleText` renderer (M)
- [x] 2026-10-11 · M2.5 Browse / category grids with paging + quick filters merged; 35 tests (UI)
- [x] 2026-10-11 · M4.5 App icon (adaptive), TV banner «بلبل تی‌وی» (Vazirmatn, rendered via Chromium/HarfBuzz), launch screen; launch logo removed after first frame to avoid overdraw (UI + M)
- [x] 2026-10-11 · M1.5 + 1.6 Home + Details (movies + series, two-pane episodes) merged; 57 tests (UI)
- [x] 2026-10-11 · M1.10d Settings screen (subtitle live preview, playback defaults, servers, developer, about) merged; 38 tests (UI)
- [x] 2026-10-11 · M3.6 Watch Next publisher + deep links merged, wired in the app (starts after first frame); 40 tests (DE + M)
- [x] 2026-10-11 · M3.5 Phone/QR remote module merged; 75 tests (DE)
- [x] 2026-10-11 · M3.1 Auth investigation: username/email + password (no OTP); docs/auth.md (R)
- [x] 2026-10-11 · **M1.11 End-to-end on the virtual TV ✅:** real home rows → details → play 720p with Persian subtitles → exit → «ادامه از ۱:۰۷» on details + «ادامه تماشا» row on Home (M). Screenshots: shots/2026-10-11/e2e-*.png
- [x] 2026-10-11 · M2.4 + 3.4 + 3.5b Search merged + wired with the phone/QR remote: Persian/English keyboard, half-space, voice (fa-IR/en-US), recents, paging; 79 tests. Verified on the emulator: typing «باز» with the D-pad returns live results (UI + M)
- [x] 2026-10-11 · Official TV app analysed (user permission): exact login (username/password → token), `Authorization: Bearer`, favourites = My List, watch-list = server progress in seconds; 2 more mirrors (playgen.ir, arzanycenter.ir) → docs/auth.md §0 (M)
- [x] 2026-10-11 · M1.10b + M2.2 Own subtitle engine (VTT parser, sync offset, Persian bidi/punctuation repair), in-player subtitle menu, auto-next episode; 91 tests (PE). Verified on the emulator: correct Persian punctuation + ZWNJ, menu works; fix by M: subtitles centred beside the open panel
- [x] 2026-10-11 · M1.12 + M2.6 QA fixes (details title ✅ verified, «مشاهده همه» cards wired, min progress fill) + Continue Watching menu (watched / remove) + episode watched toggle; 85 tests (UI)
- [x] 2026-10-11 · M1.8 / M4.1 Baseline-profile generator + Macrobenchmark modules (startup, home scroll, open details); TV GMD unsupported in AGP 9.4.1 → generate on the TV_API34 emulator (PA)
- [x] 2026-10-11 · Deep link `bolbol://play/…` verified on the emulator (M)
- [x] 2026-10-11 · Found: the movie video CDN (dl20.acenteri.ir, dl2.ecenteri.ir) was unreachable for a while from this machine; the app shows a Persian error with retry / lower quality. Series CDN fine (M)
- [x] 2026-10-11 · M3.2 + 3.3 + 3.8 Login (username/password → Bearer token), «لیست من» (local + site favourites sync), progress push on player exit / pull on app start; 118 tests (DE+UI); wired by M: «لیست من» top-bar tab, details «+ لیست من» button. Verified on the emulator: login screen, add to My List → appears in «لیست من · ۱ عنوان»
- [x] 2026-10-11 · M1.8b Baseline Profile generated on TV_API34: 24,938 rules + 21,849 startup rules, shipped in the APK (M). 📺 gain to be measured on the TV
- [x] 2026-10-11 · **Real login works** (user typed it on the virtual TV; session token stored, value never read) (U + M)
- [x] 2026-10-11 · Fix: login form keyboard. Hardware typing goes straight into a focused field; Tab/Enter jumps from username into the password field ready to type; Enter in the password field logs in (user feedback) (M)
- [ ] **Waiting on you (U):** after watching, check «لیست من» (site favourites) and whether the website/phone app shows the film in "continue watching" at the time you stopped
- **🔄 Running now (2 agents):**
  - [ ] 🔄 QA: full device pass of the release build on TV_API34 (search, UI, pages, bookmarking/My List, player, settings, back behaviour) → `qa/2026-10-11-full-pass.md` (QA, Sonnet)
  - [ ] 🔄 User request: **«پروفایل» tab** (login / profile info / VIP / logout with confirmation) + **"Made with ❤ for Mehi by Rasoul"** on Home and About (UI, Sonnet)
- [x] 2026-10-11 · User request: subtitle sync open-ended, ±120 s, 0.5 s + 5 s steps, hold-to-repeat, «همگام‌سازی: +۱۲٫۵ ثانیه» (PE)
- [x] 2026-10-11 · User request: **rebrand to «مهرناز» (Mehrnaz)**: sun + play logo, banner, adaptive icon, launch screen, applicationId `app.mehrnaz.tv`, deep link `mehrnaz://`; no user-visible «بلبل» left (UI + M)
- [x] 2026-10-11 · User request: **«پروفایل» tab** (login, full profile, VIP, «همگام‌سازی دوباره» with real result, logout with confirmation) + **"Made with ❤ for Mehi by Rasoul"** on Home + About (UI)
  - [ ] 🔄 User request: **dubbing + quality must work everywhere.** Found that details can have empty links and dubbed series use separate (shorter) dub blocks; fix: streams from `post/player-links` + dub-aware series mapping (DE, Sonnet)
- [ ] Next: fix wave from the QA report (agents per module) → re-test
- [ ] **Release v1.0.0** (user request): dedicated signing key (kept off GitHub), APK checks (minSdk 29, leanback, no native libs, signature), fresh-install test on the Android 10 virtual TV, GitHub release with the APK, send the APK + Mi TV install steps to the user (M)
- [ ] Decision 2026-10-11: **no TV access for now → develop on the Android TV emulator.** Tasks marked 📺 need the real TV and wait until it's available.

## 📋 To do

### M0: Foundations
- [ ] 0.4 📺 Connect to the TV over Wi-Fi ADB + install scrcpy; fill in "Verified on device" in `docs/device.md` (U + M)
- [ ] 0.5b 📺 Repeat the hello-world check on the real TV
- [ ] 0.7 Verify `tools/tv.sh trace` (Perfetto) once there is real UI to trace (M1)
- [ ] 0.6 📺 Measure the official app as the baseline → `perf/reports/official-baseline.md` (PA)

### M1: Watchable MVP
- [ ] 1.8c 📺 Measure the Baseline Profile gain on the TV (PA)
- [ ] 1.9 Review gate: code review + QA pass (emulator) + 📺 perf report `perf/reports/M1.md` (M, QA, PA)

### M2: Series experience + search
- [ ] 2.1 Series details: season tabs, episode list with progress, jump to next unwatched (UI + DE)
- [ ] 2.3 Remember dub/sub, quality, subtitle size (DE)
- [ ] 2.4b (optional) Instant local typeahead from the site's `search-data.json` index (DE)

### M3: Integrations
- [ ] 3.6b Check the Watch Next row on the emulator's home screen; 📺 PatchWall behaviour on the TV (M)
- [ ] 3.7 ~~Receive the website's "play on TV"~~: dropped, the official TV app doesn't use it either

### M4: Performance hardening and polish
- [ ] 4.1 📺 Full benchmark suite + traces of the slowest flows (PA)
- [ ] 4.2 Fix the top regressions until every budget passes (PE / UI)
- [ ] 4.3 📺 Memory/leak pass over a 30-minute session (PA + PE)
- [ ] 4.4 Error states: offline, mirror down, broken stream, expired login (UI)
- [ ] 4.5b Final visual polish pass (UI)
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
