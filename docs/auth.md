# Bolboljan account API: findings and implementation plan (M3.1)

Status: **research only, nothing here was tested with a real login.** Investigated 2026-10-11 by read-only requests (about 30 in total, no credentials sent, no account created, no form submitted). Raw captures are in `research/auth/` (index in section 10). Companion to `docs/api.md` §9, which this document supersedes for everything about accounts.

Evidence tags: **[V]** verified in a saved capture or a live response; **[I]** inferred from verified facts; **[?]** unknown. Confidence: **High / Med / Low** applies to the inference, not to the raw fact.


## 0. CONFIRMED from the official TV app (2026-10-11): this supersedes the guesses below

Source: the official `bolbol-tv.apk` (11,149,486 B, sha256 prefix 6cf5b64035b8a270), downloaded with the user's
permission and decompiled locally with jadx 1.5.6 (Retrofit interface `ApiService`, `AppModule`, data classes).
The APK and the decompiled code are **not** stored in the repo (third-party code); only these facts are.

| Call | Request | Response `data` |
|---|---|---|
| Login | `POST wp-json/mapi/v1/login`, **form-urlencoded** `username`, `password` | `Profile` |
| Login by code | `POST wp-json/mapi/v1/login-code`, form-urlencoded `code` | `Profile` |
| Profile | `GET wp-json/mapi/v1/user/profile` | `Profile` |
| Favourites list (= our «لیست من») | `GET wp-json/mapi/v1/user/favorite-list` | `List<Favorite>` |
| Favourite toggle | `POST wp-json/mapi/v1/user/favorite-list`, form-urlencoded `post_id` | `List<String>` |
| Watch-list (= **server Continue Watching with position**) | `GET wp-json/mapi/v1/user/watch-list` | `List<WatchList>` |
| Save progress | `POST wp-json/mapi/v1/user/watch-list`, **multipart** parts `post_id` (int), `current_time` (int **seconds**), `season` (int, omitted for movies), `episode` (int, omitted for movies) | `Unit` |

- **Envelope:** `{ "success": bool, "data": T, "message": string, "page"?: int, "per_page"?: int }`.
- **Auth header on every call when logged in:** `Authorization: Bearer <token>` (plus `Accept: application/json`).
  The official app also sends `Content-Type: application/json` globally; not needed.
- **Profile:** `id` (int), `username`, `email`, `display_name`, `token`, `vip` {`days_left`, `expire`, `expire_percent`, `status`}, `show` (object).
- **WatchList item:** `id` (post id, int), `title`, `image`, `current_time` (seconds, string or number), `duration`, `season`, `episode` (strings).
- The official app saves progress when the player closes: `current_time = exoPlayer.currentPosition / 1000`.
- Official app's API hosts: **`https://playgen.ir`** (user/* calls) and **`https://arzanycenter.ir`** (some post calls). Both
  verified 2026-10-11 to serve the same `mapi/v1` JSON as euploadi.com, so they are added to our mirror list.
- `/post/tv` and `tracker.php` are not used by the TV app.

## 1. Summary

| # | Question | Answer | Confidence |
|---|---|---|---|
| 1 | How does the website sign in? | Plain **username-or-email + password** form, posted by jQuery to `admin-ajax.php` (`action=yascode_ajax_login`). No SMS, no OTP, no captcha on the page. Session is a WordPress cookie. Registration is `username + email + password1 + password2`. | High |
| 2 | `mapi/v1/login` | `POST`, required fields are a **username and a password** (the server says so when both are missing). Response and token field name unknown. | Med (fields), Low (response) |
| 2 | Token transport | Server demands a **token** on every `/user/*` call (HTTP 400 `توکن الزامی است.`). The only token header used anywhere on the site is **`X-Token`** (website "play on TV" call). Not confirmed for `/user/*`. | Med |
| 3 | `login-code` | `POST` with a field called **`code`** (server: `کد وارد نشده است.` = "code not entered"). No page, JS or route that creates or shows such a code exists for guests, and no "request a code" route exists. Most likely the code is shown in the logged-in website panel and typed into the app (web to TV pairing), but this is a guess. | Low |
| 4 | Watch-list / favourites | `GET` and `POST` on `/user/watch-list` and `/user/favorite-list`; all need the token. Params, bodies and response shapes unknown. | Methods High, rest unknown |
| 5 | `tracker.php` | Only action seen in site JS: `remove` (form fields `action`, `post_id`, `token`). Token is `<64 hex>|<userId>`; the guest value is a constant. Save/list actions are not visible to guests. | Med |
| 6 | `/post/tv` | Body keys found: `post_id`, `type`, `quality`, `season`, `episode`. How the TV receives it is unknown (hypotheses in section 7). | High (keys), unknown (delivery) |
| 7 | Plan | Section 8: login by password through `mapi/v1/login`, `X-Token` interceptor, encrypted token store, lists as best-effort sync with Room as truth. `login-code` and `/post/tv` stay behind feature flags until a real login confirms them. | |

Surprises worth knowing:
- The site publishes **official apps** for Android, **Android TV** (`https://euploadi.com/emkanat/bolbol-tv.apk`, 11,149,486 bytes, `Last-Modified: Wed, 04 Mar 2026 16:22:59 GMT`, size taken from a `HEAD`, not downloaded), iOS, Windows and Mac. They are the clients that call `login`, `login-code`, `user/*` and `post/tv`. Decompiling the TV APK would settle most unknowns in minutes, but needs the user's permission (plan.md 3.1).
- The website's own online player is **login-gated**: `/play/353371/?dubbed=0` shows only "برای پخش آنلاین باید در سایت عضو شوید" ("you must be a member to play online"). The API's stream links work without login (docs/api.md), so the app does not need an account to play anything. An account is only for sync.
- Public `GET /post/player-links?id=<postId>` works without a token (section 9).
- The website hides the **dubbed** download rows from guests behind a "register or log in" box (`vip-required`, `movie_353371.raw.html` lines 1910, 1925, 1940), but the API hands out dub links to guests (docs/api.md 5.4.1). The gate is client-side only today; it may be enforced server-side one day, which is the one realistic reason this app would need an account for playback.

## 2. How the website signs in (Q1)

**Pages (guest, iPhone Safari UA).** `GET https://euploadi.com/sign-in/` returns 200 (`research/auth/signin.raw.html`). `/panel` redirects 301, 302, 301 to `/sign-in/` (`panel_redirect.headers`). Canonical alternate: `https://seo2024.ir/sign-in/` (`signin.raw.html` line 135). Links to `/sign-up` and `/forget-password` are on the page (lines 755-770).

**Login form** (`signin.raw.html` lines 759-772, `data-form="login"`) [V]:

| Field | Input | Placeholder |
|---|---|---|
| `username` | text | `نام کاربری یا ایمیل` ("username or email") |
| `password` | password | `رمز عبور` |

No phone number, no SMS/OTP field, no captcha element, no hidden inputs.

**Submit handler** (`script.min.js`, minified single line, search for `yascode_ajax_login`; byte offset about 29,850) [V]:

```js
$.ajax({ url: ajax_var.ajaxurl, type: "POST",
         data: { action: "yascode_ajax_login", fields: login_form.serialize(), _wpnonce: ajax_var.nonce } })
// success: response.success == 1 -> show response.msg, after 1 s location = response.redirect
// failure: show response.msg (and follow response.redirect if present)
```

- `ajax_var` is printed in the page (`signin.raw.html` line 1061): `{"ajaxurl":"https://euploadi.com/wp-admin/admin-ajax.php","home_url":"https://euploadi.com","logged":"","nonce":"<10 hex>","databody":"","icon":"..."}`. `logged` is `""` for guests, so it is truthy only when signed in. The guest nonce was identical on four pages fetched within minutes.
- `fields` is the serialized form (`username=...&password=...`) nested as one POST parameter.
- Response shape: `{success: 1|0, msg: string, redirect?: string}` [V from the JS]. The session cookie is set by WordPress (not visible to a guest; [I], High). Evidence: the logged-in actions below send no token at all and rely on `ajax_var.logged`.
- Register (`yascode_ajax_register`): fields `username`, `email`, `password1`, `password2` (`research/auth/forms_signup_forget_panel.html`). Forgot password (`yascode_ajax_forget_pass`): field `email`; then `yascode_ajax_forget_pass_confirm`. All are email based, **no SMS anywhere** [V].

**Cookie-session actions the website uses when logged in** (all `POST admin-ajax.php`, from `script.min.js`; useful only as a fallback, section 8.6):

| action | params | response fields read by the JS |
|---|---|---|
| `yascode_add_watchlist_post` (bookmark button `.watchlist-btn`, `data-post=<id>`) | `post_id` | `success`, `msg`, `act` (`add` or `remove`) |
| `yascode_panel_watchlist_toggle` (panel list page) | `post_id`, `type` (`add` or `remove`) | `success`, `result == "done"`, `msg` |
| `yascode_like_post_act` | `post_id`, `type` | `success`, `msg` |
| `yascode_add_post_notification` | `post_id`, `_wpnonce` | `success`, `msg`, `action` |

There is no admin-ajax action that returns the list contents (the panel page renders them as HTML). The bookmark button's guest error text is `جهت افزودن به علاقه مندی وارد حساب کاربری شوید` ("log in to add to favourites"), so the website's bookmark is called "علاقه مندی" (favourite); which of `watch-list` or `favorite-list` it maps to is unknown.

## 3. `mapi/v1/login` and `login-code` (Q2, Q3)

Live responses (`research/auth/api_POST_empty_*.json|headers`), 1 request each, empty body, no auth [V]:

| Request | HTTP | Body (decoded) | Header |
|---|---|---|---|
| `POST /wp-json/mapi/v1/login` | 400 | `{"success":false,"message":"نام کاربری و رمز عبور الزامی است."}` ("Username and password are required.") | `Allow: POST` |
| `POST /wp-json/mapi/v1/login-code` | 400 | `{"success":false,"message":"کد وارد نشده است."}` ("Code has not been entered.") | `Allow: POST` |

Reading:
- `login` expects a **username and a password**. Field names `username` and `password` are an inference from the message and from the website form (Med). It is one required-fields check, so one of them missing gives the same error; nothing else about the contract is visible. The route index declares no args (`research/webapp/wpjson.json`), the plugin reads params itself. WordPress `get_param()` accepts form-urlencoded and JSON bodies alike, so either encoding should work (Med); the website's `/post/tv` call uses form encoding.
- `login-code` expects one field, probably named **`code`** (Med).
- Both return HTTP **400** (not 401/422) for validation errors, always `{success:false, message}` with a Persian message. Do not parse the text; use `success` and the HTTP code.
- **Success shape is unknown** [?]. Expected (Low): `{"success":true,"data":{"token":"...", ...user fields...}}`. The client must find the token tolerantly (section 8.3).

**Token transport.** All three user GETs answer without a token with the same HTTP 400 `{"success":false,"message":"توکن الزامی است."}` ("Token is required."), headers `Allow: GET` (`api_GET_user_*.json`) [V]. That is shared middleware. The header name is not revealed. Candidates, by evidence:
1. `X-Token: <token>` (High-ish: it is the only custom token header in all site JS, used for `/post/tv`, `signin.raw.html` line 874 and `euploadi.com.index.html` line 3655).
2. `Authorization: Bearer <token>` (WordPress' CORS list advertises `Authorization`; but that list is the WordPress default and says nothing about this plugin).
3. A `token` request parameter (what `tracker.php` uses; section 6).

No probe with a dummy token was made on purpose (the brief allowed GETs without a token only). The first real-login run tries all three (section 11).

**The guest token printed in pages.** Every page prints `const token = "<64 hex>"` next to `const is_logged_in = 0`. For guests the value is **random on every request** (at least seven fetches, all different, including two fetches of the same movie URL minutes apart) [V]. Likely `bin2hex(random_bytes(32))`-style for guests; for a signed-in user the page presumably prints that user's persistent API token, which the JS then sends as `X-Token` to `/post/tv` ([I], Med). If true, it is the same token that `login` returns, and a cookie session on the website could be exchanged for an API token by reading that constant (not needed if `login` works).

## 4. Is `login-code` a TV pairing flow? (Q3)

What was searched: every saved page and script for `login-code`, `کد`, `tv`, `تلویزیون`, `pair`, `device`, `اتصال` (`home`, `sign-in`, `movie`, `series`, `play`, `/app/`, `script.min.js`, `plyr.setup.min.js`, `site-data.js`). Results [V]:
- No page, form or JS references `login-code`. The only TV related strings are the `پخش در تلویزیون` button (section 7) and the download list on `https://euploadi.com/app/` ("دانلود برای Android TV" plus a QR image). That page carries no instructions about codes (`app.raw.html` lines 883-905).
- The user panel (`/panel/...`) is behind login, so where a code might be displayed or entered is **not observable** as a guest. The theme JS has no TV or code handler for the panel either (its actions: profile save, watch-list, notifications, tickets, subscription discount code, requests).
- The route index has **no route that creates a code** (`mapi/v1` has `login`, `login-code`, `post/tv`, `post/*`, `user/*`, comments, `actor|director/*`, `file/*`). A device-code flow where the TV asks the server for a code (like Netflix or YouTube TV) needs such a route, so that design is unlikely unless the code is generated by a panel admin-ajax action we cannot see.

Conclusion: most likely (Low-Med, about 55 %) the server generates a short code that the user reads from their logged-in website panel and types into the app, which `POST`s it as `code` and gets back the same kind of token as `login`. Alternatives: (b) the TV shows a code and the user types it into a panel page (needs an admin-ajax action we cannot see), (c) an emailed login code (the site's only email flow is password reset). **Do not build the UI for any of these until a real login shows the panel.** What would settle it quickly: the user signs in to the website on a PC and looks for a TV/app/"کد" item in the panel menu (a screenshot is enough), or the TV APK is decompiled (needs permission).

## 5. Watch-list and favourites (Q4)

Live, 1 GET each, no token (`api_GET_user_*.json`) [V]:

| Request | HTTP | Body |
|---|---|---|
| `GET /wp-json/mapi/v1/user/profile` | 400 | `{"success":false,"message":"توکن الزامی است."}` |
| `GET /wp-json/mapi/v1/user/watch-list` | 400 | same |
| `GET /wp-json/mapi/v1/user/favorite-list` | 400 | same |

Route index: `GET` and `POST` on both lists, `GET` only on profile; no declared args anywhere [V]. So the filter, paging and POST body parameters cannot be read from the index and the token check hides the validation messages. Educated guesses, **all Low**:
- `GET` list: paged envelope like `post/movies` (`success, per_page, page, total_page, data[]` of list-item objects), because the same plugin returns that for every list.
- `POST` list: form field `post_id` (the website's AJAX actions use `post_id`); possibly toggles (the website's `yascode_add_watchlist_post` toggles and answers `act: add|remove`) or takes an extra `type`/`action`.
- There may be two separate server lists (`watch-list` and `favorite-list`); the website public JS exposes only one bookmark.

## 6. `tracker.php` "keep-alive" (Q5)

**What the site JS does.** Only one call exists in all captured pages, in the home page script (`research/webapp/euploadi.com.index.html` lines 1074-1118; same text in the `uuploadi.ir` capture) [V]:

```js
formData.append('action', 'remove');
formData.append('post_id', post_id);
formData.append('token', '<64 hex>|0');          // guest value; userId after the pipe
fetch('https://majnoonbazar.ir/tracker.php', { method: 'POST', body: formData })  // expects {success, message?}
```

`isLoggedIn` is a hard-coded `false` in the guest page (line 1017), and the continue-watching slider markup (`.keep-alive-box`, `.keep-alive-slider`, `.remove-keep-item`) is absent. `localStorage.keepAliveHidden11` only stores whether the box is collapsed. No other `tracker` reference exists in the home, sign-in, movie, series, play or `/app/` pages, nor in `script.min.js`, `plyr.setup.min.js`, the 485 KB Plyr bundle or `site-data.js`. So **no `save`, `progress` or `list` action is visible to a guest**; they would live in the logged-in `/play/` page or in the official apps ([?]).

**The endpoint (one `GET`, no params, no token)** [V]: `https://majnoonbazar.ir/tracker.php` answered HTTP 403 `{"success":false,"message":"Forbidden: No token provided"}` with `access-control-allow-origin: *`, `access-control-allow-methods: POST, OPTIONS`, `access-control-allow-headers: Content-Type`, `server: LiteSpeed` (a different server stack from the nginx WordPress host). Consequences:
- The token is checked first and is passed as a **form field `token`** (the CORS header list contains only `Content-Type`, so no custom auth header is accepted from browsers).
- It is a separate small PHP service, not part of `mapi/v1`.

**Token format `<hash>|<userId>`.** The guest value was byte-identical in the saved `euploadi.com` capture, the saved `uuploadi.ir` capture and a fresh fetch hours later, so it is **not per request, per session or per mirror**; it is a deterministic function of the user id ([I], Med): `<64 hex hash of the id with a server secret>|<id>`, `0` meaning guest. That differs from the random API `X-Token`, so the two tokens are not the same thing. A native app cannot derive the hash. It could obtain it only if `GET /user/profile` returns it (check in section 11) or by scraping the logged-in home page through a cookie session (fragile).

**Could it sync TV progress?** Possibly, but not today: the `save` action name and fields are unknown, we cannot get the user's token, and probing it would need an account. Recommendation: keep task 3.8 deferred; Room stays the source of truth. Re-evaluate after the real-login run reveals whether `profile` carries the tracker token and what the logged-in `/play/` page posts (the user's browser DevTools on `/play/<id>/` would show it in one minute).

## 7. `POST /post/tv` "play on TV" (Q6)

**What the website sends** [V]. Detail pages render one button per download row, `data-link` is HTML-escaped JSON (`research/auth/movie_353371.raw.html` lines 1865, 1882, 1899; `series_624236.raw.html` lines 1843-1943):

```html
<button class="play-on-tv" data-link="{&quot;post_id&quot;:353371,&quot;type&quot;:&quot;sub&quot;,&quot;quality&quot;:&quot;BluRay 1080p&quot;,&quot;season&quot;:null,&quot;episode&quot;:null}">پخش در تلویزیون</button>
```

| Key | Movie | Series (S1 E1) | Notes |
|---|---|---|---|
| `post_id` | `353371` (int) | `624236` (int) | |
| `type` | `"sub"` | `"sub"` | `"dub"` presumably for dubbed rows (not seen) |
| `quality` | `"BluRay 1080p"` | `"WEB-DL 1080p"` | The *download* label (`download_links[].quality_link` / series `quality`), not the numeric `1080` |
| `season` | `null` | `"1"` (string) | |
| `episode` | `null` | `"1"` (string) | |

The guest movie page shows 3 buttons (the sub rows 1080/720/480); its dub rows show a sign-in prompt instead (`movie_353371.raw.html` lines 1903-1950, class `vip-required`, text `برای دانلود باید ثبت‌نام کنید یا وارد شوید` = "to download you must register or log in"), so a `type: "dub"` payload was not observed. The sample series has 3 buttons per episode. So the payload identifies **a title plus a specific file (audio type + quality + season + episode)**, i.e. the TV app, once it knows this, can resolve the stream through `GET /post/{id}` or `GET /post/player-links?id=` (section 9).

**The call** (identical in every page, e.g. `signin.raw.html` lines 857-905) [V]: refuses guests (`is_logged_in === 0` redirects to `/sign-in/`), otherwise `jQuery.ajax({url:'/wp-json/mapi/v1/post/tv', type:'POST', dataType:'json', data: <data-link object>, beforeSend: xhr => xhr.setRequestHeader('X-Token', token)})`. jQuery sends `application/x-www-form-urlencoded`; `null` values go out as empty strings (`season=&episode=` for a movie). Success is `response.success` with `response.data.message` shown for **50 seconds** (`ShowAlert(50, 'success', ...)`); anything else shows the toast `خطایی پیش اومده`. The 50 s display hints at a server-side TTL of the pushed command or a "wait for the TV" message ([I], Low).

**How the TV receives it: unknown [?]. Hypotheses:**
1. **Polling** by the official TV app with the same token (Med-Low). The only GET routes that could carry it are `user/profile`, the two lists, and `post/player-links`. The last is attractive: it answers `{"success":false,"message":"پست یافت نشد."}` (404, "post not found") without parameters even though it needs no token, which fits "return the pushed item, else not found". With `?id=` it simply returns the links of that post (section 9). Testable: sign in, run `probe_login.py --poll-tv 60`, press the website button, see whether the 404 changes.
2. **FCM push** to the official app (Low-Med). Not usable by us: the push would go to the official app's Firebase project and device token.
3. Nothing is stored and the response is only an ack (unlikely).

Recommendation: task 3.7 depends on the hypothesis-1 test. If it fails, drop 3.7 (the user can just use the TV remote; the whole feature saves a few seconds).

## 8. Recommended implementation plan (Q7)

Design rule: **everything account-related is optional and fails soft.** The app is fully usable as a guest; losing the token or the server never blocks playback. Start tolerant, tighten after the first real login.

### 8.1 Screens (`feature/account`, Persian RTL, D-pad first)

1. **Account entry** (Settings → حساب کاربری). Two actions: «ورود با نام کاربری» (shown always) and «ورود با کد» (hidden behind flag `loginCodeEnabled`, default off until section 4 is settled).
2. **Password login screen**, fields in focus order:
   - `نام کاربری یا ایمیل` (text, LTR content, no autocorrect, `ImeAction.Next`)
   - `رمز عبور` (password, show/hide toggle, `ImeAction.Done`)
   - button «ورود» (disabled while empty or in flight), link «ورود با کد» if enabled.
   - Typing aid: reuse the app's own Persian/English keyboard (M2.4) in a password mode so the English layout is one key away; no voice input for passwords; **do not** route credentials through the LAN phone remote (M3.5): that server is plain HTTP.
3. **Code login screen** (only if confirmed): one numeric/alphanumeric field; text explains where to find the code (to be written after verification).
4. **States**: loading, wrong credentials (our own text, generic: «نام کاربری یا رمز عبور اشتباه است»), network/mirror down, unexpected response («پاسخ نامعتبر؛ دوباره تلاش کنید»). At most **one automatic attempt**; never retry a failed login in a loop (unknown lockout rules).
5. **Account screen**: shows the name/email from `profile` when available, «همگام‌سازی اکنون», «خروج» (deletes token, keeps local lists, marks sync off).

### 8.2 Endpoints the client will call

| Purpose | Call | Status |
|---|---|---|
| Password login | `POST mapi/v1/login`, form `username`, `password` | fields Med, response unknown |
| Code login | `POST mapi/v1/login-code`, form `code` | behind flag |
| Profile / validate token | `GET mapi/v1/user/profile` + token | shape unknown |
| Pull lists | `GET mapi/v1/user/watch-list`, `GET mapi/v1/user/favorite-list` + token | shape unknown |
| Push list change | `POST mapi/v1/user/<list>` form `post_id` (+ maybe more) + token | body unknown |

All go through `core/network`'s mirror list (rule 7). Whether a token issued on one mirror is accepted on the other is unverified; the two mirrors share one backend (identical tracker hash, same routes), so probably yes.

### 8.3 Code structure (respecting module boundaries)

- `core/model` (manager-owned): `Session(userLabel: String?)`, `AuthRepository { suspend fun login(user, pass): AuthResult; suspend fun loginWithCode(code): AuthResult; val session: StateFlow<Session?>; fun logout() }`, `RemoteListRepository { pull(list): List<PostId>; set(list, id, inList) }`. Ask the manager for these files.
- `core/network`: `AuthService` (Retrofit, returns `JsonElement` at first), `AuthInterceptor`, `TokenProvider` (interface, implemented in `core/data`). DTOs stay inside the module.
- `core/data`: `AuthRepositoryImpl`, `TokenStore`, `ListSyncRepository` (uses the existing `my_list` Room table from `core/database`).
- `feature/account`: composables and `AccountViewModel` only.

Tolerant token extraction (replace by a typed DTO after the first real response is known):

```kotlin
fun JsonElement.findToken(): String? = when (this) {
    is JsonObject -> listOf("token", "access_token", "api_token", "auth_token")
        .firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
        ?: values.firstNotNullOfOrNull { it.findToken() }
    is JsonArray -> firstNotNullOfOrNull { it.findToken() }
    else -> null
}
```

Debug builds only: log the **shape** (keys and JSON types) of the auth responses, never values (`research/auth/probe_login.py` does the same from a PC).

### 8.4 Token handling

- `AuthInterceptor` adds `X-Token: <token>` **only** to requests whose host is in the mirror list and whose path starts with `/wp-json/mapi/v1/user/` or equals `/wp-json/mapi/v1/post/tv`. Never to CDN, image or stream hosts; never forward across a cross-host redirect (OkHttp keeps custom headers on redirects, so disable `followRedirects` for these calls or re-check the host).
- If the first real run shows another transport (section 3), change this one class only.
- Remove `X-Token` from any `HttpLoggingInterceptor` (`redactHeader("X-Token")`) and keep logging off in release.
- Storage: a non-exportable Android Keystore AES-GCM key encrypting the token blob stored in DataStore (or Tink AEAD with a Keystore master key). `androidx.security:security-crypto` (EncryptedSharedPreferences) was reported deprecated upstream; check its status before choosing it. minSdk 29 supports Keystore AES-GCM. Set `android:allowBackup="false"` for the blob. **The password is never stored**; the username may be (to prefill).
- Expiry is unknown. Treat any `success:false` on a `user/*` call made with a token as "maybe expired": retry once, then drop to signed-out state and show a non-blocking banner «نشست شما منقضی شده است». Store the login time so the lifetime can be learned.

### 8.5 Sync (task 3.3)

- Room (`my_list`) stays the source of truth for the UI; the server lists are a mirror. Do not block UI on the network.
- Pull after sign-in, on app start (after first paint, background, at most once per 10 minutes), and after each local change. No periodic polling.
- Push with a tiny outbox. Because the POST may be a toggle, implement `setInList(id, wanted)` as: read the current server state (cached pull), POST only when it differs, then re-pull to confirm. This is safe for both add-only/toggle semantics.
- Map the server's post ids to the same ids the catalogue uses (`post/{id}`); the website and API share ids (`353371` everywhere).
- First sync after login: union of local and server, no deletes (avoids wiping a list because of a parsing mistake).
- Watch progress stays local (section 6). "Continue watching" keeps coming from Room.

### 8.6 Fallback if `mapi` lists are unusable

Sign in through the website path (`GET /sign-in/` with an iPhone Safari UA to read `ajax_var.nonce`, then `POST admin-ajax.php action=yascode_ajax_login fields=username=..&password=.. _wpnonce=..`, keep the WordPress cookies) and use `yascode_add_watchlist_post` / `yascode_panel_watchlist_toggle` to write. There is no JSON read for the lists (HTML scraping of the panel), so this is write-only and fragile. Not recommended; listed so nobody rediscovers it.

## 9. Side finding: `GET /post/player-links?id=<postId>` (public)

`GET /wp-json/mapi/v1/post/player-links?id=353371` returned HTTP 200 without any token [V] (`api_GET_player-links_id.json`): `{"success":true,"data":{"download_links":{...},"player_links":{...}}}`, the same two maps as the movie detail (keys `"0","3","4","1","2","5"`; same field names as docs/api.md 5.4.1). `?post_id=353371` still gave 404 `پست یافت نشد.`, so the parameter is named `id`. Series behaviour (season or episode params) was not tested. It is a lighter way to refetch links than the full detail.

## 10. What is still unknown (needs a real login)

| Unknown | Why it matters | How to settle |
|---|---|---|
| `login` request encoding, field names, response shape, token key, token lifetime | Core of 3.2 | probe script, section 11 |
| Header name for the token on `/user/*` | Every sync call | probe tries `X-Token`, `Authorization: Bearer`, `?token=` |
| `profile` shape; does it include a tracker token, TV code or subscription state | 3.8, UI | probe prints shape |
| List GET shape/paging, POST body, toggle vs add, `watch-list` vs `favorite-list` meaning | 3.3 | probe `--toggle`; plus bookmark a title on the website and compare both GETs |
| Where a login code comes from, TTL, single use | 3.2 | panel screenshot, or decompile with permission |
| `/post/tv` response text and the delivery channel | 3.7 | probe `--poll-tv 60` while pressing the website button |
| Tracker save/list actions and token source | 3.8 | DevTools on a logged-in `/play/<id>/` page |
| Token valid on both mirrors | failover | probe `--cross-mirror https://uuploadi.ir` (same token, `GET user/profile` on the other host) |
| Lockout or rate limit on wrong passwords | UX | do not test deliberately; one attempt max |

## 11. Verification run with the user's real account

`research/auth/probe_login.py` (Python 3, standard library only, **not run yet**). It logs in once, tries the three token transports on `GET user/profile`, reads both lists, optionally sends one `POST` toggle, optionally polls for the pushed item, and prints **only shapes** (keys, JSON types, string lengths and a format hint like `hex64`, `hex64|digits`, `jwt-like`); tokens, passwords and user values are never printed or saved. Credentials come from `BOLBOL_USER` / `BOLBOL_PASS` in the environment or the gitignored `.env` (rule 5: the values are never echoed).

```bash
python3 -I research/auth/probe_login.py                                  # login + profile + both lists
python3 -I research/auth/probe_login.py --toggle favorite-list --post-id 353371   # one POST, then re-read
python3 -I research/auth/probe_login.py --poll-tv 60                     # press "play on TV" on the website now
python3 -I research/auth/probe_login.py --cross-mirror https://uuploadi.ir  # is the token accepted on the other mirror?
```

Output is safe to paste into chat. Run it on the user's PC, or have the user run it; a research agent must not. The user can alternatively type the credentials on the real app once 3.2 exists; the in-app debug shape log (8.3) gives the same information.

Extra manual checks the user can do in a desktop browser while signed in (no secrets needed in the report): (a) the panel menu: any item about TV, app or "کد" (screenshot); (b) DevTools Network on `/play/<id>/`: any request to `tracker.php` and its `action` value (no need to copy the token); (c) the response body of `POST /wp-json/mapi/v1/post/tv` after pressing the button.

## 12. Capture index (`research/auth/`)

| File | What it is |
|---|---|
| `signin.raw.html`, `signin.headers` | `GET /sign-in/` (iPhone Safari UA). Guest `const token` redacted to `<GUEST_RANDOM_64HEX_REDACTED>`, PHP session cookie redacted in all `*.headers` |
| `forms_signup_forget_panel.html` | The `<form data-form=...>` blocks of `/sign-up`, `/forget-password`, and the `/panel` redirect target |
| `panel_redirect.headers` | Redirect chain of `/panel` to `/sign-in/` |
| `script.min.js` (+ headers) | Theme script with the login/register/forget/watch-list handlers (single line) |
| `plyr.setup.min.js`, `site-data.js` (+ headers) | Player setup (no tracker call) and the search-index loader (no auth) |
| `movie_353371.raw.html`, `series_624236.raw.html` | Detail pages with the `play-on-tv` buttons and `data-link` payloads (tokens redacted) |
| `play_353371.raw.html` | Guest view of the online player page ("you must be a member") |
| `app.raw.html`, `apk_head_bolbol-tv.headers` | `/app/` download page; `HEAD` of the TV APK (no body downloaded) |
| `api_POST_empty_login.json`, `api_POST_empty_login-code.json` | Empty-body POST error shapes (+ `.headers`) |
| `api_GET_user_profile.json`, `api_GET_user_watch-list.json`, `api_GET_user_favorite-list.json` | Token-required errors (+ `.headers`) |
| `api_GET_player-links_post_id.json`, `api_GET_player-links_id.json` | `player-links` with the wrong and the right parameter name |
| `tracker_GET.body`, `tracker_GET.headers` | The one parameterless `GET` on `majnoonbazar.ir/tracker.php` |
| `probe_login.py` | Shape-only verification script for the user's real login (not run) |

Request log (all 2026-10-11, 13:25-13:50 UTC): `euploadi.com` pages `/sign-in/`, `/sign-up`, `/forget-password`, `/panel` (4 hops), `/`, `/movies/353371/` (twice), `/series/624236/`, `/play/353371/?dubbed=0`, `/app/`; scripts `script.min.js`, `script-bundle.min.js`, `plyr.setup.min.js`, Plyr `bundle.min.js`, `site-data.js`; API: `GET user/profile|watch-list|favorite-list` (1 each), `POST login` and `login-code` (1 each, empty body), `GET post/player-links` (2); `HEAD emkanat/bolbol-tv.apk`; `GET majnoonbazar.ir/tracker.php` (1). One web search found nothing about this service.

## 13. Suggested edits to `docs/api.md` (for the manager)

- §3 table: `POST /login`, `/login-code`: add "400 validation shape verified (auth.md §3)"; `GET /user/*`: "400 token required"; `GET /post/player-links`: "public with `?id=`".
- §5.6: `player-links` needs `id`, returns `{download_links, player_links}`.
- §9: replace by a pointer to this document; keep the `X-Token` / `tracker.php` caveats but note the guest `X-Token` is random per request and the tracker guest token is constant.
