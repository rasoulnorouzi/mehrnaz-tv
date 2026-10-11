# Bolbol TV: private Android TV client for Bolboljan

Kotlin + Jetpack Compose for TV + Media3 ExoPlayer app, sideloaded on one **Xiaomi Mi TV P1 43"
(ELA4584EU, MT9611, 2 GB RAM, Android TV 10 / API 29)**. Persian RTL UI. **Private use only.**
The main session (Opus) is the **project manager**. It delegates to the agents in `.claude/agents/`.

## Router: read this first, then only what your task needs

| If you are… | Read |
|---|---|
| Starting any session | `TODO.md` (done / in progress / to do), then `MEMORY.md` (decisions, user feedback) |
| Finishing any task or change | update `TODO.md`, add to `MEMORY.md` §Progress log, then **send the user a report** (format at the bottom of `TODO.md`) |
| Planning or picking the next task | `docs/plan.md` (milestones, task list, acceptance criteria) |
| Calling or modelling the backend API | `docs/api.md`, then sample JSON in `research/webapp/api/*.json` |
| Building or changing any screen/component | `docs/design-system.md` (tokens, layout, focus, RTL, screen specs) |
| Working on playback or buffering | `docs/decisions.md` §Player, `core/player/README.md` |
| Working on subtitles (engine, styling, sync, settings) | `docs/subtitles.md` |
| Checking speed, memory, jank, or benchmarks | `docs/perf-budget.md` (budgets + measurement commands), `tools/tv.sh` |
| Device quirks (codecs, ADB, launcher, RAM) | `docs/device.md` |
| Why something was chosen (or to change a choice) | `docs/decisions.md`. Add a new dated entry; never silently reverse |
| Delegating work to a sub-agent | `.claude/agents/*.md` + §Agent rules below |
| Adding a module / Gradle or version questions | `docs/modules.md`, `gradle/libs.versions.toml` |
| Installing, screenshotting, or driving the TV | `tools/tv.sh help`, `docs/device.md` §ADB |
| Background (why this project exists) | `docs/feasibility-analysis.md` |

## Hard rules
1. **Performance is the #1 feature.** Every change must respect `docs/perf-budget.md`. A UI or
   player change is not "done" until it has been measured on the real TV (or flagged as unmeasured).
2. **Verify visually.** After any UI change: install on the TV (or the emulator if the TV is
   offline), screenshot the affected screens with `tools/tv.sh shot`, look at them, and check focus
   and RTL before reporting done.
3. **RTL Persian everywhere.** `LayoutDirection.Rtl`, Vazirmatn font, use start/end (never left/right),
   Persian digits in UI chrome, mirrored D-pad semantics. Latin titles stay readable (bidi-safe).
4. **2 GB RAM device.** No autoplaying trailers, no runtime blur (doesn't work on API 29 anyway),
   images decoded at display size only, one ExoPlayer instance at a time, no work on the main thread.
5. **Secrets.** Never put credentials in the repo, chat, logs, or docs. The user types them on the TV.
   For tests, read `local.properties`/`.env` (gitignored); never echo the values.
6. **Private app.** Don't publish to Play or any store. Don't hammer the API: cache, paginate lazily,
   and send no parallel bulk crawling.
7. **Mirrors rotate.** Never hard-code one host in feature code; go through `core/network`'s mirror list.
8. **Module boundaries.** `feature/*` depend on `core/*` only, never on each other. Domain models live
   in `core/model`; DTOs never leak out of `core/network`.
9. **Docs stay true.** If you change behaviour that a doc describes, update that doc in the same
   change. Log decisions in `docs/decisions.md` and progress/feedback in `MEMORY.md`.
10. **Track + report.** Every task or change: move it in `TODO.md` (To do → In progress → Done, dated),
    then send the user a report in the `TODO.md` format. No silent changes. Agents report to the
    manager; the manager reports to the user.
11. **Git.** Small commits per task with conventional messages (`feat(player): …`). Commit only when
    the user has authorised it for the session. Never push anywhere (no remote).

## Agent rules (token economy)
- **≤ 10 agents running at once** (normally 3–5). The manager tracks who is running.
- Model by difficulty (pinned in each agent file):
  - **Opus:** architecture, player engine, focus/perf debugging, final reviews
  - **Sonnet:** screens from spec, data layer, tests, device QA, perf runs, research
  - **Haiku:** lint/format, dependency bumps, doc tidying, lookups
- Each task brief must state: goal, files/modules it may touch, docs to read (link the router
  rows), acceptance criteria, and how to verify. No vague "improve X".
- Parallel agents get **disjoint modules** (use git worktrees when writing code in parallel).
  The manager reviews every diff before it is merged.
- Agents return short reports (≤150 words + file list). Long output goes into files, not chat.
- **Builds:** always `tools/gw.sh <tasks>` (queue: max 2 concurrent builds machine-wide, no lingering daemon,
  creates local.properties in worktrees). **Never `gradlew --stop`**: it kills every agent's build.
- **Emulator:** only the manager uses it (memory). Agents verify with unit tests + builds; the manager
  screenshots after merging.
- **Shared files** (settings.gradle.kts, root build, version catalog, core:model, designsystem when another
  agent owns it): only the manager edits them; agents ask in their report.
- The agent types in `.claude/agents/` load only when the session starts **inside this folder**.
  Otherwise use `general-purpose` with the same `model` and paste the role file's instructions into the brief.

## Commands (ANDROID_HOME defaults to ~/Android/Sdk)
```bash
./gradlew :app:assembleRelease          # optimised build (R8 + baseline profile)
tools/tv.sh emu                         # start the virtual TV (BolbolTV_API29) if no device
tools/tv.sh connect <ip>                # adb connect to the real TV over Wi-Fi
tools/tv.sh install                     # build + install on the TV
tools/tv.sh shot <name>                 # screenshot → shots/<date>/<name>.png
tools/tv.sh key DPAD_RIGHT [n]          # drive the UI with the remote's keys
tools/tv.sh perf                        # startup + frames + memory report → perf/reports/
./gradlew :benchmark:connectedBenchmarkAndroidTest   # Macrobenchmark on the TV
```

## Status
Task status: `TODO.md`. Current milestone and blockers: the top of `MEMORY.md`.
