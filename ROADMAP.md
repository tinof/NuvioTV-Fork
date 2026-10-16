# Shield roadmap

This is the single source of truth for open work on branch `perf/shield-pass`. The full text of each
progress step, the device report numbers and the finding IDs are in
[`docs/ROADMAP_LOG.md`](docs/ROADMAP_LOG.md). The Nvidia defaults, their revert switches and the device
quick reference are in [`docs/shield-todo.md`](docs/shield-todo.md). The patched engine AARs are described
in [`app/libs/README.md`](app/libs/README.md).

> **North star:** on an NVIDIA Shield TV Pro 2019, a Remux plays at the right rate and pacing, TrueHD and
> DTS-HD MA reach the AVR bit-exact, and the UI answers every key press at once. Each claim is measured
> against a baseline, and for the UI against the upstream app on the same box.

**Target:** Shield TV Pro 2019 (`mdarcy`, Tegra X1+, 3 GB RAM, Android 11, SHIELD Experience 9.2.4).
Older Shields are handled case by case.

**Scope.** In scope: video and audio quality (Remux, TrueHD, DTS-HD MA, Atmos and DTS:X passthrough),
UI speed, and bugs and performance in the HDR10, HDR10+, HLG and Dolby Vision playback paths. Out of
scope: HDR features and pipelines (a separate development process) and all conversions (libdovi,
Dolby Vision 7 → 8.1, HDR10+ → Dolby Vision).

Status meanings: **Open** has not shipped; **Partial** has useful pieces in place but does not meet the
gate; **Core complete** meets the gate but keeps named follow-up work; **Deferred** is intentionally not
scheduled. Items with no follow-up left move to the log.

**Step format.** Every open step is one line that `/roadmap-next` reads:
`- [<ID>][tags] <step>. Gate: <evidence>.` with an optional `Needs: <ID>[, <ID>]` for a prerequisite.
IDs are `<item>.<n>` (for example `R2.1`), assigned once and never reused or renumbered. A new step
takes 1 + the highest number ever used for that item in `ROADMAP.md`, `docs/ROADMAP_LOG.md` and
`Roadmap-Step:` commit trailers.

| Tag | Meaning |
|---|---|
| `desk` | Code, AAR or doc work; no Shield needed |
| `device` | Needs the Shield over adb (see [M2](#m2-device-safety-protocol)) |
| `owner` | Needs the owner: a decision, a reading from the TV or AVR, or a power cycle at hand |
| `new-build` | Needs a debug APK of the current HEAD installed on the Shield |

**How to update.** Change the item's status and open steps in place. Add one row to the progress log
(one sentence plus the PR) and the full text of the step to
[`docs/ROADMAP_LOG.md`](docs/ROADMAP_LOG.md). Measurements go to the log, not into the item.

- When a step ships, `/ship` removes its line in a `fork:` commit, updates the item's status, adds a
  progress-log row that cites the PR number (never a commit SHA: rebase merges rewrite SHAs), and adds
  the full step text with its ID, the gate evidence and the PR to the log.
- A step whose gate needs device or owner evidence that does not exist yet stays, with `Landed: PR #n;
  gate pending.` appended.
- A review finding that only the Shield can settle becomes a new `[device]` step with a fresh ID.
- An item with no steps left moves to the log.

## Current status (updated 2026-10-15)

The branch is 63 commits ahead of `main` and 0 behind, so it merges as a fast-forward. It adds 20 Nvidia
defaults, each with a revert switch, and its unit tests pass in CI (run 38585630985).

The device report of 2026-10-14 ran on a build from before round 3 (CI run 38561775458). That build is
still the one installed on the Shield (APK hash checked on 2026-10-15), so every `new-build` step needs a
fresh CI build of HEAD first. The report found two incidents:

- **Incident 02 (regression from this branch):** with "True black bars" on, the letterbox bars show the
  previous screen and old subtitle lines. The cause is the Nvidia `opaque_window` default (be0ce8ec9):
  it creates the app's renderer opaque, so the transparent bars are never cleared (R1).
- **Incident 01:** after Stop, the switch back to the UI refresh rate hung the NVIDIA composer HAL until a
  power cycle. The 750 ms delay added in round 3 does not touch the condition seen before the hang
  (HDCP read failures from the switch-in onwards), so it is not a proven fix (R2).

The report also measured about 3 s from click to first frame, seeks of 81–4187 ms, a 500–750 ms freeze
of the main thread at Stop, and an unpaced 4K seek-thumbnail sweep during playback (43 % CPU, 387 MB
native heap). Home scrolled at 0.23 % janky frames; on the player route single frames took up to 1442 ms
at start and 748 ms at Stop. The findings of the start, seek and memory lane (S1,
S2) were not checked by a skeptic pass; their first step is a re-read.

The owner reported moving the UI from 2160p 25 Hz to 24 Hz; on 2026-10-15 the Shield still read mode 21
(2160p 25 Hz) as active and default. Whatever the UI rate, a title at that rate needs no HDMI mode switch,
and every UI animation runs at it. Device steps that test switching use titles whose rate differs from
the UI mode.

### Waiting for the owner

Steps that need a decision or a reading from the owner. Other steps of the same item stay startable.
`/roadmap-next` reads this list; remove an entry when it is settled.

- **UI refresh rate (S4.1).** 24/25 Hz against 50/60 Hz for the UI, decided from the M1 A/B on the Home
  journeys. It trades UI fluidity against mode switches at every start.
- **AVR readings (Q1.2, Q1.3).** The Atmos, DTS:X and E-AC3 Atmos indicators during the passthrough
  A/Bs, with the TV and AVR models recorded.
- **`hbr_iec` default (Q1.2).** A listening A/B of the platform path against the fork's IEC61937 packer.
- **Hot-Stop session (R2.4).** The owner must be able to power-cycle the Shield during the session.
- **Defaults after measuring (Q2.5, R2.4).** The AFR settle hold (600 or 1200 ms) and whether "keep the
  content frame rate after playback" becomes the Nvidia default.
- **Merge into `main`.** Criteria: R1 done, the R2 mitigation shipped, Q2 done, CI green.
- **Ask the upstream maintainer (U1.1).** Whether Shield work is wanted upstream and in what form; the
  owner decides how and when to ask.

## Progress log

Newest first. One line per step. The full text is in [`docs/ROADMAP_LOG.md`](docs/ROADMAP_LOG.md).

| Date | Step | Items | Where |
|------|------|-------|-------|
| 2026-10-16 | Upstream plan: principles for upstreamable work and the toolchain, U1 added, UI baselines on the `benchmark` build. | U1, M1 | [log](docs/ROADMAP_LOG.md) |
| 2026-10-15 | Roadmap created from the two assessments and the device report. | all | [log](docs/ROADMAP_LOG.md) |
| 2026-10-15 | Second assessment: incident 02 traced to be0ce8ec9; switch-back delay unproven; start, Stop and thumbnail findings. | R1, R2, Q1, S1–S3 | [log](docs/ROADMAP_LOG.md) |
| 2026-10-15 | First assessment: every Nvidia gate and switch matches the doc; AFR, defaults and backport findings. | Q1, Q2, E1, D1 | [log](docs/ROADMAP_LOG.md) |
| 2026-10-14 | Device report on SE 9.2.4: three playback scenarios, Home frame stats, incidents 01 and 02. | all | [log](docs/ROADMAP_LOG.md) |

## Development principles

- **Regressions first.** A defect this branch introduced comes before any improvement.
- **No claim without a number.** A speed or quality claim cites an M1 measurement on the Shield, and for
  the UI the upstream app on the same journeys. "Feels faster" is not a gate.
- **What the owner sees and hears decides the order.** After regressions and baselines, the largest
  change to picture, sound or UI response comes next.
- **Nvidia changes stay gated and revertible.** Each Nvidia default is gated by `DeviceProfile`, yields
  to a saved setting where one exists, has a `debug.nuvio.*` revert switch and a row in
  [`docs/shield-todo.md`](docs/shield-todo.md).
- **Small commits.** One finding per commit where possible. The AFR work is split into reviewable
  commits. A step that changes a default gets its own branch.
- **No backport without a symptom.** A Media3 class is rebuilt only when the device logs show the
  problem it fixes.
- **Device safety.** Every device session follows [M2](#m2-device-safety-protocol). The Shield is in
  daily use.
- **Upstreamable by default.** The Shield work is meant to reach the upstream fork
  (`ysosrs123/NuvioTV-Fork`) as pull requests later. A change is Nvidia-gated, revertible and tested, or
  it states why it applies to every device. Commits that only make sense in this fork (README,
  CONTRIBUTING, CI tweaks, this roadmap, the Claude skill, the Shield docs) start their subject with
  `fork:` so they are easy to leave out. See [U1](#u1-upstream-pull-requests).
- **Stay on upstream's toolchain.** JDK 17 (as in CI), Gradle 8.13, AGP 8.13.2, Kotlin 2.3.0, JVM target
  11. A newer build toolchain gives the Shield nothing at runtime and makes every upstream pull request
  conflict in the build files. Runtime libraries (Media3, Compose, TV Material) change only as a
  measured experiment in their own pull request.
- **Measure the UI on a release-like build.** The CI debug APK is not minified and runs without the
  baseline profile, so it is slower than what users get. UI numbers come from the `benchmark` build type
  (release settings, debug key); debug builds serve playback and log checks.

## Priorities

Order (2026-10-15): the regression this branch introduced; then the safety protocol and the baselines
every later claim needs; then what runs at every start and Stop; then the owner's two goals, sound first
because a wrong passthrough path is invisible until measured.

1. **Incident 02, letterbox bleed** (R1). Gate: clean bars and a single subtitle line on a new build.
2. **Device safety and baselines** (M2, M1). Gate: the protocol tested once; baseline tables for this
   app and the upstream app in the log. The playback start stage is logged before the playback baseline.
3. **Switch-back safety and AFR plan** (R2, Q2). Gate: the mitigation ships; the re-entry test passes.
4. **Passthrough correctness** (Q1). Gate: AVR readings recorded for TrueHD, DTS-HD MA and E-AC3; the
   `hbr_iec` default decided.
5. **Start and Stop latency** (S1, S3). Gate: click → playing and the Stop stall beat the M1 baseline.
6. **Seek and thumbnails** (S2). Gate: CPU, native heap and drops with thumbnails on within the S2
   tolerance of thumbnails off. The measured 43 % CPU load comes before UI tuning.
7. **Small correctness fixes** (R3). Gate: unit tests; the CEC check passes.
8. **UI frames and navigation** (S4). Gate: the M1 journeys beat the baseline and the upstream app at the
   chosen refresh rate.
9. **Frame pacing, listener leak, decode-path bugs** (Q3, Q4).
10. **Media3 backports** (E1), only on a symptom; the ready grace is startable.
11. **Diagnostics and docs** (M3, D1).
12. **Upstream pull requests** (U1). The commit sorting and the question to the maintainer can start any
    time; the pull requests follow the items they carry.

## Reliability

### R1: incident 02, letterbox bars show the previous screen

- **Status:** Open. Findings W1–W5.
- be0ce8ec9 sets a black window background in `MainActivity.onCreate` (`MainActivity.kt:407`), before
  the window is attached, so the renderer is created opaque and never clears. When the player switches
  the window to transparent for true black bars, the bar areas keep old pixels. The default never made
  the app layer opaque to the compositor either: once a SurfaceView has attached, the window format is
  forced to TRANSLUCENT (Home showed `isOpaque=0`). Nothing outside the commit reads `opaque_window`.
- [R1.1][desk] Revert be0ce8ec9 (it applies cleanly) and remove the `opaque_window` row from the defaults
  table. Gate: unit tests green.
- [R1.2][device] On the current APK, confirm the mechanism: `setprop debug.nuvio.opaque_window 0`, restart the
  app, play a 2.40:1 title with True black bars on. Gate: bars black with `0`, stale with the default.
- [R1.3][device][new-build] Two 2.40:1 titles with True black bars on, started from the detail page, with ASS
  subtitles on the canvas overlay and on Overlay OpenGL. Gate: black bars, one subtitle line, no CLIENT
  composition in `dumpsys SurfaceFlinger`. Needs: R1.1.

### R2: switch-back after Stop (incident 01)

- **Status:** Partial. The 750 ms delay (b353ea5fc) ships but is unproven. Findings H1–H5, H7, B2, B3.
- In the incident the video layer was already gone when the switch-back hung; HDCP read failures had
  started right after the switch-in. Of the strategies weighed, only skipping the switch-back removes the
  mode set at Stop (H4). A clean run of 30 Stops cannot tell the delay from no delay (H5).
- [R2.1][desk] When the activity is finishing or destroyed, or the window is detached, return Skip plus
  `clearOriginalDisplayMode()` on Nvidia instead of restoring at once (`FrameRateUtils.kt:808`, `:840-844`),
  with a unit test for `displayModeRestorePlan`. Gate: test green.
- [R2.2][desk] A pending restore is cancelled when any new player attaches, not only one that plans a mode
  (B2). Gate: unit test.
- [R2.3][desk] User setting "Keep the content frame rate after playback", backed by `afr_restore_skip`,
  default off. Gate: setting persists; switch still overrides.
- [R2.4][device][owner][new-build] Hot-Stop test: loop HDR titles whose rate differs from the UI mode (start,
  play 60–120 s, Stop), rotating arms A/B/C = `afr_restore_delay_ms` 0 / 750 / 3000 (each with
  `afr_restore_skip 0` set explicitly) and D = `afr_restore_skip 1`. A Stop is "hot" when `NvTsecHdcpReadCaps
  failed` appears in the 30 s before it. Record hot and cold Stops and hangs per arm. Gate: the per-arm table
  is in the log; the skip setting ships either way; a delay arm becomes the default only with at least 5 hot
  Stops and no hang on it, and otherwise the fault is documented with no fix claimed. Needs: M2.1.
- Deferred: reading logcat for the HDCP failure as a leading sign (H3); it needs `READ_LOGS` granted over
  adb and is only worth it if the hot-Stop test shows the condition matters.

### R3: small correctness fixes

- **Status:** Open. Findings A-M3, A-M7.
- [R3.1][desk] Make the four `PlayerSettings` data-class defaults Nvidia-aware, as `libassRenderType` already
  is (`PlayerSettingsDataStore.kt:263, 307, 309, 311`); the corrupt-store fallback at `:1418` uses them. Gate:
  unit test on a Nvidia profile.
- [R3.2][desk] Save watch progress on the mpv lifecycle pause (`PlayerRuntimeControllerMpv.kt:247-254`), so a
  CEC sleep keeps the resume point. Gate: unit or integration test.
- [R3.3][device][owner][new-build] CEC sleep: turn the TV off during playback with ExoPlayer and with mpv,
  kill the app, check the resume point. Gate: progress saved on both. Needs: R3.2.

## Playback quality

### Q1: audio passthrough correctness

- **Status:** Partial. TrueHD reached the AVR as direct passthrough with 0 underruns on the HUD. The
  `hbr_iec` default was never A/B tested and no AVR indicator was recorded. Findings A1–A3, A6, D2, D3.
- [Q1.1][desk] Log changes: "HBR DIRECT" instead of "HBR RAW … fallback" when IEC is off by design (A1); bytes
  skipped and the first cluster time in `MKV_RESYNC` (A2); the delta and time since the last seek on
  `UnexpectedDiscontinuityException` (A3). Gate: tests on the log helpers.
- [Q1.2][device][owner][new-build] `hbr_iec` 0 against 1 on a TrueHD Atmos title and a DTS-HD MA title
  (tunneling off). Record the AVR indicator, `HBR active payload=` against `HBR DIRECT`, and
  `restartIfDisabled`. Gate: readings in the log; default decided. Needs: Q1.1.
- [Q1.3][device][owner] E-AC3 Atmos: play an E-AC3 JOC stream and record the AVR indicator. Gate: reading in
  the log.
- [Q1.4][device] `hbr_buffer_scale` 1 against 2 over 10 seeks: time from `SEEK_READY` to the first advance of
  the AudioTrack playback head (logcat), and underruns. Gate: numbers in the log; the ×2 cap kept or dropped.
- [Q1.5][device][owner] TrueHD stress: 20 seeks and 10 pause/resume cycles, then switch the AVR input away and
  back (D2, D3). Gate: no mute, no crash, sound returns without a restart; otherwise a re-prepare step is
  added here.
- [Q1.6][desk] Optional: detect E-AC3 JOC (Atmos) from the first syncframe and show it on the HUD (A6). Gate:
  unit test on a JOC syncframe.
- [Q1.7][device][new-build] The HUD shows Atmos on an E-AC3 JOC stream. Gate: screenshot in the log. Needs:
  Q1.6.

### Q2: AFR plan and settle correctness

- **Status:** Open. Findings B1, B4, B5, D1, D5.
- [Q2.1][desk] Before planning a mode, wait off the main thread, with a timeout, for an issued restore that
  the display has not reached yet, so a quick re-entry does not keep the UI rate (B1,
  `FrameRateUtils.kt:681`). Gate: unit test of the plan.
- [Q2.2][desk] Do not update `isHdrVideo` and `tunnelingApplied` from the both-off selection during an
  audio-and-video quiesce (B4, `PlayerRuntimeControllerTracks.kt:174`). Gate: unit test.
- [Q2.3][desk] Clamp the settle hold to 3000 ms so the track-AFR deadline cannot run out (B5). Gate: unit
  test.
- [Q2.4][device][new-build] Re-entry: Stop a title whose rate differs from the UI mode, then start another of
  the same rate while the restore to the UI mode is issued but not yet reached. Gate: the new title plays at
  its own rate. Needs: Q2.1.
- [Q2.5][device][owner] Settle hold 600 against 1200 ms, three starts each of a TrueHD title whose rate
  differs from the UI mode. Gate: no AudioSink write errors, no `restartIfDisabled`, picture and sound locked;
  default decided.

### Q3: frame pacing and display-listener leak

- **Status:** Open. Findings H6, C2, C4.
- SurfaceFlinger logged "Timestamp … seems implausible" on every frame of the HEVC and native DV runs
  and never on the run whose mode switch came before the codec started.
- [Q3.1][device] `render_trace 1`, check `releaseInMs` is 0–50 ms while the warning shows; then the same title
  with `afr_quiesce_av 1`. Gate: the live mode switch confirmed or ruled out as the trigger.
- [Q3.2][device][new-build] On a `-Pdebuggable=true` build installed beside the CI build with its own suffix
  (`-PnuvioAppIdSuffix=.shielddbg`; heap dumps only, never timings): `am dumpheap` after 3 playbacks and find
  the owner of each `DisplayListenerDelegate`. Gate: owner identified.
- [Q3.3][desk] Patch `VideoFrameReleaseHelper$DisplayHelper` (already in the rebuilt set) with an idempotent
  unregister, and fold in cb0d86f25f (no `Surface.setFrameRate` on an invalid surface). Gate: the AAR rebuild
  recipe reproduces the unchanged classes; unit tests green. Needs: Q3.2.
- [Q3.4][device][new-build] Five playbacks on the patched build. Gate: no "Handler on a dead thread" lines;
  the listener count stays flat. Needs: Q3.3.
- Deferred: building the player while the preflight mode switch settles (P-03). `prepare()` can render
  through a live codec, the suspected timestamp trigger.

### Q4: decode-path bugs

- **Status:** Open. Findings A-M2, D7, D8.
- [Q4.1][desk] The Dolby Vision 7 flag is set from the setting and the probe without a content check, so an
  HDR10 file logs `dv7dovi=true` and runs the DV extractor factory
  (`PlayerRuntimeControllerInitialization.kt:746-747`). Require a DV7 track. Gate: unit test.
- [Q4.2][desk] HDR Fit lock: while HDR video is locked to Fit, show "Fit (HDR)" and do not cycle or save the
  aspect mode (`PlayerAspectScaleUtils.kt:34`). Gate: unit test.
- [Q4.3][device] `async_codec` 0 against 1 on a 4K60 title: dropped frames with thumbnails on. Gate: numbers
  in the log; default kept or reverted.
- [Q4.4][device] A high-bitrate DV remux with Atmos over Wi-Fi and over Ethernet (D7). Gate: rebuffers and
  drops recorded.
- [Q4.5][device][owner] Dolby Vision signalling: read the TV's info screen for a profile 5 and a profile 8
  title (DV mode and, where shown, the signal format) against the hwcomposer mode name in logcat (D8). Gate:
  readings in the log.

## Snappiness

### S1: start latency

- **Status:** Open. Findings P-01, P-02, P-10, P-11, P-12. Not skeptic-checked.
- On a track-AFR start, playback began 1.8–2.7 s after the first frame. The stable time is read on an IO
  thread, but the result and the fixed 1200 ms hold run on the main thread, which the HDMI re-link
  stalls (`PlayerRuntimeControllerAfrTrack.kt:340-347`).
- [S1.1][desk] Re-read P-01, P-02 and P-11 against the code and the report logs before any change. Gate: each
  confirmed or dropped in the log.
- [S1.2][desk] Record the stable time on the IO thread and hold only `max(0, hold − (now − stableAt))`; apply
  the same rule to the pre-start path. Gate: unit test. Needs: S1.1.
- [S1.3][desk] When parallel networking is off, drop the 4 MB tail prewarm (P-12). Gate: unit test.
- [S1.4][device][new-build] Three starts of a 4K TrueHD remux that switches rate. Gate: click →
  `PLAYBACK_STARTED` beats the M1 baseline. Needs: M1.4.

### S2: seek and thumbnails

- **Status:** Open. Findings P-04 to P-07, P-09. Not skeptic-checked.
- The Shield counts as a strong box, so the 4K thumbnail sweep runs unpaced with 2 lanes while a remux
  plays (`SeekThumbnailEngine.kt:993`, `:1962`); strong boxes also skip the playback monitor
  (`:725`). The round-3 swap rule did not apply: SwapFree was 385 MB in that run.
- [S2.1][desk] Re-read P-05 to P-07 against the code and logs. Gate: confirmed or dropped in the log.
- [S2.2][desk] While a 4K remux plays on mdarcy: paced path, 1 lane, playback monitor on. Gate: unit test.
  Needs: S2.1.
- [S2.3][desk] Hold thumbnail work while playback is starting or an AFR hold is active (an explicit start/hold
  state: the AFR path keeps `playWhenReady` false during the hold, `PlayerRuntimeControllerAfrTrack.kt:223`),
  and cancel non-urgent thumbnail fetches when a seek is committed (P-06). Gate: unit test.
- [S2.4][desk] Abort the socket when a load is cancelled, so a stalled read does not delay the seek (P-07).
  Gate: test with a stalled source.
- [S2.5][desk] Read the MKV Cues element in bulk with a size cap; keep the audio cue points (per-track seek
  and the audio-only fallback use them) (P-04). Gate: extractor tests green.
- [S2.6][device][new-build] Identical cold starts of a 4K remux with thumbnails on and off, measured over 60 s
  from the moment the sweep starts. Gate: with thumbnails on, CPU within 5 percentage points, native heap
  within 50 MB and dropped frames within 2 of thumbnails off. Needs: M1.4.

### S3: Stop freeze

- **Status:** Open. Finding P-08. Not skeptic-checked.
- [S3.1][desk] Take the `MEM_FINAL` snapshot off the main thread, and time snapshot, stop, surface clear and
  release separately (`PlayerMemoryReporter.kt:80-83`). Stop and surface clearing already run before release
  (`PlayerRuntimeControllerLifecycle.kt:90-94`); do not reorder them. Gate: phase times logged.
- [S3.2][device][new-build] Ten Stops: main-thread stall and JankStats on the player route. Gate: beats the M1
  baseline. Needs: S3.1, M1.4.

### S4: UI frames and navigation

- **Status:** Open.
- The report measured Home at 0.23 % janky frames, and player-route frames up to 1442 ms at start and
  748 ms at Stop. No visual-quality default reads `DeviceProfile.isNvidia`.
- [S4.1][device][owner] UI refresh rate A/B: the M1 Home journeys at the current UI rate and at 50/60 Hz.
  Gate: numbers in the log; owner decides. Needs: M1.2.
- [S4.2][desk] Candidates, each behind a debug switch so one build can A/B them: the Home focus debounce of
  110/250 ms (`ModernHomeRows.kt:189-198`); the Coil cache and decode settings
  (`NuvioApplication.kt:163-293`); the automatic visual-quality tier on Nvidia (`VisualQuality.kt:32-47`).
  Gate: unit tests; each switch reverts.
- [S4.3][device][new-build] A/B each candidate, and `speed` compilation against the committed baseline
  profile, on the M1 journeys. Gate: a candidate lands only if it beats the baseline. Needs: S4.2, M1.4.
- Baseline-profile regeneration needs an API 34+ device or emulator (`scripts/perf_baseline.py:76-77`);
  the committed profile stays until then.
- Deferred: the focused-card width animation (a row redesign).

## Measurement and safety

### M1: measurement protocol and baselines

- **Status:** Open.
- `scripts/perf_baseline.py` compiles the target (`:90`), and the macrobenchmark accepts only the v2 test
  packages (`NuvioNavigationBenchmark.kt:28`) at about 60 Hz (`:39-43`). Neither fits the daily apps or a
  24/25 Hz UI.
- [M1.1][desk] Log a `PLAYBACK_STARTED` stage with the time since the click, next to the first frame, so the
  playback baseline measures what the viewer sees. Gate: unit test on the stage helper.
- [M1.2][desk][device] Write `scripts/shield_measure.py`. It takes `--serial` and wraps every adb call in a
  timeout. It runs D-pad journeys with `input keyevent` (Home row, rows down, sidebar, details, settings) and
  reads `dumpsys gfxinfo <pkg> reset` / `framestats`, `am start -W` and a JankStats summary. For playback it
  reads click → `PLAYBACK_STARTED`, seek → first frame, drops, underruns and `restartIfDisabled`. On a daily
  app it never compiles, force-stops or clears: it measures a cold start only when the app is not running. No
  `dumpsys meminfo` during audio (it caused an underrun restart in the report). The details journey reads the
  `DetailEntryTrace` stages the app already logs (`viewmodel_setup` to `controls_focus`, tag `NuvioDetails`).
  Gate: dry run against the Shield. Needs: M2.1.
- [M1.3][desk] Build the `benchmark` variant of this branch for the UI journeys (`:app:assembleFullBenchmark`;
  minified, shrunk, with the baseline profile, signed with the debug key). It installs as its own app with the
  `.debug` suffix and needs one login. Gate: the APK installs on the Shield and passes the M1 dry run. Needs:
  M1.2.
- [M1.4][device][new-build] Baselines on the same journeys: UI for this branch's `benchmark` build and the
  upstream `com.nuvio.tv.test` release build; playback for the CI debug build of this branch and the upstream
  app. Record the build type of each. Gate: both tables in the log. Needs: M1.2, M1.1, M1.3.

### M2: device-safety protocol

- **Status:** Open. The Shield is in daily use; every session leaves it as it was.
- **Before a session**, save to a dated local folder: `settings list global`, `secure` and `system`;
  `getprop | grep debug.nuvio`; the display mode from `dumpsys display`; `pm list packages
  --show-versioncode`; the installed path and SHA-256 of `com.nuvio.tv.test.shield`.
- **App data.** The manifest sets `allowBackup="false"` and CI APKs are not debuggable
  (`app/build.gradle.kts:208`), so the debug app's data cannot be backed up. Safe without a backup:
  `adb install -r` of an APK signed with the same key (data kept), setprop, launching and measuring.
  Not allowed without a verified backup: uninstall, `pm clear`, an install that changes the signing key.
  A `-Pdebuggable=true` build allows `run-as … tar`; it is used for heap dumps and backups, never for
  timings. Built locally it has a different signing key, so it installs beside the CI build under its own
  suffix (`.shielddbg`), never over it.
- **Daily apps** (`com.nuvio.tv.test`, Stremio): launch and measure only. Never `pm clear`, `force-stop`,
  `compile` or a setprop aimed at them.
- **After a session**, restore every `debug.nuvio.*` property this session changed to its snapshot
  value, and clear only those that were unset before, so an owner mitigation survives. Clear a property
  with the empty value quoted inside the device command, `adb -s <serial> shell "setprop <name> ''"`:
  adb drops a bare `""` argument and `setprop` then fails with a usage error. Restore changed
  settings and the UI display mode, then diff against the snapshot.
- **If the Shield stops answering**, or Watchdog or `flush_work` shows up: stop, save the logs, and tell
  the owner. Only a power cycle recovers it.
- [M2.1][desk][device] Scripts `scripts/shield_snapshot.sh` and `scripts/shield_restore.sh` (serial and
  timeouts as in M1; `scripts/` is gitignored, and `.gitignore` already lists these and `shield_measure.py` as
  exceptions). Gate: one snapshot and restore round trip on the Shield with an empty diff.

### M3: diagnostics

- **Status:** Open. Findings A4, A5.
- [M3.1][desk] Count playback-head stalls (the client's `disabled due to previous underrun` restarts) on the
  HUD, cumulative across track rebuilds (A4). Gate: unit test.
- [M3.2][desk] Log `STHRESH` only on API 31+ (A5). Gate: unit test.

## Engine

### E1: Media3 backports

- **Status:** Open. Findings C1, C3–C12. The engine is Media3 1.8.0 with the backports listed in
  [`app/libs/README.md`](app/libs/README.md). None of these release notes mentions NVIDIA or Tegra.
- [E1.1][desk] Ready grace (be15915b6a, 1.11.0, androidx/media#3210): override `isReady()` in
  `PlaybackSpeedAwareAudioRenderer` with the 100 ms grace and install that renderer on every Shield start.
  Read the upstream commit first. Gate: unit test.
- [E1.2][device][new-build] Ten starts and ten seeks with the ready grace. Gate: no new transient buffering
  states in the log. Needs: E1.1.
- Only on a symptom in device logs:
  - PCM AudioTrack buffer of 500 ms (560be5a871, 1.11.0, app-side) (C5);
  - operating-rate fallback (d1a3251ca4, 1.11.0; the audio half is app-side) (C6);
  - skip the flush of a codec that had no input (da867c6b1a, 1.11.0; needs a MediaCodecRenderer
    rebuild) (C7);
  - AudioTrack retry with at least 1 s (6be48df57d, 1.11.0, androidx/media#3207); no effect under the
    passthrough caps (C8);
  - frame-rate estimate counting dropped frames, immediate rendering on a new surface (C9);
  - MPEG-TS fixes, blocked: the extractor AAR has no recorded source (C10);
  - tunneling audio-session race and prewarming fix; tunneling is inert on this firmware (C11).
- The 1.11.0 Choreographer change is mostly covered by the fork's vsync re-sample (C3).

## Upstream

### U1: upstream pull requests

- **Status:** Open. The branch is 80 commits ahead of `upstream/main` and 0 behind (2026-10-16).
  Upstream has merged one outside pull request so far, so a single pull request of the whole branch is
  unlikely to land.
- [U1.1][owner] Ask the upstream maintainer whether Shield work is wanted, and whether as one pull request per
  theme. Gate: the answer recorded in the log.
- [U1.2][desk] Sort the commits in `git log upstream/main..perf/shield-pass` into fork-only and upstreamable,
  and list the every-device changes with the reason each applies everywhere (audio route under a Bluetooth
  pin, lossless track default, controller buttons, native libraries loaded from the APK). Gate: the table in
  the log; fork-only commits named.
- [U1.3][desk] For each theme, one branch from `upstream/main` with the upstreamable commits cherry-picked
  (themes: AFR and display, audio passthrough, UI and performance, engine AARs with their rebuild recipes).
  Gate: each branch builds and passes the unit tests in CI. Needs: U1.2, U1.1.
- [U1.4][desk] Pull request text per theme with the M1 numbers it rests on and the revert switches. Gate: the
  numbers come from the log. Needs: U1.3, M1.4.
- [U1.5][desk] Keep `perf/shield-pass` rebased on `upstream/main` while upstream moves. Gate: 0 commits behind
  at each pull request.

## Docs

### D1: docs and hygiene

- **Status:** Partial. The corrections to [`docs/shield-todo.md`](docs/shield-todo.md) listed in the log
  (2026-10-15) were applied with this roadmap. Findings A-M1, A-M4 to A-M6, B6, C12, W5, H6, H7, P-09, P-10.
- [D1.1][desk] Translate `audio_tunneled_sub` in pt, sr and zh (A-M4). Gate: string present in every locale.
- [D1.2][desk] Settings label for libass: Overlay OpenGL is still marked "recommended" although Nvidia starts
  on canvas; change it after the R1 subtitle check. Gate: label matches the default. Needs: R1.3.
- Closed: the Shield thermal threshold is not readable without root on SE 9.2.4; it stays unset.
