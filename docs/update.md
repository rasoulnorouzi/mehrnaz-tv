# In-app update (v1.4)

The app updates itself: no more building, sending and installing from the PC.

## How it works
- **Source:** the Releases of the app's own repo `rasoulnorouzi/mehrnaz-tv` (public since 2026-10-14; each release carries the APK + `update.json`).
  Manifest: `https://github.com/rasoulnorouzi/mehrnaz-tv/releases/latest/download/update.json`
  (`{versionCode, versionName, apkUrl, sizeBytes, sha256, notesFa}`; parsing is lenient, see `UpdateManifest`).
- **`core:update`** (`UpdateRepositoryImpl`): `check()` fetches the manifest (redirects followed, network only; at most
  once per 24 h unless forced; the last manifest is cached so Settings › «به‌روزرسانی» still shows an available update within the 24 h; there is no Home banner, user choice). A higher
  `versionCode` than the installed one = `Available`.
- `downloadAndInstall()`: asks the PackageInstaller permission first (`NeedsInstallPermission` before any download),
  checks free space (2x size + 32 MB), streams the APK to `cache/updates` with progress, verifies SHA-256 (mismatch:
  deleted + `CHECKSUM_MISMATCH`), then commits a `PackageInstaller` session; Android's confirmation screen is started
  by `InstallResultReceiver`. Same signing key, so it installs over the old version and keeps data. Old APKs are
  deleted on the next download or up-to-date check.
- **One-time switch:** Android must allow «مهرناز» to install apps (Settings -> Apps -> Special access -> Install
  unknown apps). The Settings page explains it and opens that screen (`ACTION_MANAGE_UNKNOWN_APP_SOURCES`). Android
  may restart the app when the switch changes; the cached manifest makes the update show up again at once.
- **Settings -> به‌روزرسانی:** version, «بررسی به‌روزرسانی», state text, notes, «دانلود و نصب». Opening the page
  runs a throttled check. **Settings -> توسعه‌دهنده:** manifest URL override + presets.

## Publishing a release
1. Bump `versionCode`/`versionName` in `app/build.gradle.kts`.
2. `tools/release.sh --notes "تغییرات به فارسی"` builds, signs, and writes `dist/update.json` (check it).
3. `tools/release.sh --publish --notes "..."` (or `--notes-file f.txt`) runs
   `gh release create v<version> --repo rasoulnorouzi/mehrnaz-tv <apk> update.json`.
   `releases/latest/download/update.json` always points at the newest release.
`gh` must be logged in (`gh auth login`). The newest release must contain `update.json` (the app reads `releases/latest/download/update.json`).

## Testing on the emulator
1. Build an APK with a higher versionCode than the installed one; `tools/serve-update.sh path/to.apk`.
2. In the app: developer settings -> «رایانهٔ من (شبیه‌ساز)» (sets `http://10.0.2.2:8000/update.json`).
3. Settings -> به‌روزرسانی -> check -> download and install.
Cleartext HTTP to `10.0.2.2` needs a network security config in `:app` (debug only):
`<domain-config cleartextTrafficPermitted="true"><domain>10.0.2.2</domain></domain-config>`.
Grant the install permission on the emulator with
`adb shell appops set app.mehrnaz.tv REQUEST_INSTALL_PACKAGES allow`.

## Learned on the Android 11 emulator (2026-10-14, v1.4 QA)
- Turning on «Install unknown apps» for «مهرناز» makes Android **close the app** (`REQUEST_INSTALL_PACKAGES changed`).
  The user reopens it and presses «دانلود و نصب» again; the Persian explanation says so.
- After the install, Android closes the app again (normal for a self-update); the status text tells the user to reopen it.
- The PackageInstaller result `PendingIntent` must name `InstallResultReceiver` explicitly (the receiver has no intent
  filter); with only action + package the confirmation screen never appeared.
- Verified: v1.4.0 → v1.4.1 test build through Settings › «به‌روزرسانی», Android's confirm dialog, history kept.
