# Bolboljan mobile API: developer reference

Audience: whoever builds the Kotlin Android TV client (Retrofit + kotlinx.serialization). Unofficial; derived from responses captured on 2026-10-11 in `research/webapp/` plus live probing the same day. Where a fact has no saved sample it is tagged.

Evidence tags: **S** = saved sample in `research/webapp/api/*.json`; **P** = live probe 2026-10-11, no sample saved; **R** = route index only (`research/webapp/wpjson.json`, method known, nothing else); **H** = inferred from website HTML/JS (`research/webapp/euploadi.com.index.html`); **?** = unknown.

## 1. Basics

| Item | Value |
|---|---|
| Base URL | `https://euploadi.com/wp-json/mapi/v1` |
| Mirror | `https://uuploadi.ir/wp-json/mapi/v1` (same content; permalinks reference `seo2024.ir`). Domains rotate, so make the base URL configurable with failover. |
| Backend | WordPress, custom REST namespace `mapi/v1` (listed in `/wp-json/` index) |
| Format | UTF-8 JSON; all human text is Persian (fa) unless noted |
| Auth | None needed for browse/play endpoints (section 3). Account endpoints are unexplored (section 9). |
| Pagination | `?page=N` (1-based), 12 items per page |
| Retrofit | Base URL must end with `/`; declare paths without a leading `/` (`post/all`) |

## 2. Cross-cutting quirks (read first)

1. **Four envelope shapes** (section 4).
2. **Numbers are often strings, and the same concept changes type between endpoints.** List `release` is int `2025`; detail `release` is string `"2025"`. List `metacritic_rate` is int `64`; detail is `"64%"`. `imdb_rate` is always a string (`"6.3"`; `"0.0"` means "no rating", 27 of 216 home items). Detail `vote` `"117407"`, `age` `"12"` or `"TV-Y7"`, `top250` `"4711"`. Series blocks: `count: "2"`, `subtype: "0"`.
3. **String-encoded flags.** Detail `playonline_active`, `has_dubbed`, `has_subtitle` are `"on"` or `""`. List/home `dubbed`, `subtitle`, `suggested` are real booleans.
4. **Empty string instead of null** everywhere: `trailer: ""`, `top250: ""`, `imdb_top250: ""`, `normalized_title: ""`, `profile_actor: ""`, `dl_sub: ""`, `ensub_link: ""`. `metacritic_rate: 0` and `imdb_rate: "0.0"` mean "none".
5. **PHP array/object duality.** Empty collections serialize as `[]` (e.g. `broadcast_days: []`). Movie `download_links`/`player_links` are JSON *objects keyed by numeric strings*, in non-numeric order (`"0","3","4","1","2","5"`). Because PHP emits a JSON *array* when keys happen to be `0..n-1` in order, the same field may arrive as an array for other titles (expected, not observed) and as `[]` when empty. Series use arrays of blocks (section 5.4). Decode with a map-or-array tolerant serializer (section 8).
6. **Different names for the same field across payloads:** list/home `thumbnail`, `background` = detail/related `image`, `background_image`; list `imdb_top250` = detail `top250`; list `dubbed`/`subtitle` = detail `has_dubbed`/`has_subtitle`; movie download entry `sub_type` = series block `subtype`.
7. **Image host varies:** list/home images are on `seo2024.ir`, detail images on `euploadi.com`, same path (`/wp-content/uploads/2026/02/0-76008300-1772116872.webp`). The website's own JS rewrites every `/wp-content/uploads/*.(webp|jpg|jpeg|png|gif)` URL to the "active CDN" `https://majnoonbazar.ir` (H). Treat the host as replaceable; keep the path.
8. **Titles contain HTML entities** (`&#8217;`, `&#038;`, `&#8211;`, e.g. `"فیلم The Devil&#8217;s Rain 1975"`). Unescape before display. Titles also carry a Persian type label prefix: `فیلم`, `فیلم ترکی`, `فیلم هندی`, `سریال`, `سریال ترکی`, `سریال کره ای`, `انیمه`, `انیمیشن`, or none (`"Mechamato Movie 2022"`). Detail `second_title` can have a leading space (`" فیلم The Naked Gun 2025"`).
9. `updated_at` is `"-0001-11-30"` (PHP zero date) when never updated; treat as null. `created_at` is `YYYY-MM-DD`.
10. List/home `plot` is a truncated excerpt (often ends with `...`, max seen 825 chars, can contain `\r\n`). Full text is detail `fa_plot`. Detail `en_plot` is **not English**: it is a long Persian review/critique text (empty for the series sample).
11. Detail `runtime` is a Persian string: `"85 دقیقه"` ("85 minutes"). Parse leading digits.
12. Human-readable units are English words with trailing space: `dl_capacity: "2.42 GigaByte "`, `"780 MegaByte "`; season/episode labels `"Season 1"`, `"Episode 1"`. (The website localizes them client-side.)
13. Genre and country ids are per taxonomy and differ between movies and series: action is `29` for movies but `108` for series; drama `13` vs `74`; comedy `379` vs `94526`; adventure `56` vs `109`. Never share a genre id between the two kinds.
14. Persons: `profile_actor` is the photo field for actors **and** directors (`""` when missing). Placeholder director `{"id":163337,"name":"unknown"}`. Max 21 actors observed.

## 3. Endpoint index

| Method | Path | Auth | Evidence | Section |
|---|---|---|---|---|
| GET | `/post/all` | public | S | 5.1 |
| GET | `/post/movies`, `/post/series`, `/post/suggestions` | public | S | 5.2 |
| GET | `/post/cartoons`, `/post/documentaries` | public (per feasibility doc) | R, no sample | 5.2 |
| GET | `/post/search?search=TEXT` | public | S (error only), P | 5.3 |
| GET | `/post/{id}` | public | S | 5.4 |
| GET | `/post/{id}/season/{s}` | public | P | 5.5 |
| GET | `/post/{id}/season/{s}/episode/{e}` | public | P | 5.5 |
| GET | `/post/player-links` | unknown (404 without params) | S | 5.6 |
| GET | `/actor/{id}/posts`, `/director/{id}/posts` | public (per feasibility doc) | R, no sample | 5.6 |
| GET | `/post/{id}/comments` | public (per feasibility doc) | R, no sample | 5.6 |
| GET | `/file/movies`, `/file/animations` | unknown | R | 5.6 |
| POST | `/post/{id}/comment`, `/comment/{id}` | unknown (probably needs login) | R | 9 |
| POST | `/login`, `/login-code` | unknown | R | 9 |
| GET; GET+POST | `/user/profile`; `/user/watch-list`, `/user/favorite-list` | unknown (probably token) | R | 9 |
| POST | `/post/tv` | needs `X-Token` header | H | 9 |

`mapi/v1` has no watch-progress endpoint (see section 9 for a caveat).

## 4. Envelopes and errors

| Shape | Used by | Fields |
|---|---|---|
| Paged list | `/post/movies`, `/series`, `/suggestions` (S) | `success` bool, `per_page` int (12), `page` int, `total_page` int, `data` array |
| Single | `/post/{id}` (S), season/episode (P) | `success` bool, `data` object |
| Home | `/post/all` | `success` bool, `data.sections[]` (no paging fields) |
| Error | any | `success: false`, `message` string (Persian), no `data` |

Observed totals (2026-10-11): movies `total_page` 2337, series 859, suggestions 22. Behaviour for `page > total_page` is unknown (?).

Errors:

| HTTP | Request | Body |
|---|---|---|
| 400 | `GET /post/search` with no `search` param (S; the saved sample used `?s=friends`, the wrong name) | `{"success":false,"message":"متن جستجو را وارد کنید"}` ("Enter the search text") |
| 404 | `GET /post/player-links` with no params (S) | `{"success":false,"message":"پست یافت نشد."}` ("Post not found.") |

Unknown ids on `/post/{id}` presumably return the same 404 body (not verified). Routing-level failures use the standard WordPress shape `{code, message, data:{status}}` (not captured). Do not parse `message` for logic; show your own localized text.

## 5. Endpoints

### 5.1 `GET /post/all` (home) (S)

No params. 14 sections, 18 items each, 216 items but only 147 distinct ids (titles repeat across rows).

Section object:

| Field | Type | Example | Notes |
|---|---|---|---|
| `key` | string | `"movies"` | Values seen: `movies`, `series`, `cartoons`, `suggestions`. **Not unique** (`movies` appears 10 times); use list index as row id. |
| `title` | string | `"جدیدترین فیلم ها"` | Row title (fa) |
| `view_all.url` | string | `"https://euploadi.com/wp-json/mapi/v1/post/movies"` | Absolute URL containing the request host. Use only its path and replace the host with your base URL. |
| `view_all.paramters` | object | `{"country":1199}` | Key name is misspelled in the API. `{}` when empty. Values are ints. Keys seen: `imdbtop250` (`1`), `country` (id), `genres` (id). Pass as query params to the list endpoint, plus `page`. |
| `items` | array | | May be empty: "فیلم های کره‌ای" (country 494) and "کارتون" (cartoons) were empty. Hide empty rows. |

Rows in sample order: newest movies, "250 فیلم برتر" (`imdbtop250=1`), Iranian (`country=1199`), Turkish (`1198`), Korean (`494`, empty), drama (`genres=13`), action (`29`), comedy (`379`), thriller (`468`), sci-fi (`520`), newest series, "250 سریال برتر" (`imdbtop250=1`), cartoons (`/post/movies?genres=94519`, empty), suggestions (`/post/suggestions`).

Server quirks worth guarding against:
- "جدیدترین سریال ها" (newest series) has `view_all` = `/post/series` with `genres=520`, the *movie* sci-fi id; looks like a copy/paste bug. Do not blindly trust `paramters` for series.
- "250 فیلم برتر" starts with the lowest-rated films (`House of the Dead 2003` `"2.1"`, `Cats 2019` `"2.8"`, `Radhe 2021` `"1.8"`), so the `imdbtop250` flag/ordering does not mean what the title says. Do not label it "Top 250" without checking.
- Cartoons `view_all` points at `/post/movies` with `genres=94519`, an id that appears in the series taxonomy.

Home item (11 fields, subset of the list item, **no `type`, no `release`, no `permalink`**): see column "Home" in 5.2. Determine movie vs series from the row (`view_all.url` path) or lazily from `GET /post/{id}` (`type`).

### 5.2 List endpoints (S: movies, series, suggestions)

`GET /post/movies|series|suggestions|cartoons|documentaries`

Params: `page` (int, default 1) plus optional filters `country` (int), `genres` (int, ids are per kind, quirk 13), `imdbtop250` (`1`). The filters are known only from home `paramters`; not independently probed.

Item schema (`data[]`; "Home" = also present in `/post/all` items):

| Field | Type | Example | Home | Notes |
|---|---|---|---|---|
| `id` | int | `356834` | y | Post id; use in `/post/{id}` |
| `title` | string | `"فیلم Gossip 2000"` | y | HTML entities, type-label prefix (quirk 8) |
| `fa_title` | string | `"شایعه"` | y | Persian title |
| `normalized_title` | string | `"فیلم wasteman 2025"` | - | Lower-cased search key; `""` for most items |
| `type` | string | `"movie"` | - | `"movie"` or `"serie"` (section 6) |
| `permalink` | string | `"https://seo2024.ir/movies/356834/"` | - | Web URL (`/movies/{id}/` or `/series/{id}/`); not an API URL |
| `suggested` | bool | `false` | - | `true` throughout `/post/suggestions` |
| `dubbed` | bool | `true` | y | Has a Persian dub |
| `subtitle` | bool | `true` | y | Has Persian subtitle. Both can be false (5 home items). |
| `release` | int | `2000` | - | Year |
| `metacritic_rate` | int | `58` | y | `0` = none |
| `imdb_rate` | string | `"5.9"` | y | `"0.0"` = none |
| `imdb_top250` | string | `"4711"` | - | `""` or a rank-like number; values exceed 250, so not a real top-250 rank |
| `thumbnail` | string | `"https://seo2024.ir/wp-content/uploads/2026/07/0-39075900-1783156910.webp"` | y | Poster. Sometimes `.jpg`. |
| `background` | string | `"https://seo2024.ir/.../0-53688300-1783156910.webp"` | y | Backdrop |
| `plot` | string | `"سه دانشجوی کالج ..."` | y | Truncated (quirk 10) |
| `created_at` | string | `"2026-10-10"` | - | |
| `updated_at` | string | `"-0001-11-30"` | - | Sentinel (quirk 9) |
| `countries` | array of Term | `[{"id":11,"name":"آمریکا"}]` | - | |
| `genres` | array of Term | `[{"id":13,"name":"درام"}]` | y | 0 to 10 entries |
| `actors` | array of Person | `[{"id":162346,"name":"Anthony J. Mifsud","profile_actor":"https://.../nm0585857.webp"}]` | - | May be `[]`; max 21 |
| `directors` | array of Person | `[{"id":15377,"name":"Davis Guggenheim","profile_actor":""}]` | - | May be `[]` |
| `categories` | array of Term | `[{"id":195810,"name":"بهترین فیلم های اکشن"}]` | - | Editorial lists; `[]` for 9 of 12 movies |
| `languages` | array of Term | `[{"id":172767,"name":"انگلیسی"}]` | - | May be `[]` |

`Term` = `{id:int, name:string}`. `Person` = `{id:int, name:string, profile_actor:string}`.

### 5.3 `GET /post/search?search=TEXT`

Params: `search` (required, URL-encoded UTF-8; the website's own form uses `s`, which the API ignores and answers with the 400 in section 4) and `page` (presumed to work as for other lists (?)).

**Verified 2026-10-11 (S: `post_search_friends.json`):** the paged-list envelope (`success, per_page 12, page, total_page, data[]`) with exactly the 24-field list-item schema of 5.2, including `type`. Persian queries work (URL-encoded UTF-8; `بازیگر` → 2 pages). Matches titles in both scripts.

### 5.4 `GET /post/{id}` (detail) (S: movie 353371, series 624236)

`data` is one object; `type` selects the shape of `download_links` and `player_links` (5.4.1 vs 5.4.2). All other fields are common. Every scalar below is a **string** unless noted.

| Field | Type | Example | Notes |
|---|---|---|---|
| `id` | int | `353371` | |
| `type` | string | `"movie"` / `"serie"` | |
| `title` | string | `"فیلم The Naked Gun 2025"` | |
| `second_title` | string | `" فیلم The Naked Gun 2025"` | Often equals `title`; leading space possible |
| `fa_title` | string | `"اسلحه برهنه"` | |
| `image` | string | `"https://euploadi.com/wp-content/uploads/2026/02/0-76008300-1772116872.webp"` | Poster |
| `background_image` | string | `".../0-84730100-1772116872.webp"` | Backdrop |
| `playonline_active` | string | `"on"` | Flag; meaning of `""` unknown (?). Probably "online play enabled". |
| `has_dubbed`, `has_subtitle` | string | `"on"` / `""` | Flags |
| `release` | string | `"2025"` | |
| `imdb_rate` | string | `"6.3"` | |
| `metacritic_rate` | string | `"64%"` | Strip `%`; `"0%"`/`""` for none is likely but not seen (?) |
| `imdb_id` | string | `"tt3402138"` | |
| `vote` | string | `"117407"` | IMDb vote count |
| `age` | string | `"12"`, `"TV-Y7"` | Mixed numeric age and US TV rating |
| `runtime` | string | `"85 دقیقه"` | Series sample: `"65 دقیقه"` (per episode, presumably) |
| `summary_awards` | string | `"جوایز: ۴ جایزه و مجموعاً ۱۷ نامزدی"` | Persian digits; `""` common |
| `top250` | string | `"4711"` | `""` if none |
| `en_plot` | string | | Persian long review text, misnamed (quirk 10) |
| `fa_plot` | string | | Persian plot, full length |
| `trailer` | string | `"https://dl20.acenteri.ir/zr/trailers/Movies6/2025/The.N.a.k.e.d.Gun.2025/The.N.a.k.e.d.Gun.2025.mp4"` | Direct MP4; `""` if none |
| `update_text` | string | `"فصل 1 قسمت 2 (آخر) اضافه شد"` | Changelog banner ("Season 1 episode 2 (last) added"); movie: `"دوبله (ساندفیلم) اضافه شد!"` |
| `countries`, `languages`, `genres` | Term[] | | |
| `actors`, `directors` | Person[] | | |
| `broadcast_days` | array | `[]` | Always `[]` in both samples; element shape unknown (?) |
| `download_links` | object (movie) or array (series) | | 5.4.1, 5.4.2 |
| `player_links` | object (movie) or array (series) | | 5.4.1, 5.4.2 |
| `related_posts` | array | | 10 items: `{id:int, type, title, image, background_image, release:string}` |

#### 5.4.1 Movie links (S)

`download_links` and `player_links` have the **same keys** (`"0","3","4","1","2","5"` in the sample) and each key describes the same file in both (play_link equals dl_link, fasub_link equals dl_sub, verified). Order is sub group first, then dub group; do not rely on key order or key value. Sample: 3 sub (BluRay 1080/720/480) + 3 dub (WEB-DL 1080/720/480).

`download_links[key]` and `player_links[key]`, field by field (all strings):

| download_links | player_links | Example | Notes |
|---|---|---|---|
| `dl_link` | `play_link` | `"https://dl20.acenteri.ir/zr/stream_movies/stream7/2025/The.N.a.k.e.d.Gun.2025/The.N.a.k.e.d.Gun.2025.1080p.BluRay.x264.Pahe.SoftSub.mp4"` | Progressive MP4; hand `play_link` to the player |
| `dl_sub` | `fasub_link` | `".../...SoftSub.vtt"` | Persian WebVTT; `""` for dub |
| (none) | `ensub_link` | `""` | English subtitle; empty in every movie entry seen |
| `quality_link` | `quality_link` | `"BluRay 1080p"` vs `"1080"` | **Same name, different formats** |
| `link_type` | `type_link` | `"sub"` / `"dub"` | |
| `dl_capacity` | (none) | `"2.42 GigaByte "` | Free text, trailing space |
| `sub_type` | (none) | `"0"` | Meaning unknown (?) |
| `encoder_link`, `dl_quality_example`, `dl_sound`, `dl_price` | (none) | `""` | Always empty in sample (?) |

CDN hosts differ per entry within one title (`dl20.acenteri.ir` for 1080/480, `dl2.ecenteri.ir` for 720). Do not assume one host.

#### 5.4.2 Series links (S)

Both fields are **arrays of blocks**; one block per (season, quality). The sample (one season, 2 episodes) has 3 blocks per field, ordered 1080, 720, 480. Block `i` of `player_links` matches block `i` of `download_links`; safer to match by (season number, quality number).

Block fields, side by side (all strings except `items`):

| download_links[] | player_links[] | Example | Notes |
|---|---|---|---|
| `name` | `season_name` | `"Season 1"` | Parse the trailing int for the season number |
| `quality` | `quality_link` | `"WEB-DL 1080p"` vs `"1080"` | Same split as for movies |
| `type` | `type_link` | `"sub"` vs `""` | **Player side is empty even though the download block says `"sub"`**; take audio type from `download_links[].type` |
| `count`, `capacity`, `subtype` | (none) | `"2"`, `""`, `"0"` | `count` is the episode count as a string |
| `items[]`: `{title, link, subtitle}` | `items[]`: `{play_link, fasub_link, ensub_link}` | `{"title":"Episode 1","link":"...S01E01.1080p.WEB-DL.x264.mp4","subtitle":"....vtt"}` | Player items have no title/number: position = episode order |

In the sample `fasub_link == ensub_link` (same `.vtt`) for all 6 items, so do not treat `ensub_link` as English. How a dubbed series is represented was not observed (?).

### 5.5 Season and episode endpoints (P, no saved sample)

| Request | `data.download_links` shape |
|---|---|
| `GET /post/{id}/season/{s}` | **object** keyed by quality string; each value is an **array** of `{title, link, subtitle}` (one per episode) |
| `GET /post/{id}/season/{s}/episode/{e}` | **object** keyed by quality string; each value is a **single object** `{title, link, subtitle}` |

Decode both with a one-or-many serializer (section 8.2). Not captured: exact key format (treat as opaque and extract the `\d{3,4}` token), other keys inside `data`, whether `{s}`/`{e}` are 1-based and equal to the number in `"Season N"`/`"Episode N"` (presumably), audio type, behaviour for missing season/episode, empty-result shape (may be `[]`). The series detail already carries the same per-episode link and subtitle data, so these endpoints look optional (inference).

### 5.6 Unsampled or incomplete

- `GET /post/player-links`: route exists, no declared args; without params it returns the 404 above. Required params unknown (?).
- `GET /actor/{id}/posts`, `/director/{id}/posts` (filmography, presumably a paged list), `/post/{id}/comments`, `/file/movies`, `/file/animations`: shape or purpose unknown (?).

## 6. Value vocabularies

| Field | Values seen | Notes |
|---|---|---|
| `type` | `"movie"`, `"serie"` | Note `serie`, not `series`. No other value seen anywhere (lists, detail, related). Anime/animation labelled items exist as both kinds (`انیمه PSYREN` is `serie`; `انیمیشن BoBoiBoy Movie 2` is in the movie list), so the kind comes from `type`, never from the title prefix. |
| `link_type` (movie download), `type_link` (movie player) | `"sub"`, `"dub"` | Series `type_link` is always `""` |
| series `type` (download block) | `"sub"` | `"dub"` presumably exists (?) |
| movie `player_links.quality_link`, series `quality_link` | `"1080"`, `"720"`, `"480"` | |
| movie `download_links.quality_link`, series `quality` | `"BluRay 1080p"`, `"BluRay 720p"`, `"BluRay 480p"`, `"WEB-DL 1080p"`, `"WEB-DL 720p"`, `"WEB-DL 480p"` | Parse resolution with `(\d{3,4})p` |
| `playonline_active`, `has_dubbed`, `has_subtitle` | `"on"`, `""` | `sub_type`/`subtype` is always `"0"` |

## 7. Media files

- Streams are progressive MP4 (H.264) on rotating CDN hosts (`dl20.acenteri.ir`, `dl2.ecenteri.ir`, `dl1.hostikadeh.ir`), URLs have no query string or visible signature. HTTP range works (`Accept-Ranges: bytes`, `206 Partial Content`), so seek and resume at any offset.
- Subtitles are WebVTT `.vtt` files with the same base name as the MP4, e.g. `...Pahe.SoftSub.mp4` and `...Pahe.SoftSub.vtt`. Attach as side-loaded `SubtitleConfiguration` (`MimeTypes.TEXT_VTT`, language `fa`).
- The first cue of each `.vtt` is a site credit cue, not dialogue. It is harmless; strip or ignore it if it bothers you.
- Dub entries have no subtitle URL; sub entries carry the Persian track only. Trailers are direct MP4 links too.
- Do not persist stream URLs for resume; re-resolve through the API and key progress on `postId:season:episode` (section 8).

## 8. Kotlin modelling recommendations

### 8.1 Json configuration

```kotlin
@OptIn(ExperimentalSerializationApi::class)
val BolboljanJson = Json {
    ignoreUnknownKeys = true   // server adds/renames fields freely
    coerceInputValues = true   // null / unknown enum -> property default (every DTO property needs a default)
    isLenient = true           // tolerate quoted/unquoted primitives
    explicitNulls = false
}
// Retrofit: com.squareup.retrofit2:converter-kotlinx-serialization
Retrofit.Builder()
    .baseUrl("https://euploadi.com/wp-json/mapi/v1/")  // trailing slash required
    .addConverterFactory(BolboljanJson.asConverterFactory("application/json".toMediaType()))
```

`isLenient` does not reliably turn `"2025"` into `Int` or `"on"` into `Boolean`; do not depend on it for the string-typed fields. Declare detail scalars as `String` through `LenientString` and convert in the mapper. Give every property a default (`= ""`, `= emptyList()`), so a missing key or `null` cannot crash a TV box mid-session. Decode error bodies with `ApiError(success: Boolean = false, message: String = "")` when `HttpException` is thrown.

### 8.2 Custom serializers (sketch)

```kotlin
object LenientString : KSerializer<String> {            // "2025", 2025, null -> String
    override val descriptor = PrimitiveSerialDescriptor("LenientString", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): String =
        ((decoder as JsonDecoder).decodeJsonElement() as? JsonPrimitive)
            ?.takeUnless { it is JsonNull }?.content ?: ""
    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}

object OnOff : KSerializer<Boolean> {                   // "on" / "" / true / absent
    override val descriptor = PrimitiveSerialDescriptor("OnOff", PrimitiveKind.BOOLEAN)
    override fun deserialize(decoder: Decoder): Boolean =
        ((decoder as JsonDecoder).decodeJsonElement() as? JsonPrimitive)
            ?.takeUnless { it is JsonNull }?.content?.lowercase() in setOf("on", "1", "true")
    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeString(if (value) "on" else "")
}

// object keyed "0","3","4"... OR array OR [] OR ""/null  ->  List<T>
open class MapOrList<T>(elem: KSerializer<T>) : JsonTransformingSerializer<List<T>>(ListSerializer(elem)) {
    override fun transformDeserialize(element: JsonElement): JsonElement = when (element) {
        is JsonArray -> element
        is JsonObject -> JsonArray(element.entries.sortedBy { it.key.toIntOrNull() ?: Int.MAX_VALUE }.map { it.value })
        else -> JsonArray(emptyList())
    }
}
object MovieDownloads : MapOrList<MovieDownloadDto>(MovieDownloadDto.serializer())  // same for MoviePlayers

// season endpoint: value is array, episode endpoint: value is a single object
open class OneOrMany<T>(elem: KSerializer<T>) : JsonTransformingSerializer<List<T>>(ListSerializer(elem)) {
    override fun transformDeserialize(element: JsonElement) =
        if (element is JsonArray) element else JsonArray(listOf(element))
}
```

Detail polymorphism: decode `data` through a `JsonContentPolymorphicSerializer` that reads `type` (`"serie"` selects the series DTO, anything else the movie DTO), because `download_links`/`player_links` differ per kind. Series DTOs use `List<SerieDownloadBlockDto>` / `List<SeriePlayerBlockDto>` directly (wrap in `MapOrList` too if you want defence against `[]`/object). One `PostSummaryDto` covers both list items and home items (home lacks `type`, `release`; give them defaults).

### 8.3 Proposed domain models

```kotlin
enum class Kind { MOVIE, SERIES }
enum class Audio { SUB, DUB }

data class StreamOption(
    val quality: Int,            // 1080, 720, 480
    val audio: Audio,
    val url: String,
    val subtitleFa: String?,     // null when blank
    val subtitleEn: String?,     // null when blank or identical to subtitleFa
)
data class Episode(val number: Int, val title: String, val options: List<StreamOption>)
data class Season(val number: Int, val episodes: List<Episode>)

data class TitleSummary(
    val id: Int, val kind: Kind?, val title: String, val faTitle: String, val year: Int?,
    val imdb: Float?, val metacritic: Int?, val poster: String, val backdrop: String,
    val plot: String, val hasDub: Boolean, val hasSub: Boolean, val genres: List<String>,
)
data class Title(
    val summary: TitleSummary, val fullPlot: String, val runtimeMin: Int?, val ageRating: String?,
    val trailer: String?, val cast: List<String>, val directors: List<String>,
    val movieOptions: List<StreamOption>,   // empty for series
    val seasons: List<Season>,              // empty for movies
    val related: List<TitleSummary>,
)
fun resumeKey(postId: Int, season: Int = 0, episode: Int = 0) = "$postId:$season:$episode"  // movie = "353371:0:0"
```

### 8.4 Mapping notes

- `kind`: `"movie"` to MOVIE, `"serie"` to SERIES, anything else null. Home items have no `type`: use the row's `view_all.url` path (`/post/series` means SERIES; the suggestions row has only shown movies) or resolve on detail load.
- Text: unescape HTML entities in `title`; trim `second_title`; strip the type-label prefix only for display, never for identity. Prefer `fa_title` for Persian UI.
- Numbers: `year` = `release` to Int (0/blank to null); `imdb` = `imdb_rate.toFloatOrNull()` with `0.0` mapped to null; `metacritic` = digits of `"64%"` (or the int), `0` to null; `runtimeMin` = leading digits of `runtime`. `updated_at == "-0001-11-30"` to null.
- Images: keep the path part, rebuild with the currently reachable image host (quirk 7). Treat `""` as "no image".
- **Movie options**: from `player_links` only. `quality = quality_link.toInt()`, `audio = if (type_link == "dub") DUB else SUB`, `url = play_link`, `subtitleFa = fasub_link.ifBlank { null }`, `subtitleEn = ensub_link.ifBlank { null }`. Sort by quality descending. Use `download_links` only for display extras (`dl_capacity`, source tag like `BluRay`).
- **Series**: season number = int in `season_name`/`name` (`"Season 1"` to 1). Audio = matching `download_links[].type` (`player_links[].type_link` is `""`); default SUB if a subtitle URL exists. Episode number = int in `items[].title` (`"Episode 2"`), fallback `position + 1`; group the per-quality blocks by (season, episode number) into `Episode.options`. Qualities may have different episode counts (`count` is a string): never index across blocks blindly. If `subtitleEn == subtitleFa`, set `subtitleEn = null`.
- Audio/quality preference: store `Audio` and target quality, not a URL; pick the highest option at or below the target, falling back to the other audio type when missing.
- **Resume key**: `postId:season:episode`, with `season = 0, episode = 0` for movies. It survives CDN host changes, mirror failover, quality switches and re-ordered `download_links` keys.
- Cache `GET /post/{id}` briefly; do not persist stream URLs across sessions.

## 9. Unknown / to investigate (M3)

None of these has a saved response. Everything below is from the route index (R) or the website page (H); the rest is unknown.

| Endpoint | Known | Unknown |
|---|---|---|
| `POST /login` | Method POST, no declared args (R). Website sign-in page is `https://euploadi.com/sign-in` (H; page not saved). | Body fields (username/password? phone/OTP?), response, how the token is returned |
| `POST /login-code` | Method POST (R) | Everything. Feasibility doc guesses OTP or TV-pairing code; unverified. |
| `GET /user/profile`; `GET, POST /user/watch-list`, `/user/favorite-list` | Methods only (R) | Auth header, POST body (post id? add/remove?), responses, whether items carry progress |
| `POST /post/{id}/comment`, `POST /comment/{id}` | Method POST (R) | Everything; probably needs login |
| `POST /post/tv` | See below | Request keys, success semantics |

**`POST /post/tv` (H).** The website's "play on TV" button (label `پخش در تلویزین`) does:

```js
if (is_logged_in === 0) { location.href = '/sign-in/'; return; }
const data = JSON.parse(btn.attr('data-link'));
$.ajax({ url: '/wp-json/mapi/v1/post/tv', type: 'POST', dataType: 'json', data: data,
         beforeSend: xhr => xhr.setRequestHeader('X-Token', token) });
```

- Body: jQuery serializes the `data` object as `application/x-www-form-urlencoded`. Its keys are whatever the `data-link` attribute JSON contains. The `.play-on-tv` buttons exist only on detail pages, which were not saved, so the keys are unknown.
- Header: `X-Token: <token>`, where `token` is a JS constant the server prints into every page (`const token = "<64 hex chars>"`). It was different on the two mirror fetches (`euploadi.com` vs `uuploadi.ir`), so it is per-session or per-request, not a static API key. It is emitted even when `is_logged_in = 0`, but the JS redirects guests to `/sign-in/` before any call, so guest behaviour is unknown. Real values are intentionally not recorded here.
- Response expected by the JS: `{"success": true, "data": {"message": "..."}}`; on `success` true the site shows `data.message` for 50 s; any failure shows the generic toast `خطایی پیش اومده` ("Something went wrong").
- No `Authorization`/`Bearer` use appears anywhere in the page. The WP index advertises only core Application Passwords (`/wp-admin/authorize-application.php`), which is probably unrelated to the mobile apps.
- Purpose (inferred from the label and the feasibility doc): pushes a link to the logged-in TV app. How the TV app receives it (polling? push? `login-code` pairing?) is unknown.

**Caveat on "no watch-progress endpoint".** `mapi/v1` has none, but the website home page contains a logged-in-only "keep-alive" (continue watching) slider whose remove button calls a different service. The saved guest page has the JS and CSS but no slider markup, so the feature is apparently rendered for logged-in users only (inference):

```js
fetch('https://majnoonbazar.ir/tracker.php', { method: 'POST', body: FormData{
  action: 'remove', post_id: <id>, token: '<64 hex>|0' } })   // expects {success:bool, message?:string}
```

`majnoonbazar.ir` is also the image CDN host. The `|0` suffix of the token was the same on both mirrors; if it is `<hash>|<userId>`, `0` means guest (speculation). `tracker.php` may accept other actions (for example saving progress), which is worth probing once an account exists. Local Room storage remains the safe default.

Other open items:
- Static search index: `YAS_SEARCH.json_url = https://euploadi.com/wp-content/uploads/search-data.json` (`version "1791668064"`) feeds the website's typeahead. Not downloaded; may allow offline/instant search.
- Pagination beyond `total_page`; `page` on search; whether `country`/`genres`/`imdbtop250` combine; ids outside the home sample.
- Series dub representation, multi-season ordering, 0- vs 1-based season/episode numbers, empty-result shapes of the season endpoints.
- `/post/player-links` params; `/file/*`; actor/director/comment shapes; meaning of `sub_type`, `playonline_active` when `""`, and non-empty `dl_price`/`encoder_link`/`dl_sound`.
- Whether the API or CDN requires a particular `User-Agent`/`Referer` (the mirror web pages cloak non-Apple user agents; the API samples were fetched without special handling), rate limits, and whether stream URLs ever expire.

## 10. Learned during M1 (data layer, 2026-10-11)

- **Search is a normal paged list.** `GET /post/search?search=TEXT&page=N` returns the paged envelope (section 4) with exactly the list-item schema of 5.2, including `type` (mixed `movie`/`serie` in one result). Persian queries work URL-encoded as UTF-8. Sample: `research/webapp/api/post_search_friends.json` (12 items, `total_page` 5). The old `post_search_s_friends.json` is only the 400 error body.
- **The "unknown" director placeholder can carry a photo.** In the series sample `{"id":163337,"name":"unknown"}` has a non-empty `profile_actor` (a generic image). The mapper drops persons named `unknown` instead of relying on an empty photo.
- **Home:** inside one row ids never repeat, but across rows they do (216 items, 147 distinct ids), so UI keys must combine row id and title id. `view_all.paramters` is always an object in the sample (the mapper also tolerates `[]`). 27 of 216 home items have `imdb_rate "0.0"`.
- **Entities seen in titles:** `&#038;`, `&#8211;`, `&#8217;` (plus real U+2019 characters in the search sample). Type labels seen: `فیلم`, `فیلم ترکی`, `فیلم هندی`, `سریال`, `سریال ترکی`, `انیمه`, `انیمیشن`.
- **Series row bug handled in the mapper:** a `series` row whose `genres` id is also used by a `movies` row of the same feed (the 520 case) loses that genre in `ListQuery`, because WordPress term ids are globally unique and a series row can never legitimately use a movie genre. The cartoons row (`/post/movies?genres=94519`, a series-taxonomy id) is deliberately not treated as a movie row.
- **Assumptions the client makes that are not yet verified live:** a 404 `{"success":false}` (or `success:false` with 200) from a list/search call means "no results / past the last page" and becomes an empty page; from `/post/{id}` it means not found. HTTP 5xx and non-JSON bodies on `/wp-json/` calls are treated as a broken mirror and trigger failover; any JSON answer (including 4xx) is accepted as a real API answer.
- **Episode numbering:** the client uses the number in the download item title (`Episode N`) when every title of the block parses and they are distinct, otherwise the 1-based position. Dub vs sub of a series is read from `download_links[].type`; with no type anywhere, a block with subtitle URLs is subtitled, one without is treated as dubbed.
- **Image hosts (fix, quirk 7 revisited):** the API's own hosts do not serve their upload files. `https://euploadi.com/wp-content/uploads/...` and the same path on `https://uuploadi.ir` answer 404 (HTML); the same path on `https://majnoonbazar.ir` (the site's active CDN) and `https://seo2024.ir` answers 200 `image/webp`. The client therefore rewrites every URL whose path starts with `/wp-content/uploads/` to the first host of `NetworkModule.DEFAULT_IMAGE_HOSTS` (`majnoonbazar.ir`, then `seo2024.ir`), and `ImageCdnInterceptor` retries the same path on the next host on an IOException, a non-2xx status or a non-image body. Trailer and stream hosts (`dl*.acenteri.ir`, `dl1.hostikadeh.ir`) are not images and are left alone.

## 11. Dubbed versions and `post/player-links` (found 2026-10-11, samples in `research/webapp/api/dub/`)
- **`GET post/player-links?id={postId}`** (public) returns `{download_links, player_links}` for a movie or a whole series.
  **The official TV app always gets streams from here** (`?id=` for movies, `?id=&season=&episode=` for episodes, same body).
- **`GET post/{id}` can return EMPTY `download_links`/`player_links` (`[]`) while player-links has them**
  (e.g. series 623954 «Kill Jackie», `has_dubbed: "on"`). So details alone is not enough to play.
- Series blocks in player-links are an **object keyed by numeric strings** (`"0","2","4","1","3","5"`), not an array. Use the map-or-array decoder.
- **Dub blocks are separate blocks:** download `type: "dub"` / player `type_link: "dub"`; sub blocks have download `type: "sub"`
  and player `type_link: ""`. Match download and player blocks **by the same key**.
- **Dubbing lags:** the dub blocks can have fewer episodes (4 dubbed vs 8 subtitled in Season 1). Episodes are per-audio:
  episode N may exist only as SUBTITLED.
- Movies in player-links have the same 6-entry shape as in details (3 sub + 3 dub qualities).
- **Client behaviour (data layer):** `details(id)` requests `post/{id}` and `post/player-links?id={id}` in parallel and uses the
  player-links streams/seasons when they parse and are non-empty (movie entries or series blocks, key-matched download/player
  blocks, audio from `type_link`/`type` == `dub`), otherwise the links embedded in the detail. Episodes are merged per
  (season, episode) across all blocks, so an episode carries sub and dub streams in every quality, or only the audio that exists.
