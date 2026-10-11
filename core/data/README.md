# :core:data

The data layer. `:app` depends on this module only; it implements the `:core:model` interfaces on top of
`:core:network` (Retrofit, DTOs, mappers, mirror failover) and `:core:database` (Room).

## Public API
```kotlin
class DataGraph(context: Context, mirrors: List<String> = DEFAULT_MIRRORS, imageHosts: List<String> = DEFAULT_IMAGE_HOSTS) {
    val catalog: CatalogRepository   // homeFeed(), details(id), list(query, page), search(text, page)
    val watch: WatchRepository       // continueWatching(), progress(), saveProgress(), markWatched(), ...
    val prefs: PrefsRepository       // playback prefs, subtitle style, per-title subtitle offset
    val account: AccountRepository   // optional login + "My List" (لیست من), synced best effort with the site
    val accountVipDaysLeft: Flow<Int?>             // VIP days of the signed-in user, for AccountViewModel.factory
    suspend fun pushProgress(p: PlaybackProgress)  // player exit: position to the server (no-op signed out)
    suspend fun pullProgress()                     // app start: fill local progress from the server
    val okHttpClient: OkHttpClient   // the ONE shared client: API, Coil, Media3
    val currentMirror: String; val currentImageHost: String
    fun setMirrors(mirrors: List<String>); fun setImageHosts(imageHosts: List<String>)
}
```
Everything is created lazily; constructing `DataGraph` does no I/O. One Room DB (`bolbol.db`) and one
OkHttp client per graph. Repository implementations are `internal`.

## Wiring in `:app`
```kotlin
class BolbolApp : Application() { val data by lazy { DataGraph(this) } }
// ViewModels:   viewModelFactory { initializer { HomeViewModel((this[APPLICATION_KEY] as BolbolApp).data.catalog) } }
// Coil 3:       ImageLoader.Builder(ctx).components { add(OkHttpNetworkFetcherFactory(data.okHttpClient)) }
// Media3:       DefaultMediaSourceFactory(OkHttpDataSource.Factory(data.okHttpClient))   // media3-datasource-okhttp
```
Errors are always `CatalogException` (Offline, AllMirrorsDown, NotFound, BadResponse); map them to Persian text in the UI.

## Caching
- **Home:** `homeFeed()` emits the cached rows (`fromCache = true`) if any, then fetches, stores the raw
  server JSON in Room (`home_cache`, one row) and emits fresh rows (`fromCache = false`). Network failure with a
  cache: the flow just completes. Without a cache: it throws. Unparsable cache, empty fresh payload and cache
  write failures are all handled without losing the good cache. Every collection refetches.
- **Details:** `post/{id}` and `post/player-links?id=` are fetched in parallel; streams/seasons come from player-links when it
  succeeds with links of the title's kind, else from the detail's own links (a failing player-links never fails the details).
  Seasons carry sub and dub streams per episode (`Episode.streams`, all qualities); dub blocks may cover fewer episodes.
  The merged result sits in an in-memory LRU of 32 titles, 10 min TTL, failures not cached. Lists/search are not cached;
  blank search sends nothing.
- **HTTP:** 50 MB OkHttp disk cache in `cacheDir/http` (mainly images and videos); connect 10 s, read 20 s.
- **Subtitles:** `subtitleStyle()` emits `SubtitleStyle()` until saved (table `subtitle_style`, one row); the per-title offset
  (`subtitle_offset`, no row = 0) is rounded to 0.5 s and clamped to +-10 s on save. Own tables, so playback prefs never overwrite them.
- **Watch progress:** `saveProgress` upserts snapshot + progress in one transaction (un-hides the title); completed is
  forced at >= 95 %. Continue Watching = latest progress row per title, not completed, not hidden, newest first.

## Mirrors and image CDN
Default list: `https://euploadi.com/`, `https://uuploadi.ir/`, then the official TV app's `https://playgen.ir/` and
`https://arzanycenter.ir/` (`NetworkModule.DEFAULT_MIRRORS`, docs/api.md section 1, docs/auth.md section 0).
Only `/wp-json/` requests are rewritten; image and stream URLs are never touched. A call goes to the last mirror
that worked and moves to the next one on an IOException, HTTP 5xx or a non-JSON (HTML) body; all failing gives
`Offline` (nothing reachable) or `AllMirrorsDown` (mirrors answered with errors). The good mirror is remembered in
memory only. JSON 4xx answers (404 not found) are real answers and do not fail over.

**Images:** the API's own hosts 404 on `/wp-content/uploads/` files, so every image URL the repositories return (poster,
backdrop, photos, related) gets the primary image CDN's scheme+host (path/query kept; trailers etc. untouched).
`NetworkModule.DEFAULT_IMAGE_HOSTS`: `https://majnoonbazar.ir/` (primary), `https://seo2024.ir/`. `ImageCdnInterceptor`
(same client) retries an uploads request on the next host after an IOException, non-2xx status or non-image body and
remembers the good host; `/wp-json/` and streams never match. Cached home JSON is re-parsed, so old caches get fixed URLs.

## Account and My List (docs/auth.md section 0 and 8.7)
Optional: playback never needs it. `DataGraph.account` is the `AccountRepository` of `:core:model`.
- **Login** `POST login` (form `username`, `password`) gives `data.token`, `display_name`, `vip`. One attempt per call, no retries.
  Wrong credentials, network and server problems are different `LoginResult`s; a 400 saying a field is "required" is a
  `Failed` (our request shape was refused), never "wrong password".
- **Session** (token, name, VIP days) lives in the private SharedPreferences file `account_session` in `SessionStore`
  (also the `TokenProvider` of the HTTP layer). The app sandbox is the protection (private sideloaded app, `allowBackup` is
  false); no Keystore. The password is never stored. Logout deletes the file and keeps the list.
- **Token header** `Authorization: Bearer <token>` is added by `AuthTokenInterceptor` (a network interceptor) to
  `/wp-json/mapi/v1/user/` calls on a mirror origin only, never to images, streams, the catalogue or after a redirect elsewhere.
  A 401/403 on such a call drops the session.
- **My List** is Room table `my_list` (`pendingSync` 0 synced / 1 add / 2 removed tombstone), the source of truth for the UI;
  changes never wait for the network. `syncMyList()` mirrors it to the site's `favorite-list` with the rules in
  `MyListMerge.kt` (union on first login, server removals only for rows without pending changes, nothing deleted from an
  incomplete read, each pending id toggled once after reading the state, confirmed by a second read). Throttled to once per
  10 min unless changes are pending; a local change schedules a push after 800 ms. Never throws.
- **Server progress** `pushProgress` / `pullProgress` (see docs/auth.md 8.7). Wire up: player exit and app start.
- **Debug**: `adb shell setprop log.tag.BolbolAccount DEBUG` logs only the shape (keys and types) of account answers.

## Tests
`./gradlew :core:network:testDebugUnitTest :core:data:testDebugUnitTest` (JVM only; MockWebServer and DAO fakes; the account has `FakeMyListDao`/`FakeAccountRemote` in `AccountFakes.kt`). Room SQL
is verified by Room at compile time only; exercise it on the emulator. DB version 1 is edited in place while unreleased:
an installed debug build must clear app data after schema changes.
