#!/usr/bin/env bash
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-35/android.jar"
ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$ROOT/build"
KS="$ROOT/release.keystore"

need_command() {
    command -v "$1" >/dev/null 2>&1 || {
        echo "error: required command not found: $1" >&2
        exit 1
    }
}

need_file() {
    [ -e "$1" ] || {
        echo "error: required file not found: $1" >&2
        exit 1
    }
}

need_command javac
need_command zip
need_command keytool
for tool in aapt2 d8 zipalign apksigner; do
    need_file "$BT/$tool"
done
need_file "$PLATFORM"

JAVAC_VERSION="$(javac -version 2>&1 | sed -n 's/^javac //p' | tail -1)"
JAVA_MAJOR="$(printf '%s\n' "$JAVAC_VERSION" | sed -n 's/^\([0-9][0-9]*\).*/\1/p')"
if ! [[ "$JAVA_MAJOR" =~ ^[0-9]+$ ]] || [ "$JAVA_MAJOR" -lt 17 ]; then
    echo "error: JDK 17 or newer is required (found: ${JAVAC_VERSION:-unknown})" >&2
    exit 1
fi

if [ "${XQZ_SKIP_TESTS:-0}" != "1" ]; then
    "$ROOT/test.sh"
fi

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/gen"

"$BT/aapt2" compile --dir "$ROOT/res" -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/unsigned.apk" -I "$PLATFORM" \
    --manifest "$ROOT/AndroidManifest.xml" -R "$OUT/res.zip" \
    --java "$OUT/gen" --auto-add-overlay

find "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
find "$ROOT/src" -name '*.java' >> "$OUT/sources.txt"
javac --release 17 -classpath "$PLATFORM" -d "$OUT/classes" @"$OUT/sources.txt"

find "$OUT/classes" -name '*.class' -print0 \
    | xargs -0 "$BT/d8" --lib "$PLATFORM" --min-api 29 --output "$OUT/dex"

(cd "$OUT/dex" && zip -q "$OUT/unsigned.apk" classes.dex)

"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

STORE_PASS="${XQZ_STORE_PASS:-}"
if [ -z "$STORE_PASS" ] && [ -f "$ROOT/.storepass" ]; then
    STORE_PASS="$(cat "$ROOT/.storepass")"
fi
if [ -z "$STORE_PASS" ]; then
    echo "error: set XQZ_STORE_PASS or create spike/.storepass with the keystore password." >&2
    exit 1
fi

if [ ! -f "$KS" ]; then
    if [ "${XQZ_ALLOW_NEW_KEY:-0}" != "1" ]; then
        echo "error: $KS not found." >&2
        echo "       Restore the release keystore so updates install over the released APK." >&2
        echo "       To create a new signing identity intentionally, rerun with XQZ_ALLOW_NEW_KEY=1." >&2
        exit 1
    fi
    keytool -genkeypair -keystore "$KS" -alias xqz \
        -storepass "$STORE_PASS" -keypass "$STORE_PASS" \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=XQZ Enabler,O=Arti,C=EE"
fi

"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$STORE_PASS" \
    --key-pass "pass:$STORE_PASS" --out "$ROOT/XQZEnabler.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "$ROOT/XQZEnabler.apk"

echo "APK: $ROOT/XQZEnabler.apk"
