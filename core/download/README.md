# :core:download — offline saving engine (v1.4)

Implements core:model's `DownloadRepository` (`Downloads.kt`). UI: `:feature:downloads` and `:feature:details`;
playback of a saved copy: `:feature:playback` (`PlayerViewModel(downloads = …)`). Plan: `docs/plan.md` §v1.4.

## Engine choice: own resumable OkHttp writer to a plain file
Media3's `DownloadManager` + `DownloadService` + `SimpleCache` was the suggested option; we chose a ~400-line writer because:
- **A saved copy is one ordinary `.mp4`.** The player plays it with `DefaultDataSource` (`file://` → `FileDataSource`),
  the same ExoPlayer, LoadControl and memory budget as streaming. No `SimpleCache` instance has to be shared between
  `:core:download` and `:core:player` (it locks its folder: one instance per process), and the streaming path never
  goes through a `CacheDataSource`.
- **One source of truth.** Room's `download` table (DB v2) holds status, bytes and paths. `DownloadManager` would add
  its own SQLite index (media3-database) that must be kept in step with ours, plus ~5 MB span files per movie.
- **Small and predictable on 2 GB RAM:** a 64 KB buffer, one coroutine, no extra process-wide index in memory.
- What Media3 would have given us for free and we rebuilt: HTTP Range resume (+ `If-Range` with the ETag /
  Last-Modified), retry with back-off, pause/resume on network loss, and the foreground service. All of it is unit-tested
  against a fake CDN (MockWebServer) in `DownloadEngineTest`.

## How it works
| Piece | What |
|---|---|
| `DownloadRepositoryImpl(context, okHttpClient, dao, userAgent?)` | The public entry. Starts the queue on construction (create it at app start so interrupted downloads continue). Uses `okHttpClient.newBuilder()` without the disk cache, connect 10 s / read 30 s. |
| `DownloadEngine` | One download at a time, oldest QUEUED first. Saves the subtitle (`subtitle.vtt`, ≤ 2 MB) and poster (`poster.jpg`, ≤ 5 MB) first (best effort, retried after the video), then the video as `video.<ext>.part` → renamed on completion. Progress to Room about once a second. |
| `RangePlan` | 206 at our offset → append; 200 → start over (no range support, or `If-Range` saw a changed file); 416 with our size → done; 4xx → FAILED/SOURCE_GONE; 5xx/408/429/I-O → retry. |
| `SpaceCheck` | `start()` refuses when size + **300 MB** (+ what other queued downloads still need) > free (`StatFs`). While writing: under 64 MB free → FAILED/NO_SPACE. |
| `DownloadRetry` | No network → PAUSED/NETWORK until the `ConnectivityManager` callback (no attempts used). Network up → PAUSED, queued again after 5 s, 15 s, 30 s, 60 s, 2 min…; 8 failures in a row without a saved byte → FAILED/NETWORK. `retry()` gives a fresh budget. |
| `DownloadService` | Foreground service (`dataSync`), started when a download begins; it stops itself (after `startForeground`, never before: that crashes on API 28+) when the queue is idle; one ongoing notification «در حال دانلود» + title + Persian percent. Partial wake lock + Wi-Fi lock while active. Not sticky: after a process death the queue resumes at the next app start (`requeueInterrupted`). |
| `sizeOf(stream)` | HEAD → `Content-Length`; servers without HEAD → `Range: bytes=0-0` → `Content-Range` total. Cached per URL (successes only). |
| `offlineMedia(key)` | COMPLETED + the file exists → `OfflineMedia(stream.url = file:///…/video.mp4, subtitleUri = file:///…/subtitle.vtt)`. If the subtitle couldn't be saved, `stream.subtitleUrl` keeps the remote URL (loads when online). |

Files: `filesDir/downloads/<postId>_<season>_<episode>/` (app-private: no storage permission; removed on uninstall).
`DownloadItem.title.posterUrl` is the saved poster's `file://` URI once saved. `remove()` deletes the row first (the
queue can't pick it again), cancels the running transfer, then the folder; `removeAll()` also clears orphan folders.

`start()` rules: a COMPLETED item with its file → `AlreadySaved`; an unfinished item of the **same** stream → `Started`
(FAILED/PAUSED are retried, keeping the bytes); of **another** stream → replaced; offline → `Failed(NETWORK)`.

Logcat: `adb logcat -s BolbolDownload` (pauses, failures, sidecar problems).

## Wiring in `:app` (manual DI)
```kotlin
// build.gradle.kts: implementation(project(":core:download"))
val downloads: DownloadRepository by lazy { DownloadRepositoryImpl(this, data.okHttpClient, data.downloadDao) }
// onCreate: create it once in the background so interrupted downloads continue (keeps cold start free):
appScope.launch { downloads }
```
Construction makes the folder, registers a network callback and launches the queue on `Dispatchers.IO`.
The manifest here adds the service and INTERNET, ACCESS_NETWORK_STATE, WAKE_LOCK, FOREGROUND_SERVICE(_DATA_SYNC) and
POST_NOTIFICATIONS (harmless on the API 30 TV).

## Tests
`DownloadLogicTest` (space rule, Content-Range, RangePlan, retry delays, file layout, entity ↔ model mapping) and
`DownloadEngineTest` (full save, resume after restart with Range/If-Range, changed file, no range support, dropped
connection, 5xx retry, 8-failure limit + retry, 404, offline pause/resume, space refusal, disk filling up, remove while
running, one at a time, quality replacement, size probe + cache). The DB v1→v2 migration is tested in `:core:database`.
