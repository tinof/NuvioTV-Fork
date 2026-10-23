#!/usr/bin/env bash
# Build libnuvio_rpu.so (native/rpu_builder, ROADMAP DV2.2) for arm64-v8a with cargo-ndk and put
# it in app/src/main/jniLibs/arm64-v8a/, where Gradle packages it. Local only: CI builds no Rust.
#
#   scripts/build_rpu_builder.sh
#
# Needs: cargo with cargo-ndk (`cargo install cargo-ndk --locked`), the NDK version that
# app/build.gradle.kts names under $ANDROID_HOME/ndk. rustup installs the toolchain and target
# of native/rpu_builder/rust-toolchain.toml on the first run. Without the library the app
# plays everything natively (DoviRpuBuilder.isAvailable() is false).
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
crate="$root/native/rpu_builder"
out="$root/app/src/main/jniLibs"
lib="$out/arm64-v8a/libnuvio_rpu.so"

command -v cargo >/dev/null || { echo "cargo not found (rustup.rs)" >&2; exit 1; }
cargo ndk --version >/dev/null 2>&1 || {
  echo "cargo-ndk not found: cargo install cargo-ndk --locked" >&2; exit 1; }

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ndk_version="$(sed -n 's/^ *ndkVersion *= *"\([^"]*\)".*/\1/p' "$root/app/build.gradle.kts" | head -1)"
[ -n "$ndk_version" ] || { echo "no ndkVersion in app/build.gradle.kts" >&2; exit 1; }
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$ndk_version"
[ -d "$ANDROID_NDK_HOME" ] || {
  echo "NDK $ndk_version not found at $ANDROID_NDK_HOME (the first Gradle build installs it)" >&2; exit 1; }

# From the crate directory, so cargo reads its rust-toolchain.toml and .cargo/config.toml.
cd "$crate"
cargo ndk -t arm64-v8a --platform 24 -o "$out" build --release --locked

[ -f "$lib" ] || { echo "cargo-ndk wrote no $lib" >&2; exit 1; }
# 16 KB pages, as the app's other native libraries. NDK 29's llvm-readelf is a symlink to
# llvm-readobj, so a glob, not `find -type f`.
readelf=""
for candidate in "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin/llvm-readelf; do
  [ -x "$candidate" ] && { readelf="$candidate"; break; }
done
[ -n "$readelf" ] || {
  echo "no llvm-readelf under $ANDROID_NDK_HOME/toolchains/llvm/prebuilt; cannot check the 16 KB alignment" >&2; exit 1; }
loads="$("$readelf" -lW "$lib" | awk '$1 == "LOAD"')"
[ -n "$loads" ] || { echo "$readelf found no LOAD segments in $lib" >&2; exit 1; }
bad_align="$(echo "$loads" | awk '$NF != "0x4000"')"
[ -z "$bad_align" ] || { echo "LOAD segments not 16 KB aligned in $lib:" >&2; echo "$bad_align" >&2; exit 1; }
echo "== $lib"
echo "   align   $(echo "$loads" | wc -l | tr -d ' ') LOAD segments at 0x4000"
echo "   size    $(wc -c < "$lib" | tr -d ' ') bytes"
echo "   sha256  $(shasum -a 256 "$lib" | cut -d' ' -f1)"
