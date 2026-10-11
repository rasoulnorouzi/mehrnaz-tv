# Subtitles: engine + settings spec

User requirement (2026-10-11): subtitles are central. Most titles are watched with Persian soft
subtitles (`.vtt` next to the `.mp4`), so they must be readable, adjustable, and correctly timed.

## Why our own subtitle engine (instead of ExoPlayer's SubtitleView)
| Need | ExoPlayer text pipeline | Own engine |
|---|---|---|
| Timing offset (fix out-of-sync subs, ±0.5 s steps) | not supported for side-loaded subs | trivial: look up cues at `position - offset` |
| Persian rendering (Vazirmatn, line height, bidi of lines starting with "-", digits or Latin) | Android TextView spans, limited control | Compose `Text` with our font, `TextDirection.ContentOrRtl`, per-line bidi fixes |
| Styling options (size, colour, box/outline, position, bold) | partial (CaptionStyleCompat) | full |
| Drop the site's credit cue, strip `<c.colorXXXX>` tags | needs a filter hook | in the parser |
| Same look in the settings preview and the player | no | yes: one `SubtitleText` composable |

Cost: we parse WebVTT ourselves (simple format) and render one `Text`. Off the main thread, with a binary
search per tick, it is cheaper than the platform pipeline.

## Engine (`:core:player` `subtitles/`)
1. **Load:** fetch the `.vtt` with the shared OkHttp client (HTTP cache makes re-opens free); size cap 2 MB.
2. **Parse** (pure Kotlin, unit-tested): strip BOM; parse `HH:MM:SS.mmm --> HH:MM:SS.mmm` and `MM:SS.mmm`; ignore
   NOTE/STYLE/REGION blocks and cue settings; multi-line text; remove tags (`<c.*>`, `<i>`, `<b>`, `<u>`, voice tags)
   but keep the text; decode `&amp; &lt; &gt; &nbsp; &lrm; &rlm;`; drop cues containing "بلبل جان" / "bolboljan";
   sort by start time. The result is an immutable `SubtitleTrack(cues: List<Cue(startMs, endMs, lines)>)`.
3. **Sync:** a coroutine ticks every 100 ms while playing (and on seek/pause), finds the active cue(s) at
   `positionMs - offsetMs` by binary search, and emits **only when the active cue changes**
   (`StateFlow<SubtitleCue?>`), so Compose recomposes a few times per sentence, not per frame.
4. **Bidi:** each line is rendered RTL. If a line starts with a neutral/LTR char (`-`, digits, Latin, quotes),
   prefix U+200F (RLM) so the dash or number sits on the right as Persian readers expect. Lines already starting
   with U+202B (RLE, seen in the site's files) are kept as-is.
5. **Fallback:** if loading or parsing fails, show a one-time toast "زیرنویس بارگذاری نشد" and keep playing.

## Settings (global, saved in Room; preview = the same `SubtitleText` composable)
| Setting | Options | Default |
|---|---|---|
| Subtitles | روشن / خاموش | روشن (for subtitled streams) |
| Size | کوچک 0.8 · معمولی 1.0 · بزرگ 1.25 · خیلی بزرگ 1.5 (× base 30 sp) | معمولی |
| Text colour | سفید · زرد | سفید |
| Background | حاشیه (black outline, no box) · کادر نیمه‌شفاف (60 % black box) | حاشیه |
| Weight | معمولی (Vazirmatn Medium) · ضخیم (Bold) | ضخیم |
| Position | پایین · کمی بالاتر · بالاتر (8 % / 14 % / 20 % from the bottom) | پایین |
| Timing offset | −120 s … +120 s on a 0.5 s grid (`SubtitleStyle.OFFSET_MAX_MS` = 120 000, user request 2026-10-11: "any amount, e.g. ±50 s"); steps of 0.5 s and 5 s, **hold OK to repeat**; **saved per title** (per release) | 0 |

**Where the user changes them:**
- **In the player** (quick menu on MENU / "تنظیمات پخش"): on/off, size, and sync: «زودتر ۰٫۵ ثانیه» / «دیرتر ۰٫۵ ثانیه»,
  «زودتر ۵ ثانیه» / «دیرتر ۵ ثانیه» (2-column grid) and «تنظیم مجدد», with instant effect on the live subtitle.
  Holding OK on a step button repeats it (first repeat after 400 ms, then every 120 ms: +50 s with the 5 s button
  in ~1.4 s). The title shows the value with sign and one decimal, «همگام‌سازی: +۱۲٫۵ ثانیه», and every change shows
  the same value in a small toast «زیرنویس: +۱۲٫۵ ثانیه». Saving is debounced (400 ms) while a button is held.
- **Settings → زیرنویس**: all options, with a live preview (sample Persian line + a line starting with "-") over a
  still frame.

## Data model changes (`:core:model`, manager-owned)
- `SubtitleStyle(enabled, sizeScale, color: WHITE|YELLOW, background: OUTLINE|BOX, bold, position: LOW|MID|HIGH)`.
- `PrefsRepository`: `subtitleStyle(): Flow<SubtitleStyle>`, `saveSubtitleStyle(style)`,
  `subtitleOffsetMs(postId): Int`, `saveSubtitleOffsetMs(postId, ms)`.
- `PlaybackPrefs.subtitleScale` is replaced by `SubtitleStyle.sizeScale`.

## Acceptance
- Parser unit tests on the real sample files (`.vtt` of The Naked Gun and LEGO ONE PIECE S01E01): cue count, first
  real cue, credit cue dropped, tags stripped, RLE lines preserved.
- On the emulator: screenshots of the player with subtitles at each size, outline vs box, yellow, and after +2 s offset.
- On the TV (📺): no dropped frames attributable to subtitle updates (gfxinfo during dialogue scenes).

## As built (2026-10-11, M1.10b)
- Engine: `core/player/.../subtitles/` (`VttParser`, `SubtitleLoader`, `SubtitleSync`, `SubtitleOffset`, `SubtitleCues`).
  ExoPlayer gets **no** text tracks; one `SubtitleText` composable draws everything (player + Settings preview).
- **Visual-order repair (beyond the original spec):** about 200 lines in the Naked Gun file were typed for LTR players
  (punctuation at the wrong end). The engine detects and repairs them; verified on the emulator: «ساخت،» and «می‌کنم.» render
  correctly. Risk: a false positive could flip a correct line. Revisit with more files if a user reports odd punctuation.
- Credit cues dropped: «بلبل جان», "bolboljan", and the handle `@bollbolljan`.
- With the player's settings panel open, subtitles are re-centred in the still-visible video area (manager fix).
