#!/usr/bin/env bash
# tools/release.sh: build the release APK, sign it with the permanent key using v1+v2+v3
# (maximum installer compatibility on TVs), verify, and copy to dist/mehrnaz-tv-v<version>.apk.
# The key lives outside the repo: ~/.bolboltv/keystore.properties (+ release.jks). Never commit it.
#
# Usage: tools/release.sh [--publish] [--notes "نکته‌های فارسی"] [--notes-file FILE]
#   Without --publish: build + sign only (dist/ gets the APK, its .sha256 and update.json).
#   With --publish: also `gh release create v<version>` on the public repo below with the APK and update.json.
#   The in-app updater reads update.json (docs/update.md). Run --publish only when the manager says so.
set -euo pipefail
REPO="rasoulnorouzi/mehrnaz-releases"
PUBLISH=0; NOTES=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --publish) PUBLISH=1 ;;
    --notes) NOTES="${2:?--notes needs a text}"; shift ;;
    --notes-file) NOTES="$(cat "${2:?--notes-file needs a path}")"; shift ;;
    *) echo "release.sh: unknown argument $1" >&2; exit 2 ;;
  esac
  shift
done
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)"
PROPS="$HOME/.bolboltv/keystore.properties"
[[ -f "$PROPS" ]] || { echo "release.sh: missing $PROPS (the permanent signing key)" >&2; exit 1; }
prop() { grep "^$1=" "$PROPS" | cut -d= -f2-; }

"$ROOT/tools/gw.sh" :app:assembleRelease -q
IN="$ROOT/app/build/outputs/apk/release/app-release.apk"
BADGING="$("$BT/aapt2" dump badging "$IN")"
VERSION="$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"$BADGING" | head -1)"
VERSION_CODE="$(sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" <<<"$BADGING" | head -1)"
mkdir -p "$ROOT/dist"
OUT="$ROOT/dist/mehrnaz-tv-v$VERSION.apk"
"$BT/zipalign" -f -p 4 "$IN" "$OUT.tmp"
"$BT/apksigner" sign --ks "$(prop storeFile)" --ks-key-alias "$(prop keyAlias)" \
  --ks-pass "pass:$(prop storePassword)" --key-pass "pass:$(prop keyPassword)" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUT" "$OUT.tmp"
rm -f "$OUT.tmp" "$OUT.idsig"
"$BT/apksigner" verify -v "$OUT" | grep -E "Verified using v[123] scheme"
sha256sum "$OUT" | tee "$OUT.sha256"
echo "Release APK: $OUT"

# update.json for the in-app updater. python3 builds the JSON so Persian notes are escaped correctly.
SIZE="$(stat -c %s "$OUT")"
SHA="$(sha256sum "$OUT" | cut -d' ' -f1)"
APK_NAME="$(basename "$OUT")"
python3 - "$ROOT/dist/update.json" "$VERSION_CODE" "$VERSION" \
  "https://github.com/$REPO/releases/download/v$VERSION/$APK_NAME" "$SIZE" "$SHA" "$NOTES" <<'PY'
import json, sys
path, code, name, url, size, sha, notes = sys.argv[1:]
with open(path, "w", encoding="utf-8") as f:
    json.dump({"versionCode": int(code), "versionName": name, "apkUrl": url, "sizeBytes": int(size),
               "sha256": sha, "notesFa": notes}, f, ensure_ascii=False, indent=2)
    f.write("\n")
PY
echo "update.json: $ROOT/dist/update.json"

if [[ $PUBLISH == 1 ]]; then
  command -v gh >/dev/null || { echo "release.sh: gh CLI not found" >&2; exit 1; }
  gh release create "v$VERSION" --repo "$REPO" "$OUT" "$ROOT/dist/update.json" \
    --title "مهرناز $VERSION" --notes "${NOTES:-Mehrnaz $VERSION}"
  echo "Published v$VERSION to $REPO"
fi
