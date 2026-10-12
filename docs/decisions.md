# Decisions (lightweight ADRs)

Format: date · decision · why · alternatives rejected · revisit-if. Append new entries; to reverse one,
add a new entry that references it.

---

## Player
**2026-10-11 · Media3 ExoPlayer 1.11.x as the only engine, rendering to a SurfaceView.**
- Why: it's what YouTube, Google TV, Plex (fork) and Stremio (default) use on Android TV. It has
  hardware MediaCodec decoding, native progressive-MP4-over-range support, side-loaded WebVTT
  (`MediaItem.SubtitleConfiguration`), and a tiny footprint (~0.3 MB). It has `PlaybackStatsListener`
  for metrics and scrubbing mode (1.8+) for fast D-pad seeking. SurfaceView gives the most efficient
  composition path and full-resolution video on TVs that upscale a 1080p UI.
- Rejected: **libVLC** (heavy native libs, a history of RTL subtitle bugs, 4.0 not stable);
  **mpv-android** (best subtitle typography via libass, but heavy and harder to integrate with
  Compose/TV); the **Media3 FFmpeg extension** (audio-only decoders; our streams are H.264+AAC).
  The **Compose `PlayerSurface`** isn't at parity yet, so we use `AndroidView(SurfaceView)`.
- Tuning: explicit `DefaultLoadControl` values (start ~1.5 s, rebuffer ~3 s, max ~40 s, 60 MB cap),
  `setScrubbingModeEnabled` while seeking, one player instance, released on stop.
- Subtitles: Persian VTT via the Media3 text pipeline, `CaptionStyleCompat` with the Vazirmatn
  typeface, explicit RTL alignment. Strip the site-credit first cue. Test cues that start with Latin text/digits.
- Revisit if: sources switch to MKV/HEVC/AC-3 (then add the FFmpeg audio extension) or ASS subtitles appear.

**2026-10-11 · Quality switch (v1.1-2): keyframe start, own media client, watchdog, pre-warm.** Evidence in
`perf/reports/v1.1-quality-switch.md`: a movie's qualities sit on different CDN hosts (1080p+480p vs 720p; cold DNS
1.7 s), every file's `moov` is 2-4.5 MB at the front, keyframes are 1-10 s apart, and `setMediaItem(item, pos)` ignores
`SeekParameters` (hidden decode from the previous keyframe).
- Switches start at the keyframe before the old position (`KeyframeStart` wrapper period, `PREVIOUS_SYNC` for the
  start only; ≤ 15 s back). Replays ≤ 10 s rather than skipping anything. `start()` is unchanged.
- Video uses `MediaHttp.mediaClient` = the shared client's `newBuilder()` (same pool) **without the disk cache** (the
  first, Range-less 200 response was being teed into the API cache) and with **connect 6 s / read 10 s**; Media3's retry
  policy with 0.2/0.5/1/2 s delays and fatal 4xx (except 408/429). Subtitles keep the cached shared client.
- Watchdog (`StreamSwitchPolicy`): no first frame 8 s after a switch and no bytes for 2 s → re-prepare once on fresh
  connections; again (or 20 s total) → error panel with «کیفیت پایین‌تر».
- Pre-warm: one `Range: bytes=0-0` request per other host after the first frame of `start()` (≤ 1/host/min).
- `switchingQuality` flow + `QualitySwitchIndicator` keep the last frame with «در حال تغییر کیفیت…».
- LoadControl unchanged (40 s of 1080p ≈ 20 MB, under the 60 MB cap). Revisit after the TV measurement (`BolbolSwitch` log).

## UI toolkit
**2026-10-11 · Jetpack Compose for TV (`androidx.tv:tv-material` 1.1.0, Foundation lazy lists).**
- Why: Google recommends it, and Leanback is deprecated ("use Compose for TV instead"). It has
  built-in TV focus handling and is fastest to build polished custom UI with.
- Risk: Compose stutters on the first passes on low-end TVs. Mitigations are mandatory: Baseline + Startup
  Profiles, R8 full mode, strong skipping (Kotlin ≥ 2.0.20), stable keys/contentType, focus effects in `graphicsLayer`.
- Rejected: Leanback (deprecated, rigid look); classic Views (slow to build, no advantage after profiles).
- Revisit if: after profiles, scroll jank stays above budget on the TV (fallback: RecyclerView rows in `AndroidView`).

## Data & storage
**2026-10-11 · Retrofit + OkHttp + kotlinx.serialization; Room; Hilt; Coil 3.**
- Lenient JSON (the API mixes maps/arrays, `"on"`/`""` flags, string numbers). DTOs stay inside `core/network`.
- Room stores watch progress (the server has none), history, preferences, home-row cache, my-list.
- Mirror failover inside an OkHttp interceptor (`euploadi.com` → `uuploadi.ir` → …), user-editable.

## Product
- **2026-10-11 · Persian RTL UI**, Vazirmatn font (user choice).
- **2026-10-11 · No profiles**, a single shared history (user choice).
- **2026-10-11 · No trailer autoplay, no runtime blur, no seek-preview thumbnails**: RAM/CPU cost on a 2 GB TV.
- **2026-10-11 · Watch Next via `androidx.tvprovider`**, not the Engage SDK (Engage requires Google
  allowlisting; tvprovider still works until about H2 2027 on Android TV home). Low priority: PatchWall may ignore it.
- **2026-10-11 · Phone/QR remote via NanoHTTPD** (tiny), LAN-only, active only while search is open, random token.
- **2026-10-11 · minSdk 29, targetSdk 36**: one known device; no need for older APIs.
- **2026-10-11 · Rebrand «بلبل تی‌وی» → «مهرناز» (Mehrnaz)** before the first release, so the app does not look like the website's own app (user request). New sun-with-play-triangle logo («مهر» = love and sun; accent `#FFC93C`), banner, adaptive icon and launch screen; `applicationId` `app.mehrnaz.tv`; deep-link scheme `mehrnaz://`. Kotlin packages/namespaces stay `app.bolbol.tv.*` (invisible to the user, large rename). The login card says «ورود به حساب کاربری سایت» (it is the user's account on the website). The subtitle credit-cue filters keep matching the site's name: they detect subtitle content, not our branding.

## Architecture (M1)
**2026-10-11 · Manual dependency injection instead of Hilt.**
- Why: one activity and a handful of repositories. A hand-written `AppGraph` in `:app` is trivially
  small, starts faster (no generated component graph, no extra KSP pass), and avoids Hilt/AGP 9
  compatibility risk. ViewModels get their dependencies through `viewModelFactory { initializer { … } }`.
- Revisit if: the graph grows past ~15 bindings or needs scoping that becomes painful.

**2026-10-11 · Navigation 3 (`androidx.navigation3` 1.2.0) in `:app`.** Feature modules expose screen
composables with callbacks (`onOpenTitle(id)`, `onPlay(request)`); only `:app` knows the back stack.

**2026-10-11 · Data layer split:** `:core:network` (Retrofit/DTOs/mappers/mirrors) and `:core:database`
(Room) are internal. `:core:data` implements the `core:model` repository interfaces on top of both.
Features see only `core:model` interfaces.

**2026-10-11 · Player timeline stays left-to-right inside the RTL UI.** DPAD_RIGHT = forward +10 s,
the seek bar fills left→right. This matches the remote's arrows and how media timelines work. The rest of
the player chrome (titles, menus) is RTL.

**2026-10-11 · Account session stored in plain private SharedPreferences, no Keystore.** The login token (and display
name, VIP days) is kept in `shared_prefs/account_session.xml` inside the app sandbox. Why: the app is private, sideloaded
on one TV, `allowBackup` is false, and the token only reaches the user's own favourites and watch positions on a movie
site; Keystore + DataStore (docs/auth.md 8.4's first recommendation) would add moving parts for no real gain on this
threat model. The password is never stored. Revisit if the app is ever shared or the token gains wider powers.

**2026-10-11 · "My List" = the site's `favorite-list`; server `watch-list` = continue watching with position**
(confirmed from the official TV app, docs/auth.md section 0). My List stays local-first in Room and is mirrored
best effort; progress is pushed on player exit and pulled at app start, only filling in what is missing or further.

## Accounts & sync (M3)
**2026-10-11 · Login = the official app's API** (confirmed by decompiling it with the user's permission, docs/auth.md §0):
form `username`/`password` → `data.token`; `Authorization: Bearer`. Token stored in app-private SharedPreferences
(`account_session.xml`): the TV app sandbox is the protection for this private app; the password is never stored or logged.
**«لیست من» = local Room list (source of truth) + best-effort two-way sync with the site's favourites** (toggle endpoint, so
membership is checked before toggling). **Progress sync:** push `current_time` (seconds) to the site's watch-list when the
player closes; pull once per app start, filling titles/episodes missing locally or further on the server.

## Local files (v1.3, planned)
**2026-10-12 · (planned) USB playback reuses the online pipeline via negative title ids.** Local titles get ids < 0 from a
new `local_title` table, so progress, title snapshots, prefs, subtitle offsets, Continue Watching, Watch Next, auto-next and
the episodes panel work without changes; a local file is keyed by USB volume UUID + relative path. DB v1 → v2 is additive
(2 tables + tested migration). Storage access = `MANAGE_EXTERNAL_STORAGE` (private sideloaded app; granted once via ADB
appops or TV Settings), SAF picker as fallback. Embedded text subtitles are drawn by our own renderer so the user's style and
sync apply. Continue Watching for local titles: shared row vs separate row, both built behind a Settings switch; the user picks.
- Rejected: a separate progress store for local files (duplicates every resume/CW path); `READ_EXTERNAL_STORAGE` + MediaStore
  only (on API 30 it can't see sidecar `.srt` files); VLC/Kodi side by side (loses our UI, subtitles and history).
- Revisit if: the TV doesn't allow all-files access, or the user wants LAN (PC/NAS) sources instead of USB.
