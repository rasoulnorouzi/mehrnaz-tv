# Design system & screen specs

Inspiration (2025–2026): Netflix TV (2025 redesign: top bar, richer tiles, the focused tile
expands with synopsis/runtime), Google TV (immersive list, safe margins, focus scale),
Disney+/Prime (season tabs + episode list, Skip Intro, next-episode countdown), Plex/Stremio
(Continue Watching first, clean player overlay). Everything is adapted for **RTL Persian** and a
**2 GB TV**: richness comes from typography, spacing and focus feedback, not from heavy effects.

## Foundations
- **Canvas:** 960×540 dp (1080p @ density 2). Overscan safe margins: **48 dp start/end, 27 dp top/bottom**.
- **Direction:** `CompositionLocalProvider(LocalLayoutDirection provides Rtl)` at the root. Rows flow
  right→left, and DPAD_LEFT moves "forward" in a row. Use start/end everywhere.
- **Type:** Vazirmatn (bundled, subset to Persian + Latin, weights 400/500/700). Scale: Display 36 sp ·
  Headline 24 · Title 18 · Body 14 · Label 12. Line height ≥ 1.4 for Persian. Persian digits in chrome
  (۱۲:۳۰); keep IMDb rates/years in Latin digits where they read as data (decide once, in `Formatters.kt`).
- **Colour (dark only):** background `#0B0D12`, surface `#151922`, surfaceHigh `#1E2430`,
  text `#F2F4F8` / secondary `#A9B0BD`, **accent `#FFC93C`** (Bolboljan's yellow), focus ring white,
  progress bar accent, error `#FF5A5F`. No transparency stacks over video except the overlay scrim.
- **Shape/elevation:** cards 8 dp radius; no shadows (use a 2 dp focus border + 1.08× scale instead).
- **Motion:** focus scale 150 ms ease-out; backdrop crossfade 250 ms, debounced 300 ms; no parallax.

## Components (core/designsystem)
- `PosterCard` (2:3, 140×210 dp): poster, top-start badges (دوبله / زیرنویس, IMDb), bottom progress bar
  if started. On focus: scale 1.08 + white border + title/year below.
- `WideCard` (16:9, 260×146 dp): for Continue Watching and episodes; progress bar + "S1·E3 · ۲۴ دقیقه مانده".
- `Row` = header (Title 18 sp) + `LazyRow` with `BringIntoViewSpec` keeping the focused item at the
  start edge (pivot ~ 0.1). Stable keys, contentType.
- `HeroBackdrop`: static image ≤ 1280×720, with a pre-baked gradient scrim (drawn once, no blur),
  debounced to the focused item.
- `TvButton` (primary yellow / secondary outline), `Chip` (audio/quality pickers), `ProgressBar`,
  `Shimmer` placeholder (solid colour blocks; no animated gradients on low-end).
- `TvKeyboard`: **Persian (فارسی) and English layouts, both required** (user requirement 2026-10-11). Has a visible switch key (فا / EN) and remembers the last-used layout. Digits row, space, backspace, clear. Grid of D-pad-friendly keys; long-press for alternates (e.g. ی/ي, ک/ك).

## Screens
1. **Home**
   - Top bar: جستجو · خانه · فیلم · سریال · کارتون · لیست من · تنظیمات.
   - Hero area (top 45 %) shows the focused item's backdrop + title + meta.
   - Rows: **ادامه تماشا (Continue Watching)** first, then the API sections (newest movies, top 250, Iranian, Turkish, …, newest series, suggestions).
   - Paints from cache instantly, then refreshes.
2. **Details**
   - Backdrop + scrim. Title (fa + en), year · runtime · age · IMDb · genres, plot (3 lines, expandable).
   - Buttons: **ادامه از ۴۷:۱۲ / پخش**, start over, audio chips (دوبله / زیرنویس), quality, my list.
   - Movie: cast row and related row.
   - Series: season tabs + vertical episode list (WideCard with progress); focus starts on the next unwatched episode.
3. **Player**
   - Full-screen SurfaceView. Overlay on any key, auto-hides after 4 s.
   - Overlay: title/episode, seek bar with time (Persian digits), play/pause, ±10 s (D-pad, accelerating
     when held), audio/subtitle/quality menu, subtitle size, next episode.
   - Resume dialog on start: "ادامه از ۴۷:۱۲" (focused) / "از ابتدا".
   - Next-episode card at the last ~30 s / credits with a 10 s countdown.
4. **Search:** keyboard (start side) + results grid (end side) + mic button + "type on your phone" QR.
5. **Category / View all:** a grid of PosterCards, paged, with filter chips (genre, year, dubbed).
6. **Account / Settings:** login (credentials or pairing code), mirror selection, default audio/quality,
   subtitle size, show-performance toggle, clear cache, app version.
7. **Errors:** offline, server unreachable (tries the next mirror automatically), stream failed (try
   another quality) — always with a focused retry button.

## Focus rules
- Every screen declares its initial focus. Back returns focus to where the user came from (save/restore focus requesters per row).
- Rows remember the last focused column. Moving up/down lands on the remembered item, not index 0.
- Never trap focus. Overlay menus restore focus on close.
