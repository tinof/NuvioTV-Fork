#!/usr/bin/env bash
# Run the unit tests for Checks and keep what `sync.py compare-results` reads in <out-dir>: the JUnit
# XML, the Gradle log and meta.json. Run it from the tree under test. It exits with Gradle's code.
#
#   scripts/ci_unit_tests.sh <out-dir>
#
# meta.json is what tells a crash from a clean run. A test JVM that dies writes no XML for that run,
# not even for the classes that finished (Gradle 8.13, Runtime.halt in a test), and an older run's
# XML stays where it was and looks complete, so it goes first. Gradle only says "Process 'Gradle
# Test Executor 1' finished with non-zero exit value". source_classes counts the test files of this
# tree, so a run can be held to the tests that tree really has.
set -uo pipefail

out="${1:?usage: ci_unit_tests.sh <out-dir>}"
mkdir -p "$out"

results=app/build/test-results/testFullDebugUnitTest
rm -rf "$results"
./gradlew :app:testFullDebugUnitTest --continue --stacktrace 2>&1 | tee "$out/gradle.log"
code=${PIPESTATUS[0]}

cp "$results"/*.xml "$out/" 2>/dev/null

crashed=false
grep -q 'Gradle Test Executor .* finished with non-zero exit value' "$out/gradle.log" && crashed=true
classes="$(git ls-files 'app/src/test/*Test.kt' | wc -l | tr -d ' ')"
printf '{"sha":"%s","gradle_exit":%d,"crashed":%s,"source_classes":%d}\n' \
  "$(git rev-parse HEAD)" "$code" "$crashed" "$classes" > "$out/meta.json"
exit "$code"
