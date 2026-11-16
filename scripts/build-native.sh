#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to an installed Android NDK (tested with 27.2.12479018)}"

case "$(uname -s)" in
  Linux) ndk_host=linux-x86_64 ;;
  Darwin) ndk_host=darwin-x86_64 ;;
  *) echo 'Use Linux or macOS to build the Android libraries.' >&2; exit 1 ;;
esac
compiler_dir="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$ndk_host/bin"

for abi in arm64-v8a armeabi-v7a; do
  case "$abi" in
    arm64-v8a) go_arch=arm64; compiler=aarch64-linux-android26-clang ;;
    armeabi-v7a) go_arch=arm; compiler=armv7a-linux-androideabi26-clang ;;
  esac
  test -x "$compiler_dir/$compiler" || { echo "Missing compiler: $compiler_dir/$compiler" >&2; exit 1; }
  mkdir -p "app/src/main/jniLibs/$abi"
  GOOS=android GOARCH="$go_arch" GOARM=7 CGO_ENABLED=1 CC="$compiler_dir/$compiler" \
    go build -trimpath -buildmode=c-shared \
    -ldflags='-w -s -linkmode=external -extldflags=-Wl,-z,common-page-size=16384,-z,max-page-size=16384' \
    -o "app/src/main/jniLibs/$abi/libumbraproxy.so" .
done
