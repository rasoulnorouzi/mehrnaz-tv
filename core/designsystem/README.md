# core:designsystem: component catalogue

Everything is in package `app.bolbol.tv.core.designsystem` (one import). Wrap the app in `BolbolTheme { }`
(forces RTL, dark colours, Vazirmatn). Specs: `docs/design-system.md`. Rules: stable `key` + `contentType` in lists,
no blur/shadow/infinite animation, images sized to the layout. Interactive parts are D-pad focusable with a white
2 dp border and a `graphicsLayer` scale (1.08 cards, 1.05 controls).

## Setup (once, in `Application`)
`SingletonImageLoader.setSafe { bolbolImageLoader(it) }`: 15 % heap memory cache, 64 MB disk cache, hardware bitmaps.

## Cards and rows
| Component | Use |
|---|---|
| `PosterCard(title, imageUrl, onClick, modifier, year, imdb, dubbed, subtitled, progress, alwaysShowCaption, onFocused)` | 2:3 poster 140x210 dp. Title + year appear below only while focused (space reserved). `progress` 0..1 draws the bar. |
| `PosterCard(item: TitleSummary, onClick, modifier, progress, alwaysShowCaption, onFocused)` | Same, from the domain model (Persian title preferred, year Latin). |
| `WideCard(title, imageUrl, onClick, modifier, subtitle, progress, onFocused)` | 16:9 260x146 dp, two always-visible text lines below. Continue Watching, episodes. |
| `WideCard(item: ContinueWatchingItem, onClick, modifier, onFocused)` | Builds "فصل ۲ · قسمت ۳ · ۲۴ دقیقه مانده" and the progress bar for you. |
| `ContentRow(title, modifier, state, contentPadding, itemSpacing, pivotFraction) { item(..) / items(..) }` | Header + `LazyRow`; focused item parked near the start edge (pivot 0.1); remembers the focused item (`focusRestorer`). |
| `ContentRow(title, items, key, modifier, contentType, state) { item -> .. }` | Typed variant, one composable per element. |
| `StartPivotBringIntoView(pivotFraction) { .. }` | The scroll spec alone, for custom lazy lists. |

## Backdrop, navigation, actions
| Component | Use |
|---|---|
| `HeroBackdrop(imageUrl, modifier) { content }` | Full-width backdrop (decoded <= 1280x720), static start+bottom scrim, one 250 ms crossfade per URL change (new image preloaded first). `content` draws over the scrim. |
| `rememberDebouncedValue(value, 300.milliseconds)` | Feed the focused item's backdrop URL through it. |
| `TopBar(items, selectedIndex, onSelect, modifier, selectOnFocus = false)` | Tabs (جستجو · خانه · ...), selected = accent + underline, focus lands on the selected tab. |
| `TvButton(text, onClick, modifier, icon, style = Primary/Secondary, enabled)` | Yellow filled / outlined (white fill on focus). Icons: `BolbolIcons.Play/Replay/Add/Check/Refresh/Search`. |
| `SelectableChip(text, selected, onClick)` / `ChipGroup(options, selectedIndex, onSelect)` | Pickers (audio, quality, season). |

## Small pieces and states
| Component | Use |
|---|---|
| `ProgressLine(fraction, modifier, height = 4.dp)` | Accent on dark track, fills from the start edge. |
| `AudioBadge(dubbed, subtitled)` / `RatingBadge(imdb)` | "دوبله" / "زیرنویس" pills; "IMDb 8.1" pill. |
| `MetaLine(parts, modifier, color, maxLines)` | Joins non-blank parts with " · ". |
| `BolbolText(text, modifier, style, color, maxLines, textAlign)` | `Text` that is safe for Persian + Latin titles (RTL paragraph + bidi wrap, so "Dune (2024)" and "Mission!" render right). Use it for any title string. |
| `LoadingState()` / `ErrorState(message, onRetry)` / `EmptyState(message)` | Full-area states. Loading is static (no animation). Error requests focus on "تلاش دوباره". |

## Text and numbers
- Fonts: `VazirmatnFamily`, `BolbolTypography` (Display 36 / Headline 24 / Title 18 / Body 14 / Label 12; letter spacing 0).
  For Android views (SubtitleView): `vazirmatnTypeface(context, weight)`. Font files: `src/main/res/font/`, license `FONT-LICENSE.txt`.
- Digit policy: Persian numerals for time, counters and episodes; **Latin for release years and IMDb ratings** (data).
- `formatDurationFa(ms)` "۱:۲۳:۴۵" / "۴۷:۱۲" · `formatRemainingFa(ms)` "۲۴ دقیقه مانده" (rounds up) ·
  `formatRuntimeFa(min)` "۱ ساعت و ۴۲ دقیقه" · `formatEpisodeFa(season, episode, includeSeason)` ·
  `formatImdb(f)` "8.1" · `String/Int.toPersianDigits()`.
- Tokens: `BolbolColors`, `BolbolSpacing` (SafeHorizontal 48 dp, SafeVertical 27 dp, ItemGap, FOCUS_SCALE), `PosterCardDefaults`, `WideCardDefaults`.

## Typical home row
```kotlin
ContentRow("فیلم‌های جدید", items = movies, key = { it.id }, contentType = { "poster" }) { m ->
    PosterCard(m, onClick = { open(m.id) }, onFocused = { focusedBackdrop = m.backdropUrl })
}
```

## Debug gallery (debug builds only)
`adb shell am start -n app.bolbol.tv/app.bolbol.tv.core.designsystem.gallery.GalleryActivity` (add `--ei page 1` or `--ei page 2`
for the other screens: 0 home, 1 wide cards + controls, 2 states). Sources: `src/debug/`.
