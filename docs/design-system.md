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
   - Top bar (as built): جستجو · خانه · فیلم · سریال · لیست من · پروفایل · تنظیمات (7 tabs; the bar remembers the last focused tab). On its far end (left in RTL), outside the tabs, a non-focusable dedication line **"Made with ❤ for Mehi by Rasoul"** (`DedicationLine`: LTR, Label 12 sp, secondary text, vector heart #FF6B81); the line is measured first and the tabs take the remaining width, so they never overlap. The same line sits under the version in Settings → درباره.
   - Hero area (top 45 %) shows the focused item's backdrop + title + meta.
   - Rows: **ادامه تماشا (Continue Watching)** first, then the API sections (newest movies, top 250, Iranian, Turkish, …, newest series, suggestions).
   - Paints from cache instantly, then refreshes.
   - Every server row ends with a poster-sized **«مشاهده همه»** card → the Browse grid for that row.
   - **Continue Watching card: long-press OK or MENU** → popup «ادامه پخش» · «رفتن به صفحهٔ فیلم» / «رفتن به صفحهٔ سریال» (opens the details page; for a series, with that episode's season selected and that episode focused once) · «علامت‌گذاری به‌عنوان دیده‌شده» · «حذف از ادامه تماشا» · «انصراف». Progress bars show at least 3 % fill once started.
2. **Details**
   - Backdrop + scrim. Title (fa + en), year · runtime · age · IMDb · genres, plot (3 lines, expandable).
   - Buttons: **ادامه از ۴۷:۱۲ / پخش**, start over, audio chips (دوبله / زیرنویس), quality, my list.
   - **«+ لیست من» / «✓ در لیست من»** toggle next to the play buttons (local list; synced with the site's favourites when logged in).
   - Movie: cast row and related row.
   - Series (as built: two panes, info on the start side, episodes on the end side): season chips + vertical episode list with progress/✓; primary button «ادامه فصل X قسمت Y» / «شروع …»; **long-press an episode toggles watched**. Opened from Continue Watching's «رفتن به صفحهٔ سریال» the details select that episode's season and focus the episode row once (not again after returning from the player); a missing season/episode falls back to the default focus.
3. **Player**
   - Full-screen SurfaceView. Overlay on any key, auto-hides after 4 s.
   - Overlay: title/episode, seek bar with time (Persian digits), play/pause, ±10 s (D-pad, accelerating
     when held), audio/subtitle/quality menu, subtitle size, next episode.
   - Resume dialog on start: "ادامه از ۴۷:۱۲" (focused) / "از ابتدا".
   - Next-episode card at the last ~30 s / credits with a 10 s countdown.
   - Controls row (RTL, start → end): movies «پخش/توقف» · «تنظیمات پخش»; **series** «قسمت بعد» · «پخش/توقف» ·
     «قسمت قبل» · «قسمت‌ها» · «تنظیمات پخش» (v1.1-1/v1.1-4; «قسمت بعد» moved to the start/right in v1.4-7, user
     request). «قسمت بعد/قبل» are always drawn for a series (the row never shifts) and **disabled** at the last / first
     episode; their ⏭/⏮ glyphs point outward, away from play/pause (in RTL «قسمت بعد» points right, «قسمت قبل» left,
     each on the outer side of its label), unlike play/pause.
     The remote's **MEDIA_PREVIOUS / MEDIA_NEXT** do the same. A manual switch saves this episode's position
     (not marked completed), replaces the player entry (one ExoPlayer; Back still returns to details) and the new
     episode opens with the resume prompt if it has saved progress. Auto-next (card) keeps: completed + start at 0.
   - **«قسمت‌ها» panel** (series): the same end-edge side panel as «تنظیمات پخش» (`PANEL_WIDTH` 400 dp, solid
     surface, subtitles re-centre beside it) while the video keeps playing. Title «قسمت‌ها», season chips
     («فصل ۱ · فصل ۲ …», only with >1 season; otherwise a «فصل ۱» label), then the season's episodes in a
     LazyColumn (no images): «قسمت ۳» + title (generic "Episode 3" titles hidden), «دوبله» pill when a dubbed
     stream exists, ✓ when watched, a 3 dp progress bar when started. The playing episode has a lighter fill,
     an accent marker at the start edge and «در حال پخش»; its season is preselected and it **has focus on open**.
     OK on it closes the panel; OK on another episode switches to it. Opens from the button or with **DOWN on
     the controls' button row** (so DOWN, DOWN from the bare video). The next-episode countdown is frozen while
     it is open.
   - **«تنظیمات پخش» panel** (v1.2-1 added speed): «صدا» · «کیفیت» · **«سرعت پخش»** · «زیرنویس» sections, all with the
     same chips (accent fill + dot when selected). The speed chips are equal-width in two rows of three, slowest
     first (RTL: «۰٫۵×» at the start/right): «۰٫۵× · ۰٫۷۵× · عادی» / «۱٫۲۵× · ۱٫۵× · ۲×». Voice pitch stays natural. Each
     pick shows the toast «سرعت پخش: ۱٫۵×» (or «عادی»), and the controls' status text becomes «۷۲۰ · زیرنویس · ۱٫۵×»
     (nothing extra at normal speed). The speed lasts for the app session (next episode, auto-next, quality/audio
     switches keep it) but is not saved: «عادی» again after the app restarts.
   - **BACK order:** side panel (settings or episodes; focus returns to the button that opened it) → controls →
     next-episode card → exit. From the resume dialog or the error panel BACK exits at once.
4. **Search:** keyboard (start side) + results grid (end side) + mic button + "type on your phone" QR.
5. **Category / View all:** a 5-column grid of PosterCards, paged (next page within the last 2 rows), with quick-filter chips for the Movies/Series tabs: «همه» · «۲۵۰ برتر IMDb» (+ «ایرانی», «ترکی» for movies; country ids are per taxonomy). "View all" pages have no chips.
6. **Profile and «لیست من»** (two top-bar tabs; login/logout lives only in Profile):
   - **«پروفایل»:** *Signed out:* title «پروفایل» + «با ورود به حساب، لیست من و ادامهٔ تماشا با سایت و گوشی همگام می‌شود» at the start, the login card at the end (username/email + password, «نمایش رمز», «ورود»); fields accept typing directly from a hardware keyboard, Tab/Enter moves to the password, Enter logs in; OK on a field opens the TV keyboard. Focus starts on the username field. *Signed in:* a profile card (circular avatar with the first letter of the name on the accent colour, display name, «نام کاربری», «ایمیل» (LTR), VIP pill «اشتراک ویژه: ۲۳ روز مانده» / «بدون اشتراک ویژه»), buttons **«همگام‌سازی دوباره»** (forces a sync, ignoring the 10-minute throttle; then «همگام شد ✓» or «همگام‌سازی ناموفق بود») and **«خروج از حساب»**, and the line «لیست و تاریخچهٔ تماشا روی این تلویزیون می‌ماند». Focus starts on «همگام‌سازی دوباره», also right after a login. Logout asks in a small dialog «از حساب خارج می‌شوید؟» with «بله، خروج» / «انصراف» (**«انصراف» focused**, Back cancels); «بله» signs out and the login card returns. Local My List and watch progress stay on the device.
   - **«لیست من»:** only the My List grid under a «لیست من» header with the count (5 columns; empty: «لیست شما خالی است»). While signed out a slim hint row «برای همگام‌سازی با سایت، از بخش پروفایل وارد شوید» with a «پروفایل» button sits under the header. Focus starts on the first poster (or the hint's button when the list is empty and signed out).
7. **Settings:** login (credentials or pairing code), mirror selection, default audio/quality,
   subtitle size, show-performance toggle, clear cache, app version.
8. **Errors:** offline, server unreachable (tries the next mirror automatically), stream failed (try
   another quality) — always with a focused retry button.

## Focus rules
- Every screen declares its initial focus. Back returns focus to where the user came from (save/restore focus requesters per row).
- Rows remember the last focused column. Moving up/down lands on the remembered item, not index 0.
- Never trap focus. Overlay menus restore focus on close.
