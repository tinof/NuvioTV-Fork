#!/usr/bin/env bash
# Build the full debug (or benchmark) APK and, optionally, install it on the Shield over adb with
# `install -r` and check the installed hash. By default the build runs in the cloud: the private repo
# tinof/NuvioTV-Builds builds HEAD, and scripts/shield_cloud_build.py downloads and verifies the APK
# (scripts/cloud-build/README.md). --local builds this tree on the Mac with upstream's toolchain (JDK 17);
# libnuvio_rpu.so is built first by scripts/build_rpu_builder.sh when cargo-ndk is installed.
#
#   scripts/shield_build_install.sh [--local] [--suffix .shield] [--benchmark] [--debuggable] [--install]
#                                   [--rebuild] [--allow-empty-config]
#
# - --local       build here with Gradle: the way to build a dirty tree or a commit that is not on origin.
# - --suffix      applicationId suffix (default .shield, the same package CI builds).
# - --benchmark   build :app:assembleFullBenchmark (release-like, for UI timings) instead of debug.
# - --debuggable  pass -Pdebuggable=true (heap dumps, run-as backups); debug builds only. Use a separate
#                 suffix, e.g. .shielddbg, so it never replaces the CI build.
# - --install     install on $ANDROID_SERIAL (required) and compare the on-device sha256.
# - --rebuild     cloud: dispatch a new build even when a verified download or a finished run of HEAD with
#                 the same inputs exists; a run still going is attached to. A --local build always builds.
# - --allow-empty-config  with --local: build without NUVIO_SUPABASE_URL in local.dev.properties /
#                 local.properties (compile check only: the app cannot log in).
#
# Cloud: the tree must be clean, untracked files included, and HEAD on an origin branch. A debug build
# takes about 30 minutes; NUVIO_CLOUD_WAIT_MIN (default 100) bounds the wait. Exit 75 means the wait
# ended while the run was still going: nothing is cancelled, so run the same command again to attach to
# it. The cloud signs with the CI debug key, which must be the Mac's (apksigner and keytool need a JVM).
#
# Signing (--local): NUVIO_DEBUG_STORE_FILE, else ~/.config/nuvio/nuvio-debug.keystore when it exists,
# else AGP's ~/.android/debug.keystore. The .shield package on the Shield is signed with the CI
# key; installing a build with another key over it fails, so this script refuses that up front.
# It never uninstalls and never clears app data.
#
# Version: upstream's app/build.gradle.kts falls back to versionCode 1568 / "1.1.0-nt.9.2" without
# -ParcVersionCode / -ParcVersionName, below the 1579 / "1.1.0-nt.10" fork builds already installed,
# so `install -r` would be refused as a downgrade. The script passes 1580 / "1.1.0-nt.10.shield"
# (VersionUtils ranks that name above nt.10 and below a later nt.11 release);
# NUVIO_VERSION_CODE and NUVIO_VERSION_NAME override them (keep the code above the installed one).
#
# Every build runs sync.py protected-apk on the APK and stops when it fails. It also checks where the
# native code came from: libnuvio_rpu.so against the stamp build_rpu_builder.sh wrote (the cloud's for a
# cloud build), and whether libffmpegJNI.so is the prebuilt AAR's or the local module's (a cloud build
# links the pinned FFmpeg asset, unpacked for this check into .local/cloud-ffmpeg/).
# NUVIO_ALLOW_NATIVE_MISMATCH=1 keeps a build that fails only on that origin (exit 4). Nothing keeps a
# lost marker or a missing library.
#
# .local/shield-builds.tsv gets a row per build: time, commit (with " (dirty tree)" for a --local build of
# a changed tree), package, sha256, certificate, and for a cloud build a sixth column cloud:<run id>.
set -euo pipefail

suffix=".shield"
variant="Debug"
debuggable=false
install=false
allow_empty_config=false
local_build=false
rebuild=false
while [ $# -gt 0 ]; do
  case "$1" in
    --local) local_build=true; shift ;;
    --suffix) suffix="$2"; shift 2 ;;
    --benchmark) variant="Benchmark"; shift ;;
    --debuggable) debuggable=true; shift ;;
    --install) install=true; shift ;;
    --rebuild) rebuild=true; shift ;;
    --allow-empty-config) allow_empty_config=true; shift ;;
    -h|--help) sed -n '2,46p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
if [ "$variant" = "Benchmark" ] && $debuggable; then
  echo "--debuggable is for debug builds: a benchmark build is never debuggable" >&2; exit 2
fi
if $allow_empty_config && ! $local_build; then
  echo "--allow-empty-config needs --local: the cloud build always gets the Supabase URL of its secret" >&2; exit 2
fi

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
version_code="${NUVIO_VERSION_CODE:-1580}"
version_name="${NUVIO_VERSION_NAME:-1.1.0-nt.10.shield}"
case "$version_code" in
  ''|*[!0-9]*) echo "NUVIO_VERSION_CODE must be a whole number, was '$version_code'" >&2; exit 2 ;;
esac

# This tree, built on the Mac. Sets head_sha, dirty, apk and stamp for the checks below.
build_local() {
  JAVA_HOME="${JAVA17_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null)}" || {
    echo "JDK 17 not found, set JAVA17_HOME (upstream's toolchain; Gradle 8.13 does not run on newer JDKs)" >&2; exit 1; }
  export JAVA_HOME

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
    echo "== libnuvio_rpu.so rebuilt by cargo-ndk"
  elif [ -f "$rpu_lib" ]; then
    echo "warning: cargo-ndk not found; packaging the existing $rpu_lib without rebuilding it" \
      "(sha256 $(shasum -a 256 "$rpu_lib" | cut -d' ' -f1), modified $(date -r "$rpu_lib" '+%Y-%m-%d %H:%M:%S'))" >&2
  else
    echo "warning: cargo-ndk not found; the APK has no libnuvio_rpu.so, so Dolby Vision conversion is off" >&2
  fi

  gradle_args=(":app:assembleFull${variant}" "-PnuvioAppIdSuffix=${suffix}"
    "-ParcVersionCode=${version_code}" "-ParcVersionName=${version_name}")
  $debuggable && gradle_args+=("-Pdebuggable=true")
  echo "== building ${gradle_args[*]} at ${head_sha:0:9}${dirty}, key: ${key_desc}"
  scripts/stop_kotlin_lsp.sh "$root"
  # Backstop: a KSP incremental cache corrupted by an earlier crash or a concurrent build fails every
  # later build the same way. Delete it and retry once without the daemon, which still holds the
  # broken cache files open ("Storage for [...kspCaches...] is already registered").
  gradle_log="$(mktemp -t shield-gradle)"
  if ! ./gradlew "${gradle_args[@]}" 2>&1 | tee "$gradle_log"; then
    if grep -Eq 'ksp\.com\.intellij\.util\.io\.|kspCaches/.*is already registered' "$gradle_log"; then
      rm -f "$gradle_log"
      echo "== the KSP incremental cache is corrupt; deleting app/build/kspCaches and retrying once with --no-daemon" >&2
      rm -rf app/build/kspCaches
      ./gradlew --no-daemon "${gradle_args[@]}"
    else
      rm -f "$gradle_log"
      exit 1
    fi
  fi
  rm -f "$gradle_log"

  lower="$(echo "$variant" | tr '[:upper:]' '[:lower:]')"
  apk="app/build/outputs/apk/full/${lower}/app-full-arm64-v8a-${lower}.apk"
  [ -f "$apk" ] || { echo "no arm64 APK at $apk" >&2; ls "app/build/outputs/apk/full/${lower}" >&2; exit 1; }
  stamp="$rpu_lib.stamp"
  build_column=""
}

# HEAD, built by the build repo's runner. Sets head_sha, dirty, apk, stamp and ffmpeg_dir.
build_cloud() {
  # apksigner runs the java on PATH, and shield_cloud_build.py the keytool of JAVA_HOME; any version does.
  local jdk="${JAVA17_HOME:-${JAVA_HOME:-}}"
  if [ -z "$jdk" ] && [ -x /usr/libexec/java_home ]; then jdk="$(/usr/libexec/java_home 2>/dev/null || true)"; fi
  if [ -n "$jdk" ] && [ -x "$jdk/bin/java" ]; then
    export JAVA_HOME="$jdk"
    export PATH="$jdk/bin:$PATH"
  fi
  java -version >/dev/null 2>&1 || {
    echo "no Java runtime for apksigner and keytool; set JAVA17_HOME or JAVA_HOME, or put a JDK on PATH" >&2; exit 1; }

  local args=(build --variant "$variant" --suffix "$suffix" --version-code "$version_code" --version-name "$version_name")
  $debuggable && args+=(--debuggable)
  $rebuild && args+=(--rebuild)
  # Exit 75: the wait ended and the run keeps going; the same command attaches to it.
  cloud_result="$(python3 -I scripts/shield_cloud_build.py "${args[@]}")" || exit $?

  head_sha="$(cloud_field commit)"
  apk="$(cloud_field apk)"
  stamp="$(cloud_field stamp)"
  ffmpeg_dir="$root/$(cloud_field ffmpeg)"
  dirty=""
  build_column="$(printf '\tcloud:%s' "$(cloud_field run_id)")"
  if [ -z "$head_sha" ] || [ ! -f "$apk" ] || [ ! -f "$stamp" ] || [ ! -d "$ffmpeg_dir" ]; then
    echo "shield_cloud_build.py named no complete build:" >&2
    echo "$cloud_result" >&2
    exit 1
  fi
}
cloud_field() { printf '%s\n' "$cloud_result" | sed -n "s/^$1=//p"; }

if $local_build; then build_local; else build_cloud; fi

# Before the build log and the install: an APK without the protected features is not a build.
code=0
if $local_build; then
  python3 -I .claude/skills/upstream-sync/scripts/sync.py protected-apk "$apk" --stamp "$stamp" || code=$?
else
  # The cloud linked the pinned asset; the environment wins over local.properties here as in Gradle. The
  # ceiling keeps git from naming this checkout's HEAD as the FFmpeg source of the unpacked headers.
  USE_LOCAL_FFMPEG_DECODER=true FFMPEG_BUILD_DIR="$ffmpeg_dir/static-libs" FFMPEG_SOURCE_DIR="$ffmpeg_dir/headers" \
    GIT_CEILING_DIRECTORIES="$root/.local/cloud-ffmpeg" \
    python3 -I .claude/skills/upstream-sync/scripts/sync.py protected-apk "$apk" --stamp "$stamp" || code=$?
fi
if [ "$code" -eq 4 ] && [ "${NUVIO_ALLOW_NATIVE_MISMATCH:-}" = "1" ]; then
  echo "warning: the native origin check failed; keeping this build because NUVIO_ALLOW_NATIVE_MISMATCH=1" >&2
elif [ "$code" -eq 4 ]; then
  echo "the native origin check failed on $apk; fix the FAIL lines above, or keep this build with NUVIO_ALLOW_NATIVE_MISMATCH=1" >&2
  exit 1
elif [ "$code" -ne 0 ]; then
  echo "protected-apk failed on $apk with exit $code; no override keeps this build" >&2
  exit 1
fi

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
printf '%s\t%s%s\t%s\t%s\t%s%s\n' "$(date -u +%FT%TZ)" "$head_sha" "$dirty" "$pkg" "$local_sha" "$cert" "$build_column" >> .local/shield-builds.tsv

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
