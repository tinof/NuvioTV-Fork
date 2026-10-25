#!/usr/bin/env bash
# Put the Shield back the way a shield_snapshot.sh snapshot found it, after a device session (ROADMAP M2).
#
#   scripts/shield_restore.sh [--serial S] [--session DIR] [--setting ns/key]... [--dry-run]
#
# - --serial    as for shield_snapshot.sh; default $ANDROID_SERIAL. Never printed.
# - --session   the session folder (or its before/). Default: the newest complete one under
#               ~/shield-snapshots; when that one is already restored clean, pass --session.
# - --setting   a settings key (global|secure|system/<key>) the session changed: put back to its
#               snapshot value, or deleted when the snapshot did not have it. Repeatable; a line
#               "setting ns/key" in <session>/session-changes.txt counts the same.
# - --dry-run   read only: nothing is sent; the plan and diff go to restore.dry-<stamp>/.
#
# Without being named it restores the debug.nuvio.* properties (clearing with '' those that were unset)
# and the logcat buffer sizes. Other changed settings are reported, never written, so a change the owner
# made survives. adb cannot set the display mode on Android 11: when the mode differs and a test build
# (com.nuvio.tv.test.<suffix>) is in front, it sends HOME once; otherwise the owner sets the mode.
# It never installs, clears, force-stops or compiles anything and never touches the daily apps. Then it
# captures after/ and diffs it against before/ into restore/diff.txt.
# Exit codes: 0 clean; 1 refused; 2 usage; 3 drift remains; 4 owner action needed; 5 the Shield did not
# answer or logged Watchdog/flush_work: stop, no retry, tell the owner.
set -euo pipefail
# shellcheck source-path=SCRIPTDIR source=shield_snapshot.sh
. "$(dirname "$0")/shield_snapshot.sh"

SHIELD_POLL_INTERVAL="${SHIELD_POLL_INTERVAL:-1}"
SHIELD_POLL_TRIES="${SHIELD_POLL_TRIES:-10}"
PROP_RE='^debug\.nuvio\.[A-Za-z0-9._-]+$'
SETTING_RE='^(global|secure|system)/[A-Za-z0-9_.:-]+$'
US="$(printf '\037')"

named=()
notes=()
n_drift=0
n_owner=0
n_info=0
n_ignored=0

is_named() {  # <ns/key>
  local k
  for k in ${named[@]+"${named[@]}"}; do [ "$k" = "$1" ] && return 0; done
  return 1
}

# Reads the session's "setting ns/key" lines into named[]; other lines are notes.
read_session_changes() {  # <file>
  local line key
  [ -f "$1" ] || return 0
  while IFS= read -r line || [ -n "$line" ]; do
    line="$(printf '%s' "$line" | tr -d '\r' | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')"
    case "$line" in "" | "#"*) continue ;; esac
    key=""
    [[ ! "$line" =~ ^setting[[:space:]]+([^[:space:]]+)$ ]] || key="${BASH_REMATCH[1]}"
    if [ -n "$key" ] && [[ "$key" =~ $SETTING_RE ]]; then
      named+=("$key")
    else
      case "$line" in setting*) echo "warning: '$line' in session-changes.txt is not 'setting <global|secure|system>/<key>'; read as a note" >&2 ;; esac
      notes+=("$line")
    fi
  done <"$1"
}

# Compares two snapshot folders and writes the findings to <out>: DRIFT (props, logcat sizes,
# non-volatile settings), OWNER (display mode), INFO (packages, test APKs, display props) and
# IGNORED (volatile settings that changed). Sets the n_* counters.
report() {  # <a> <b> <out>
  local a="$1" b="$2" out="$3" ns kind key old new pkg bsha asha suffix row
  n_drift=0 n_owner=0 n_info=0 n_ignored=0
  : >"$out"
  while IFS="$US" read -r kind key old new; do
    echo "DRIFT prop $key: '$old' -> '$new'" >>"$out"
    n_drift=$((n_drift + 1))
  done < <(snap_kv_diff "$a/debug-nuvio.props" "$b/debug-nuvio.props")
  while IFS="$US" read -r kind key old new; do
    echo "DRIFT logcat buffer $key: ${old:-none} -> ${new:-none}" >>"$out"
    n_drift=$((n_drift + 1))
  done < <(snap_kv_diff "$a/logcat-buffers.txt" "$b/logcat-buffers.txt")
  for ns in global secure system; do
    while IFS="$US" read -r kind key old new; do
      if snap_is_volatile "$key" && ! is_named "$key"; then
        echo "IGNORED (volatile) $key: '$old' -> '$new'" >>"$out"
        n_ignored=$((n_ignored + 1))
      elif is_named "$key" && [ "$kind" != added ] && [ "$old" = null ]; then
        echo "OWNER setting $key was null in the snapshot, which settings put cannot write; now '$new': set it by hand" >>"$out"
        n_owner=$((n_owner + 1))
      elif is_named "$key"; then
        echo "DRIFT setting $key ($kind, named): '$old' -> '$new'" >>"$out"
        n_drift=$((n_drift + 1))
      else
        echo "DRIFT setting $key ($kind): '$old' -> '$new'; not written back unless named (--setting $key)" >>"$out"
        n_drift=$((n_drift + 1))
      fi
    done < <(snap_kv_diff "$a/settings-$ns.txt" "$b/settings-$ns.txt" "$ns/")
  done
  if [ "$(kv_get "$a/display-mode.txt" active):$(kv_get "$a/display-mode.txt" default)" != \
       "$(kv_get "$b/display-mode.txt" active):$(kv_get "$b/display-mode.txt" default)" ]; then
    echo "OWNER display mode is $(kv_get "$b/display-mode.txt" active) ($(kv_get "$b/display-mode.txt" active_desc)), default $(kv_get "$b/display-mode.txt" default); the snapshot had $(kv_get "$a/display-mode.txt" active) ($(kv_get "$a/display-mode.txt" active_desc)), default $(kv_get "$a/display-mode.txt" default) ($(kv_get "$a/display-mode.txt" default_desc)). adb cannot set it on Android 11: set it in Settings > Device Preferences > Display & Sound > Resolution." >>"$out"
    n_owner=$((n_owner + 1))
    case "$(kv_get "$a/meta" foreground_pkg)" in
      *launcher*) ;;
      *) echo "INFO the snapshot was taken with $(kv_get "$a/meta" foreground_pkg) in front; its mode may have been that app's" >>"$out"
         n_info=$((n_info + 1)) ;;
    esac
  fi
  while IFS="$US" read -r kind key old new; do
    echo "INFO package $key: ${old:-not installed} -> ${new:-not installed}" >>"$out"
    n_info=$((n_info + 1))
  done < <(snap_kv_diff "$a/packages.txt" "$b/packages.txt")
  while IFS="$US" read -r kind key old new; do
    case "$key" in prop.*) ;; *) continue ;; esac
    echo "INFO display ${key#prop.}: '$old' -> '$new'" >>"$out"
    n_info=$((n_info + 1))
  done < <(snap_kv_diff "$a/display-info.txt" "$b/display-info.txt")
  while IFS="$US" read -r kind pkg bsha asha; do
    suffix="${pkg#com.nuvio.tv.test}"
    echo "INFO test APK $pkg: ${bsha:-not installed} -> ${asha:-not installed}" >>"$out"
    n_info=$((n_info + 1))
    [ -n "$bsha" ] || continue
    if [ -f "$a/apk/$pkg.apk" ]; then
      echo "     to put the recorded APK back: adb -s \"\$s\" install -r $a/apk/$pkg.apk" >>"$out"
    else
      row="$(awk -F'\t' -v h="$bsha" -v p="$pkg" '$4 == h && $3 == p { print $2 }' "$(dirname "$0")/../.local/shield-builds.tsv" 2>/dev/null | tail -1 || true)"
      echo "     no APK copy (snapshot without --pull-apk). To rebuild it: check out ${row:-the commit it was built from (not in .local/shield-builds.tsv)} and run scripts/shield_build_install.sh --suffix $suffix --install, with the --benchmark or --debuggable flag it was built with" >>"$out"
    fi
  done < <(snap_kv_diff <(awk -F'\t' '{ print $1 "=" $3 }' "$a/apks.tsv") <(awk -F'\t' '{ print $1 "=" $3 }' "$b/apks.tsv"))
}

verdict_code() {
  if [ "$n_owner" -gt 0 ]; then echo "$EXIT_OWNER"
  elif [ "$n_drift" -gt 0 ]; then echo "$EXIT_DRIFT"
  else echo 0; fi
}

# The health scan; on a hit the run stops with the device verdict.
health_or_stop() {  # <file> <when>
  if ! snap_health "$1"; then
    echo device >"$rd/verdict"
    shield_stop "the Shield logged Watchdog or flush_work $2 ($(basename "$1").hits)"
  fi
}

main() {
  local serial="" session="" dry=false c stamp before pre after label rc kind key old new acts=() act
  local ns k fg i pa pd ba bd code newest t
  while [ $# -gt 0 ]; do
    case "$1" in
      --serial) serial="${2:?--serial needs a value}"; shift 2 ;;
      --session) session="${2:?--session needs a value}"; shift 2 ;;
      --setting)
        [[ "${2:-}" =~ $SETTING_RE ]] || shield_die "$EXIT_USAGE" "--setting '${2:-}': use <global|secure|system>/<key>"
        named+=("$2"); shift 2 ;;
      --dry-run) dry=true; shift ;;
      -h | --help) sed -n '2,21p' "$0"; exit 0 ;;
      *) echo "unknown argument: $1" >&2; exit "$EXIT_USAGE" ;;
    esac
  done

  if [ -z "$session" ]; then
    # Newest by the time the snapshot finished (ISO UTC sorts as text), not by folder name.
    newest=""
    for c in "$SHIELD_SNAPSHOTS"/*/before/complete; do
      [ -f "$c" ] || continue
      t="$(kv_get "$c" date_utc)"
      if [ -z "$session" ] || [[ "$t" > "$newest" ]] || [ "$t" = "$newest" ]; then session="${c%/before/complete}"; newest="$t"; fi
    done
    [ -n "$session" ] || shield_die "$EXIT_REFUSED" "no complete snapshot under $SHIELD_SNAPSHOTS; pass --session"
    # restored-clean outlives later runs (a dry run, a refused or stopped one), so the newest session
    # never falls back to being a baseline once it was restored.
    if [ -f "$session/restored-clean" ]; then
      shield_die "$EXIT_REFUSED" "the newest complete session, $session, is already restored clean; pass --session to restore another one"
    fi
  fi
  session="${session%/}"
  [ "$(basename "$session")" != before ] || session="$(dirname "$session")"
  before="$session/before"
  [ -f "$before/complete" ] || shield_die "$EXIT_REFUSED" "$before has no complete marker: that snapshot did not finish; not restoring to it"
  [ ! -f "$before/parse-errors" ] || shield_die "$EXIT_REFUSED" "$before has parse errors; not restoring to it"
  [ "$(kv_get "$before/meta" format)" = "$SNAP_FORMAT" ] || shield_die "$EXIT_REFUSED" "$before/meta is not format $SNAP_FORMAT"
  [ -n "$(kv_get "$before/meta" device_id_sha256)" ] && [ "$(kv_get "$before/meta" device)" = mdarcy ] ||
    shield_die "$EXIT_REFUSED" "$before/meta does not name the device; not restoring to it"
  label="$(kv_get "$before/meta" label)"
  echo "using $session"
  [ -z "$(find "$before/complete" -mmin +1440)" ] || echo "warning: the snapshot is more than 24 h old" >&2
  read_session_changes "$session/session-changes.txt"
  snap_load_volatile
  [ "$snap_local_volatile" = 0 ] || echo "using $snap_local_volatile local volatile patterns from $SHIELD_SNAPSHOTS/volatile-keys.txt; move them into VOLATILE_KEYS"

  shield_init "$serial"
  stamp="$(date +%Y%m%d-%H%M%S)"
  if $dry; then
    rd="$session/restore.dry-$stamp"
  else
    for c in restore after; do [ ! -e "$session/$c" ] || mv "$session/$c" "$session/$c.prev-$stamp"; done
    rd="$session/restore"
  fi
  pre="$rd/pre"
  after="$session/after"
  mkdir -p "$pre"
  shield_log="$rd/adb-stderr.log"
  shield_hang_dir="$rd"
  shield_connect

  # Read before any write: who the device is, whether it is healthy, and what changed.
  snap_getprop "$pre"
  [ "$(snap_device "$pre")" = mdarcy ] || shield_die "$EXIT_REFUSED" "device is '$(snap_device "$pre")', not mdarcy; nothing written"
  [ "$(snap_device_id "$pre")" = "$(kv_get "$before/meta" device_id_sha256)" ] ||
    shield_die "$EXIT_REFUSED" "this is not the Shield the snapshot was taken on; nothing written"
  health_or_stop "$rd/logcat-tail.txt" "before the restore"
  snap_collect "$pre" pre "$label" false
  [ ! -f "$pre/parse-errors" ] || shield_die "$EXIT_REFUSED" "getprop lines for debug.nuvio did not parse (restore/pre/parse-errors); nothing written"
  [ "$(kv_get "$pre/meta" uptime_s)" -ge "$(kv_get "$before/meta" uptime_s)" ] ||
    echo "INFO the Shield rebooted since the snapshot: the reboot cleared the debug.nuvio.* properties; the snapshot's are set again"

  # Plan.
  : >"$rd/plan.txt"
  while IFS="$US" read -r kind key old new; do
    [[ "$key" =~ $PROP_RE ]] || shield_die "$EXIT_REFUSED" "refusing to set property '$key'"
    if [ "$kind" = added ]; then acts+=("setprop $key ''"); else acts+=("setprop $key $(shq "$old")"); fi
  done < <(snap_kv_diff "$before/debug-nuvio.props" "$pre/debug-nuvio.props")
  while IFS="$US" read -r kind key old new; do
    if [ "$kind" = changed ]; then acts+=("logcat -b $key -G $old"); else echo "cannot restore logcat buffer $key ($kind)" >>"$rd/plan.txt"; fi
  done < <(snap_kv_diff "$before/logcat-buffers.txt" "$pre/logcat-buffers.txt")
  for ns in global secure system; do
    while IFS="$US" read -r kind key old new; do
      is_named "$key" || continue
      k="${key#*/}"
      if [ "$kind" = added ]; then acts+=("settings delete $ns $k")
      elif [ "$old" = null ]; then echo "owner: $key was null in the snapshot, which settings put cannot write; set it by hand" >>"$rd/plan.txt"
      else acts+=("settings put $ns $k $(shq "$old")"); fi
    done < <(snap_kv_diff "$before/settings-$ns.txt" "$pre/settings-$ns.txt" "$ns/")
  done
  for act in ${acts[@]+"${acts[@]}"}; do echo "write: $act" >>"$rd/plan.txt"; done
  pa="$(kv_get "$pre/display-mode.txt" active)"; pd="$(kv_get "$pre/display-mode.txt" default)"
  ba="$(kv_get "$before/display-mode.txt" active)"; bd="$(kv_get "$before/display-mode.txt" default)"
  [ "$pa:$pd" = "$ba:$bd" ] || echo "display: mode $pa (default $pd), snapshot $ba (default $bd); HOME once if a test build is in front, else the owner sets it" >>"$rd/plan.txt"
  for c in ${notes[@]+"${notes[@]}"}; do echo "session note: $c"; done

  if $dry; then
    report "$before" "$pre" "$rd/diff.txt"
    cat "$rd/plan.txt" "$rd/diff.txt"
    echo "dry run: nothing sent. DRIFT $n_drift / OWNER $n_owner / INFO $n_info / IGNORED $n_ignored ($rd/plan.txt, $rd/diff.txt)"
    exit "$(verdict_code)"
  fi

  # Apply. A write that fails is logged and shows as drift in the diff; a timeout stops the run.
  : >"$rd/actions.log"
  for act in ${acts[@]+"${acts[@]}"}; do
    rc=0
    shield_adb_to "$rd/.act" shell "$act" || rc=$?
    echo "$(date -u +%T) $act -> exit $rc" >>"$rd/actions.log"
    [ "$rc" = 0 ] || echo "warning: '$act' failed (exit $rc)" >&2
  done
  if [ "$pa:$pd" != "$ba:$bd" ]; then
    snap_foreground "$rd"  # read again now: the owner may have opened an app since restore/pre
    fg="$snap_fg"
    if [[ "$fg" =~ $TEST_PKG_RE ]]; then
      shield_sh "$rd/.act" "input keyevent KEYCODE_HOME"
      echo "$(date -u +%T) input keyevent KEYCODE_HOME ($fg was in front) -> exit 0" >>"$rd/actions.log"
      for ((i = 0; i < SHIELD_POLL_TRIES; i++)); do
        sleep "$SHIELD_POLL_INTERVAL"
        shield_sh "$rd/.display" "dumpsys display"
        snap_parse_display "$rd/.display" >"$rd/.mode" || continue
        [ "$(kv_get "$rd/.mode" active):$(kv_get "$rd/.mode" default)" != "$ba:$bd" ] || break
      done
      rm -f "$rd/.display" "$rd/.mode"
    else
      echo "$(date -u +%T) display differs and $fg is in front, not a test build: no key sent" >>"$rd/actions.log"
    fi
  fi
  rm -f "$rd/.act"
  health_or_stop "$rd/logcat-tail-after.txt" "during the restore"

  mkdir -p "$after"
  shield_log="$after/adb-stderr.log"
  snap_getprop "$after"
  snap_collect "$after" after "$label" false
  report "$before" "$after" "$rd/diff.txt"
  code="$(verdict_code)"
  case "$code" in 0) echo clean ;; "$EXIT_DRIFT") echo drift ;; *) echo owner ;; esac >"$rd/verdict"
  [ "$code" != 0 ] || cp "$rd/verdict" "$session/restored-clean"
  [ ! -s "$rd/actions.log" ] || cat "$rd/actions.log"
  cat "$rd/diff.txt"
  echo "verdict $(cat "$rd/verdict"): DRIFT $n_drift / OWNER $n_owner / INFO $n_info / IGNORED $n_ignored ($rd/diff.txt)"
  exit "$code"
}

main "$@"
