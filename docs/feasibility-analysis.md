# Bolboljan Android TV client: feasibility analysis

Date: 2026-10-11
Verdict: **Feasible, low technical risk.** The service has a clean, unauthenticated JSON API with direct, seekable MP4 streams and WebVTT subtitles. A custom Android TV app with full "continue watching" and resume support can be built without reverse-engineering the official APK.

---

## 1. What bolboljan.co actually is

- `bolboljan.co` is only a download landing page (static HTML behind ArvanCloud). It links to:
  - Android phone APK: `pagers.top/emkanat/Bolboljan.V5.3.0.apk` (6.8 MB, Nov 2025)
  - Android TV APK: `pagers.top/emkanat/bolbol-tv.apk` (11.1 MB, Mar 2026)
  - iOS / macOS: a PWA ("Add to Home Screen") hosted on rotating mirror domains
- The real backend is a **WordPress 7.1 site** (theme `Orlando7`) served from several mirror domains that all return the same content:
  - `euploadi.com` (nginx, direct)
  - `uuploadi.ir` (ArvanCloud)
  - `seo2024.ir` (used in permalinks)
  - subtitles credit `bolboljan.net`
- Mirror pages cloak themselves (non-Apple user agents get a fake "403" or are redirected to aws.amazon.com/s3). Domains rotate, so **the app must not hard-code one host**.

The official APKs were not downloaded or decompiled; it wasn't necessary.

## 2. The API (`/wp-json/mapi/v1`)

A custom "mobile API" namespace, discovered through the public WordPress REST index (`/wp-json/`). This is almost certainly what the official apps use.

### Public endpoints (verified, no login needed)

| Endpoint | Purpose | Notes |
|---|---|---|
| `GET /post/all` | Home screen | 14 rows (newest movies, top 250, Iranian, Turkish, genres, newest series, suggestions…), each with a `view_all` URL |
| `GET /post/movies?page=N` | Movie list | 12/page, ~2,337 pages (~28k titles) |
| `GET /post/series?page=N` | Series list | ~859 pages (~10k titles) |
| `GET /post/cartoons`, `/post/documentaries`, `/post/suggestions` | Category lists | same paging shape |
| `GET /post/search?search=TEXT` | Search | parameter is `search` |
| `GET /post/{id}` | Full details | posters, backdrop, IMDb/Metacritic, plot (fa), cast, genres, `download_links`, `player_links`, `related_posts` |
| `GET /post/{id}/season/{s}` | Season episodes | grouped by quality |
| `GET /post/{id}/season/{s}/episode/{e}` | One episode | all qualities |
| `GET /actor/{id}/posts`, `/director/{id}/posts` | Filmography | |
| `GET /post/{id}/comments` | Comments | |

### Auth endpoints (not tested; need an account)

`POST /login`, `POST /login-code` (probably OTP / TV pairing code), `GET /user/profile`, `GET|POST /user/watch-list`, `GET|POST /user/favorite-list`, `POST /post/tv` (website "play on TV" button: sends a link to the logged-in TV app; uses an `X-Token` header).

**The server has no watch-progress endpoint.** That explains why the official app can't remember where you stopped. A new client has to store progress itself, which is easy (section 4).

### Media

- Movie `player_links` give one entry per quality (1080/720/480) and per audio type (`sub` = original + Persian subtitle, `dub` = Persian dub).
- Streams are **progressive MP4 (H.264)** on CDN hosts such as `dl20.acenteri.ir`, `dl2.ecenteri.ir`, `dl1.hostikadeh.ir`.
- Verified `Accept-Ranges: bytes` / `206 Partial Content`, so **seeking and resuming at any timestamp works**.
- Subtitles are separate **WebVTT** files (`fasub_link`, sometimes `ensub_link`). Media3/ExoPlayer can side-load these natively.
- Trailers are also direct links.

## 3. Why the current TV app falls short (per your report)

- No watch history and no resume position (the backend doesn't store it, and the app doesn't do it locally)
- Weak TV/D-pad UI

Both can be fixed on the client side.

## 4. Proposed app

**Stack (standard modern Android TV):**
- Kotlin + **Jetpack Compose for TV** (`androidx.tv:tv-material`) for D-pad focus, rows and immersive backdrops
- **Media3 ExoPlayer** for MP4 playback, side-loaded VTT subtitles, and subtitle styling (size, background, colour)
- **Room** (SQLite) for local watch history and progress
- Retrofit/OkHttp + kotlinx.serialization for the API; Coil for posters (`.webp`)
- Full RTL layout with the Vazirmatn font

**Features that fix the actual pain points:**
1. **Continue Watching** row at the top of the home screen (movies and series), sorted by last watched
2. **Resume prompt**: "Resume from 47:12 / Start over". Position is saved every ~10 s and on pause, stop or back.
3. **Per-episode progress bars** and watched ticks in the season/episode grid. Opening a series jumps to the next unwatched episode.
4. **Auto-play next episode** with a countdown and a "skip intro"-style +85 s button
5. **Remembers your preferences** per title and globally: dub vs. subtitle, quality, and subtitle size
6. Local "My List" (optional sync with the site's watch-list after login)
7. Search with the on-screen keyboard and voice (Android TV speech input)
8. Android TV home-screen integration: a "Watch Next" channel, so continue-watching shows up on the launcher itself
9. **Configurable/auto-failover base URL** (try `euploadi.com`, then `uuploadi.ir`, …) to survive domain rotation

**Key for resume tracking:** `postId + season + episode` (not the URL), so progress survives CDN or link changes.

## 5. Effort estimate

| Milestone | Scope | Rough effort |
|---|---|---|
| M1: MVP | Home rows, details, player (quality/dub/sub picker), local resume + Continue Watching | ~1–2 weeks |
| M2: Series UX | Season/episode grid with progress, auto-next, preference memory, search | +1 week |
| M3: Polish | Watch Next channel, mirror failover, subtitle styling, optional login/watch-list sync | +1 week |

You sideload the APK onto the TV (`adb install` or a USB/Downloader app). No Play Store needed.

## 6. Risks and caveats

| Risk | Level | Mitigation |
|---|---|---|
| API is unofficial/undocumented and may change or require a token later | Medium | Thin API layer, defensive JSON parsing; the official apps depend on the same API, so breaking changes are unlikely to be silent |
| Domain rotation / blocking | Medium | Mirror list + failover, user-editable base URL |
| CDN hosts are `.ir`; availability/speed from outside Iran may vary | Low–Med | Worked from this machine; offer a lower-quality fallback |
| Legal: the site redistributes films/series it does not appear to license | ⚠️ Your call | Keep it a **personal-use, private client**. Don't publish it on Google Play or distribute it. The app only plays links the site already serves publicly. |
| `X-Frame-Options: DENY` etc. | None | Irrelevant: we use the JSON API, not the web pages |

## 7. Cheaper alternatives (for comparison)

| Option | Effort | Resume/history | UI quality |
|---|---|---|---|
| **A. Native Android TV app (recommended)** | 2–4 weeks | Full, purpose-built | Best, tailored to Persian/RTL |
| B. Kodi add-on (Python) using the same API | 2–4 days | Kodi tracks resume points + watched flags automatically | Kodi skin; Persian RTL rendering is acceptable, not perfect |
| C. Keep the official app + external player (VLC/Just Player remember position per file) | 0 | Partial (per file only, no "continue watching" list) | Unchanged. Only works if the official app offers "open with" |

## 8. Recommendation

Go with **Option A**. The API gives everything the UI needs (metadata, artwork, multi-quality seekable streams, subtitles) without login, and the missing feature (progress) is purely client-side. If you want something working this week, **Option B (Kodi add-on)** is a fast stopgap that reuses the same API research.

### Suggested next steps
1. Confirm the target device (Android TV OS version / Google TV / Fire TV?). This sets `minSdk`.
2. Scaffold the Android project in this folder (`app/`), build M1, and sideload it for testing.
3. Optionally create a site account to test the `login-code` (TV pairing) and watch-list sync endpoints.

---

### Evidence (saved in `../research/`)
- `home.html`, `headers.txt`: landing page
- `webapp/wpjson.json`: full REST route index
- `webapp/api/*.json`: sample responses (home, movies, series, suggestions, one movie, one series)
- `webapp/links.txt`: sample stream + subtitle URLs (verified 206/range support)
