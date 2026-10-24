# TODO: Bolbol TV

The single source of truth for task status. Updated after **every** task or change, and each
update is followed by a short report to the user (format at the bottom).
Task numbers match `docs/plan.md`. Owner: M = manager (Opus) · PE = player-engineer (Opus) ·
UI = tv-ui-engineer (Sonnet) · DE = data-engineer (Sonnet) · QA = qa-device (Sonnet) ·
PA = perf-analyst (Sonnet) · R = researcher (Sonnet) · C = chores (Haiku) · U = you (the user)

Last updated: 2026-10-25 (**v1.5.0 released**: search finds new titles by itself) · 2026-10-14 (**v1.4.0 released**: in-app update, offline saving, genres, search keyboard + prediction, phone-remote typing bug, player prev/next swap, details episode box; USB files postponed) · 2026-10-12 (**v1.3 «فایل‌های من» (USB playback) planned**, not started) · 2026-10-11 (**v1.2.0 released**: playback speed; before that **v1.1.0**: prev/next, episodes panel, CW «go to page», faster quality switch, 3 colours, 0.5 s sync only)
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
- [x] 2026-10-11 · **v1.2.0 RELEASED: playback speed** (user request, fa): «سرعت پخش» ۰٫۵× · ۰٫۷۵× · عادی · ۱٫۲۵× · ۱٫۵× · ۲× in
  «تنظیمات پخش» (2 rows), natural pitch, shown in the controls' status line, kept for the app session (no DB change). Sonnet agent +
  M review/QA (2× measured on the emulator at 480p; chips de-stretched). GitHub release v1.2.0, APK sent (M)
- [x] 2026-10-11 · **v1.1.0 RELEASED** (user: "go ahead for implementation and production"): 5 agents in parallel worktrees
  (2 Opus, 3 Sonnet), all merged and reviewed by M; signed APK installed **over v1.0.0** on the Android 11 emulator with history
  kept; GitHub release v1.1.0, APK sent to the user (M)
  - v1.1-1 + v1.1-4 «قسمت قبل/بعد» (+ ⏮/⏭) and the in-player «قسمت‌ها» panel (PE). Screens: shots/2026-10-11/v110-controls, -k2
  - v1.1-2 quality switch: keyframe start, separate media client (6 s/10 s timeouts, fast retry), 8 s watchdog, host pre-warm,
    «در حال تغییر کیفیت…» over the last frame (PE + M wiring). Emulator 720→1080: first frame 2.25 s. Report perf/reports/v1.1-quality-switch.md.
    User 2026-10-11: "the lag resolves after a few seconds, don't bother too much" → no further work unless the TV shows a problem.
  - v1.1-3 colours فیروزه‌ای / سبز روشن / صورتی (stored by name: no DB migration needed, schema v1 unchanged) (DE + UI)
  - v1.1-5 CW menu «رفتن به صفحهٔ فیلم/سریال», series opens with that episode focused (UI)
  - v1.1-6 subtitle sync 0.5 s steps only (user request) (UI)
  - Found in QA (M): episode URLs with an apostrophe came as `She\s` (404) → repaired in core/network (`mediaUrlOrNull`), ⏮/⏭
    also work on the resume prompt / error panel, switch label centred beside the open panel
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
  - [ ] 🔄 User request: **«پروفایل» tab** (login / profile info / VIP / logout with confirmation) + **"Made with ❤ for Mehi by Rasoul"** on Home and About (UI, Sonnet)
- [x] 2026-10-11 · User request: subtitle sync open-ended, ±120 s, 0.5 s + 5 s steps, hold-to-repeat, «همگام‌سازی: +۱۲٫۵ ثانیه» (PE)
- [x] 2026-10-11 · User request: **rebrand to «مهرناز» (Mehrnaz)**: sun + play logo, banner, adaptive icon, launch screen, applicationId `app.mehrnaz.tv`, deep link `mehrnaz://`; no user-visible «بلبل» left (UI + M)
- [x] 2026-10-11 · User request: **«پروفایل» tab** (login, full profile, VIP, «همگام‌سازی دوباره» with real result, logout with confirmation) + **"Made with ❤ for Mehi by Rasoul"** on Home + About (UI)
- [x] 2026-10-11 · User request: **dubbing + quality everywhere**: streams from `post/player-links`, object-keyed series blocks, per-episode dub (DE); verified on the **Android 11** emulator: movie دوبله↔زیرنویس switch keeps position, quality ۱۰۸۰/۷۲۰/۴۸۰, dubbed series ep 1 offers both, ep 5 subtitle-only (M)
- [x] 2026-10-11 · QA pass of the release build (R8 clean, 9/12 pass, 7 bugs) → `qa/2026-10-11-full-pass.md` (QA)
- [x] 2026-10-11 · TV is **Android 11** (user). Created `MiTV_API30` emulator; fresh install of the signed release APK ✅, appears in the TV app list as «مهرناز» with banner ✅ (M)
- [x] 2026-10-11 · Release pipeline: permanent key (~/.bolboltv, off-repo), `tools/release.sh` (zipalign + v1/v2/v3), APK checks ✅ (M)
- [x] 2026-10-11 · Fix wave merged: QA-01 My List focus, QA-02 player Back (first Back closes panel ✅ verified), QA-03 synced progress «ادامه از …» (data + home + details), QA-04 hero fallback, QA-05 view-all focus, QA-06 top scrim, QA-07 bidi (UI/DE/PE + M)
- [x] 2026-10-11 · **User installed v1.0.0 on the real Mi TV (Android 11) and reports "everything's working pretty well"** (U)
- [x] 2026-10-11 · **v1.0.0 RELEASED**: final signed APK (5.2 MB, app.mehrnaz.tv), fresh install + home + playback verified on the Android 11 emulator, GitHub release https://github.com/rasoulnorouzi/bolboljan-androidtv/releases/tag/v1.0.0, APK sent to the user (M)
- [ ] Decision 2026-10-11: **no TV access for now → develop on the Android TV emulator.** Tasks marked 📺 need the real TV and wait until it's available.

## 📋 To do

### v1.5: search finds new titles by itself (🔄 2026-10-24, user report: "Carrie 2026" missing)
- [x] 2026-10-24 · Cause: API `post/search` misses new titles by Latin name (empty `normalized_title`); lists are fresh (M)
- [x] 2026-10-24 · Fix: on-device copy of the site's daily `search-data.json` (4.2 MB gz, 304 when unchanged, every 12 h,
  checked at start + every 6 h), local search + predictions, API fallback; mirrors learn domain moves from redirects;
  tests (network streaming/304/failover/redirect-learning, index refresh/ranking/fallback) (M)
- [x] 2026-10-25 · v1.5.0 QA on a fresh Android 11 TV emulator (MiTV_API30_QA; the old one no longer trusts the PC adb key): v1.4.0 search "carrie" has no 2026 series (bug reproduced) → v1.5.0 over it finds «کری / Carrie 2026» first, "dustfall" too, opens and plays with subtitles; released (M)

### v1.4: update in-app, offline saving, genres, search, fixes (🔄 started 2026-10-14; spec docs/plan.md §v1.4)
- [x] 2026-10-14 · v1.4-0 Contract (DownloadRepository, UpdateRepository, Genre) + skeleton modules core:download, core:update, feature:downloads (M)
- [x] 2026-10-14 · v1.4-1 Download engine (own resumable writer, one at a time, foreground service) + DB v1→v2 (tested migration) + offline playback; v1.4-7 «قسمت بعد» on the right (PE). Emulator: 480p episode saved in ~1 min, played with Wi-Fi off with saved subtitles, deleted
- [x] 2026-10-14 · v1.4-2 Downloads UI (details «دانلود» + quality dialog with sizes/free space, red «حذف دانلود» with confirm, per-episode control, «دانلودها» tab + screen with «حذف»/«حذف همه», offline-start button) and v1.4-3 episode box that scrolls inside itself. Merged; awaiting engine wiring + device QA (UI)
- [x] 2026-10-14 · v1.4-4 Genres: 23 movie + 17 series genres (ids from the site's genre pages, spot-checked; no API endpoint exists), one chip row «همه · ۲۵۰ برتر · … | ژانرها». Merged; tests pass; device QA pending (DE)
- [x] 2026-10-14 · v1.4-5 Search keyboard (44 dp keys, prediction row from recents + a local title index; site index is 28.7 MB so not used) and v1.4-6 phone-remote fix (root cause: the QR panel was a Dialog that closed on the first text and stopped the phone server). Merged; tests pass; device QA pending (UI + M)
- [x] 2026-10-14 · v1.4-8 In-app update: core:update (daily check, SHA-256, PackageInstaller, one-time permission), Settings › «به‌روزرسانی» (only there, user request), release.sh `--publish` + tools/serve-update.sh. Merged + wired; device QA pending (DE + M)
- [x] 2026-10-14 · v1.4-9 Wiring + full emulator QA on MiTV_API30 (M): v1.4.0 over v1.2.0 keeps history; in-app update v1.4.0→v1.4.1 test build works (after 2 fixes); downloads → offline play → delete; search predictions; phone remote 5 texts in a row; genre/quick chips. Fixed in QA: install-result receiver, update-page focus, startup crash on a bad update URL, dev URL field focus trap, top bar «ت..», Latin digits, browse initial focus, free space after delete. Screens: shots/2026-10-14/
- [x] 2026-10-14 · **v1.4.0 RELEASED**: code pushed to rasoulnorouzi/mehrnaz-tv, GitHub release v1.4.0 with APK + update.json; `releases/latest/download/update.json` verified, APK checksum matches (M)

### v1.3 «فایل‌های من»: USB movies and series (⏸ postponed by the user 2026-10-14; planned 2026-10-12)
Full spec: `docs/plan.md` §v1.3. User's folders: `Show/Season N/episodes` or `Show/episodes`; files are MKV and MP4,
with no subtitle, a sidecar `.srt`, or embedded soft subtitles.
- [ ] v1.3-1 `core:model` contract for local titles (negative ids) + `LocalMediaRepository` (M)
- [ ] v1.3-2 USB scan + series/season/episode detector + DB v1→v2 (2 new tables, tested migration) (DE)
- [ ] v1.3-3 Local playback: `.srt` + Persian encoding detection, embedded subs drawn by our renderer, subtitle + audio menus, FFmpeg audio ext. if needed (PE)
- [ ] v1.3-4 «فایل‌های من» screens: entry on home, grid, series + movie pages (UI)
- [ ] v1.3-5 Continue Watching **both options** (A: one shared row with USB badge, B: separate «ادامه از فلش» row) + Settings switch; user picks on the TV, then remove the loser (UI + M)
- [ ] v1.3-6 Wiring, `tools/tv.sh` USB access grant, emulator QA with a fake USB, signed release over v1.2.0 (M + QA)
- [ ] 📺 v1.3-7 On the TV: USB format, "all files access" setting, AC3/DTS audio, 4K HEVC from USB (U + PA)

### v1.x ideas / follow-ups
- [ ] 📺 Check 2× at 1080p on the real TV (the emulator's software decoder can't; the MT9611 hardware decoder should) (PA)
- [ ] 📺 Measure the quality switch on the real TV (`adb logcat -s BolbolPerf BolbolSwitch`, goal firstFrame ≤ 3 s) (PA)
- [ ] Remove the now-unused `SubtitleOffset.LARGE_STEP_MS` (+ its test) in core/player (C)
- [ ] Regenerate the Baseline Profile so the new player panels are covered (M)

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
