#!/usr/bin/env bash
# Build the full debug (or benchmark) APK locally with upstream's toolchain (JDK 17) and,
# optionally, install it on the Shield over adb with `install -r` and check the installed hash.
# libnuvio_rpu.so is built first by scripts/build_rpu_builder.sh when cargo-ndk is installed.
#
#   scripts/shield_build_install.sh [--suffix .shield] [--benchmark] [--debuggable] [--install] [--allow-empty-config]
#
# - --suffix      applicationId suffix (default .shield, the same package CI builds).
# - --benchmark   build :app:assembleFullBenchmark (release-like, for UI timings) instead of debug.
# - --debuggable  pass -Pdebuggable=true (heap dumps, run-as backups). Use a separate suffix,
#                 e.g. .shielddbg, so it never replaces the CI build.
# - --install     install on $ANDROID_SERIAL (required) and compare the on-device sha256.
# - --allow-empty-config  build without NUVIO_SUPABASE_URL in local.dev.properties / local.properties
#                 (compile check only: the app cannot log in).
#
# Signing: NUVIO_DEBUG_STORE_FILE, else ~/.config/nuvio/nuvio-debug.keystore when it exists,
# else AGP's ~/.android/debug.keystore. The .shield package on the Shield is signed with the CI
# key; installing a build with another key over it fails, so this script refuses that up front.
# It never uninstalls and never clears app data.
set -euo pipefail

suffix=".shield"
variant="Debug"
debuggable=false
install=false
allow_empty_config=false
while [ $# -gt 0 ]; do
  case "$1" in
    --suffix) suffix="$2"; shift 2 ;;
    --benchmark) variant="Benchmark"; shift ;;
    --debuggable) debuggable=true; shift ;;
    --install) install=true; shift ;;
    --allow-empty-config) allow_empty_config=true; shift ;;
    -h|--help) sed -n '2,19p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null)" || {
  echo "JDK 17 not found (upstream's toolchain; Gradle 8.13 does not run on newer JDKs)" >&2; exit 1; }
export JAVA_HOME
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"

if [ -z "${NUVIO_DEBUG_STORE_FILE:-}" ] && [ -f "$HOME/.config/nuvio/nuvio-debug.keystore" ]; then
  export NUVIO_DEBUG_STORE_FILE="$HOME/.config/nuvio/nuvio-debug.keystore"
fi
key_desc="${NUVIO_DEBUG_STORE_FILE:-AGP default debug key}"

if [ "$suffix" = ".shield" ] && [ -z "${NUVIO_DEBUG_STORE_FILE:-}" ]; then
  echo "suffix .shield needs the CI debug keystore (NUVIO_DEBUG_STORE_FILE or ~/.config/nuvio/nuvio-debug.keystore);" >&2
  echo "use another suffix, e.g. --suffix .shieldlocal, to build beside it" >&2
  exit 1
fi

# Same rule as CI: without the Supabase URL the APK builds but cannot log in.
supabase_set() { [ -f "$1" ] && grep -Eq '^[[:space:]]*NUVIO_SUPABASE_URL[[:space:]]*=[[:space:]]*[^[:space:]]' "$1"; }
if ! supabase_set local.dev.properties && ! supabase_set local.properties && ! $allow_empty_config; then
  echo "NUVIO_SUPABASE_URL is not set in local.dev.properties or local.properties; the APK would not log in." >&2
  echo "Put the CI properties files in the repo root, or pass --allow-empty-config for a compile check." >&2
  exit 1
fi

head_sha="$(git rev-parse HEAD)"
dirty=""
[ -n "$(git status --porcelain --untracked-files=no)" ] && dirty=" (dirty tree)"

# libnuvio_rpu.so (Dolby Vision conversion, arm64-v8a only) is built by cargo-ndk, not Gradle.
rpu_lib="app/src/main/jniLibs/arm64-v8a/libnuvio_rpu.so"
if cargo ndk --version >/dev/null 2>&1; then
  scripts/build_rpu_builder.sh
elif [ -f "$rpu_lib" ]; then
  echo "warning: cargo-ndk not found; packaging the existing $rpu_lib without rebuilding it" >&2
else
  echo "warning: cargo-ndk not found; the APK has no libnuvio_rpu.so, so Dolby Vision conversion is off" >&2
fi

gradle_args=(":app:assembleFull${variant}" "-PnuvioAppIdSuffix=${suffix}")
$debuggable && gradle_args+=("-Pdebuggable=true")
echo "== building ${gradle_args[*]} at ${head_sha:0:9}${dirty}, key: ${key_desc}"
./gradlew "${gradle_args[@]}"

lower="$(echo "$variant" | tr '[:upper:]' '[:lower:]')"
apk="app/build/outputs/apk/full/${lower}/app-full-arm64-v8a-${lower}.apk"
[ -f "$apk" ] || { echo "no arm64 APK at $apk" >&2; ls "app/build/outputs/apk/full/${lower}" >&2; exit 1; }

tools="$(find "$ANDROID_HOME/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
pkg="$("$tools/aapt2" dump badging "$apk" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
cert="$("$tools/apksigner" verify --print-certs "$apk" | sed -n 's/.*SHA-256 digest: //p' | head -1)"
local_sha="$(shasum -a 256 "$apk" | cut -d' ' -f1)"
echo "== $apk"
echo "   package $pkg"
echo "   cert    $cert"
echo "   sha256  $local_sha"
# Outside build/, so `./gradlew clean` keeps it; /ship:roadmap-next's new-build probe reads it.
mkdir -p .local
printf '%s\t%s%s\t%s\t%s\t%s\n' "$(date -u +%FT%TZ)" "$head_sha" "$dirty" "$pkg" "$local_sha" "$cert" >> .local/shield-builds.tsv

$install || exit 0

serial="${ANDROID_SERIAL:?set ANDROID_SERIAL to the Shield (host:5555) to install}"
adb() { gtimeout 60 command adb -s "$serial" "$@"; }
# A ~120 MB APK over Wi-Fi needs longer than a shell call.
adb_long() { gtimeout 600 command adb -s "$serial" "$@"; }
device="$(adb shell getprop ro.product.device | tr -d '\r')"
[ "$device" = "mdarcy" ] || { echo "device is '$device', not mdarcy; not installing" >&2; exit 1; }

# A key mismatch would fail the install anyway; check first so the error is clear.
# pm path exits 1 when the package is not installed.
installed_path="$( (adb shell pm path "$pkg" 2>/dev/null || true) | head -1 | sed 's/^package://' | tr -d '\r')"
if [ -n "$installed_path" ]; then
  tmp="$(mktemp -d)"
  adb_long pull "$installed_path" "$tmp/installed.apk" >/dev/null
  installed_cert="$("$tools/apksigner" verify --print-certs "$tmp/installed.apk" | sed -n 's/.*SHA-256 digest: //p' | head -1)"
  rm -rf "$tmp"
  if [ "$installed_cert" != "$cert" ]; then
    echo "installed $pkg is signed with $installed_cert, this build with $cert; not installing (no uninstall)" >&2
    exit 1
  fi
fi

echo "== installing $pkg on $device"
adb_long install -r "$apk"
device_path="$(adb shell pm path "$pkg" | head -1 | sed 's/^package://' | tr -d '\r')"
device_sha="$(adb shell sha256sum "$device_path" | cut -d' ' -f1 | tr -d '\r')"
if [ "$device_sha" = "$local_sha" ]; then
  echo "== installed hash matches: $device_sha"
else
  echo "installed hash $device_sha does not match $local_sha" >&2
  exit 1
fi
