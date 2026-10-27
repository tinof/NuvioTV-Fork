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
Dolby Vision 7 → 8.1, HDR10+ → Dolby Vision). The conversion track has its own plan in
[Appendix A](#appendix-a-on-device-dolby-vision-conversion).

Status meanings: **Open** has not shipped; **Partial** has useful pieces in place but does not meet the
gate; **Core complete** meets the gate but keeps named follow-up work; **Deferred** is intentionally not
scheduled. Items with no follow-up left move to the log.

**Step format.** Every open step is one line that `/ship:roadmap-next` reads:
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

**Upstream sync (2026-10-20).** The branch was rebased onto upstream 1.1.0-nt.7.3. Where upstream changed a
setting this pass also sets, the fork's value stays: AV1 trailers need hardware on Nvidia, full swap still
refuses thumbnail decodes on `mdarcy`, TrueHD stays on the platform sink (Q1.2 now covers upstream's
keep-alive) and the fork's datasource AAR stays. Elsewhere upstream's new defaults apply on the Shield too,
listed in [`docs/shield-todo.md`](docs/shield-todo.md). Device numbers above predate them, and the installed
build predates the sync.

**Appendix A (2026-10-24).** On the separate Dolby Vision track, DV2.1 to DV2.4, DV2.7 and DV2.8 are done
and DV1.4 is closed. HLG titles can now play as Dolby Vision 8.4 with the setting on; DV3.2 (HLG → 8.4 on
the Shield), DV1.8 and DV2.9 (the dump's device checks) are next. A.4 ends with how device builds are made.

### Waiting for the owner

Steps that need a decision or a reading from the owner. Other steps of the same item stay startable.
`/ship:roadmap-next` reads this list; remove an entry when it is settled.

- **UI refresh rate (S4.1).** 24/25 Hz against 50/60 Hz for the UI, decided from the M1 A/B on the Home
  journeys. It trades UI fluidity against mode switches at every start.
- **AVR readings (Q1.2, Q1.3).** The Atmos, DTS:X and E-AC3 Atmos indicators during the passthrough
  A/Bs, with the TV and AVR models recorded.
- **`hbr_iec` default (Q1.2).** A listening A/B of the platform path against the fork's IEC61937 packer.
- **Hot-Stop session (R2.4).** The owner must be able to power-cycle the Shield during the session.
- **Defaults after measuring (Q2.5, R2.4).** The AFR settle hold (600 or 1200 ms) and whether "keep the
  content frame rate after playback" becomes the Nvidia default.
- **Merge into `main`.** Criteria: the R2 mitigation shipped, Q2 done, CI green. R1 counts as
  done: R1.1 shipped and R1.3 was deferred (2026-10-18).
- **Ask the upstream maintainer (U1.1).** Whether Shield work is wanted upstream and in what form; the
  owner decides how and when to ask.

## Progress log

Newest first. One line per step. The full text is in [`docs/ROADMAP_LOG.md`](docs/ROADMAP_LOG.md).

| Date | Step | Items | Where |
|------|------|-------|-------|
| 2026-10-27 | U1.5: rebased onto upstream 1.1.0-nt.8.2 with no drop and no new Nvidia gate; both protected features kept every line; a base-layer commit fixes a race in upstream's rate-gate test that failed on CI, and `/upstream-sync` verify gains `--allow-new` for such commits. | U1 | [log](docs/ROADMAP_LOG.md), PR #24 |
| 2026-10-26 | Resource check during 4K DV playback with the DTS-HD MA transcode: 19 % of the four CPU cores (transcode 19.5 % of one core), GPU on overlay and near idle, 49 °C; memory is the limit (SwapFree 31 MB, NvMap full), so a live second decode for DV5 has no room now. | DV5, Q6 | [log](docs/ROADMAP_LOG.md) |
| 2026-10-26 | U1.5: rebased onto upstream 1.1.0-nt.8.1 (official 1.1.0-beta.8); three fork commits dropped as upstream does the same; the AAC-to-DTS and HLG-to-Dolby-Vision work is now a protected feature that `/upstream-sync` checks line by line. | U1 | [log](docs/ROADMAP_LOG.md), PR #22 |
| 2026-10-26 | M2.1 shipped: `shield_snapshot.sh` and `shield_restore.sh` save the Shield's state before a device session and put it back after, writing a setting back only when the session named it; one round trip on the Shield ended clean with an empty diff. | M2 | [log](docs/ROADMAP_LOG.md), PR #21 |
| 2026-10-25 | DV2.11 shipped: a Matroska HEVC track whose hvcC carries no parameter sets (BBC broadcast HLG) takes its SPS and alternative-transfer SEI from the first AU before the formats are published, and a converted track gets the AU's parameter sets as its initialization data. | DV2 | [log](docs/ROADMAP_LOG.md), PR #20 |
| 2026-10-25 | DV2.10 shipped: HLG signalled only in the alternative-transfer SEI (BBC broadcast HLG) converts to Dolby Vision 8.4; the SEI is read from the hvcC at track init or from the first AU by the extractor's early scan before the formats are published, and every case the scan cannot read stays native with a log line. | DV2 | [log](docs/ROADMAP_LOG.md), PR #19 |
| 2026-10-24 | DV2.7 shipped: the Matroska extractor injects the static 8.4 RPU into every AU of a converted HLG track, scene refresh on frame 0 only, with the setting shown on Nvidia; PQ stays native until an 8.1 provider exists. | DV2 | [log](docs/ROADMAP_LOG.md), PR #17 |
| 2026-10-24 | DV2.8 shipped: the RPU debug dump at the video renderer (first N AUs after a start, seek or Format change, and every scene refresh) and `rpu_dump_to_bin.py`; DV2.9 added for the device checks. | DV2 | [log](docs/ROADMAP_LOG.md), PR #16 |
| 2026-10-24 | DV2.3 shipped: the signalling decision table for Matroska, Dolby Vision 8.1 from PQ and 8.4 from HLG at track init, behind a hidden setting, a per-playback gate and a native retry on a decoder failure. | DV2 | [log](docs/ROADMAP_LOG.md), PR #15 |
| 2026-10-23 | Appendix A hand-off check: stale references in `DESIGN.md` fixed; DV2.3 and DV2.7 say what they must settle; DV4.5 needs DV1.8. | DV1–DV4 | [log](docs/ROADMAP_LOG.md) |
| 2026-10-23 | DV2.2 shipped: `libnuvio_rpu.so`, a JNI `cdylib` over `dovi_rpu_builder` that renders static 8.1 and 8.4 RPUs, shot L1 RPUs and HDR10+ L1, byte-identical to hdr-analyze's fixtures. | DV2 | [log](docs/ROADMAP_LOG.md), PR #14 |
| 2026-10-23 | DV2.4 shipped: the HLG contract decides HLG for 8.4 from the Matroska Colour values and the SPS VUI at track init, and logs the alternative-transfer SEI. | DV2 | [log](docs/ROADMAP_LOG.md), PR #13 |
| 2026-10-23 | DV1.4 closed on the owner's eye check; DV2.1 done in hdr-analyze (E16.1); DV2.2 becomes a separate `cdylib` after a link test; DV1.8, DV2.7 and DV2.8 added. | DV1, DV2, DV3 | [log](docs/ROADMAP_LOG.md), hdr-analyze PR #67 |
| 2026-10-22 | Q6.1 and Q6.10 shipped: DTS-HD MA for mono and stereo AAC when the output lists DTS-HD, AARs built locally only. | Q6 | [log](docs/ROADMAP_LOG.md), PR #12 |
| 2026-10-21 | Q6 added and built on `feat/Q6-aac-dts`: AAC to DTS transcode, DTS 2.0 confirmed on the owner's HT-A9. | Q6 | [log](docs/ROADMAP_LOG.md), [design](docs/aac-to-dts/DESIGN.md) |
| 2026-10-21 | Q5 and M3.3 added from a live capture: 12 stutter events in 5 min, correlated with memory pressure. | Q5, M3 | [log](docs/ROADMAP_LOG.md) |
| 2026-10-20 | U1.5 made repeatable: two-layer branches, the daily drift issue and the `/upstream-sync` skill. | U1 | [log](docs/ROADMAP_LOG.md), PR #9 |
| 2026-10-20 | U1.5: rebased onto upstream 1.1.0-nt.7.3; the fork keeps its Shield settings where upstream changed them. | U1, Q1 | [log](docs/ROADMAP_LOG.md), PR #6 |
| 2026-10-19 | Appendix A: DV7 added for Profile 7 input, from AetherEngine's FEL design and hdr-analyze's FEL plan. | DV7 | [log](docs/ROADMAP_LOG.md) |
| 2026-10-18 | R1.3 and D1.2 deferred by the owner: ASS subtitles are not in use. | R1, D1 | [log](docs/ROADMAP_LOG.md) |
| 2026-10-18 | R1.2 closed unrun: the default it tested was reverted in R1.1; its session did not reproduce incident 02. | R1 | [log](docs/ROADMAP_LOG.md) |
| 2026-10-18 | R1.1: the Nvidia `opaque_window` default reverted; R1.2 did not reproduce incident 02 with it. | R1 | [log](docs/ROADMAP_LOG.md), PR #4 |
| 2026-10-16 | Appendix A: plan for on-device Dolby Vision conversion (HDR10+, HDR10, HLG), a separate track. | DV1–DV6 | [log](docs/ROADMAP_LOG.md) |
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

1. **Incident 02, letterbox bleed** (R1). Deferred on 2026-10-18: R1.1 shipped; the ASS subtitle check
   (R1.3) waits until ASS subtitles are in use.
2. **Device safety and baselines** (M2, M1). Gate: the protocol tested once; baseline tables for this
   app and the upstream app in the log. The playback start stage is logged before the playback baseline.
3. **Switch-back safety and AFR plan** (R2, Q2). Gate: the mitigation ships; the re-entry test passes.
4. **Playback stutter under memory pressure** (Q5). Added 2026-10-21: the owner sees it in every 4K title.
   Gate: Q5.7 beats the Q5.2 baseline.
5. **Passthrough correctness** (Q1). Gate: AVR readings recorded for TrueHD, DTS-HD MA and E-AC3; the
   `hbr_iec` default decided.
6. **Start and Stop latency** (S1, S3). Gate: click → playing and the Stop stall beat the M1 baseline.
7. **Seek and thumbnails** (S2). Gate: CPU, native heap and drops with thumbnails on within the S2
   tolerance of thumbnails off. The measured 43 % CPU load comes before UI tuning.
8. **Small correctness fixes** (R3). Gate: unit tests; the CEC check passes.
9. **UI frames and navigation** (S4). Gate: the M1 journeys beat the baseline and the upstream app at the
   chosen refresh rate.
10. **Frame pacing, listener leak, decode-path bugs** (Q3, Q4).
11. **Media3 backports** (E1), only on a symptom; the ready grace is startable.
12. **Diagnostics and docs** (M3, D1).
13. **Upstream pull requests** (U1). The commit sorting and the question to the maintainer can start any
    time; the pull requests follow the items they carry.

## Reliability

### R1: incident 02, letterbox bars show the previous screen

- **Status:** Deferred (owner, 2026-10-18). The `opaque_window` default is reverted (R1.1, PR #4), so the window background
  and True black bars match upstream. The device check of 2026-10-18 did not reproduce incident 02 with that
  default (black bars, no stale pixels; see the log), so the cause below is not confirmed. Findings W1–W5.
- be0ce8ec9 set a black window background in `MainActivity.onCreate` (`MainActivity.kt:407`), before
  the window is attached, so the renderer is created opaque and never clears. When the player switches
  the window to transparent for true black bars, the bar areas keep old pixels. The default never made
  the app layer opaque to the compositor either: once a SurfaceView has attached, the window format is
  forced to TRANSLUCENT (Home showed `isOpaque=0`). Nothing outside the commit reads `opaque_window`.
- Deferred (owner, 2026-10-18: ASS subtitles are not in use): R1.3, two 2.40:1 titles with True black
  bars on, started from the detail page, with ASS subtitles on the canvas overlay and on Overlay OpenGL.
  Gate: black bars, one subtitle line, no CLIENT composition in `dumpsys SurfaceFlinger`. Runs on a build
  with R1.1. Restore it as a `[device][new-build]` step when ASS subtitles come into use.

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
- [R3.1][desk] Make the four `PlayerSettings` data-class defaults Nvidia-aware, the way `libassRenderType` was
  until the sync of 2026-10-26 made it inert (a `default…()` function with a `debug.nuvio.*` switch) (`PlayerSettingsDataStore.kt:263, 307, 309, 311`); the corrupt-store fallback at `:1418` uses them. Gate:
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
  (tunneling off), each with a seek and a pause: upstream 1.1.0-nt.7 keeps the TrueHD stream alive through
  them on the packer path only (3aeb1e180). Record the AVR indicator and whether it relocks, `HBR active
  payload=` against `HBR DIRECT`, `iec_keepalive_armed`, and `restartIfDisabled`. Gate: readings in the log; default decided. Needs: Q1.1.
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

### Q5: memory pressure and frame skips

- **Status:** Open. From the live capture of 2026-10-21 (see the log).
- In 5 minutes of a 4K HDR title with DTS-HD passthrough over Wi-Fi, SurfaceFlinger showed 12 stutter
  events: 10 app-side skips of 1–7 frames and 2 late presents.
  - 11 of the 12 fell in windows of heavy page-cache refaulting, and the worst coincided with the only
    direct-reclaim burst.
  - This is a correlation; it is not yet shown to be the cause.
- The player holds a 500 MB buffer on 3 GB boxes (`BufferSizing.kt:17`).
- Pressure handling reacts only to the system `lowMemory` flag (`BufferMemoryKeeper.kt:126`), sampled only
  in the 10 s `BUFFER:` log block (`PlayerRuntimeControllerPlaybackEvents.kt:397-408`).
- The restore steps let the buffer grow back into reclaim (`BufferMemoryKeeper.kt:138-141`).
- [Q5.1][desk][device] Commit `scripts/shield_frame_gaps.py` with a `.gitignore` exception, as in M2.1.
  - It takes `--serial`, wraps every adb call in a timeout and is read-only.
  - It resolves the PID and the SurfaceView layer from the package, and drops SurfaceFlinger frames from
    before the capture started.
  - It reports coverage gaps.
  - It reports app-side skips against late presents, next to vmstat, GC and SIGKILL exits. A kill is named
    lmkd only when an lmkd line confirms it.
  - Gate: a dry run on the Shield shows both event classes with no coverage gap.
- [Q5.2][device][new-build] Baseline on a CI build of unchanged HEAD with the same build type, package and
  settings that Q5.7 will use.
  - Take it once the device state has settled (at least 1 day of uptime).
  - Use one fixed 4K HDR, DTS-HD, Wi-Fi title from the same start position, and three 30-minute runs.
  - Keep the title, segment, settings and device state frozen until Q5.7.
  - Gate: skips, direct-reclaim windows and SIGKILL exits per run in the log.
  - Needs: Q5.1.
- [Q5.3][desk] Proactive pressure.
  - Sample MemAvailable at a bounded cadence of its own (for example every 2 s while playing), not only in
    the 10 s `BUFFER:` block.
  - Raise LOW when `availMb − thresholdMb` falls below `SYSTEM_MARGIN_MB` + a margin, not only on
    `lowMemory`.
  - Add a `debug.nuvio.*` switch for the margin.
  - Gate: unit tests, including a pressure burst shorter than 10 s.
- [Q5.4][desk] Nvidia default: an automatic budget of about 300 MB on 3 GB `mdarcy` (`BufferSizing.kt:17`).
  - Count the parallel chunk window inside the budget (`BufferSizing.kt:81`). Since the sync of 2026-10-26,
    `spareMb` keeps a 28 MB margin (47d1c5834) instead of 100 + 40 MB, so measure the baseline again first.
  - Gate it by `DeviceProfile`, with a revert switch and a row in `docs/shield-todo.md`.
  - Gate: unit test.
- [Q5.5][desk] Hysteresis.
  - Ease a level only when the room covers the added amount plus a margin, and hold 3–5 min after a
    critical event (`BufferMemoryKeeper.kt:138-141`, `:248`).
  - Keep trimming idle memory while pressure lasts, at most every 30 s, and log only trims that free
    memory (`:133`).
  - Gate: unit tests, including allocations that go idle after a level change.
- [Q5.6][device][new-build] Find the large-object churn (17–27 MB of large objects freed per GC, every 3–6 s
  during playback).
  - Use a `.shielddbg` debuggable build, heap dumps or allocation tracking only (M2).
  - First suspects: the per-access-unit copy in `IecPassthroughAudioSink.kt:1059` and the 8-frame burst
    pool (`:1177`).
  - Gate: allocation sites named in the log.
- [Q5.7][device][new-build] The Q5.2 runs on a build of HEAD plus Q5.3–Q5.5, with everything else the same.
  - Gate: skips and direct-reclaim windows below the Q5.2 baseline in all three runs, no rebuffers, and
    buffer ahead never under 8 s.
  - Needs: Q5.2, Q5.3, Q5.4, Q5.5.

### Q6: Transcode AAC to DTS

- **Status:** Partial. Branch `feat/Q6-aac-dts`. Per-profile setting, default off, that sends mono and
  stereo AAC to the AVR as DTS for the owner's Sony HT-A9 and its Neural:X upmixer: DTS-HD Master Audio
  (DTS core + lossless XLL) when the output lists DTS-HD, else the DTS core (FFmpeg `dca`, 48 kHz,
  1509 kbit/s), else PCM. Design, evidence, measurements, review findings and build steps:
  [`docs/aac-to-dts/DESIGN.md`](docs/aac-to-dts/DESIGN.md) and, for DTS-HD MA,
  [`docs/aac-to-dts/DTSHD_PORT.md`](docs/aac-to-dts/DTSHD_PORT.md).
  - MediaCodec declines such tracks while the sink can play DTS, so the FFmpeg renderer takes them.
  - The resampler (44.1 → 48 kHz) is tuned against soxr precision 28 (images ≤ −130 dB, 4.1× default CPU).
  - The lossless layer comes from our own XLL writer `dcaxll` (LGPL-2.1-or-later, `~/Documents/GitHub/dts-hdma`),
    vendored in `ffmpeg-decoder-downmix/src/main/jni/dcaxll/`. Mono goes out as dual-mono stereo. The core
    is aligned to its input (one 512-sample unit of lookahead); a refused DTS-HD track steps down to the
    core, then to PCM, and is never learned as a denial of real DTS.
  - The branch also carries AC-3 transcode fixes (frame count per buffer, end-of-stream flush, output
    timestamps, priming) and the first decoder AAR built from `ffmpeg-decoder-downmix`.
  - Since 2026-10-22 APKs and AARs are built locally only (PR #11). `app/libs` keeps the DTS-core AAR of
    2026-10-21; DTS-HD MA needs a build with the local module (DESIGN.md §8). The app reaches the new
    renderer API by reflection, so it runs on the core with the committed AAR.
  - 2026-10-21: the owner heard DTS 2.0 on the HT-A9 from a 48 kHz AAC 2.0 title (DIRECT `AUDIO_FORMAT_DTS`).
  - 2026-10-22: the HT-A9 shows DTS-HD MA for a 48 kHz AAC 2.0 title; the log and AudioFlinger agree
    (stereo kept, DIRECT `AUDIO_FORMAT_DTS_HD`, 2 channels; decoder thread about 20 % of one core).
  - After the merge, delete the CI-only branch `ci/Q6-ffmpeg-aar` on the fork (it holds no DTS-HD code).
- [Q6.2][device][owner][new-build] Toggle on; play AAC 2.0 at 48 kHz and at 44.1 kHz, a mono title and an
  HE-AACv2 title. Gate: logcat `DTS transcode:` line, `dumpsys media.audio_flinger` shows a DIRECT DTS
  output at 48 kHz, the HUD shows "DTS transcode", and the HT-A9 shows DTS with Neural:X.
  48 kHz AAC 2.0 confirmed 2026-10-21; 44.1 kHz, mono and HE-AACv2 pending. Needs: Q6.1.
- [Q6.3][device][owner][new-build] Sync and controls: a flash-and-beep clip as DTS against PCM (offset
  within 20 ms), 20 seeks, pause/resume, speed 1.25x (PCM) and back (DTS) in both decoder modes, play to
  the end, resume at 30 min, track switch AAC 2.0 ↔ E-AC3 5.1 ↔ AAC 5.1, Bluetooth during playback.
  Gate: results in the log, no `UnexpectedDiscontinuity` or `audioSinkError`. Needs: Q6.1.
- [Q6.4][device][new-build] Regression of Force AC-3 and denied-codec AC-3 on the module-built AAR.
  Gate: AC-3 output, sync and HUD as before. Needs: Q6.1.
- [Q6.5][device][new-build] CPU of the decoder thread for 44.1 kHz AAC with the tuned resampler
  (`top -H`). Gate: number in the log. Needs: Q6.1.
- [Q6.6][owner] Decide whether the structured playback issue report gets a `transcodeAacToDts` field (the
  report server's schema is unknown; today only the raw events line carries `aacToDts=`). Gate: decision
  in the log.
- [Q6.7][desk] The denied-codec AC-3 decision asks `getSinkFormatSupport`, as the DTS one does, so speed,
  the passthrough policy and the PCM latch apply to it (today `sinkSupportsFormat` bypasses them, DESIGN
  §3). Gate: unit test with a sink whose policy denies AC-3.
- [Q6.8][desk] Extend the FFmpeg-first decoder re-creation (`PlayerRuntimeControllerDtsTranscode.kt`) to
  Force AC-3 and denied-codec AC-3. Gate: unit test of the decision helper.
- [Q6.9][desk] `ffmpeg_jni.cc` compiles without warnings at `-Wall -Wextra`. Gate: build log.
- [Q6.11][device][owner][new-build] DTS-HD MA on both routes (System passthrough on and off) for 48 kHz and
  44.1 kHz AAC 2.0, mono (dual mono, level against the old mono core) and HE-AACv2; seeks, pause, the end
  of a title, HDMI replug. Gate: log `DTS-HD MA transcode:` line, DIRECT `AUDIO_FORMAT_DTS_HD`, the HUD
  names the DTS-HD MA transcode, the HT-A9 shows DTS-HD MA. 48 kHz AAC 2.0 on the platform route confirmed
  2026-10-22. Needs: Q6.10.
- [Q6.12][device][new-build] Fallback chain: `settings put global nuvio_fault_reject_mime audio/vnd.dts.hd`
  gives `aac_dts_step_down step=HD_OFF` and the core; rejecting `audio/vnd.dts` too gives PCM; delete the
  setting after. Gate: the log lines. Needs: Q6.10.
- [Q6.13][device][new-build] Review finding F2 of PR #12 (NEEDS-HUMAN): with MediaCodec first, AAC that the
  platform decoder cannot take (the Shield's `c2.android.aac.decoder` stops at 48 kHz, so 64–96 kHz AAC)
  stays on FFmpeg; a speed change to 1.25x then leaves its DTS decoder running, because
  `dtsTranscodeNeedsDecoderRecreate` assumes MediaCodec takes the track (`PlayerRuntimeControllerDtsTranscode.kt:86`,
  test `DtsTranscodeDecoderRecreateTest.mediaCodecFirst_onlyBetweenHdAndCore`). Play a 96 kHz AAC 2.0 file
  with MediaCodec first, switch to 1.25x and back. Gate: the log shows PCM at 1.25x and DTS again at 1x;
  if not, decide the fix (re-create when FFmpeg keeps the track, asking MediaCodec's renderer whether it
  handles the source). Needs: Q6.10.

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

- **Status:** Done (M2.1, PR #21). The Shield is in daily use; every session leaves it as it was. This
  item stays as the protocol every device session follows.
- **Before a session**, save to a dated local folder: `settings list global`, `secure` and `system`;
  `getprop | grep debug.nuvio`; the display mode from `dumpsys display`; `pm list packages
  --show-versioncode`; the installed path and SHA-256 of `com.nuvio.tv.test.shield`.
  `scripts/shield_snapshot.sh --serial "$s" --label <step>` saves all of this, plus the logcat buffer sizes
  and every `com.nuvio.tv.test.*` test build (`--pull-apk` keeps a copy of each APK), to
  `~/shield-snapshots/<date>-<label>/before/`. It first checks that the logs show no Watchdog or `flush_work`
  lines. It refuses to finish when a setting has a multi-line value or a listed test build has no APK
  path. It writes `before/complete` last, and restore uses only a snapshot that has it. The session names
  every settings key it changes in that folder's `session-changes.txt`.
- **App data.** The manifest sets `allowBackup="false"` and CI APKs are not debuggable
  (`app/build.gradle.kts:208`), so the debug app's data cannot be backed up. Safe without a backup:
  `adb install -r` of an APK signed with the same key (data kept), setprop, launching and measuring.
  Not allowed without a verified backup: uninstall, `pm clear`, an install that changes the signing key.
  A `-Pdebuggable=true` build allows `run-as … tar`; it is used for heap dumps and backups, never for
  timings. Built locally it has a different signing key, so it installs beside the CI build under its own
  suffix (`.shielddbg`), never over it.
- **Local builds.** `scripts/shield_build_install.sh [--suffix S] [--benchmark] [--debuggable] [--install]`
  builds with JDK 17 like CI, logs HEAD, package, certificate and APK hash to `.local/shield-builds.tsv`, and with
  `--install` runs `adb install -r` on `$ANDROID_SERIAL` and checks the installed hash. It builds `.shield` only
  with the CI debug keystore (`NUVIO_DEBUG_STORE_FILE`, or `~/.config/nuvio/nuvio-debug.keystore`), and it
  refuses to install when the installed app's certificate differs. Without that keystore, local builds use
  their own suffix (`.shieldlocal`) beside the CI build.
- **Daily apps** (`com.nuvio.tv.test`, Stremio): launch and measure only. Never `pm clear`, `force-stop`,
  `compile` or a setprop aimed at them.
- **After a session**, restore every `debug.nuvio.*` property this session changed to its snapshot
  value, and clear only those that were unset before, so an owner mitigation survives. Clear a property
  with the empty value quoted inside the device command, `adb -s <serial> shell "setprop <name> ''"`:
  adb drops a bare `""` argument and `setprop` then fails with a usage error. Restore changed
  settings and the UI display mode, then diff against the snapshot.
  `scripts/shield_restore.sh --serial "$s" [--setting ns/key]` does this.
  - It restores the properties and the logcat buffer sizes.
  - It writes a changed setting back only when the session named it. Any other changed setting is
    reported and left alone, so a change the owner made by hand survives.
  - adb cannot set the display mode on Android 11. When a test build is in front, restore sends HOME
    once. Otherwise the mode is an owner action.
  - It then diffs `after/` against `before/`. Exit codes: 0 clean, 3 drift remains, 4 owner action,
    5 stop.
- **If the Shield stops answering**, or Watchdog or `flush_work` shows up: stop, save the logs, and tell
  the owner. Only a power cycle recovers it.

### M3: diagnostics

- **Status:** Open. Findings A4, A5.
- [M3.1][desk] Count playback-head stalls (the client's `disabled due to previous underrun` restarts) on the
  HUD, cumulative across track rebuilds (A4). Gate: unit test.
- [M3.2][desk] Log `STHRESH` only on API 31+ (A5). Gate: unit test.
- [M3.3][desk] Log `droppedBufferCount`, `skippedOutputBufferCount` and `maxConsecutiveDroppedBufferCount`
  with the 10 s `BUFFER:` line.
  - `onDroppedVideoFrames` fires only after 50 drops (`PlayerRuntimeControllerInitialization.kt:3462`), so
    single drops never reach logcat today.
  - Gate: unit test.

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

- **Status:** Open. The branch is 0 commits behind `upstream/main` after the U1.5 sync of 2026-10-26 (onto
  1.1.0-nt.8.1, official NuvioTV 1.1.0-beta.8). The open "Upstream drift" issue (label `upstream-drift`)
  shows how far behind it is; a daily workflow on `main` keeps it current.
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
- [U1.5][desk] Keep `perf/shield-pass` rebased on `upstream/main` while upstream moves: run `/upstream-sync`
  when the drift issue is open. Two layers: `main` = `upstream/main` + the fork's base layer (CI, test and fix
  commits, the drift watcher), `perf/shield-pass` = `main` + the Shield work; both are rebased, never merged.
  Gate: 0 commits behind at each pull request.

## Docs

### D1: docs and hygiene

- **Status:** Partial. The corrections to [`docs/shield-todo.md`](docs/shield-todo.md) listed in the log
  (2026-10-15) were applied with this roadmap. Findings A-M1, A-M4 to A-M6, B6, C12, W5, H6, H7, P-09, P-10.
- [D1.1][desk] Translate `audio_tunneled_sub` in pt, sr and zh (A-M4). Gate: string present in every locale.
- Closed by the sync of 2026-10-26: D1.2, the settings label for libass. Upstream's atlas pipeline (4f2fd58e2)
  always renders the same way and the settings no longer offer a render type, so there is no label to fix.
- Closed: the Shield thermal threshold is not readable without root on SE 9.2.4; it stays unset.

## Appendix A: on-device Dolby Vision conversion

A separate track, outside the north star and the Priorities list. `/ship:roadmap-next` does not rank these
steps; they are picked by hand. Steps use the format above, with item IDs DV1 to DV7. Work in
`hdr-analyze` (`tinof/hdr-analyze`) is named as such. Conversion steps follow the development
principles: each conversion is gated by `DeviceProfile`, yields to a saved setting, has a
`debug.nuvio.*` revert switch and is documented. The plan was drafted on 2026-10-16 with Codex
(gpt-6-astra, medium) as co-designer; the design, its evidence and the reviews are in
[`docs/dv-conversion/`](docs/dv-conversion/).

### A.1 Goal

Convert HDR video to Dolby Vision during playback on the Shield, without re-encoding the picture: add one
RPU NAL to each access unit (AU) and signal the track as Dolby Vision.

| Source | Output | Where L1 comes from | Pixels needed |
|---|---|---|---|
| HDR10+ | Profile 8.1 | The HDR10+ SEI of each AU | No |
| HDR10 | Profile 8.1 | Static, a shot list made elsewhere, or measured on the Shield | Only when measured on the Shield |
| HLG | Profile 8.4 | Static composer; L1 static, from a shot list, or measured on the Shield | Only when measured on the Shield |

Why: the 2019 Shield outputs Dolby Vision and HDR10 but not HDR10+, so HDR10+ titles play as static
HDR10 today. HDR10 and HLG titles gain a dynamic, TV-led mapping only when the L1 metadata is good.

**Profile 7.** The fork already converts Profile 7 to 8.1 per AU with libdovi (mode 2 on Nvidia). DV7
makes that path robust and prepares FEL improvements that change only the RPU. The Shield cannot play
the FEL residual: its decoder ignores the enhancement layer, and Android has no way to present composed
frames with a Dolby Vision RPU (`DESIGN.md` 5.2).

**Plan in one paragraph.** One RPU injection layer with interchangeable L1 providers. First the
providers that need no pixels (static, HDR10+), then shot lists made on a PC (a local sidecar, then a LAN
server), then analysis on the Shield: first while nothing plays, last during playback on a second
decoder. hdr-analyze contributes code (the RPU builders, the per-frame statistics, the shot aggregation,
the HLG composer), not its CLI: `mkvdovi` calls `hdr10plus_tool`, `dovi_tool`, `ffmpeg` and `mkvmerge` as
programs, which cannot run inside the APK.

### A.2 Design summary

The evidence, the architecture, the budget and the risks are in
[`docs/dv-conversion/DESIGN.md`](docs/dv-conversion/DESIGN.md). In short:

- Every AU carries a complete RPU. Providers are judged on admissibility (identity, timeline, colour
  contract, provenance), completeness and quality class (`full`, `approximate`, `static`); HDR10+
  content keeps its HDR10+ metadata unless an A/B says otherwise.
- L1 holds per shot. Before the first AU of a shot reaches the decoder, the shot's provider and L1 are
  committed and frozen; later results go to the cache or to a later shot.
- Providers that are ready at once inject in the extractor. Later providers swap a complete, newly
  written RPU into the codec input buffer at `onQueueInputBuffer`, which never waits.
- AUs are identified by track, configuration, analysis generation, decode order and presentation time;
  every seek, flush, release or discontinuity starts a new generation.
- Dolby Vision is signalled at track init from container metadata and the SPS; an unknown transfer or
  contradicting colour tags mean native playback. Untagged HLG range, matrix and primaries follow the
  logged defaults of `DESIGN.md` 2.5. Matroska first.
- CUDA is not available to Shield apps. Vulkan compute needs raw Vulkan (`ash` or C++) for the frame
  import; what the current firmware offers is for DV1.2 to find out.

### A.3 Tiers

Each tier adds a provider to the same injector. A later tier is never required for an earlier one.

- **T0, no pixels.** Compatibility first: RPUs made on the desktop and played through the app. Then
  static providers and HDR10+ → 8.1 (L1 from each AU's own SEI, held while `dovi_tool`'s scene key
  is unchanged). HLG → 8.4 uses the bt2100 composer with a conservative nominal L1: maximum 3079 (the
  source-range ceiling, not a measured value) and a fixed average of 1283 (owner, 2026-10-22). HDR10 →
  8.1 static derives a heuristic L1 from MaxCLL and MaxFALL, with rules for missing, zero or
  implausible values, and is opt-in only: in Dolby Vision mode the TV probably does not apply its own
  HDR10 dynamic tone mapping (a guess, tested in G-E).
- **T1, shot lists made on a PC.** First a local sidecar with a playback manifest, made by `mkvdovi`.
  Then a LAN server mode in hdr-analyze that analyses the same source from a random-access point before
  the playhead and streams finished shots (its CUDA path runs at about 325 fps for 4K HDR10 and 490 fps
  for 4K HLG, 13 to 20 times real time at 24p).
- **T2, analysed on the Shield while idle.** One decoder, no playback: the next episode or a
  continue-watching item is analysed and cached. Needs the pixel path (G-A) and a thermal run (G-F), not
  a second concurrent decode.
- **T3, analysed on the Shield during playback.** A second, best-effort `MediaCodec` instance decodes a
  copy of the AUs the extractor has loaded, ahead of the playhead. Modes: every frame, without the
  sub-layer non-reference pictures (`_N` NAL types) of the highest TemporalId, which no other picture
  references (RFC 7798 §1.1.4; in the usual single-layer x265 encode all pictures have TemporalId 0),
  or IRAP only. PQ first, then HLG with the composer reconstruction from the start. A shot is committed
  with measured L1 only when the whole shot is analysed in time; otherwise it keeps its fallback. Shots
  are cached at their quality class.
- **T4, full parity.** Every frame at full resolution, the robust peak estimator, L1 within the parity
  tolerance of the desktop.

### A.4 Device gates

Probes and implementation may run before qualification. Enabling a configuration needs a passing result
for every gate that applies to its tier, recorded in
[`docs/dv-conversion/QUALIFICATION.md`](docs/dv-conversion/QUALIFICATION.md) against the device and
firmware, transfer and composer, resolution, frame rate, decode mode, pixel path and the playback and
display chain. A missing, failed or unrun result keeps that configuration off. Idle analysis (T2) needs
neither G-B nor the playback-time G-F run.

- **G-A, pixel path (T2, T3).** `vulkaninfo` on SE 9.2.4; an `ImageReader` (PRIVATE,
  `USAGE_GPU_SAMPLED_IMAGE`) on the plain HEVC decoder; the AHardwareBuffer import; and patterns decoded
  on the Shield and compared with the code values the desktop decoder gives for the same file. PQ and
  HLG are qualified separately. Patterns: Y, Cb and Cr ramps, endpoints, saturated patches, chroma edges
  and siting, odd strides and crops. Also repeated buffer reuse, acquire and release fences, and image
  lifetime until the GPU finishes. A P010 ByteBuffer output read on the CPU is a second probe. GLES
  external sampling is allowed for scene cuts only.
- **G-B, a second 4K decode (T3).** `getMaxSupportedInstances` is an upper bound only. Dolby Vision
  playback plus an analysis decoder at `KEY_PRIORITY` 1, per mode, at 4K24 and 4K60. Pass for a
  configuration: no added dropped frames or rebuffers against the same title without analysis, memory
  within the budget model, the decoder reclaimed or analysis stopped cleanly under pressure, and
  sustained analysis faster than playback.
- **G-C, Dolby Vision from synthesized signalling (all tiers).** Files made by `mkvdovi` (8.1 from HDR10
  and from HDR10+, 8.4 bt2100 and canary) and the same title with deliberately different L1 values,
  played through Nuvio; then the same sources converted in the app. Pass: Dolby Vision on the TV, the
  canary turns green, the L1 variants change the picture as expected, the RPUs pulled from the debug
  log validate with `dovi_tool info`, no added dropped frames. Two more checks on DV7.6's fixed-pixel
  clip: a pulse variant whose L1 alternates (passes when the picture pulses with the L1 period), and,
  for the DV7 FEL candidates only, a Profile 8.1 mapping canary against its identity control (passes
  when only the canary turns green).
- **G-D, renderer replacement (T1 server, T3).** The framing and capacity read in DV1.1, then the
  replacement tests of `DESIGN.md` 2.3 and 2.4 (mutation, flushes, seeks, reuse) passing on the device.
- **G-E, worth it (T0 HDR10, T1, T3).** Owner A/B on the TV over a fixed corpus: dark scenes,
  highlights, fades, HLG broadcast and HLG dark material. Static HDR10 → 8.1 against native HDR10,
  shot lists against static, HDR10+ against measured.
- **G-F, sustained run (T2, T3).** A thermal steady-state run of two hours, once for idle analysis (T2)
  and once for playback with analysis (T3), recording frame drops, analysis rate and lead, memory
  high-water and the thermal state.

Every device run records, against the same title with the same settings: dropped frames, rebuffers,
start and seek latency, memory high-water, the analysis lead, and the share of AUs with a `full`,
`approximate` and `static` RPU. Before a gate is declared passed, its corpus, expected outcomes and
numerical tolerances are frozen. The corpus includes long uncut shots, repeated seeks, network stalls
and resource reclaim; the report covers recovery and fallback coverage as well as the steady state.

**Device builds.** `scripts/shield_build_install.sh --install` (JDK 17, `ANDROID_SERIAL` set) first builds
`libnuvio_rpu.so` when cargo-ndk is installed (`scripts/build_rpu_builder.sh`), then the APK, and installs
it with the certificate and hash checks of M2. Without cargo-ndk the APK has no builder and conversion
stays off. Every device session follows [M2](#m2-device-safety-protocol).

### A.5 Steps

#### DV1: device gates and desk checks

- **Status:** Open. DV1.4 closed on 2026-10-23 on the owner's eye check (log); its precise half is DV1.8.
- [DV1.1][desk] Read the Media3 1.8.0 renderer path and the patched AARs: the sample framing at
  `onQueueInputBuffer` per container, the reconfiguration prefix, the real input-buffer capacity for 4K
  HEVC (`KEY_MAX_INPUT_SIZE` is only the request), and how flushes and releases reach the renderer.
  Gate: findings with file:line in the log.
- [DV1.2][device] G-A probe for PQ and HLG. Gate: the report in the log and the G-A rows of the
  qualification matrix set to pass or fail.
- [DV1.3][device] G-B probe per mode at 4K24 and 4K60. Gate: the report in the log and the G-B rows of
  the qualification matrix set to pass or fail.
- [DV1.5][desk] In hdr-analyze, measure what decimation costs: per-shot L1 at sample rates 2 and 3,
  downscale 2 and 4, without top-layer `_N` pictures and IRAP only, against full analysis on the
  validation corpus. Gate: error table in hdr-analyze `docs/VALIDATION.md`.
- [DV1.6][device] G-F idle thermal run (T2): two hours with the DV1.2 probe's decode and import loop as
  the load. Gate: the report in the log and the idle G-F row of the qualification matrix. Needs: DV1.2.
- [DV1.7][device][new-build] G-F playback thermal run (T3): two hours of playback with analysis. Gate:
  the report in the log and the playback G-F row of the qualification matrix. Needs: DV5.4.
- [DV1.8][device][owner][new-build] G-C, precise half (DV1.4 closed on the owner's eye check): the
  `mkvdovi` files (8.4 bt2100 and canary, 8.1 from HDR10 and HDR10+, the L1 variants) played through
  Nuvio with the DV2.8 dump on, from the start, from a mid-file resume point and with seeks. Gate: the
  dumped RPUs validate with `dovi_tool info` and equal the files' RPUs; the owner's TV readings and the
  playback chain in the log; the G-C rows of the qualification matrix set. Needs: DV2.8.

#### DV2: shared code, signalling and injector

- **Status:** Partial. DV2.1 is done: hdr-analyze's `dovi_rpu_builder` (its E16.1, PR #67), one RPU NAL
  per AU, byte-identical to `dovi_tool generate` 2.3.4; contract in hdr-analyze `docs/RPU_BUILDER.md`.
  DV2.4 is done (PR #13): `HlgContract` decides at Matroska track init whether an HEVC track without
  Dolby Vision is HLG an 8.4 RPU can describe (`HlgDecision`, read by the extractor's `getHlgDecision`);
  nothing acts on it before DV2.3.
  DV2.2 is done (PR #14): `nuvio_rpu` (`native/rpu_builder`), its own `cdylib` `libnuvio_rpu.so` over
  `dovi_rpu_builder` at hdr-analyze `f55c779` (its PR #68, E16.2), read through `DoviRpuBuilder`; built
  locally only (`scripts/build_rpu_builder.sh`), nothing calls it before DV2.3 and DV2.7.
  DV2.3 is done (PR #15): `DvSignalling` publishes a Matroska HEVC track without Dolby Vision as 8.1
  (PQ) or 8.4 (HLG) at track init when `DvConversionGate` allows it, and keeps the decision on the track
  (`getDolbyVisionSignalling`) for DV2.7.
  DV2.8 is done (PR #16): with `debug.nuvio.rpu_dump` = N the video renderer logs the RPU of the first N
  AUs after a start, a seek or a Format change and of every scene-refresh AU (`RpuDumpRecorder`), and
  `scripts/rpu_dump_to_bin.py` rebuilds an `RPU.bin`; the device checks the review left are DV2.9.
  DV2.7 is done (PR #17): a converted track gets one RPU NAL per AU in the extractor
  (`ConvertedTrackInjector`), prepared at track init so a track without an RPU stays native; only the
  static 8.4 provider exists, so PQ stays native until DV3.1 or DV3.3; `scene_refresh_flag` on frame 0
  only, `debug.nuvio.dv_refresh_every_start` for every start; the setting is shown on Nvidia as
  "Convert HLG to Dolby Vision", off by default.
  DV2.10 is done (PR #19): HLG signalled only in an alternative-transfer SEI (BBC broadcast HLG, an SDR
  transfer in the VUI) converts as 8.4 when that SEI says 18 over an HLG-compatible stated transfer; an
  SEI in the hvcC decides at track init, one in the first AU is read by the extractor's early scan before
  the formats are published.
  DV2.11 is done (PR #20): a track whose hvcC carries no SPS (BBC broadcast HLG, parameter sets only in
  the access units) takes the SPS and the SEI from the first AU in the early scan; the contract decides
  again with that SPS, and a converted track gets the AU's parameter sets as its initialization data.
- [DV2.5][desk] In hdr-analyze, extract the per-frame statistics on plain planes and shot L1 into the lib
  crate, with no behaviour change. Gate: `mkvdovi` and `hdr_analyzer_mvp` output byte-identical on the
  test corpus. Needs: DV2.1.
- [DV2.6][desk] In hdr-analyze, a causal scene detector, versioned on its own, with a chosen delay and an
  irreversible commit rule. Gate: its cut and per-shot L1 disagreement with the global detector on the
  validation corpus, in hdr-analyze `docs/VALIDATION.md`. Needs: DV2.5.
- Deferred: one combined staticlib (`dolby_vision` 3.4 with `capi`, plus the builder) that replaces
  `libdovi.a`. It moves the Profile 7 path from 3.3.2 to 3.4.0, so it needs a Profile 7 regression check;
  worth it when DV7.3 needs a `scene_refresh_flag` setter.
- [DV2.9][device][new-build] The DV2.8 dump on the device, for what no JVM test reaches (PR #16 review
  F2 to F5): with `debug.nuvio.rpu_dump` set, a Matroska Dolby Vision title played from the start logs
  `pts=` equal to the file's presentation times (no Media3 stream offset); a seek back inside the buffer
  starts a generation that dumps its first N AUs again; an HLS or a DASH Dolby Vision title is dumped; on
  an Amlogic box with native FEL, if one is at hand, a Profile 7 title is dumped. Gate: the captures and
  `scripts/rpu_dump_to_bin.py --list` output in the log. Needs: DV2.8.
- [DV5.1][desk] Renderer replacement (moved here from DV5, ID kept): RPUs written off the renderer
  thread, the swap at `onQueueInputBuffer` in the verified framings, shot commitment, AU identity,
  analysis generations and the completeness watermark. Gate: the tests of `DESIGN.md` 2.3 and 2.4 pass.
  Needs: DV1.1, DV2.2.

#### DV3: T0, no pixels

- **Status:** Open.
- [DV3.1][device][owner][new-build] HDR10+ → 8.1 provider. Gate: an HDR10+ title plays as Dolby Vision
  on the owner's TV; the debug log shows an RPU in every AU and L1 equal to `dovi_tool` for the same
  frames. First write into `DESIGN.md` 2.4 the HDR10+ rules of hdr-analyze `docs/RPU_BUILDER.md`: the
  scene rule runs in presentation order while the extractor reads in decode order; an AU without a
  payload reuses the previous payload in decode order; non-HDR10+ T.35 messages are skipped. Needs:
  DV2.7, DV2.8.
- [DV3.2][device][owner][new-build] HLG → 8.4 static provider: `DoviRpuBuilder.createStatic84(BT2100)`
  (the crate's `static_84_params`) with `static84L1()` (`STATIC_84_L1`, 0 / 1283 / 3079, V29 average
  floor). The RPU carries the default L6 and the crate's CM v4.0 L3 and
  L254. The canary comes from `debug.nuvio.dv_hlg_canary` = 1 (`DESIGN.md` 2.4), read once per
  extractor. Gate: G-C second half: the canary turns green through the app's conversion, bt2100 shows no
  tint; a BBC broadcast HLG sample (HLG only in the SEI, DV2.10) converts and its HLG contract log line names
  the SEI; the DV2.8 dump validates with `dovi_tool info`; a mid-file start and seeks played with both
  settings of the scene-refresh switch (`DESIGN.md` 2.2), the default kept or changed from what the TV
  shows. Needs: DV2.4, DV2.7, DV2.8, DV2.10, DV2.11. Session 1 (2026-10-25, log): a BBC broadcast
  HLG sample plays as Dolby Vision with normal colour on the owner's TV, its RPUs byte-identical to
  `dovi_tool generate`; still to run: the canary, a start from frame 0, seeks with both refresh
  settings and the `mkvdovi` comparison. The owner saw highlights slightly too bright (static L1; G-E).
- [DV3.3][device][owner][new-build] HDR10 → 8.1 static provider, opt-in. The MaxCLL/MaxFALL heuristic is
  not in `dovi_rpu_builder` (hdr-analyze E16.1 left it out); where it lives is decided when this step
  starts. Gate: G-E A/B recorded; the default stays off unless the owner prefers it. Needs: DV2.7.

#### DV4: T1, shot lists made on a PC

- **Status:** Open.
- [DV4.4][desk] In hdr-analyze, the playback manifest of `DESIGN.md` 2.8 around the L1 payload, written
  by `mkvdovi` and accepted for imported `RPU.bin` files. Gate: tests with variable frame rate, a
  non-zero start, an open-GOP start, a different encode and a mismatched composer. Needs: DV2.1.
- [DV4.1][desk] Shot-list provider in the fork: load a manifest from a file or URL, check identity
  incrementally as AUs are read, inject in the extractor. Gate: unit tests; a different encode of the
  same title is rejected. Needs: DV4.4, DV2.2, DV2.3; for HLG also DV2.4.
- [DV4.5][device][owner][new-build] A local sidecar end to end. Gate: every AU of the title carries the
  sidecar's L1 (debug log), no added dropped frames, the owner's G-E reading. Needs: DV4.1, DV1.8.
- [DV4.2][desk] In hdr-analyze, a server mode: analyse a URL from a random-access point before a given
  time, with a warm-up for scene context and crop, and stream finished shots; the first shot after a
  start inside a long shot is marked incomplete. Gate: its shots equal an offline run with the same
  start point and policy. Needs: DV4.4.
- [DV4.3][device][owner][new-build] End to end with the LAN server. Gate: on fixtures whose declared
  analysis context lets a complete following shot be finished and delivered within 10 s, its metadata
  reaches the injector before that shot's commitment deadline; other fixtures, including a seek inside
  a long uncut shot, keep the fallback until an eligible shot is ready, and the time to the first usable
  finished shot is recorded; no added dropped frames. Needs: DV4.1, DV4.2, DV4.5, DV5.1.

#### DV5: T2 and T3, analysed on the Shield

- **Status:** Open. T2 depends on G-A and G-F; T3 also on G-B.
- [DV5.11][device] Vulkan frame import and synchronization (`ash` or C++): fences, image lifetime,
  buffer reuse. Gate: the G-A lifetime, fence and reuse tests pass on the Shield. Needs: DV1.2.
- [DV5.3][desk] PQ statistics and reductions in Vulkan: workgroup-local histograms and partial sums, a
  small final reduction, no global 64-bit atomics. Gate: on the same decoded frames, per-frame statistics
  within the parity tolerance of the desktop CPU path. Needs: DV5.11, DV2.5.
- [DV5.8][desk] Vulkan HLG composition (composer reconstruction, chroma siting). Gate: on the same
  decoded frames, within the parity tolerance of the desktop two-pass path. Needs: DV5.3.
- [DV5.6][device][new-build] T2: idle-time analysis of the next episode on one decoder, results cached
  as manifests with their quality class; PQ titles first, HLG titles once DV5.8 passes. Gate:
  identical-frame parity and the declared sampling and scene-policy error are evaluated separately; the
  next play uses the validated covered ranges at their recorded class and the fallback elsewhere;
  first-frame reuse is required only when the coverage includes that frame; offline parity is claimed
  only for a complete validated analysis. Needs: DV5.3, DV4.1, DV1.6.
- [DV5.2][desk] Analysis decoder feeding: the tee of loaded AUs and best-effort priority. Gate: feeding
  and lifecycle unit tests. Needs: DV1.3.
- [DV5.10][desk] Dependency-safe skipping modes (without top-layer `_N` pictures, IRAP only). Gate:
  reference-dependency and output-coverage tests for each mode. Needs: DV5.2.
- [DV5.7][desk] One memory budget for compressed playback data, the tee queue, decoder surfaces,
  imported images and scratch; analysis is turned off when it would cut the playback reserve. Gate:
  unit tests of the budget model; the time to reach the lead after start and seek is logged. Needs:
  DV5.2.
- [DV5.12][device][new-build] Stress run of decoder feeding, skipping and the memory budget. Gate:
  playback stays within the G-B criteria under pressure and reclaim. Needs: DV5.10, DV5.7.
- [DV5.4][device][new-build] T3 for PQ at 4K24, on qualified configurations only. Gate: no added dropped
  frames or rebuffers against the same title without analysis; the share of AUs at each quality class
  and the lead distribution recorded; per-shot L1 within the agreed tolerance for the declared
  approximation policy. Needs: DV5.1, DV5.2, DV5.3, DV5.7, DV5.12, DV2.6, DV1.5.
- [DV5.9][device][new-build] T3 for HLG at 4K24. Gate: as DV5.4, plus the HLG patterns of G-A. Needs:
  DV5.4, DV5.8, DV3.2.
- [DV5.5][desk] Cache finished ranges with provenance and quality class; uncovered ranges keep the
  fallback; cache filling does not depend on meeting playback deadlines. Gate: a partly watched title
  replays with its covered ranges at their recorded class. Needs: DV5.4, DV4.1.

#### DV6: T4, full parity

- **Status:** Deferred until DV5.4 passes.

#### DV7: Profile 7 input

- **Status:** Open. The evidence and what carries over from AetherEngine are in
  [`DESIGN.md` 5](docs/dv-conversion/DESIGN.md#5-profile-7-input). DV7.8 waits for hdr-analyze's FEL
  reference reconstruction and its decision gates (`docs/FEL_PLAN.md` sections 6 and 7).
- [DV7.1][desk] Guard every libdovi call in `dovi_bridge.cpp` (parse, convert, write and the read-only
  readers): check the RPU CRC (MPEG-2 CRC32 over the unescaped payload) and pre-scan for the known abort
  paths (`DESIGN.md` 5.1; hdr-analyze E15.1 guards the same parser) before libdovi sees the RPU; a
  refused RPU takes the failure path. Gate: tests that feed each known abort shape and a CRC-broken RPU
  through the guard and get a refusal, not a crash, and a CRC-repairing mutation run over synthetic and
  real Profile 7 RPUs with no crash.
- [DV7.2][device][owner][new-build] Probe how the Shield's Dolby Vision decoder handles, inside a
  converted Profile 7 stream, an AU without an RPU and an AU that repeats the previous AU's RPU, with a
  `debug.nuvio.*` switch that drops or repeats the RPU on every Nth AU. Gate: the HUD, logcat and the
  owner's TV reading for both cases in the log.
- [DV7.3][desk] Failure handling from DV7.2's result: a failed or refused RPU is replaced by the last good
  converted RPU for at most 3 AUs and then dropped; the run resets at every flush, seek and release;
  `use_prev_vdr_rpu_flag` is resolved in decode order in the extractor. A borrowed RPU is rewritten with
  `scene_refresh_flag` cleared, which the bundled libdovi C API cannot do: an entry point in DV2.2's
  staticlib, or a rebuilt libdovi with a setter. Gate: unit tests for the borrow run, its reset and a
  `use_prev_vdr_rpu_flag` fixture; every borrowed RPU has `scene_refresh_flag` 0 and a valid CRC. Needs:
  DV7.1, DV7.2, DV2.2.
- [DV7.4][desk] The per-RPU fallback from mode 2 to mode 1 on every path (in-band Matroska
  `DolbyVisionMatroskaTransformer.kt:453`, MP4 `dovi_bridge.cpp:757`, TS `dovi_bridge.cpp:878`), with
  `docs/shield-todo.md:49` kept accurate. Gate: unit tests per path that a mode-2 failure is retried in
  mode 1; the HUD's mode count shows the mode used.
- [DV7.5][desk] One log line per Dolby Vision load: conversion taken or not, with the first reason in a
  fixed order (setting, `DeviceProfile`, revert switch, profile, mode, abandon). Gate: unit tests for
  each reason.
- [DV7.6][desk] In hdr-analyze (its step WS6.7), an offline fixed-pixel HDR10 test clip in three
  Profile 8.1 variants that differ only in their RPUs: an identity control, a mapping canary whose
  chroma mapping turns the picture green on a device that applies the 8.1 mapping (like the 8.4
  canary), and a pulse whose L1 alternates
  between a mid value and a maximum of 4095 on a fixed period. Gate: every RPU validates with
  `dovi_tool info` and passes the `dolby_vision` crate's Profile 8 checks, the mapping and L1 read back
  as written, and the three files have byte-identical video apart from the RPU NAL units.
- [DV7.7][device][owner] The DV7.6 clips through Nuvio to the owner's TV, with the expected visible
  difference written down before the session and the playback chain recorded; then one FEL title in
  mode 2 and in mode 1, as a first look only (mode 1 is not FEL_PLAN candidate b, `DESIGN.md` 5.4). Gate:
  the RPUs pulled from the debug log equal the files' RPUs, the owner's readings are in the log, and the
  two G-C rows of the qualification matrix are set; the canary result is evidence for hdr-analyze's
  FEL_PLAN gate 3 on this chain; no default changes. Needs: DV7.6.
- [DV7.8][desk] A FEL RPU provider: complete RPUs from hdr-analyze in the playback manifest, injected in
  the extractor for Profile 7 titles, with a different RPU length per AU allowed, and the title's
  residual statistics (FEL_PLAN candidate d) in the HUD. Starts only when DV7.7's canary passes and
  hdr-analyze has passed FEL_PLAN gates 1 to 4 for candidate b or gates 1 to 5 for candidate c; turning
  it on by default needs FEL_PLAN gate 6. Gate: extractor unit tests with manifest fixtures: every AU
  carries the manifest's RPU, RPU lengths vary per AU, and a different encode of the same title is
  rejected. Needs: DV4.1, DV4.4, DV7.7.

#### Owner decisions

- Decided 2026-10-22: the static 8.4 L1 is 0 / 1283 / 3079 with the V29 average floor (DV3.2, A.3).
- Decided 2026-10-23: DV1.4 closed on the owner's eye check of the 8.4 bt2100 and canary files; the
  `dovi_tool` checks and the other G-C rows move to DV1.8.
- Parity tolerances. Proposal: for identical frames, per-shot L1 median within 4 codes and maximum
  within 16 codes of the desktop; for approximation policies, the error table of DV1.5 decides.
- Whether static HDR10 → 8.1 is ever on by default (after G-E).
- Whether a LAN PC running hdr-analyze is an acceptable dependency.
- Whether Profile 7 FEL titles stay on mode 2 on Nvidia after DV7.7, and whether an RPU-only FEL
  provider (DV7.8) is wanted once hdr-analyze has measured what it gains; a default change needs
  FEL_PLAN gate 6 (real playback against dual-layer playback of the disc).
