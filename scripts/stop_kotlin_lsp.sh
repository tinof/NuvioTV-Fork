#!/usr/bin/env bash
# Stop the Kotlin language server for this checkout, and the Gradle daemons it started, before a
# local Gradle build.
#
#   scripts/stop_kotlin_lsp.sh [<repo-root>]
#
# A local build writes thousands of files, which overflows kotlin-lsp's file watcher. The server then
# re-imports the project: a second Gradle build (its own daemon, a 6 GB heap) that compiles the
# debug variant and runs KSP next to ours. On 2026-11-16 that corrupted app/build/kspCaches, held
# the Gradle cache lock for 6 minutes, and left the server's daemon stuck in full GC at 700% CPU.
# ~/.gradle/init.d/kotlin-lsp-import.gradle now moves the import's build directory out of the repo.
# This guard stops the server, so it neither competes for CPU and memory nor re-imports mid-build.
#
# Only a server whose working directory is <repo-root> is stopped (that is the workspace it
# indexes). Claude Code starts it again on the next LSP call. It is a no-op where no server runs,
# for example on CI.
set -uo pipefail

root="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
command -v pgrep >/dev/null 2>&1 && command -v lsof >/dev/null 2>&1 || exit 0

stopped=()
for pid in $(pgrep -f 'kotlin-lsp --stdio' || true); do
  cwd="$(lsof -a -p "$pid" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p' | head -1)"
  [ "$cwd" = "$root" ] || continue
  # The server respawns its Gradle daemon within seconds, so record the children, then stop the
  # server first and its children after it.
  children="$(pgrep -P "$pid" || true)"
  kill "$pid" 2>/dev/null || true
  stopped+=("$pid")
  for child in $children; do kill "$child" 2>/dev/null || true; stopped+=("$child"); done
done
[ ${#stopped[@]} -eq 0 ] && exit 0

for _ in 1 2 3 4 5 6 7 8 9 10; do
  alive=()
  for pid in "${stopped[@]}"; do kill -0 "$pid" 2>/dev/null && alive+=("$pid"); done
  [ ${#alive[@]} -eq 0 ] && break
  sleep 1
done
for pid in ${alive[@]+"${alive[@]}"}; do kill -9 "$pid" 2>/dev/null || true; done
echo "== stopped kotlin-lsp and its Gradle daemons (pids ${stopped[*]}); the next LSP call starts it again"
