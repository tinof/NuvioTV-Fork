#!/usr/bin/env bash
# Build the Shield APK on the runner of the private build repo (tinof/NuvioTV-Builds). Its workflow,
# whose source is scripts/cloud-build/shield-build.yml, fetches this repository at the requested
# commit and runs this script from that commit, so the recipe is versioned with the code it builds.
#
#   scripts/cloud-build/ci_build.sh
#
# Every input comes from the environment; the workflow passes its inputs only through env:
#   CLOUD_COMMIT           full SHA, must be HEAD of this checkout
#   CLOUD_REQUEST_ID       nonce of the request, ^[a-z0-9-]{8,40}$ (names the artifact)
#   CLOUD_VARIANT          Debug or Benchmark
#   CLOUD_SUFFIX           applicationId suffix, e.g. .shield
#   CLOUD_DEBUGGABLE       true or false (Debug only)
#   CLOUD_VERSION_CODE     whole number
#   CLOUD_VERSION_NAME     e.g. 1.1.0-nt.10.shield
#   CLOUD_PROPERTIES_FILE  the decoded secret LOCAL_PROPERTIES_BASE64
#   CLOUD_KEYSTORE_FILE    the decoded secret DEBUG_KEYSTORE_BASE64, the CI debug key
#   CLOUD_OUT              directory the workflow uploads: the arm64 APK, its rpu stamp, build-info.json
#   GITHUB_REPOSITORY      the build repo; the FFmpeg asset is one of its releases
#   GH_TOKEN               read token of the build repo, for that download only; unset before the build
#   CARGO_NDK_VERSION      default 4.1.2 (the workflow's cargo-ndk cache key names the same version)
#
# It runs only on GitHub Actions: it writes ~/.gradle/gradle.properties and installs SDK packages,
# a Rust toolchain and cargo-ndk. The Gradle arguments are those of scripts/shield_build_install.sh
# for the same inputs (a test holds the two together). Exit 2: bad input; 1: the build or a check failed.
set -euo pipefail

die() { local code="$1"; shift; echo "ci_build: $*" >&2; exit "$code"; }

[ "${GITHUB_ACTIONS:-}" = "true" ] || die 2 "runs only on GitHub Actions (it writes ~/.gradle and installs toolchains); build on the Mac with scripts/shield_build_install.sh"

root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"
started=$SECONDS

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1
}

# --- 1. Inputs -------------------------------------------------------------------------------------
commit="${CLOUD_COMMIT:-}"
request_id="${CLOUD_REQUEST_ID:-}"
variant="${CLOUD_VARIANT:-}"
suffix="${CLOUD_SUFFIX:-}"
debuggable="${CLOUD_DEBUGGABLE:-}"
version_code="${CLOUD_VERSION_CODE:-}"
version_name="${CLOUD_VERSION_NAME:-}"
properties_file="${CLOUD_PROPERTIES_FILE:-}"
keystore_file="${CLOUD_KEYSTORE_FILE:-}"
out="${CLOUD_OUT:-}"
build_repo="${GITHUB_REPOSITORY:-}"
cargo_ndk_version="${CARGO_NDK_VERSION:-4.1.2}"

re_commit='^[0-9a-f]{40}$'
re_request='^[a-z0-9-]{8,40}$'
re_suffix='^(\.[a-z][a-z0-9_]*)+$'
re_code='^[0-9]{1,9}$'
re_name='^[0-9A-Za-z._-]{1,64}$'
re_repo='^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$'
[[ $commit =~ $re_commit ]] || die 2 "CLOUD_COMMIT must be a full 40-hex SHA"
[[ $request_id =~ $re_request ]] || die 2 "CLOUD_REQUEST_ID must match $re_request"
case "$variant" in Debug|Benchmark) ;; *) die 2 "CLOUD_VARIANT must be Debug or Benchmark" ;; esac
[[ $suffix =~ $re_suffix ]] || die 2 "CLOUD_SUFFIX must match $re_suffix"
case "$debuggable" in true|false) ;; *) die 2 "CLOUD_DEBUGGABLE must be true or false" ;; esac
[ "$variant" = "Benchmark" ] && [ "$debuggable" = "true" ] && die 2 "a Benchmark build is never debuggable"
[[ $version_code =~ $re_code ]] || die 2 "CLOUD_VERSION_CODE must be a whole number"
[[ $version_name =~ $re_name ]] || die 2 "CLOUD_VERSION_NAME must match $re_name"
[[ $build_repo =~ $re_repo ]] || die 2 "GITHUB_REPOSITORY must name the build repo"
[ -n "$out" ] || die 2 "CLOUD_OUT is not set"
[ -s "$properties_file" ] || die 2 "CLOUD_PROPERTIES_FILE is missing or empty (secret LOCAL_PROPERTIES_BASE64)"
# No throwaway key: a build signed with another key cannot update the installed app.
[ -s "$keystore_file" ] || die 2 "CLOUD_KEYSTORE_FILE is missing or empty (secret DEBUG_KEYSTORE_BASE64)"
head_sha="$(git rev-parse HEAD)"
[ "$head_sha" = "$commit" ] || die 2 "HEAD is $head_sha, not CLOUD_COMMIT $commit"
[ -z "$(git status --porcelain --untracked-files=no)" ] || die 1 "the checkout of $commit has tracked changes"

# --- 2. local.properties without the Mac's paths and the release keys ----------------------------------
# The same rule as scripts/shield_cloud_setup.py, which strips these before it stores the secret; a
# test feeds both one file. Comments and blank lines go too.
filter_properties() {
  local line key re_skip='^[[:space:]]*([#!]|$)' re_key='^[[:space:]]*([^=:[:space:]]+)'
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%$'\r'}"
    [[ $line =~ $re_skip ]] && continue
    case "$line" in *\\) echo "ci_build: a line continuation in local.properties is not supported" >&2; return 1 ;; esac
    key=""
    [[ $line =~ $re_key ]] && key="${BASH_REMATCH[1]}"
    case "$key" in sdk.dir|FFMPEG_*|USE_LOCAL_FFMPEG_DECODER|DOVI_LIBDOVI_*|NUVIO_RELEASE_*) continue ;; esac
    printf '%s\n' "$line"
  done
}
( umask 077; filter_properties < "$properties_file" > local.properties )

# --- 3. Mask the credentials, list key names only -----------------------------------------------------
# Mask a value only for a credential key, and never a short one: a masked "nuvio" or "true" would
# hide the package name and every flag in the log (.github/workflows/pr-full-debug-build.yml:55-65).
re_url_credentials='^[A-Za-z][A-Za-z0-9+.-]*://[^/?#@]+@'
secret_value() {
  local key="$1" value="$2" lower
  [ "${#value}" -ge 8 ] || return 1
  lower="$(printf '%s' "$value" | tr '[:upper:]' '[:lower:]')"
  case "$lower" in true|false) return 1 ;; esac
  case "$key" in
    *_KEY|*_SECRET|*_CLIENT_ID|*_TOKEN|*_PASSWORD) return 0 ;;
    *_URL|*_URI) [[ $value =~ $re_url_credentials ]] && return 0 ;;
  esac
  return 1
}
keys=""
re_pair='^[[:space:]]*([^=:[:space:]]+)[[:space:]]*[=:]?[[:space:]]*(.*)$'
while IFS= read -r line; do
  [[ $line =~ $re_pair ]] || continue
  key="${BASH_REMATCH[1]}"
  value="${BASH_REMATCH[2]}"
  value="${value%"${value##*[![:space:]]}"}"
  if secret_value "$key" "$value"; then
    # Workflow commands unescape %25, so a literal % is escaped.
    printf '::add-mask::%s\n' "$(printf '%s' "$value" | sed 's/%/%25/g')"
  fi
  if [ -n "$value" ]; then keys="$keys $key"; fi
done < local.properties
echo "== properties with values:${keys:- none}"
case " $keys " in
  *" NUVIO_SUPABASE_URL "*) ;;
  *) die 1 "NUVIO_SUPABASE_URL is not set in the secret LOCAL_PROPERTIES_BASE64; the APK would not log in" ;;
esac

# --- 4. The pinned FFmpeg static libraries and headers --------------------------------------------------
pin="scripts/cloud-build/ffmpeg-asset.json"
[ -f "$pin" ] || die 1 "$pin is missing, so this commit pins no FFmpeg asset. On the Mac run 'python3 -I scripts/shield_cloud_setup.py ffmpeg', commit the pin and build that commit"
# Prints "tag<TAB>asset<TAB>sha256", then one "<abi>/<lib><TAB>sha256" line per pinned library.
read_pin='
import json, re, sys
pin = json.load(open(sys.argv[1]))
fields = (pin["tag"], pin["asset"], pin["sha256"])
checks = (r"ffmpeg-n[0-9.]+-[0-9]+", r"[A-Za-z0-9._-]+", r"[0-9a-f]{64}")
assert all(re.fullmatch(c, f) for c, f in zip(checks, fields)), "malformed tag, asset or sha256"
libs = pin["libs"]
assert libs and all(re.fullmatch(r"[a-z0-9_-]+/lib[a-z0-9]+\.a", k) and re.fullmatch(r"[0-9a-f]{64}", v)
                    for k, v in libs.items()), "malformed libs"
print("\t".join(fields))
for name, sha in sorted(libs.items()):
    print(name + "\t" + sha)
'
pin_fields="$(python3 -I -c "$read_pin" "$pin")" || die 1 "$pin is not a valid pin"
IFS=$'\t' read -r ffmpeg_tag ffmpeg_asset ffmpeg_sha <<< "$(printf '%s\n' "$pin_fields" | head -1)"
ffmpeg_libs="$(printf '%s\n' "$pin_fields" | tail -n +2)"
download="$RUNNER_TEMP/ffmpeg-download"
ffmpeg_dir="$RUNNER_TEMP/ffmpeg"
rm -rf "$download" "$ffmpeg_dir"
mkdir -p "$download" "$ffmpeg_dir"
echo "== FFmpeg asset $ffmpeg_asset of release $ffmpeg_tag in $build_repo"
gh release download "$ffmpeg_tag" -R "$build_repo" -p "$ffmpeg_asset" -D "$download"
got="$(sha256 "$download/$ffmpeg_asset")"
[ "$got" = "$ffmpeg_sha" ] || die 1 "the FFmpeg asset has sha256 $got, the pin says $ffmpeg_sha; never build on an asset the pin does not name"
tar -xf "$download/$ffmpeg_asset" -C "$ffmpeg_dir"
rm -rf "$download"
while IFS=$'\t' read -r name want; do
  [ -f "$ffmpeg_dir/static-libs/$name" ] || die 1 "the FFmpeg asset has no static-libs/$name"
  got="$(sha256 "$ffmpeg_dir/static-libs/$name")"
  [ "$got" = "$want" ] || die 1 "static-libs/$name has sha256 $got, the pin says $want"
done <<< "$ffmpeg_libs"
[ -f "$ffmpeg_dir/headers/libavcodec/avcodec.h" ] || die 1 "the FFmpeg asset has no headers/libavcodec/avcodec.h"
# The environment wins over local.properties, in Gradle (ffmpeg-decoder-downmix/build.gradle.kts) and
# in sync.py protected-apk alike.
export USE_LOCAL_FFMPEG_DECODER=true
export FFMPEG_SOURCE_DIR="$ffmpeg_dir/headers"
export FFMPEG_BUILD_DIR="$ffmpeg_dir/static-libs"
# The token was for the download; the build runs third-party Gradle and Cargo code.
unset GH_TOKEN GITHUB_TOKEN

# --- 5. The CI debug key ----------------------------------------------------------------------------
export NUVIO_DEBUG_STORE_FILE="$keystore_file"
keytool -list -keystore "$NUVIO_DEBUG_STORE_FILE" -storepass android -alias androiddebugkey >/dev/null \
  || die 1 "the secret DEBUG_KEYSTORE_BASE64 is not the debug keystore (store and key password android, alias androiddebugkey)"

# --- 6. NDK and CMake, the versions the build files name ------------------------------------------------
[ -n "${ANDROID_HOME:-}" ] || die 1 "ANDROID_HOME is not set"
export ANDROID_HOME
# As scripts/build_rpu_builder.sh reads it.
ndk_version="$(sed -n 's/^ *ndkVersion *= *"\([^"]*\)".*/\1/p' app/build.gradle.kts | head -1)"
[ -n "$ndk_version" ] || die 1 "no ndkVersion in app/build.gradle.kts"
cmake_version="$(sed -n 's/^ *version *= *"\([0-9.]*\)".*/\1/p' ffmpeg-decoder-downmix/build.gradle.kts | head -1)"
[ -n "$cmake_version" ] || die 1 "no CMake version in ffmpeg-decoder-downmix/build.gradle.kts"
packages=""
[ -f "$ANDROID_HOME/ndk/$ndk_version/source.properties" ] || packages="$packages ndk;$ndk_version"
[ -x "$ANDROID_HOME/cmake/$cmake_version/bin/cmake" ] || packages="$packages cmake;$cmake_version"
if [ -n "$packages" ]; then
  sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
  [ -x "$sdkmanager" ] || sdkmanager="$(command -v sdkmanager)" || die 1 "no sdkmanager to install$packages"
  echo "== installing$packages"
  # yes ends on SIGPIPE when sdkmanager closes the pipe; that exit is not a failure.
  { yes || :; } | "$sdkmanager" --licenses >/dev/null
  # shellcheck disable=SC2086 # package names have no spaces
  "$sdkmanager" --install $packages
fi

# --- 7. libnuvio_rpu.so: the crate's Rust toolchain, cargo-ndk, the unchanged local script --------------
rpu_started=$SECONDS
channel="$(sed -n 's/^channel *= *"\([^"]*\)".*/\1/p' native/rpu_builder/rust-toolchain.toml | head -1)"
[ -n "$channel" ] || die 1 "no channel in native/rpu_builder/rust-toolchain.toml"
rustup toolchain install "$channel" --profile minimal --target aarch64-linux-android --no-self-update
export PATH="$HOME/.local/cargo-ndk/bin:$PATH"
if [ "$(cargo ndk --version 2>/dev/null || true)" != "cargo-ndk $cargo_ndk_version" ]; then
  # From $HOME, so the crate's rust-toolchain.toml does not pick the toolchain for the install.
  (cd "$HOME" && cargo install cargo-ndk --version "$cargo_ndk_version" --locked --root "$HOME/.local/cargo-ndk")
fi
scripts/build_rpu_builder.sh
rpu_lib="app/src/main/jniLibs/arm64-v8a/libnuvio_rpu.so"
rpu_seconds=$((SECONDS - rpu_started))

# --- 8. Gradle on 2 vCPU and 8 GB -------------------------------------------------------------------
# The user-home file wins over the project's gradle.properties (-Xmx6144m). The Kotlin compiler runs
# in the Gradle JVM, so no second JVM competes for memory. No build cache: every output is built here.
# A 3 GB heap ran out during compileFullDebugKotlin (first runs, 2026-11-26); 7.7 GB of RAM plus
# 3 GB of swap stayed mostly free. 5 GB of heap, and 6 GB more swap so R8 and the native linkers that
# follow the Kotlin compile do not meet the OOM killer.
if [ "${RUNNER_ENVIRONMENT:-}" = github-hosted ] && [ ! -e /mnt/nuvio.swap ]; then
  sudo -n fallocate -l 6G /mnt/nuvio.swap && sudo -n chmod 600 /mnt/nuvio.swap \
    && sudo -n mkswap /mnt/nuvio.swap >/dev/null && sudo -n swapon /mnt/nuvio.swap \
    || echo "::warning::could not add swap" >&2
fi
gradle_home="${GRADLE_USER_HOME:-$HOME/.gradle}"
mkdir -p "$gradle_home"
cat > "$gradle_home/gradle.properties" <<'EOF'
org.gradle.jvmargs=-Xmx5g -XX:MaxMetaspaceSize=1g -XX:+UseParallelGC -Dfile.encoding=UTF-8
org.gradle.workers.max=2
org.gradle.caching=false
kotlin.compiler.execution.strategy=in-process
EOF

# --- 9. The build, with the arguments of scripts/shield_build_install.sh --------------------------------
gradle_args=(":app:assembleFull${variant}" "-PnuvioAppIdSuffix=${suffix}"
  "-ParcVersionCode=${version_code}" "-ParcVersionName=${version_name}")
[ "$debuggable" = "true" ] && gradle_args+=("-Pdebuggable=true")
echo "== building ${gradle_args[*]} at ${head_sha:0:9}"
gradle_started=$SECONDS
./gradlew --no-daemon "${gradle_args[@]}"
gradle_seconds=$((SECONDS - gradle_started))

# --- 10. The checks a local build passes, with no override --------------------------------------------
lower="$(echo "$variant" | tr '[:upper:]' '[:lower:]')"
apk="app/build/outputs/apk/full/${lower}/app-full-arm64-v8a-${lower}.apk"
[ -f "$apk" ] || die 1 "no arm64 APK at $apk"
python3 -I .claude/skills/upstream-sync/scripts/sync.py protected-apk "$apk" --stamp "$rpu_lib.stamp" \
  || die 1 "protected-apk failed on $apk; the cloud keeps no build that fails it"
native_json="$RUNNER_TEMP/native-libraries.json"
# Report-only: its REQUIRED list still names libass.so, which the libass AAR no longer ships, so it
# refuses every current APK. The local build does not run it either; protected-apk above is the gate.
if ! python3 -I scripts/verify_native_apk.py "$apk" --abi arm64-v8a > "$native_json"; then
  echo "::warning::verify_native_apk.py failed on $apk (report only; see its output above)" >&2
  echo '{"nativeLibraries": null, "verify_native_apk": "failed, report only"}' > "$native_json"
fi

# --- 11. The artifact -------------------------------------------------------------------------------
tools="$(find "$ANDROID_HOME/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
pkg="$("$tools/aapt2" dump badging "$apk" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
cert="$("$tools/apksigner" verify --print-certs "$apk" | sed -n 's/.*SHA-256 digest: //p' | head -1)"
[ -n "$pkg" ] && [ -n "$cert" ] || die 1 "aapt2 or apksigner read no package or certificate from $apk"
apk_sha="$(sha256 "$apk")"
mkdir -p "$out"
cp "$apk" "$out/"
cp "$rpu_lib.stamp" "$out/libnuvio_rpu.so.stamp"
rustc_version="$(cd native/rpu_builder && rustc --version)"
java_version="$(java -version 2>&1 | head -1 || true)"
export BI_COMMIT="$commit" BI_REQUEST_ID="$request_id" BI_VARIANT="$variant" BI_SUFFIX="$suffix"
export BI_DEBUGGABLE="$debuggable" BI_VERSION_CODE="$version_code" BI_VERSION_NAME="$version_name"
export BI_APK="$apk" BI_APK_SHA="$apk_sha" BI_PACKAGE="$pkg" BI_CERT="$cert" BI_KEYS="$keys"
export BI_FFMPEG_TAG="$ffmpeg_tag" BI_FFMPEG_ASSET="$ffmpeg_asset" BI_FFMPEG_SHA="$ffmpeg_sha" BI_FFMPEG_LIBS="$ffmpeg_libs"
export BI_STAMP="$rpu_lib.stamp" BI_NATIVE="$native_json" BI_NDK="$ndk_version" BI_CMAKE="$cmake_version"
export BI_RUSTC="$rustc_version" BI_CARGO_NDK="$cargo_ndk_version" BI_JAVA="$java_version" BI_REPO="$build_repo"
export BI_RPU_S="$rpu_seconds" BI_GRADLE_S="$gradle_seconds" BI_TOTAL_S="$((SECONDS - started))"
python3 -I - "$out/build-info.json" <<'PY'
import json, os, sys
from pathlib import Path
e = os.environ
apk = Path(e["BI_APK"])
info = {
    "schema": 1,
    "commit": e["BI_COMMIT"],
    "request_id": e["BI_REQUEST_ID"],
    "inputs": {"variant": e["BI_VARIANT"], "suffix": e["BI_SUFFIX"], "debuggable": e["BI_DEBUGGABLE"] == "true",
               "version_code": e["BI_VERSION_CODE"], "version_name": e["BI_VERSION_NAME"]},
    "apk": {"name": apk.name, "sha256": e["BI_APK_SHA"], "size": apk.stat().st_size},
    "package": e["BI_PACKAGE"],
    "cert_sha256": e["BI_CERT"],
    "ffmpeg_asset": {"tag": e["BI_FFMPEG_TAG"], "asset": e["BI_FFMPEG_ASSET"], "sha256": e["BI_FFMPEG_SHA"],
                     "libs": dict(line.split("\t") for line in e["BI_FFMPEG_LIBS"].splitlines() if line)},
    "rpu_stamp": json.loads(Path(e["BI_STAMP"]).read_text()),
    "native_libraries": json.loads(Path(e["BI_NATIVE"]).read_text())["nativeLibraries"],
    "property_keys": sorted(e["BI_KEYS"].split()),
    "toolchain": {"ndk": e["BI_NDK"], "cmake": e["BI_CMAKE"], "rustc": e["BI_RUSTC"],
                  "cargo_ndk": e["BI_CARGO_NDK"], "java": e["BI_JAVA"]},
    "seconds": {"rpu": int(e["BI_RPU_S"]), "gradle": int(e["BI_GRADLE_S"]), "total": int(e["BI_TOTAL_S"])},
    "run": {"repository": e["BI_REPO"], "id": e.get("GITHUB_RUN_ID", ""), "attempt": e.get("GITHUB_RUN_ATTEMPT", "")},
}
Path(sys.argv[1]).write_text(json.dumps(info, indent=2, sort_keys=True) + "\n")
PY
echo "== $apk"
echo "   package $pkg"
echo "   cert    $cert"
echo "   sha256  $apk_sha"
echo "   minutes rpu $((rpu_seconds / 60)), gradle $((gradle_seconds / 60)), total $(((SECONDS - started) / 60))"
