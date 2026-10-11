#!/usr/bin/env bash
# tools/release.sh: build the release APK, sign it with the permanent key using v1+v2+v3
# (maximum installer compatibility on TVs), verify, and copy to dist/mehrnaz-tv-v<version>.apk.
# The key lives outside the repo: ~/.bolboltv/keystore.properties (+ release.jks). Never commit it.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)"
PROPS="$HOME/.bolboltv/keystore.properties"
[[ -f "$PROPS" ]] || { echo "release.sh: missing $PROPS (the permanent signing key)" >&2; exit 1; }
prop() { grep "^$1=" "$PROPS" | cut -d= -f2-; }

"$ROOT/tools/gw.sh" :app:assembleRelease -q
IN="$ROOT/app/build/outputs/apk/release/app-release.apk"
VERSION="$("$BT/aapt2" dump badging "$IN" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)"
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
