#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$ROOT/build/test-classes"

rm -rf "$OUT"
mkdir -p "$OUT"

PURE_SOURCES=(
    "$ROOT/src/ee/wut/xqz/enabler/MonitorProtocol.java"
    "$ROOT/src/ee/wut/xqz/enabler/RetryBackoff.java"
    "$ROOT/src/ee/wut/xqz/enabler/HeartbeatLoop.java"
    "$ROOT/src/ee/wut/xqz/enabler/RunState.java"
)

TEST_SOURCES=()
while IFS= read -r -d '' file; do
    TEST_SOURCES+=("$file")
done < <(find "$ROOT/test" -name '*.java' -print0)

javac --release 17 -d "$OUT" "${PURE_SOURCES[@]}" "${TEST_SOURCES[@]}"
java -ea -cp "$OUT" ee.wut.xqz.enabler.AllTests
