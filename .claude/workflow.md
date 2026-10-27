# Workflow contract

What `/ship`, `/ship:roadmap-next` and `/ship:roadmap-init` need to know about this repository. The schema,
with every key and section, is `~/.claude/skills/ship/reference/contract.md`. Keep every `##` heading
exactly as written: the skills look sections up by name. A missing section, or one whose body is `none`,
turns that part off. A diff that touches this file is always Tier 2. This file is fork-only.

## Settings

```
contract:       1
repo:           tinof/NuvioTV-Fork
base:           perf/shield-pass
merge:          rebase
branch:         <type>/<id>-<slug>
roadmap:        ROADMAP.md
roadmap-log:    docs/ROADMAP_LOG.md
roadmap-memory: off
push-roadmap:   no
docs-prefix:    "fork: "
gate:           ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}" ./gradlew :app:testFullDebugUnitTest --continue
gate-focused:   ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}" ./gradlew :app:testFullDebugUnitTest --tests '<pattern>'
gate-fallback:  ci
gate-skip:      '*.md' 'docs/**'
ci:             pr-checks
logic:          '*.kt' '*.kts' '*.java' '*.c' '*.cpp' '*.h' '*CMakeLists.txt'
tests:          'app/src/test/**' 'app/src/androidTest/**' 'scripts/tests/**'
self-check:     none
```

- The merge is `--rebase`, so each commit (one finding per commit, `fork:` commits) stays separate for
  the upstream pull requests (ROADMAP U1). It gives every commit a new SHA, so freshness checks compare
  trees, and the roadmap cites PR numbers, never SHAs.
- The gate needs JDK 17 and `ANDROID_HOME`. On its first run the Android Gradle plugin installs the
  missing platform 36, build-tools 35 and NDK 29 itself (licences are accepted); that run takes about six
  minutes. Mutation testing is not used in this repo.
- Since 2026-10-22 GitHub builds no APK or AAR: the five binary workflows (`PR Full Debug Build` and its
  comment, `Build Android TV Release`, `Build FFmpeg decoder AAR`, `Patch datasource AAR`) are disabled
  with `gh workflow disable`, and binaries are built locally only (`scripts/shield_build_install.sh`, the
  local FFmpeg module build in `ffmpeg-decoder-downmix/README.md`). CI is the fork-only `Checks` workflow
  (`.github/workflows/checks.yml`): unit tests on every pull request into `main` or `perf/shield-pass`,
  test reports only. `/ship` waits for the PR's checks (`ci: pr-checks`).

## Gates

none

## Tier 2 paths

```
app/src/main/java/com/nuvio/tv/core/player/FrameRateUtils.kt
app/src/main/java/com/nuvio/tv/core/player/DisplayCapabilities.kt
app/src/main/java/com/nuvio/tv/core/player/*AfrProbe.kt
app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerAfr*.kt
app/src/main/java/com/nuvio/tv/ui/screens/player/AfrPreflightGate.kt
app/src/main/java/com/nuvio/tv/core/player/AudioPassthroughPolicy.kt
app/src/main/java/com/nuvio/tv/core/player/PassthroughBufferCaps.kt
app/src/main/java/com/nuvio/tv/core/player/SurroundFormatResolver.kt
app/src/main/java/com/nuvio/tv/ui/screens/player/iec/**
app/src/main/java/com/nuvio/tv/ui/screens/player/PlaybackSpeedAware*.kt
app/src/main/java/com/nuvio/tv/ui/screens/player/Passthrough*.kt
app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerLifecycle.kt
app/src/main/java/com/nuvio/tv/data/local/*DataStore.kt
app/src/main/java/com/nuvio/tv/core/performance/DeviceProfile.kt
app/src/main/java/com/nuvio/tv/MainActivity.kt
app/src/main/java/com/nuvio/tv/core/player/LetterboxRenderPolicy.kt
app/libs/**
**/*.gradle.kts
gradle/**
.github/workflows/**
.claude/**
.agents/**
```

Display-mode switching and passthrough decide picture and sound on the Shield; the player lifecycle and
the window setup are where incidents 01 and 02 came from; settings stores and `DeviceProfile` carry the
Nvidia defaults; the AARs are patched engine code; the build files carry the toolchain that has to stay
on upstream's versions. CI workflows build and sign the APKs the Shield runs, and the agent config
instructs the reviewers.

## Routing

default

## Testing rules

- Unit tests run on the JVM with `unitTests.isReturnDefaultValues = true` (`app/build.gradle.kts`): an
  Android framework call returns 0, `false` or `null` instead of failing. Test the extracted pure logic
  (for example `LetterboxRenderPolicy`, `displayModeRestorePlan`), never a value a framework call returns.
- Use `MainDispatcherRule` for coroutines and `TestPreferencesStore` for DataStore-backed settings
  (`app/src/test/java/com/nuvio/tv/`).
- Behaviour only the Shield shows (HDMI mode switches, AVR passthrough, SurfaceFlinger composition,
  HDCP) is not mocked into a unit test: the finding is NEEDS-HUMAN and becomes a `[device]` roadmap step.

## Probes

- `shield: s="$(rg -o 'adb connect ([0-9.]+):5555' -r '$1' docs/nvidia-shield.md | head -1):5555"; gtimeout 15 adb connect "$s" >/dev/null 2>&1; [ "$(gtimeout 15 adb -s "$s" shell getprop ro.product.device 2>/dev/null | tr -d '\r')" = mdarcy ] && echo "Shield reachable (mdarcy)" || { echo "Shield unreachable"; false; }` The Shield answers over adb and is the `mdarcy` box.
- `new-build: s="$(rg -o 'adb connect ([0-9.]+):5555' -r '$1' docs/nvidia-shield.md | head -1):5555"; gtimeout 15 adb connect "$s" >/dev/null 2>&1; h=$(gtimeout 15 adb -s "$s" shell 'sha256sum "$(pm path com.nuvio.tv.test.shield | head -1 | cut -d: -f2)"' 2>/dev/null | cut -d' ' -f1); [ -n "$h" ] && awk -F'\t' -v c="$(git rev-parse HEAD)" -v h="$h" '$2==c && $4==h {f=1} END {exit !f}' .local/shield-builds.tsv 2>/dev/null && echo "installed APK is a local build of HEAD" || { echo "new build needed"; false; }` The installed `com.nuvio.tv.test.shield` APK hashes to a row of `.local/shield-builds.tsv` for HEAD built from a clean tree.

## Tags

- `device`: needs probe `shield`
- `new-build`: needs probe `shield`, `new-build`

## Readiness rules

none

## Material

- `docs/nvidia-shield.md`: the Shield's address and adb quick reference (gitignored; never print the
  address in an answer).
- `.local/shield-builds.tsv`: the local build log that `scripts/shield_build_install.sh` writes (date,
  commit, package, APK sha256, signing cert).

## Branching

- Types: `fix/`, `feat/`, `perf/`, `docs/`, `fork/`.
- Sharing rule: desk steps of one item may share a branch.
- Exceptions: a step that changes a default gets its own branch; AFR changes are one commit per finding.

## Release

none

## Codex notes

- Codex's sandbox cannot reach the Shield, the gitignored `docs/nvidia-shield.md` or `.local/`; device
  state and build freshness come from the probes, not from Codex.
- The full native build (libdovi, FFmpeg decoder) runs only locally since 2026-10-22, never in CI; a
  missing native artifact in a checkout is not a finding.

## Reminders

- Run the M2 device-safety protocol (snapshot first, restore after) before any device step.
- Never install a CI artifact built before 2026-10-17 over `com.nuvio.tv.test.shield`: it is signed with
  the old debug key (`b2ec56…`) and cannot update the installed app (`c22ec9…`).

## Notes

Upstream syncs (ROADMAP U1.5) run through `/upstream-sync` (`.claude/skills/upstream-sync/`), not `/ship`.
One rebased branch: `perf/shield-pass`, the default branch, = `upstream/main` + the fork's CI, test and fix
commits and `.github/workflows/upstream-drift.yml` (which only runs from the default branch) + the Shield work.
`main` mirrors upstream at the base `perf/shield-pass` was last rebased onto and carries no fork commit. A
sync publishes both with one atomic `--force-with-lease` push, after scripted verification, the gate against an
upstream baseline, an independent review of the changed hunks and green CI on a pushed candidate branch. Upstream's four issue workflows (triage, labels,
housekeeping, release) are disabled in this fork so they leave the drift issue alone; their files stay as
upstream has them.

Migrated from `docs/REVIEW.md` on 2026-10-17. The generic review rules (core rules, who built it,
resolving findings, changes after the review, closing a step, review record and labels, outside
contributors) now live in the ship plugin; the routing table there is the same one this repo used.

`roadmap-memory` is off: like the earlier repo copy of roadmap-next, every run ranks afresh and writes
nothing to the roadmap.

The `new-build` probe accepts local builds only, which since 2026-10-22 are the only builds: CI makes no
APK.

Readiness rules are `none`: a rule blocks every step it covers whenever its probe fails, so a path rule
would block `[desk]` steps on the player code while the Shield is off. The `device` and `new-build` tags
carry the device needs. A step that needs a heap dump or a `run-as` backup needs a debuggable build,
which never comes from CI: a local
`scripts/shield_build_install.sh --debuggable --suffix .shielddbg --install` from a clean tree at HEAD,
which installs beside the CI build. No probe checks that; roadmap-next reads it from the step text.

The gate does not pin JDK 17 the way `scripts/shield_build_install.sh` does (`java_home -v 17`); it relies
on `java` resolving to 17 in the shell, which it did on 2026-10-17.
