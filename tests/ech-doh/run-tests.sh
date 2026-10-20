#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="${REPO_ROOT:-$(cd -- "$SCRIPT_DIR/../.." && pwd)}"
SRC="$REPO_ROOT/app/src/main/java/com/yenaly/han1meviewer"
OUT="$SCRIPT_DIR/out"

for required in "$SRC/logic/network/ech/EchDoh.kt" "$SRC/logic/network/ech/EchHosts.kt" "$SRC/logic/network/HDns.kt" "$SRC/logic/network/DohConfig.kt"; do
  [[ -f "$required" ]] || { echo "Missing production source: $required" >&2; exit 2; }
done

GRADLE_LIB="${KOTLIN_GRADLE_LIB:-}"
if [[ -z "$GRADLE_LIB" ]]; then
  GRADLE_LIB="$(find "$HOME/.gradle/wrapper/dists" -path '*/gradle-*/lib' -type d 2>/dev/null | sort -V | tail -n 1)"
fi
[[ -n "$GRADLE_LIB" && -d "$GRADLE_LIB" ]] || { echo "Could not locate Gradle Kotlin compiler libraries; set KOTLIN_GRADLE_LIB" >&2; exit 2; }

REF_LIB="${ECH_DOH_REFERENCE_DIR:-$SCRIPT_DIR}/lib"
DEPS="${TMPDIR:-/tmp}/han1me-ech-doh-deps"
mkdir -p "$DEPS" "$OUT"
fetch_or_use() {
  local filename="$1" url="$2"
  if [[ -s "$REF_LIB/$filename" ]]; then
    printf '%s' "$REF_LIB/$filename"
  elif [[ -s "$DEPS/$filename" ]]; then
    printf '%s' "$DEPS/$filename"
  else
    echo "Downloading test dependency $filename" >&2
    curl --fail --location --silent --show-error "$url" -o "$DEPS/$filename.tmp"
    mv -f "$DEPS/$filename.tmp" "$DEPS/$filename"
    printf '%s' "$DEPS/$filename"
  fi
}
OKHTTP="$(fetch_or_use okhttp.jar https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp-jvm/5.4.0/okhttp-jvm-5.4.0.jar)"
OKIO="$(fetch_or_use okio.jar https://repo1.maven.org/maven2/com/squareup/okio/okio-jvm/3.16.0/okio-jvm-3.16.0.jar)"
DOH="$(fetch_or_use doh.jar https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp-dnsoverhttps/5.4.0/okhttp-dnsoverhttps-5.4.0.jar)"
STDLIB="$(find "$GRADLE_LIB" -maxdepth 1 -name 'kotlin-stdlib-*.jar' ! -name '*sources*' | sort -V | tail -n 1)"
ANNOTATIONS="$(find "$GRADLE_LIB" -maxdepth 1 -name 'annotations-*.jar' ! -name '*sources*' | sort -V | tail -n 1)"
[[ -s "$STDLIB" && -s "$ANNOTATIONS" ]] || { echo "Could not locate Kotlin stdlib/annotations in $GRADLE_LIB" >&2; exit 2; }
CP="$STDLIB:$ANNOTATIONS:$OKHTTP:$OKIO:$DOH"

rm -rf "$OUT" && mkdir -p "$OUT"
java -cp "$GRADLE_LIB/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -classpath "$CP" -d "$OUT" \
  "$SCRIPT_DIR/stubs/android/util/AndroidStubs.kt" \
  "$SCRIPT_DIR/stubs/com/yenaly/han1meviewer/AppStubs.kt" \
  "$SCRIPT_DIR/stubs/com/yenaly/han1meviewer/logic/network/ech/SupportStubs.kt" \
  "$SRC/logic/network/DohConfig.kt" \
  "$SRC/logic/network/HDns.kt" \
  "$SRC/logic/network/ech/EchDoh.kt" \
  "$SRC/logic/network/ech/EchHosts.kt" \
  "$SCRIPT_DIR/Regression.kt" 2>&1 | tee "$SCRIPT_DIR/compile.log"
java -Dech.test.dir="$SCRIPT_DIR" -cp "$OUT:$CP" regression.RegressionKt 2>&1 | tee "$SCRIPT_DIR/test.log"
