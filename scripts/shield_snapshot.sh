#!/usr/bin/env bash
# Save the Shield's state before a device session (ROADMAP M2). Read-only on the device.
#
#   scripts/shield_snapshot.sh [--serial S] [--label L] [--out DIR] [--pull-apk]
#
# - --serial    the Shield's adb serial (host:5555); default $ANDROID_SERIAL. Never printed.
# - --label     session label, letters, digits and . _ - (default session).
# - --out       session folder (default ~/shield-snapshots/<YYYYMMDD-HHMM>-<label>). The snapshot goes
#               to its before/, which must not exist yet or must be empty.
# - --pull-apk  also save the base APK of each installed com.nuvio.tv.test.* package (~120 MB each).
#
# before/ holds the settings, the debug.nuvio.* properties, the logcat buffer sizes, the display mode,
# the packages, the test APKs' hashes and meta. before/complete is written last: shield_restore.sh only
# restores to a snapshot that has it. The session folder also gets session-changes.txt, where the
# session names the settings it changes.
# Exit codes: 0 saved; 1 refused (no serial, not mdarcy, folder in use, a value missing); 2 usage;
# 5 the Shield did not answer or logged Watchdog/flush_work: stop, no retry, tell the owner (M2: only
# a power cycle recovers it). scripts/shield_restore.sh sources this file for the same collectors.
set -euo pipefail

SNAP_FORMAT=1
SHIELD_ADB_TIMEOUT="${SHIELD_ADB_TIMEOUT:-30}"
# A ~120 MB APK over Wi-Fi needs longer than a shell call.
SHIELD_ADB_PULL_TIMEOUT="${SHIELD_ADB_PULL_TIMEOUT:-600}"
SHIELD_SNAPSHOTS="${SHIELD_SNAPSHOTS:-$HOME/shield-snapshots}"
EXIT_REFUSED=1
EXIT_USAGE=2
# shellcheck disable=SC2034 # shield_restore.sh's
EXIT_DRIFT=3
# shellcheck disable=SC2034
EXIT_OWNER=4
EXIT_DEVICE=5
# Test builds install as com.nuvio.tv.test.<suffix>; the trailing dot keeps the daily app out.
TEST_PKG_RE='^com\.nuvio\.tv\.test\.'
# What M2 says to stop on: any line with the Watchdog tag, whatever it says, and flush_work. Only
# the tag counts: a healthy Looper line can name com.android.server.Watchdog$HandlerChecker.
HEALTH_RE='(^|[[:space:]])[VDIWEF] Watchdog[[:space:]]*:|flush_work'
# Settings the system rewrites between snapshots with no session action, as ns/key globs. Add a key
# only with the session it was seen in. ~/shield-snapshots/volatile-keys.txt adds local patterns.
VOLATILE_KEYS=(
  'global/network_watchlist_last_report_time' # 2026-10-24 dv32 before/after
  'global/zen_mode_config_etag'               # 2026-10-24 dv32 before/after
)

shield_serial=""
shield_to=""
shield_log=/dev/null
shield_hang_dir=""
shield_errtmp=""
shield_hung=0
snap_fg=""
snap_volatile=()
snap_local_volatile=0

shield_die() { local code="$1"; shift; printf '%s\n' "$*" >&2; exit "$code"; }

# Quotes one argument for the device shell.
shq() { printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"; }

# Replaces the serial and its host part with <serial>: the address is never printed or saved.
shield_redact() {
  local line host="${shield_serial%:*}"
  while IFS= read -r line || [ -n "$line" ]; do
    [ -z "$shield_serial" ] || line="${line//"$shield_serial"/<serial>}"
    [ -z "$host" ] || line="${line//"$host"/<serial>}"
    printf '%s\n' "$line"
  done
}

# The same for a whole file, in place (a device log names the Shield's own address).
shield_redact_file() {  # <file>
  local host="${shield_serial%:*}"
  [ -n "$host" ] || return 0
  sed "s/${host//./\\.}/<serial>/g" "$1" >"$1.redacted" && mv "$1.redacted" "$1"
}

shield_init() {  # [cli serial]
  shield_serial="${1:-${ANDROID_SERIAL:-}}"
  [ -n "$shield_serial" ] || shield_die "$EXIT_REFUSED" \
    "no serial: pass --serial or set ANDROID_SERIAL (the shield probe in .claude/workflow.md derives it from docs/nvidia-shield.md)"
  if command -v gtimeout >/dev/null 2>&1; then shield_to=gtimeout
  elif command -v timeout >/dev/null 2>&1; then shield_to=timeout
  else shield_die "$EXIT_REFUSED" "neither gtimeout nor timeout is on PATH (brew install coreutils)"; fi
  shield_errtmp="$(mktemp "${TMPDIR:-/tmp}/shield-adb.XXXXXX")"
  trap 'rm -f "$shield_errtmp"' EXIT
}

# Stops the run: no further adb call, no retry (M2).
shield_stop() {  # <what happened>
  shield_hung=1
  local what
  what="$(printf '%s' "$1" | shield_redact)"
  if [ -n "$shield_hang_dir" ] && [ -d "$shield_hang_dir" ]; then
    printf 'date_utc=%s\nproblem=%s\n' "$(date -u +%FT%TZ)" "$what" >"$shield_hang_dir/hang.txt"
  fi
  printf '%s\n' "STOP: $what. No more adb calls, no retry." \
    "Collected so far: ${shield_hang_dir:-nothing}. Save the logs and tell the owner (M2: only a power cycle recovers a hung Shield)." >&2
  exit "$EXIT_DEVICE"
}

# adb with a timeout, stdin closed. stdout goes to <out> (CRs removed); stderr, redacted, to
# $shield_log. Returns adb's exit code; a timeout stops the run. Never call it inside $(...):
# the stop would only leave the subshell.
shield_adb_to() {  # [--long] <out> <adb args...>
  local to="$SHIELD_ADB_TIMEOUT" rc=0
  if [ "$1" = --long ]; then to="$SHIELD_ADB_PULL_TIMEOUT"; shift; fi
  local out="$1"
  shift
  [ "$shield_hung" = 0 ] || return "$EXIT_DEVICE"
  "$shield_to" -k 5 "$to" adb -s "$shield_serial" "$@" </dev/null >"$out" 2>"$shield_errtmp" || rc=$?
  shield_redact <"$shield_errtmp" >>"$shield_log"
  case "$rc" in 124 | 137) shield_stop "the Shield did not answer 'adb $*' within ${to} s" ;; esac
  if [ "$out" != /dev/null ]; then tr -d '\r' <"$out" >"$out.cr" && mv "$out.cr" "$out"; fi
  return "$rc"
}

# One device shell command as one string (quote arguments with shq; no pipes, filtering runs on the
# host). The _may form returns the exit code (pm path exits 1 for a package that is not installed);
# shield_sh stops the run on any failure.
shield_sh_may() { shield_adb_to "$1" shell "$2"; }
shield_sh() {  # <out> <command>
  local rc=0
  shield_adb_to "$1" shell "$2" || rc=$?
  [ "$rc" = 0 ] || shield_stop "'adb shell $2' failed (exit $rc)"
}

shield_connect() {
  case "$shield_serial" in
    *:*) "$shield_to" 15 adb connect "$shield_serial" </dev/null >/dev/null 2>&1 || true ;;
  esac
}

kv_get() {  # <file> <key>: the value after the first '='
  awk -v k="$2" '{ i = index($0, "=") } i && substr($0, 1, i - 1) == k { print substr($0, i + 1); exit }' "$1"
}

sha12() { printf '%s' "$1" | shasum -a 256 | cut -c1-12; }

# Full getprop, parsed on the host into <dir>: debug-nuvio.props (name=value, empty values dropped:
# Android cannot delete a property, so empty is unset), getprop-debug-nuvio.raw, and .identity and
# .props-display for meta and display-info. A debug.nuvio line that does not parse goes to
# parse-errors, which keeps the snapshot from completing.
snap_getprop() {  # <dir>
  local d="$1" sn
  shield_sh "$d/.getprop" getprop
  : >"$d/getprop-debug-nuvio.raw"
  : >"$d/.props"
  : >"$d/.props-display"
  awk -v d="$d" '
    { n = index($0, "]: [") }
    substr($0, 1, 1) != "[" || !n || substr($0, length($0)) != "]" {
      bad++
      if (index($0, "debug.nuvio")) print > (d "/parse-errors")
      next
    }
    {
      k = substr($0, 2, n - 2); v = substr($0, n + 4, length($0) - n - 4)
      if (k ~ /^debug\.nuvio\./) {
        print > (d "/getprop-debug-nuvio.raw")
        if (v != "") print k "=" v > (d "/.props")
      }
      if (k == "ro.product.device") dev = v
      if (k == "ro.serialno") sn = v
      if (k == "ro.build.display.id") bid = v
      if (k == "persist.vendor.sys.display.resolution" || k == "vendor.sys.tegra.refresh" ||
          k ~ /^vendor\.sys\.ui\.resolution\./ || k ~ /^persist\.logd\.size/)
        print "prop." k "=" v > (d "/.props-display")
    }
    END {
      print "device=" dev > (d "/.identity"); print "build_id=" bid > (d "/.identity")
      print "getprop_unparsed=" bad + 0 > (d "/.identity")
      print sn > (d "/.serialno")
    }' "$d/.getprop"
  # Only a hash of the hardware serial is kept, written before anything can stop the run.
  sn="$(cat "$d/.serialno")"
  rm -f "$d/.serialno"
  [ -z "$sn" ] || echo "device_id=$(sha12 "$sn")" >>"$d/.identity"
  LC_ALL=C sort "$d/.props" >"$d/debug-nuvio.props"
  rm -f "$d/.getprop" "$d/.props"
}

snap_device() { kv_get "$1/.identity" device; }
snap_device_id() { kv_get "$1/.identity" device_id; }  # 12 hex of ro.serialno; empty without one

# The last 5000 lines of system, main and crash into <file>. Returns 1 when one matches HEALTH_RE;
# the matches go to <file>.hits.
snap_health() {  # <file>
  shield_sh "$1" "logcat -d -b system,main,crash -t 5000"
  shield_redact_file "$1"
  if grep -E "$HEALTH_RE" "$1" >"$1.hits"; then return 1; fi
  rm -f "$1.hits"
}

snap_settings() {  # <dir>
  local d="$1" ns
  for ns in global secure system; do
    shield_sh "$d/.tmp" "settings list $ns"
    # A line without '=' would be the continuation of a multi-line value; none seen on SE 9.2.4.
    awk -v u="$d/settings-$ns.unparsed" '$0 == "" { next } index($0, "=") > 1 { print; next } { print > u }' \
      "$d/.tmp" | LC_ALL=C sort >"$d/settings-$ns.txt"
    [ ! -f "$d/settings-$ns.unparsed" ] ||
      echo "warning: settings $ns has lines without '=', kept in settings-$ns.unparsed" >&2
  done
}

# logcat -g prints a size in its largest whole unit (1536 KiB shows as "1 MiB"), so a snapshot of a
# size that is not a whole unit records it rounded down; the default sizes are whole units.
snap_logcat() {  # <dir>: buffer sizes only, in KiB; the consumed figures change all the time
  local d="$1"
  shield_sh "$d/logcat-g.raw" "logcat -b all -g"
  awk '/^[a-z]+: ring buffer is [0-9]+ (B|KiB|MiB|GiB)/ {
      n = $5
      if ($6 == "B") n = int(n / 1024); else if ($6 == "MiB") n *= 1024; else if ($6 == "GiB") n *= 1048576
      print substr($1, 1, length($1) - 1) "=" n "K"
    }' "$d/logcat-g.raw" | LC_ALL=C sort >"$d/logcat-buffers.txt"
}

# display-mode.txt from a dumpsys display dump: active and default mode IDs with WxH@fps from the mode
# table. With <info>, also the mode table and specs as display-info lines.
snap_parse_display() {  # <dump> [info]
  awk -v info="${2:-}" '
    !a && match($0, /mActiveModeId=[0-9]+/) { a = substr($0, RSTART + 14, RLENGTH - 14) }
    !df && match($0, /mDefaultModeId=[0-9]+/) { df = substr($0, RSTART + 15, RLENGTH - 15) }
    info != "" && !ds && match($0, /mDesiredDisplayModeSpecs=.*/) { ds = 1; print "desired_specs=" substr($0, RSTART + 25) > info }
    info != "" && !sp && match($0, /mDisplayModeSpecs=.*/) { sp = 1; print "specs=" substr($0, RSTART + 18) > info }
    !m && index($0, "mBaseDisplayInfo=") {
      m = 1; s = $0
      while (match(s, /\{id=[0-9]+, width=[0-9]+, height=[0-9]+, fps=[0-9.]+/)) {
        split(substr(s, RSTART + 1, RLENGTH - 1), f, ", "); s = substr(s, RSTART + RLENGTH)
        id = substr(f[1], 4); desc[id] = substr(f[2], 7) "x" substr(f[3], 8) "@" sprintf("%.3f", substr(f[4], 5))
        if (info != "") print "mode." id "=" desc[id] > info
      }
    }
    END {
      if (a == "" || df == "") exit 1
      print "active=" a; print "default=" df; print "active_desc=" desc[a]; print "default_desc=" desc[df]
    }' "$1"
}

snap_display() {  # <dir>
  local d="$1"
  shield_sh "$d/dumpsys-display.raw" "dumpsys display"
  : >"$d/.display-info"
  snap_parse_display "$d/dumpsys-display.raw" "$d/.display-info" >"$d/display-mode.txt" ||
    shield_die "$EXIT_REFUSED" "dumpsys display has no mActiveModeId or mDefaultModeId; kept in $d/dumpsys-display.raw"
  cat "$d/.display-info" "$d/.props-display" | LC_ALL=C sort >"$d/display-info.txt"
  rm -f "$d/.display-info" "$d/.props-display"
}

# The resumed activity's package into $snap_fg ("unknown" when the dump has none). Android 11 prints
# "mResumedActivity: ActivityRecord{<id> u0 <pkg>/<activity> t<task>}".
snap_foreground() {  # <dir for the dump>
  shield_sh "$1/.activities" "dumpsys activity activities"
  snap_fg="$(awk '
    /(mResumedActivity: |topResumedActivity=)ActivityRecord\{/ {
      n = split(substr($0, index($0, "ActivityRecord{")), t, " ")
      if (n >= 3 && t[2] ~ /^u[0-9]+$/) { split(t[3], q, "/"); print q[1]; found = 1; exit }
    }
    END { if (!found) print "unknown" }' "$1/.activities")"
  rm -f "$1/.activities"
}

snap_packages() {  # <dir>: name=versionCode
  local d="$1"
  shield_sh "$d/.tmp" "pm list packages --show-versioncode"
  awk '/^package:/ {
      sub(/^package:/, ""); n = index($0, " versionCode:")
      if (n) print substr($0, 1, n - 1) "=" substr($0, n + 13); else print $0 "="
    }' "$d/.tmp" | LC_ALL=C sort >"$d/packages.txt"
}

# apks.tsv: package, versionCode, sha256 and path of the base APK of every test build. A split APK
# prints several pm path lines; only base.apk is hashed.
snap_apks() {  # <dir> <pull: true|false>
  local d="$1" pull="$2" pkgs=() line pkg ver path sha rc
  : >"$d/apks.tsv"
  while IFS= read -r line; do pkgs+=("$line"); done < <(grep -E "$TEST_PKG_RE" "$d/packages.txt" || true)
  for line in ${pkgs[@]+"${pkgs[@]}"}; do
    pkg="${line%%=*}"
    ver="${line#*=}"
    rc=0
    shield_sh_may "$d/.tmp" "pm path $(shq "$pkg")" || rc=$?
    [ "$rc" = 0 ] || continue
    path="$(awk '/^package:.*\/base\.apk$/ { sub(/^package:/, ""); print; exit }' "$d/.tmp")"
    [ -n "$path" ] || path="$(awk '/^package:/ { sub(/^package:/, ""); print; exit }' "$d/.tmp")"
    [ -n "$path" ] || continue
    shield_sh "$d/.tmp" "sha256sum $(shq "$path")"
    sha="$(awk '{ print $1; exit }' "$d/.tmp")"
    printf '%s\t%s\t%s\t%s\n' "$pkg" "$ver" "$sha" "$path" >>"$d/apks.tsv"
    if [ "$pull" = true ]; then
      mkdir -p "$d/apk"
      rc=0
      shield_adb_to --long /dev/null pull "$path" "$d/apk/$pkg.apk" || rc=$?
      [ "$rc" = 0 ] || shield_die "$EXIT_REFUSED" "adb pull of $pkg failed (exit $rc)"
    fi
  done
}

# Everything after getprop and the health check, into <dir>; meta last.
snap_collect() {  # <dir> <kind> <label> <pull>
  local d="$1" kind="$2" label="$3" pull="$4" root head dirty uptime
  snap_settings "$d"
  snap_logcat "$d"
  snap_display "$d"
  snap_foreground "$d"
  snap_packages "$d"
  snap_apks "$d" "$pull"
  shield_sh "$d/.uptime" "cat /proc/uptime"
  uptime="$(awk '{ printf "%d\n", $1; exit }' "$d/.uptime")"
  root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
  head="$(git -C "$root" rev-parse HEAD 2>/dev/null || echo unknown)"
  dirty=false
  [ -z "$(git -C "$root" status --porcelain --untracked-files=no 2>/dev/null)" ] || dirty=true
  {
    echo "format=$SNAP_FORMAT"
    echo "kind=$kind"
    echo "label=$label"
    echo "date_utc=$(date -u +%FT%TZ)"
    echo "device=$(snap_device "$d")"
    echo "device_id_sha256=$(snap_device_id "$d")"
    echo "build_id=$(kv_get "$d/.identity" build_id)"
    echo "getprop_unparsed=$(kv_get "$d/.identity" getprop_unparsed)"
    echo "uptime_s=$uptime"
    echo "foreground_pkg=$snap_fg"
    echo "head_sha=$head"
    echo "dirty=$dirty"
  } >"$d/meta"
  rm -f "$d/.tmp" "$d/.uptime" "$d/.identity"
}

# Prints the changed, added and removed keys of two key=value files (split at the first '=') as
# kind<US>key<US>old<US>new lines sorted by key. <prefix> goes in front of every key.
snap_kv_diff() {  # <a> <b> [prefix]
  awk -v p="${3:-}" '
    BEGIN { US = "\037" }
    { i = index($0, "="); if (!i) next; k = p substr($0, 1, i - 1); v = substr($0, i + 1) }
    FILENAME == ARGV[1] { a[k] = v; next }
    { b[k] = v }
    END {
      for (k in a) if (!(k in b)) print "removed" US k US a[k] US
      for (k in b) if (!(k in a)) print "added" US k US US b[k]; else if (a[k] != b[k]) print "changed" US k US a[k] US b[k]
    }' "$1" "$2" | LC_ALL=C sort -t "$(printf '\037')" -k2,2
}

snap_load_volatile() {
  local line
  snap_volatile=("${VOLATILE_KEYS[@]}")
  snap_local_volatile=0
  [ -f "$SHIELD_SNAPSHOTS/volatile-keys.txt" ] || return 0
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%%#*}"
    line="$(printf '%s' "$line" | tr -d '[:space:]')"
    [ -n "$line" ] || continue
    snap_volatile+=("$line")
    snap_local_volatile=$((snap_local_volatile + 1))
  done <"$SHIELD_SNAPSHOTS/volatile-keys.txt"
}

snap_is_volatile() {  # <ns/key>
  local p
  for p in ${snap_volatile[@]+"${snap_volatile[@]}"}; do
    # shellcheck disable=SC2053 # $p is a glob on purpose
    [[ "$1" == $p ]] && return 0
  done
  return 1
}

main() {
  local serial="" label=session out="" pull=false before f
  while [ $# -gt 0 ]; do
    case "$1" in
      --serial) serial="${2:?--serial needs a value}"; shift 2 ;;
      --label) label="${2:?--label needs a value}"; shift 2 ;;
      --out) out="${2:?--out needs a value}"; shift 2 ;;
      --pull-apk) pull=true; shift ;;
      -h | --help) sed -n '2,18p' "$0"; exit 0 ;;
      *) echo "unknown argument: $1" >&2; exit "$EXIT_USAGE" ;;
    esac
  done
  case "$label" in *[!A-Za-z0-9._-]*) shield_die "$EXIT_USAGE" "label '$label': use letters, digits and . _ -" ;; esac
  [ -n "$out" ] || out="$SHIELD_SNAPSHOTS/$(date +%Y%m%d-%H%M)-$label"
  before="$out/before"
  if [ -d "$before" ] && [ -n "$(ls -A "$before")" ]; then
    shield_die "$EXIT_REFUSED" "$before exists and is not empty; pick another --label or --out"
  fi
  shield_init "$serial"
  mkdir -p "$before"
  shield_log="$before/adb-stderr.log"
  shield_hang_dir="$before"
  shield_connect

  snap_getprop "$before"
  [ "$(snap_device "$before")" = mdarcy ] ||
    shield_die "$EXIT_REFUSED" "device is '$(snap_device "$before")', not mdarcy; nothing saved as a baseline"
  [ -n "$(snap_device_id "$before")" ] || shield_die "$EXIT_REFUSED" "getprop has no ro.serialno; the snapshot cannot name the device"
  if ! snap_health "$before/logcat-tail.txt"; then
    cp "$before/logcat-tail.txt.hits" "$before/unhealthy.txt"
    shield_stop "the Shield logged Watchdog or flush_work (before/unhealthy.txt); the session must not start"
  fi
  snap_collect "$before" before "$label" "$pull"
  [ ! -f "$before/parse-errors" ] ||
    shield_die "$EXIT_REFUSED" "getprop lines for debug.nuvio did not parse (before/parse-errors); the snapshot is not complete"
  for f in settings-global.txt settings-secure.txt settings-system.txt packages.txt; do
    [ -s "$before/$f" ] || shield_die "$EXIT_REFUSED" "$f came back empty; the snapshot is not complete"
  done
  # A multi-line value is cut at its first line, and restore would write that cut value back.
  for f in settings-global settings-secure settings-system; do
    [ ! -f "$before/$f.unparsed" ] ||
      shield_die "$EXIT_REFUSED" "$f has a multi-line value (before/$f.unparsed); the snapshot is not complete"
  done

  if [ ! -f "$out/session-changes.txt" ]; then
    cat >"$out/session-changes.txt" <<EOF
# Session $label, snapshot $(kv_get "$before/meta" date_utc).
# shield_restore.sh writes a settings key back to its snapshot value only when it is named here or
# with --setting: one line per key, "setting <global|secure|system>/<key>".
# debug.nuvio.* properties and logcat buffer sizes are restored without being named.
# Other lines are notes that restore prints; lines starting with # are skipped.
EOF
  fi
  printf 'date_utc=%s\nfiles=%s\n' "$(date -u +%FT%TZ)" "$(find "$before" -mindepth 1 -maxdepth 1 | wc -l | tr -d ' ')" >"$before/complete"

  echo "snapshot: $out"
  echo "  display  mode $(kv_get "$before/display-mode.txt" active) ($(kv_get "$before/display-mode.txt" active_desc)), default $(kv_get "$before/display-mode.txt" default) ($(kv_get "$before/display-mode.txt" default_desc))"
  echo "  front    $snap_fg"
  echo "  props    $(wc -l <"$before/debug-nuvio.props" | tr -d ' ') debug.nuvio.* set"
  echo "  logcat   $(tr '\n' ' ' <"$before/logcat-buffers.txt")"
  echo "  test APKs $(wc -l <"$before/apks.tsv" | tr -d ' ')"
  echo "Name the settings this session changes in $out/session-changes.txt."
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then main "$@"; fi
