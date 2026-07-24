#!/usr/bin/env bash
# Cross-compile the ECH proxy for Android arm64-v8a.
# Output: app/src/main/jniLibs/arm64-v8a/libechproxy.so
#
# The .so extension is required for Android to extract the binary from the APK
# into the app's nativeLibraryDir with executable permission.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
OUTPUT_DIR="$PROJECT_DIR/app/src/main/jniLibs"

echo "=== Building ECH proxy (Go) ==="

cd "$SCRIPT_DIR"

# Build for arm64-v8a (primary target)
mkdir -p "$OUTPUT_DIR/arm64-v8a"
CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build \
    -ldflags="-s -w" \
    -trimpath \
    -o "$OUTPUT_DIR/arm64-v8a/libechproxy.so" \
    .

echo "Built: $OUTPUT_DIR/arm64-v8a/libechproxy.so"
ls -lh "$OUTPUT_DIR/arm64-v8a/libechproxy.so"

# Also build for x86_64 (emulator testing)
mkdir -p "$OUTPUT_DIR/x86_64"
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build \
    -ldflags="-s -w" \
    -trimpath \
    -o "$OUTPUT_DIR/x86_64/libechproxy.so" \
    .

echo "Built: $OUTPUT_DIR/x86_64/libechproxy.so"
ls -lh "$OUTPUT_DIR/x86_64/libechproxy.so"

echo "=== ECH proxy build complete ==="
