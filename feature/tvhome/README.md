# :feature:tvhome: Continue Watching on the Android TV home (Watch Next / "Play Next")
`androidx.tvprovider`, not the Engage SDK (needs Google allowlisting; see `docs/decisions.md`).

## API (`app.bolbol.tv.feature.tvhome`)
- `WatchNextPublisher(context)`: `suspend publish(items): Boolean` makes the launcher match `items` (ours only: insert
  new, update changed, delete stale). Idempotent, IO-dispatched, logs and returns `false` on failure (only cancellation
  propagates). Input equal to the last successful publish does no work. `suspend clear()` deletes all our programs.
- `DeepLinks`: `play(key)` = `bolbol://play/{postId}/{season}/{episode}` (movies `/0/0`); `parse(uri)` is its strict inverse.
- One program per title (newest episode wins), newest first, max 20; finished items, unknown duration or blank title are
  skipped. `WATCH_NEXT_TYPE_CONTINUE`, `TYPE_MOVIE`/`TYPE_TV_EPISODE` («فصل ۱ · قسمت ۲»), poster = 16:9 backdrop
  (falls back to the 2:3 poster), `internalProviderId` = `WatchKey.asString()`.

## Permissions and behaviour (checked 2026-10-11 against AOSP TvProvider android10-release)
- Manifest: `com.android.providers.tv.permission.WRITE_EPG_DATA` (the provider's `writePermission`). Protection level
  `normal`: auto-granted at install, no prompt. It is enforced for writes (Google's docs say declare it), and
  `tvprovider`'s own manifest merges it anyway. `READ_EPG_DATA` is deprecated and not enforced. No runtime permission.
- Apps see only their own rows. Queries must pass **no selection** (SecurityException), so we filter in code.
- `browsable` is system-only (writing it throws). When the user removes a card the system sets `browsable = 0` and sends
  `ACTION_WATCH_NEXT_PROGRAM_BROWSABLE_DISABLED` (no receiver needed; the next publish sees the row). We keep that row as a
  marker and re-add the title only once `progress.updatedAtMs` is newer (marker deleted, fresh program inserted).
- Changed rows are updated in place; `internalProviderFlag1` stores a content fingerprint used to detect changes.
- Unverified on the Mi TV P1: stock Android TV home shows the row, PatchWall may ignore it; the launcher may add its own
  "S1 E2" next to the episode label (check with `tools/tv.sh shot`).
## Wiring in the app
```kotlin
val publisher = WatchNextPublisher(app)          // app scope, not a ViewModel
appScope.launch { watch.continueWatching().debounce(2_000).collect { publisher.publish(it) } }
```
Poster URLs are mirror-specific; a new URL changes the fingerprint and republishes. Call `clear()` to switch the feature off.

## Manifest the app needs (`:app`, inside `MainActivity`; handle `onNewIntent` if singleTop/singleTask)
```xml
<intent-filter>
  <action android:name="android.intent.action.VIEW" />
  <category android:name="android.intent.category.DEFAULT" />
  <category android:name="android.intent.category.BROWSABLE" />
  <data android:scheme="bolbol" android:host="play" />
</intent-filter>
```
Then `DeepLinks.parse(intent.data)?.let { key -> openPlayer(key) }`.
