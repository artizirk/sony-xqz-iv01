#!/usr/bin/env bash
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-35/android.jar"
ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$ROOT/build"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

"$BT/aapt2" compile --dir "$ROOT/res" -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/unsigned.apk" -I "$PLATFORM" \
    --manifest "$ROOT/AndroidManifest.xml" -R "$OUT/res.zip" --auto-add-overlay

find "$ROOT/src" -name '*.java' > "$OUT/sources.txt"
javac --release 17 -classpath "$PLATFORM" -d "$OUT/classes" @"$OUT/sources.txt"

find "$OUT/classes" -name '*.class' -print0 \
    | xargs -0 "$BT/d8" --lib "$PLATFORM" --min-api 29 --output "$OUT/dex"

(cd "$OUT/dex" && zip -q "$OUT/unsigned.apk" classes.dex)

"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

KS="$ROOT/release.keystore"
STORE_PASS="${XQZ_STORE_PASS:-xqz-release}"
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -alias xqz \
        -storepass "$STORE_PASS" -keypass "$STORE_PASS" \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=XQZ Enabler,O=Arti,C=EE"
fi

"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$STORE_PASS" \
    --key-pass "pass:$STORE_PASS" --out "$ROOT/XQZEnabler.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "$ROOT/XQZEnabler.apk"

echo "APK: $ROOT/XQZEnabler.apk"
