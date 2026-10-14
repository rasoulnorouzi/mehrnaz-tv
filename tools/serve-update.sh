#!/usr/bin/env bash
# tools/serve-update.sh <apk> [notes]: serve a TEST update from this PC for the emulator.
# Writes update.json (versionCode/versionName read from the APK; apkUrl points at http://10.0.2.2:PORT, the
# emulator's alias for this PC) and serves the folder with python's http.server until Ctrl+C.
# In the emulator app: Settings -> توسعه‌دهنده -> «رایانهٔ من (شبیه‌ساز)», then Settings -> به‌روزرسانی.
# Needs a debug/test build whose network security config allows cleartext to 10.0.2.2 (docs/update.md).
set -euo pipefail
APK="${1:?usage: tools/serve-update.sh <apk> [notes]}"
NOTES="${2:-نسخهٔ آزمایشی}"
PORT="${PORT:-8000}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)"
DIR="$(mktemp -d)"
trap 'rm -rf "$DIR"' EXIT
cp "$APK" "$DIR/test-update.apk"
BADGING="$("$BT/aapt2" dump badging "$APK")"
VERSION="$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"$BADGING" | head -1)"
CODE="$(sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" <<<"$BADGING" | head -1)"
SIZE="$(stat -c %s "$APK")"
SHA="$(sha256sum "$APK" | cut -d' ' -f1)"
python3 - "$DIR/update.json" "$CODE" "$VERSION" "http://10.0.2.2:$PORT/test-update.apk" "$SIZE" "$SHA" "$NOTES" <<'PY'
import json, sys
path, code, name, url, size, sha, notes = sys.argv[1:]
json.dump({"versionCode": int(code), "versionName": name, "apkUrl": url, "sizeBytes": int(size),
           "sha256": sha, "notesFa": notes}, open(path, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
PY
echo "Serving versionCode=$CODE versionName=$VERSION at http://10.0.2.2:$PORT/update.json (Ctrl+C to stop)"
cd "$DIR" && exec python3 -m http.server "$PORT" --bind 0.0.0.0
