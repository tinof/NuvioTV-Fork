# Roadmap log

History for [`ROADMAP.md`](../ROADMAP.md): the full text of each progress-log step, the measurements and the
finding IDs the roadmap items cite. `ROADMAP.md` keeps the current status, the open work and a one-line log;
the detail is here, newest first.

Finding IDs: `A-M*`, `A-C*`, `B*`, `C*`, `D*` come from the first assessment; `W*`, `A1`–`A7`, `P-*`, `H*`
from the second. Line numbers are as of commit 8db45982c.

From 2026-10-16, roadmap steps carry stable IDs (`<item>.<n>`, for example `R2.1`), and entries from then
on name the step ID and the PR.

## 2026-10-24: DV2.3 The signalling decision table for Matroska (PR #15)

- [DV2.3][desk] The signalling decision table of `DESIGN.md` 2.5 for Matroska only: a branch in the
  vendored extractor, the Dolby Vision level from the stream, native fallback when the decoder fails,
  behind a setting that is off by default, a `DeviceProfile` gate and a `debug.nuvio.*` revert switch.
  Gate: unit tests over every row of the table. Needs: DV2.4 (the HLG rows read its transfer).
  What the step must settle, found by a fresh-session check (2026-10-23):
  - Order: the HLG contract runs after `formatBuilder.build()` today (`MatroskaExtractor.java:3876`,
    `:3884`); move it before, and give a converted 8.4 Format `Hlg84.baseLayerColorInfo`, so the HDR10
    wrapper `HdrColorSignalingTrackOutput` (`DolbyVisionExtractorsFactory.kt:388-401`) leaves it HLG. A
    test checks the Format after that wrapper.
  - The PQ row uses the HLG contract's field rules with transfer 16: range limited, matrix 9, primaries 9,
    a stated contradiction refuses, an untagged field is accepted and logged.
  - The Dolby Vision level comes from width, height and frame rate in Dolby's profiles-and-levels table;
    without a frame rate (no DefaultDuration) take the level for 60 fps. Unit-tested.
  - Names, unless the code suggests better: a `PlayerSettings` field (`PlayerSettingsDataStore.kt`), off
    by default and not shown before DV2.7, because a Dolby Vision Format without RPUs must never reach
    the decoder; the gate `DeviceProfile.isNvidia()`,
    `DolbyVisionCompatibility.isDolbyVisionDecoderPresent()` and `DoviRpuBuilder.isAvailable()`; the
    revert switch `debug.nuvio.dv_convert`.
  - Decoder failure: reuse the existing retry (`retryCurrentStreamWithDolbyVisionFallback`,
    `PlayerRuntimeControllerObservers.kt:908`) with conversion off on the retry.
  - HLG signalled only in an alternative-transfer SEI resolves to NotHlg and plays native, which the
    `DESIGN.md` 2.5 row allows; its "Format-change path" has no step.
  - A converted 8.4 Format keeps the container's static HDR block (`HlgContract` carries it); change
    that only if DV3.2 shows a problem.

**Evidence**

- Gate `:app:testFullDebugUnitTest`: 6705 tests, 0 failures, 10 skipped, on the reviewed head plus the F1 fix.
- New tests: `MatroskaDvSignallingTest` (16: every row of `DESIGN.md` 2.5 through the vendored extractor,
  the Format after the HDR10 wrapper, the static HDR block for 8.1 and 8.4, the 60 fps fallback, strip and
  HDR10+ modes), `DvSignallingTest` (9: level vectors for truncated DefaultDurations, the width tie-break,
  above level 13, the PQ field rules), `DvConversionGateTest` (4: every reason and their order),
  `DvConvertFallbackTest` (3: an init failure of a converted Format is a Dolby Vision decoder failure, only
  a playback that converted a track retries native) and `ConvertHdrToDolbyVisionSettingTest` (1).
- CI: the `Checks` workflow on PR #15.
- Review (Tier 2, codex and gemini): F1 FIXED (`DvSignallingTest`): a DefaultDuration outside 5 to 121 fps
  was discarded and the level taken at 60 fps. Gemini P2 note: `DolbyVisionStatsSession.reset()` does not
  clear `convertedTrackTarget` (a new session is allocated per playback).
- The level table is FFmpeg's `libavcodec/dovi_rpuenc.c` (`dv_levels`); Dolby's profiles-and-levels PDF
  was not read.

**Resolution:** a Matroska HEVC track without Dolby Vision is published as `video/dolby-vision`,
`dvhe.08.LL`, with the HLG (8.4) or PQ (8.1) contract's base-layer colour when the per-playback
`DvConversionGate` allows it (hidden setting `convertHdrToDolbyVision`, revert switch
`debug.nuvio.dv_convert`, Nvidia, Dolby Vision decoder and display, `libnuvio_rpu`, no strip or map mode);
a Dolby Vision decoder failure of such a playback replays the stream native. Nothing converts until DV2.7
shows the setting.

## 2026-10-23: Appendix A hand-off check

After DV2.2 and DV2.4 merged, three read-only Opus agents checked whether a fresh session could continue
Appendix A from the repo alone. One checked the roadmap's consistency, one checked the design docs
against the merged code, and one planned DV2.3 with no prior context and listed what it could not
find. Changes:

- `ROADMAP.md`:
  - Current status points to Appendix A, and A.4 says how device builds are made.
  - The DV1 status names DV1.4's eye-check close; DV4.5 needs DV1.8 instead of DV1.4.
  - The DV2 status cites hdr-analyze PR #68 (`f55c779`).
  - DV2.3 lists what it must settle:
    - the HLG contract runs before the Format build, and a converted 8.4 Format carries
      `baseLayerColorInfo`, so the HDR10 wrapper leaves it HLG;
    - the PQ row's field rules;
    - the Dolby Vision level, including when no frame rate is known;
    - the setting, gate and revert-switch names;
    - the decoder-failure retry;
    - SEI-only HLG stays native;
    - the static HDR block is kept.
  - DV2.7 says how `DoviRpuBuilder` is called and who owns the handle.
  - DV3.2 names the Kotlin API.
  - Owner decisions records the static 8.4 L1 and the DV1.4 close.
- `docs/dv-conversion/DESIGN.md`:
  - Every stale fork `file:line` reference is updated (`MatroskaExtractor.java`,
    `DolbyVisionExtractorsFactory.kt`, `PlayerRuntimeController*.kt`,
    `NuvioExoPlayerPerformanceHelper.kt`).
  - The "Fork HLG detection reads only the Colour element" statement now describes DV2.4.
  - Section 2.7 records what DV2.2 shipped.
  - The 519 KB figure is labelled as the test build.

## 2026-10-23: DV2.2 Fork-side Rust crate over dovi_rpu_builder (PR #14)

- [DV2.2][desk] A fork-side Rust crate over `dovi_rpu_builder` (pinned to an hdr-analyze commit), built
  with cargo-ndk as a `cdylib` for arm64-v8a with its own `System.loadLibrary`, not linked into
  `dovi_bridge` (`DESIGN.md` 2.7). JNI entry points: static 8.1 and 8.4 RPUs (composer bt2100 or canary),
  shot L1 → RPU, HDR10+ payload → L1. `panic = "unwind"` with every entry inside `catch_unwind`; errors
  as stable codes; both scene-refresh variants of a shot rendered once and handed out as immutable byte
  arrays. Without the library, conversion stays off and playback native. Gate: a host `cargo test` of
  the wrapper equals hdr-analyze's committed fixtures (`dovi_rpu_builder/tests/fixtures`) at the pinned
  commit; the cargo-ndk build links; `scripts/verify_native_apk.py` finds the library and its entry
  points in the APK.

**Evidence**

- Host `cargo test` in `native/rpu_builder`: 15 tests. The fixture tests
  (`static_84_equals_dovi_tool_for_bt2100_and_canary`, `profile_81_cases_equal_dovi_tool`,
  `hdr10plus_sequences_equal_dovi_tool_for_every_peak_source` and two more) equal the copies of
  hdr-analyze's `dovi_rpu_builder/tests/fixtures` at the pinned commit `f55c779` (`tests-fixtures/SHA256SUMS`);
  `the_version_names_the_pinned_commit` ties the version string to the pin.
- `cargo test --features jvm-tests`: 21 tests, the `Java_*` exports called through a real JVM, including
  `a_panic_is_code_99_at_every_kind_of_entry`.
- `scripts/build_rpu_builder.sh`: the cargo-ndk build links for arm64-v8a, three LOAD segments at 16 KB.
- `scripts/verify_native_apk.py` on a local `scripts/shield_build_install.sh` debug APK of the reviewed head:
  `lib/arm64-v8a/libnuvio_rpu.so` present with its entry-point marker; its tests (`scripts/tests`) 7 passed.
- Gate `:app:testFullDebugUnitTest`: 6672 tests, 0 failures, 10 skipped, `DoviRpuBuilderTest` (7) included.
- CI: the `Checks` workflow on PR #14.
- Review (Tier 2, codex and gemini): both LGTM, no findings, no P2 notes.

**Resolution:** `nuvio_rpu` wraps `dovi_rpu_builder` as its own `cdylib`, `libnuvio_rpu.so`, loaded by
`DoviRpuBuilder` with its own `System.loadLibrary` and not linked into `dovi_bridge`. Every JNI entry
runs inside `catch_unwind` and returns stable codes; a shot renders both scene-refresh variants once as
immutable byte arrays. The library is built locally only (`scripts/build_rpu_builder.sh`, run by
`scripts/shield_build_install.sh`); without it `DoviRpuBuilder.isAvailable()` is false and playback stays
native. Nothing calls it yet: DV2.3 and DV2.7 are the first readers.

## 2026-10-23: DV2.4 The HLG contract (PR #13)

- [DV2.4][desk] The HLG contract: transfer from the SPS VUI at track init, refusal of contradicting
  range, matrix or primaries, no HDR10 colour info on an HLG base layer, and the alternative-transfer
  SEI logged but not acted on. Gate: unit tests with VUI-only, contradicting and untagged HLG samples.

**Evidence**

- Gate `:app:testFullDebugUnitTest`: 6665 tests, 0 failures, 10 skipped, on the reviewed head plus the F1 fix.
- New tests: `HlgContractTest` (21), `HevcVuiColourTest` (8), `HevcInitNalsTest` (7),
  `MatroskaColourFormatTest` (5), `MatroskaHlgContractTest` (11), `MatroskaSampleSeiTest` (2). The SPS
  and SEI fixtures are x265 encodes: VUI-only HLG, untagged 10-bit (HLG from the container only),
  contradicting range, matrix and primaries, 4:2:2, 4:4:4 and 8-bit, PQ, and an alternative-transfer
  SEI; synthetic SPS vectors cover the layout branches the encodes skip. Generators in `app/src/test/resources/hevc-colour`.
- CI: the `Checks` workflow on PR #13.
- Review (Tier 2, codex and gemini): F1 FIXED (`MatroskaSampleSeiTest`): an alternative-transfer SEI in
  the first sample instead of the hvcC was not logged. Gemini P2 notes: `HevcVuiColour` lists Main
  (profile 1) although only 10-bit streams pass; the SEI walk stops at a malformed payload length.

**Resolution:** `HlgContract` gives an HEVC Matroska track without Dolby Vision one `HlgDecision` at
track init (not HLG, transfer unknown, refused with a reason, or HLG for 8.4 with a BT.2020 limited HLG
base-layer `ColorInfo` that keeps only the container's own static block, never PQ or the HDR10
defaults), and logs it with any alternative-transfer SEI from the
hvcC or the first sample. Playback does not change; DV2.3 is the first reader.

## 2026-10-23: DV1.4 closed, DV2.1 done in hdr-analyze, DV2.2 repackaged

- [DV1.4][device][owner] G-C, first half: `mkvdovi` files and L1 variants played through Nuvio. Gate:
  the owner's TV readings and the `dovi_tool info` checks in the log. Closed by the owner on an eye
  check of the `mkvdovi` 8.4 files (bt2100 and the P8 canary): Dolby Vision through Nuvio on the Shield,
  the canary green. The `dovi_tool info` checks, the other G-C rows, a mid-file start and seeks were not
  recorded; they move to the new step DV1.8. Qualification matrix: the 8.4 G-C row is "pass, eye check
  only"; the other G-C rows stay not run.

- [DV2.1][desk] In hdr-analyze, a lib crate with the RPU builders (8.1, 8.4, HDR10+, static). Gate:
  `mkvdovi` output is byte-identical on the test corpus. Done as hdr-analyze E16.1, PR #67 (merged
  2026-10-22). The gate was widened there, because `mkvdovi` never built RPUs in-process (it runs
  `dovi_tool generate`):
  - The new crate `dovi_rpu_builder` writes one RPU NAL per AU (`0x7C01` plus the escaped payload).
  - It equals `dovi_tool generate` 2.3.4 on every committed fixture and on 64/64 HDR10+ corpus runs.
  - The nine 0.7.0 `mkvdovi` RPU baselines are unchanged.
  - Static 8.4 L1 (owner, 2026-10-22): min 0, avg 1283, max 3079, V29 average floor.
  - The canary composer lives in `dovi84_composer::canary_mapping` and is not offered by `mkvdovi`.
  - Not included: the HDR10 MaxCLL/MaxFALL heuristic (DV3.3).

**Review of the merged crate against this plan** (two Opus finders, an Opus verifier per finding; 15
findings, all confirmed, most of them notes):
- Blocker for DV2.2 as written ("a cargo-ndk staticlib … linked like libdovi"). `libdovi.a` is a Rust
  staticlib (`dolby_vision` 3.3.2, rustc 1.93.0, not LTO-merged). A second LTO staticlib linked with it
  into one `.so` fails with NDK 29: `duplicate symbol: rust_eh_personality`. A test staticlib without
  LTO, built with the same rustc 1.93.0, linked in both archive orders, because both carry the same std
  object; that depends on the two compilers matching and is not used. Detail in `DESIGN.md` 2.7.
- A test `cdylib` over the merged crate built for arm64-v8a: 519 KB stripped, NEEDED libc, libm and
  libdl only. Its first static 8.4 AU, bt2100 and canary, equalled hdr-analyze's committed `dovi_tool`
  fixtures (host test).
- Thread safety: `RpuBuilder`, `ShotRpu`, `Hdr10PlusHold` and `Error` are `Send + Sync`. A write costs
  about 1–2 µs on an Apple-silicon Mac (not a Tegra number).
- For hdr-analyze, not blocking DV3.2:
  - `RpuBuilder::new` accepts parameters that every later write refuses, such as L6 above 10000 or nine
    trim targets. The refusal comes as the generic `Error::Rpu`.
  - Doc fixes: the static 8.4 RPU also carries the crate's CM v4.0 L3 and L254; the panic strategy is
    the consuming crate's; `Hdr10PlusHold::reset` after a seek is caller policy.
- For the fork:
  - The scene-refresh rule after a start or seek was undecided (now `DESIGN.md` 2.2).
  - HDR10+ needs presentation order for its scene rule while the extractor reads in decode order, and
    a rule for AUs without a payload (now in DV3.1).
  - "Held while the payload is unchanged" was stricter than `dovi_tool`'s scene key (A.3 and
    `DESIGN.md` 1 reworded).
  - JNI entries need their own `catch_unwind`.

**Steps changed or added**

- [DV2.2][desk] Reworded: a fork-side Rust crate over `dovi_rpu_builder` (pinned to an hdr-analyze
  commit), built with cargo-ndk as a `cdylib` for arm64-v8a with its own `System.loadLibrary`, not
  linked into `dovi_bridge`. JNI entry points: static 8.1 and 8.4 RPUs (composer bt2100 or canary), shot
  L1 → RPU, HDR10+ payload → L1. `panic = "unwind"` with every entry inside `catch_unwind`; errors as
  stable codes; both scene-refresh variants of a shot rendered once. Without the library, conversion stays
  off. Gate: a host `cargo test` of the wrapper equals hdr-analyze's committed fixtures at the pinned
  commit; the cargo-ndk build links; `scripts/verify_native_apk.py` finds the library and its entry
  points. Deferred: one combined staticlib replacing `libdovi.a`.
- [DV2.3][desk] Now Matroska only, and Needs: DV2.4.
- [DV2.7][desk] New: extractor injection for tracks that carry no Dolby Vision, through the existing
  transformer, with the scene-refresh rule of `DESIGN.md` 2.2. Gate: unit tests: one RPU per AU, every
  other NAL byte-identical, refresh as the rule says, native tracks untouched. Needs: DV2.2, DV2.3.
- [DV2.8][desk] New: RPU debug dump behind a `debug.nuvio.*` switch for the existing and the converted
  paths, plus a script that rebuilds an `RPU.bin` for `dovi_tool info`. Gate: unit test of the encoder;
  a fixture round trip through the script.
- [DV1.8][device][owner][new-build] New: G-C, precise half, with the DV2.8 dump, from the start, from a
  mid-file resume point and with seeks. Gate: dumped RPUs validate with `dovi_tool info` and equal the
  files' RPUs; the owner's readings; the G-C rows set. Needs: DV2.8.
- [DV3.1] Needs now DV2.7, DV2.8 (DV1.4 closed); the HDR10+ order and gap rules go into `DESIGN.md` 2.4
  first.
- [DV3.2] Names `static_84_params` and `STATIC_84_L1`; gate adds the DV2.8 dump check and a mid-file
  start and seeks with both settings of the scene-refresh switch. Needs: DV2.4, DV2.7, DV2.8.
- [DV3.3] Notes that the heuristic is not in `dovi_rpu_builder`. Needs: DV2.7.

## 2026-10-22: Q6.1, Q6.10 Decoder AAR and the DTS-HD MA transcode target (PR #12)

- [Q6.1][desk] Decoder AAR with the DTS target in `app/libs`. Done 2026-10-21 (CI run 39594133508, AAR
  `a1152a6d…`, 6569 tests). No later AAR is committed: binaries are local only since 2026-10-22, and an
  AAR with the DTS-HD MA writer stays off GitHub. The step leaves the roadmap when the branch ships.

- [Q6.10][desk] DTS-HD MA transcode target: the `dcaxll` writer in the JNI, target choice (DTS-HD MA only
  when the output lists DTS-HD, on both routes), step-down HD → core → PCM, frame counting and IEC bursts
  for core+ExSS units, core alignment, the seek discard by output time, the priming-trim clock fix, labels.
  Gate: host native test (`ffmpeg-decoder-downmix/src/test/native`, bit-exact XLL decode), module and app
  unit tests. Done on the branch 2026-10-22.

**Evidence**

- Gate `:app:testFullDebugUnitTest`: 6611 tests, 0 failures, 10 skipped, on the reviewed head plus the F1 fix.
- Host native test `ffmpeg-decoder-downmix/src/test/native` against FFmpeg n7.0.2 with ASan and UBSan: all
  checks pass, among them a bit-exact XLL decode of the PCM the core encoder saw, core/XLL alignment, dual
  mono, end of stream, reset and the seek discard.
- Device, 2026-10-22: the HT-A9 shows DTS-HD MA for a 48 kHz AAC 2.0 title; log
  `DTS-HD MA transcode: 48000 Hz 2 ch -> 48000 Hz 2 ch (stereo)`; AudioFlinger DIRECT
  `AUDIO_FORMAT_DTS_HD`, 2 channels; the decoder thread about 20 % of one core.
- CI: the `Checks` workflow on PR #12.
- Review (Tier 2, codex and gemini): F1 FIXED (`PlaybackSpeedAwareAudioSinkAacToDtsSpeedTest`), F2
  NEEDS-HUMAN, now Q6.13.

**Resolution:** the DTS-HD MA target is on `perf/shield-pass`; DTS-HD MA needs a local-module build because
binaries are built locally only since PR #11. The device steps Q6.2–Q6.5, Q6.11–Q6.13 stay open.

## 2026-10-21: Q6 Transcode AAC to DTS (branch `feat/Q6-aac-dts`)

The owner asked whether AAC tracks could be transcoded to DTS during playback with FFmpeg's `dca` encoder,
for their Sony HT-A9 (Neural:X). The detail is in
[`docs/aac-to-dts/DESIGN.md`](aac-to-dts/DESIGN.md); this entry records the session.

**Feasibility**

- A workflow (dca source, sink path, Codex design opinion, skeptic) found it feasible.
- The fork's FFmpeg renderer already re-encoded to AC-3 for Force AC-3.
- `dca` needs packed S32, the experimental flag and 512-sample frames.
- Media3's `DecoderAudioRenderer` reports one access unit per buffer, so DTS frame counting had to be fixed.
- On the owner's HDMI route, AAC already reaches the AVR as lossless PCM. The owner chose DTS for the AVR's
  processing.

**Owner decisions**

- AAC with 1–2 channels only.
- Tuned swresample instead of libsoxr.
- An all-device toggle, default off.

**Plan review**

- Codex reviewed the plan in two rounds through the `ExitPlanMode` hook and raised 6 concerns: the
  end-of-stream flush, output timestamps, the priming trim and its timestamps, PREFER re-creation in both
  directions, and the end-of-stream loss bound.
- All were handled within the plan.

**Built**

- 17 commits on `feat/Q6-aac-dts` (see DESIGN §4).
- Two CI AARs from the module:
  - `d8e211b4…`, run 39589348150, the module as it was;
  - `a1152a6d…`, run 39594133508, with the DTS target.
- The resampler was measured on a host n7.0.2 build: `filter_size` 144, `cutoff` 0.875, `kaiser_beta` 13
  give images ≤ −130 dB and ripple 0.0033 dB at 4.1× the default CPU (soxr: 3.4×).

**Code review**

- 4 Opus finders, each with an Opus verifier.
- 10 findings confirmed. 9 were fixed and the tenth was the planned AAR step (DESIGN §6). The most serious:
  - a refused transcode would have been learned as a DTS denial, because `player.audioFormat` is null at
    error time;
  - the DTS decision bypassed the speed and policy checks through `sinkSupportsFormat`.

**Gate**

- `:app:testFullDebugUnitTest` against the shipped AAR: 6569 tests, 0 failures.

**Device** (M2 snapshot `~/shield-snapshots/20261021-aacdts/`)

- *Blue Lights* S1E1 (AAC 2.0, 48 kHz) on the `.shield` debug build.
- Logcat: `DTS transcode: 48000 Hz 2 ch -> 48000 Hz 2 ch, 1509000 bit/s, frame 512 samples`.
- AudioFlinger: a DIRECT output with processing format `AUDIO_FORMAT_DTS` at 48 kHz.
- After the start: no underruns, sink errors or discontinuities.
- Owner: "converted properly to DTS 2.0. Play perfectly on my Sony HT-A9."
- Q6.2–Q6.5 are still open.

## 2026-10-21: live playback capture (Q5, M3.3 added)

The owner reported occasional dropped frames during playback. This was a read-only capture while the owner
watched.

**Session**

- **App and title:** `com.nuvio.tv.test` 1.1.0-nt.7.3 (installed 2026-10-20, `speed-profile`). A 4K HDR title
  at 23.976 fps on display mode 6 (2160p 23.976 Hz), DTS-HD passthrough through the fork's IEC sink, over
  Wi-Fi.
- **Shield:** up 6.7 days. Load average about 3. zram swap 524 of 524 MB used, as at the 2026-10-14
  baseline.
- **Method:** `dumpsys SurfaceFlinger --latency` on the video layer every ~8.8 s, which keeps about 511
  frames per dump, plus `logcat -v monotonic` and `/proc/vmstat` every 2.5 s. 296 s of memory samples.
- **Event classes:**
  - A gap in desired present means the app never queued the frame: an app-side skip.
  - A gap only in actual present means a late compositor present.

**Results**

- **Events:** 12. 10 app-side skips (7, 1, 3, 1, 1, 3, 1, 2, 4 and 1 frames) and 2 late presents
  (3 frames and 1 frame).
  - The owner's report at about uptime 583323 s matches the 4-frame skip at 583316.9 s.
  - The events fall at −4.4 s to +3.8 s from the sampling ticks, so they are not an observer effect.
- **Memory:**
  - 11 of the 12 events fell in 2.5 s windows with 40–80k `workingset_refault`. The 2-frame skip at
    583311.0 s had only 7.5k.
  - The 7-frame skip at 583200.9 s fell in the capture's only direct-reclaim window
    (`pgscan_direct` 791), next to a Nuvio "NativeAlloc concurrent copying GC" of 1.097 s total.
  - Totals over 296 s: `workingset_refault` 939,858; `pgscan_kswapd` 1,323,002; `pgscan_direct` 1,051;
    `pgmajfault` 5,761; `pswpin` 66 and `pswpout` 297. MemAvailable ranged from 343 to 590 MB.
  - 80 SIGKILL process exits (Zygote "exited due to signal 9"). Whether lmkd or ActivityManager sent them
    was not established.
  - Respawn loops: GservicesProvider started 20 times and TvProvider 14 times. Nuvio is not the
    TvProvider caller: `TvRecommendationManager.kt:155` defers Watch Next while playing.
  - Resident memory: Nuvio RSS 772–798 MB, of which `allocHeldMb` 489 is the media buffer. NvMap
    812 MB, unattributed without root.
- **Buffer manager:** on `onTrimMemory` 15 the cap went from 500 to 175 MB and buffered-ahead fell from
  52 s to 8 s. It eased to 300 MB about 60 s later. RSS went 770 → 422 → 586 MB in 2 min.
- **Ruled out:**
  - Frame rate (matched).
  - Composition (video on a DEVICE overlay).
  - Thermals (49 °C, fan 0).
  - Wi-Fi (5 GHz, −51 dBm, Rx 650 Mbps, buffer never under 8 s).
  - Audio: AudioFlinger DIRECT track with 0 underruns and 0 delayed writes; corrected timestamp jitter
    std 0.07 ms. IEC `stalls` counts writes into a full track (`IecPassthroughAudioSink.kt:748-750`),
    which is backpressure.
- **Limits:** this is a correlation with memory pressure, not a proven cause.
  - The scratch analyzer hard-coded the PID.
  - It kept about 21 s of SurfaceFlinger history from before the capture started (frame span 321 s
    against 296 s of samples).
  - `NuvioExoEvent dropped_video_frames` cannot show single drops, because of the 50-drop notify
    threshold.

**Same day: reboot control and debloat start**

- **Collector fixes:** the scratch collector now resolves the PID and the layer at capture time, and drops
  frames from before the capture. Re-run on the first capture, it still finds the same 12 events.
- **Control run** (reboot only, capture from about 10 min after boot, 600 s, same build):
  - 5 events: 4 single-frame app-side skips and 1 late present.
  - All 5 fell between 1153 and 1164 s of uptime, while the system pushed memory into swap: SwapFree
    went from 314 to 229 MB, with 2.6–12k `pswpout` and 44–64k `workingset_refault` per 2.5 s.
  - 6 SIGKILL exits; MemAvailable 244–384 MB.
  - Nuvio held `allocHeldMb` 452 with RSS 780–826 MB.
- **Reading:** a reboot lowers the rate (0.5 per min against 2.4 per min at 6.7 days up) and the severity
  (1 frame against 7). The box is back in reclaim within about 15 min of 4K playback, so a reboot is no
  fix.
- **Debloat, in progress:** 7 packages were disabled, from the ATV Optimizer catalog
  (`bryanroscoe/shield_optimizer`): `tvrecommendations`, `nvgamecast`, `katniss`, `mediashell`,
  `tv.remote.service`, `play.games` and `com.lb.app_manager`.
  - MemAvailable went from about 384 to 450 MB at once, during playback.
  - Not yet done: the reboot, the SMB toggle and the after-run.
  - The device record and the undo commands are in the local Shield reference.
  - Q5.2 waits until the debloated state has settled.

**Steps added**

- Q5.1 to Q5.7 (`ROADMAP.md`, item Q5): a frame-gap script; a controlled baseline; proactive pressure
  sampled at its own cadence; an Nvidia 3 GB budget of about 300 MB; hysteresis; the search for the
  large-object churn; and the comparison run.
- M3.3: log the decoder drop and skip counters with the `BUFFER:` line.

## 2026-10-20: U1.5 made repeatable (PR #9)

Upstream ships several times a week, so the sync of the same day became a procedure with its own checks.

- Branches: `main` is now `upstream/main` + the fork's base layer (the 14 CI, test and fix commits and the
  drift watcher); `perf/shield-pass` is `main` + the Shield work. Both moved in one atomic leased push. The old
  `main` (the base layer on the pre-sync upstream) was 285 commits behind. The new `main` passes the unit gate
  on its own (6361 tests, 0 failures) and CI `PR Full Debug Build`.
- Drift watcher: `.github/workflows/upstream-drift.yml` on `main`, daily and on dispatch, keeps one issue
  labelled `upstream-drift` current. The issue shows the commit counts for both layers, the Tier 2 paths
  upstream changed (read from this contract), the trial-merge conflicts and upstream's first-parent log; it
  closes when the fork is level. Upstream `#N` references are qualified with upstream's repo name and
  `@names` defused so the issue neither links to this fork's issues nor pings anyone. Issues were enabled for
  it; upstream's four issue workflows are disabled in the fork.
- `/upstream-sync` (`.claude/skills/upstream-sync/`): preflight, map, classify, rebase, verify, CI on pushed
  candidates, publish, follow-ups. `sync.py` and `both.py` carry the checks done by hand on 2026-10-20, with
  28 unit tests in `scripts/tests/test_upstream_sync.py`. Replayed against that sync (local tags
  `sync/2026-10-20/*`), verify gives, per layer, 13 identical and 1 changed for `main` and 67 identical, 14
  changed and the 2 expected drops for `perf/shield-pass` (80, 15 and 2 for the whole stack); map gives the
  same 52 overlapping commits and 18 trial conflicts. An independent review found 8 gaps that sync did not
  hit (upstream renames and deletions, drops decided for the other layer, a failed analyst group, local
  branches left behind after the push); all are fixed and tested. The named workflow
  `upstream-sync-classify` reached the sync's verdict on the datasource AAR in a one-analyst run.
- Cleanup: merged local branches and the 12 copies of ysosrs123 branches on origin were deleted.

## 2026-10-20: U1.5 upstream sync onto 1.1.0-nt.7.3 (follow-ups: PR #6)

Step: [U1.5][desk] Keep `perf/shield-pass` rebased on `upstream/main` while upstream moves. Gate: 0 commits
behind at each pull request. (The step stays open: it recurs.)

- Upstream had 285 new commits (51 first-parent, with the merge of official NuvioTV 1.1.0-beta.7). Nine Opus
  analysts, one per area, classified every fork commit against them before the rebase, and a critic checked
  coverage. The rebase was done by hand: 80 of the 95 commits re-applied with identical patches, 15 needed
  a conflict fix or new context, and `be0ce8ec9` with its revert `5b03ca095` was dropped as a net-zero pair
  (rationale in the R1.1 entry).
- Evidence: `:app:testFullDebugUnitTest` green (6549 tests, 0 failures; plain upstream/main: 6361 tests, 1
  failure, `testHttpError404`); CI `PR Full Debug Build` run 39407560065 green; an independent review of the
  resolutions found no defects. Force-pushed with a lease on the old tip.
- Resolutions that keep the fork's intent on upstream's new code:
  - The datasource AAR stays the fork's (`692cae00…`). Upstream's ba710a269 rebuild (`44c07852…`) still reports
    localhost 404/410 as 400.
  - `MemoryBudget`: upstream's chunk ceiling and the trailer pool take the heap-only `lowHeap`; the fork's RAM
    tier stays for the other users.
  - Trailers use upstream's decoder check with `hdrDisplay = false`; the lossless default also skips tracks that
    upstream flags as commentary or audio description; the "DV decoder" Stats row sits beside upstream's
    slimmer DV rows; the pre-blurred glass hooks into upstream's prepared hero, whose fade stays 280 ms.
- Owner decision on the settings upstream changed: keep the fork's where this pass has one, take upstream's
  elsewhere. Kept, as new Nvidia rows in `docs/shield-todo.md`: AV1 trailers need hardware (`trailer_av1_hw`),
  full swap still refuses thumbnail decodes on `mdarcy` (`thumb_swap_room`, against 4f7c16200). Kept and
  noted: TrueHD on the platform sink (Q1.2 now tests upstream's keep-alive, 3aeb1e180, on the packer path).
  Taken from upstream: Automatic speed-up on, higher parallel-download limits, the TV channel sync at boot,
  DV detail rows only for native FEL.
- The datasource patch workflow now checks against the original AAR from git history; its old guard could
  not pass against any rebuilt AAR.

## 2026-10-19: Appendix A, DV7 Profile 7 input

The owner asked whether AetherEngine's Profile 7 FEL playback (an engine for Apple platforms that
composes the base layer, the enhancement layer and the RPU on the GPU, then attaches a per-frame Profile
8.1 RPU that tvOS applies) can be used in this fork, together with hdr-analyze's FEL plan. Two parallel
Opus agents checked the platform facts on the web and mapped AetherEngine's design onto this fork's code;
their file:line references and the DoViBaker and libplacebo siting quotes were re-checked by hand.

- The composition does not carry over to the Shield: Android surfaces take only static HDR metadata, no
  app API carries a Dolby Vision RPU, `OMX.Nvidia.DOVI.decode` ignores the enhancement layer, and a
  re-encode is ruled out (`docs/dv-conversion/DESIGN.md` 5.2).
- libdovi can abort the app: the `dolby_vision` 3.3.2 crate calls `unimplemented!` on polynomial pieces
  with linear interpolation, its cargo-c build sets `-Cpanic=abort`, and its C API has no
  `catch_unwind`. The bridge checks no CRC. Today a failed RPU is dropped, and the mode-2 → mode-1
  fallback exists only on the BlockAdditional path (`DESIGN.md` 5.1).
- New item DV7 (DV7.1–DV7.8): the libdovi guard, the RPU failure probe and handling, the fallback on
  every path, one route log line, an 8.1 canary and a pulse clip from hdr-analyze, and an RPU-only FEL
  provider that waits for hdr-analyze's FEL reference reconstruction. G-C gains the two clips.
- AetherEngine lessons for existing steps went into `DESIGN.md` 2.3 (generation change inside the
  critical section), 2.6 (an absent curve is the identity), 2.9 (completion callbacks and image lifetime)
  and 3 (a pure pixel-rate gate for the second decode).
- In hdr-analyze, `docs/FEL_PLAN.md` gained AetherEngine and DoViBaker as prior art and a table of
  enhancement-layer siting, and its roadmap gained WS6.7 (the clip DV7.6 names) and E15.1 (the same
  parser guard as DV7.1) (tinof/hdr-analyze PR #65).
- Codex (gpt-6.1-sol, high) reviewed the draft: 7 major and 4 minor points, all re-checked and applied.
  The main ones: the bundled C API cannot clear a scene-refresh flag, so DV7.3 needs DV2.2's writer;
  mode 1 keeps Profile 7 syntax and is not FEL_PLAN candidate b; the pulse test needs a fixed-pixel
  clip; DV7.8 follows FEL_PLAN's gates exactly (`docs/dv-conversion/codex-dv7-review.md`).

## 2026-10-18: R1.3 and D1.2 deferred

The owner deferred R1.3 (True black bars with ASS subtitles on the canvas overlay and Overlay OpenGL, on a
build with R1.1): ASS subtitles are not in use, so the check is not a priority. D1.2 (the libass
"recommended" label) waited on R1.3 and is deferred with it. R1 counts as done for the merge into `main`.
Both stay in `ROADMAP.md` as Deferred bullets, to be restored as steps when ASS subtitles come into use.

## 2026-10-18: R1.2 Confirm the mechanism on the current APK (closed unrun)

Step: [R1.2][device] On the current APK, confirm the mechanism: `setprop debug.nuvio.opaque_window 0`,
restart the app, play a 2.40:1 title with True black bars on. Gate: bars black with `0`, stale with the
default.

- Resolution: closed without meeting its gate. The default arm did not reproduce incident 02 (session in
  the R1.1 entry below), so the `0` arm was not run; R1.1 then reverted the default this step tested, so
  it cannot run again. R1.3 checks the bars, with ASS subtitles, on the new build.

## 2026-10-18: R1.1 Revert be0ce8ec9 and remove the `opaque_window` row (PR #4)

Step: [R1.1][desk] Revert be0ce8ec9 (it applies cleanly) and remove the `opaque_window` row from the
defaults table. Gate: unit tests green.

- Evidence: `:app:testFullDebugUnitTest` green on the PR head (5658 tests, 0 failures, JDK 17); CI
  `PR Full Debug Build` on PR #4. Cross-review Tier 2, Codex and Gemini: LGTM.
- Resolution: MainActivity sets a transparent window background in `onCreate` again, and
  `LetterboxRenderPolicy` loses `opaqueWindowOutsidePlayback` and `opaqueWindowNow`. The True black bars
  code (`LetterboxRenderPolicy`, the `transparent_letterbox` preference, the player) matches
  `upstream/main`.
- R1.2 device session before the revert was installed (installed APK sha256 `c7c3b0a99f58bc30…`, True
  black bars on, `opaque_window` unset): Blade Runner 2049 (2.39:1, DV remux, 2160p25 → 2160p23.976),
  started by deep link and through Home → Continue watching, with Finnish PGS subtitles selected and the
  player controls shown and hidden. Bars pure black in every screenshot (max pixel 0–1), window format
  TRANSPARENT, app layer `isOpaque=0`, no HDCP read failures. Incident 02 did not reproduce, so the
  `opaque_window 0` arm was not run and W1 stays unconfirmed. Not covered: ASS subtitles, which R1.3 tests.
  The owner closed R1.1 on the grounds that the revert restores upstream's behaviour; it does not claim
  to fix incident 02.

## 2026-10-16: Appendix A, on-device Dolby Vision conversion

The owner asked whether the fork can convert HDR10+, HDR10 and HLG to Dolby Vision during playback,
with hdr-analyze's code and the Shield's GPU. The plan is a separate track in `ROADMAP.md` Appendix A,
items DV1–DV6, outside the Priorities list.

- HDR10+ → 8.1 needs no pixels: L1 is arithmetic on each AU's HDR10+ SEI. CUDA is not available to
  Shield apps; measured L1 for HDR10 and HLG would use Vulkan compute, first while nothing plays (one
  decoder), then during playback on a second decoder, behind device gates for the pixel path (G-A), a
  concurrent 4K decode (G-B), Dolby Vision from synthesized signalling (G-C) and a thermal run (G-F).
- The evidence, the architecture, the budget and the risks moved to `docs/dv-conversion/DESIGN.md`, so
  the appendix keeps only the goal, tiers, gates and steps.
- Codex pass 0 (gpt-6-astra, medium; it saw no draft) contributed the injector with interchangeable L1
  providers, the late replacement at renderer input, the watermark, and the evidence that Profile 8.4
  plays through a Shield TV Pro into an LG C9 (hdr-analyze `docs/ROADMAP_LOG.md:237-262`).
- A Claude citation check of the draft found four errors, all fixed: Media3 passes the dvcC to the
  decoder for MP4; dovi_tool takes HDR10+ L1 from each scene's first frame; the two gpuinfo reports are
  1.0.61 and 1.0.56; the CUDA evidence is one forum thread. It also showed that holding back a track's
  Format can deadlock preparation (`MatroskaExtractor.java:1411-1414`). Details in
  `docs/dv-conversion/claude-factcheck-r1.md`.
- Codex's critique of draft 2 (gpt-6-astra, medium) raised 21 points, three of them blockers: a shot's
  provider and L1 must be committed and frozen before its first AU is queued; an AU needs more identity
  than its presentation time, with a new analysis generation at every seek, flush and release; and HLG
  needs the full 8.4 colour contract and composer-domain measurement from the first measured tier. The
  plan now adds a playback manifest, a signalling decision table, probes separate from qualification
  (`docs/dv-conversion/QUALIFICATION.md`), idle-time analysis on one decoder before playback-time
  analysis, and split steps. The disposition of each point is in `docs/dv-conversion/codex-r1-review.md`.
- Codex's re-check of those changes found 12 points resolved and 9 partly resolved; its replacement texts
  were applied (`docs/dv-conversion/codex-r2-recheck.md`). Codex accepted keeping HDR10+ in T0.

## 2026-10-16: upstream plan and toolchain

The Shield work is meant to reach `ysosrs123/NuvioTV-Fork` as pull requests later. Three principles were
added: upstreamable by default (Nvidia-gated, revertible, tested; fork-only commits marked `fork:`), stay
on upstream's toolchain (JDK 17, Gradle 8.13, AGP 8.13.2, Kotlin 2.3.0, JVM target 11), and measure the UI
on a release-like build.

- The build toolchain runs only on the development machine. The app targets Java 11 bytecode and runs on
  the Shield's Android 11 runtime, so a newer JDK, Gradle or AGP gives the Shield nothing and makes every
  upstream pull request conflict in the build files.
- Runtime libraries are different: Media3 (1.8.0, patched AARs) and Compose (BOM 2026.05.01, TV Material
  1.1.0-rc01) can change playback and UI behaviour. They change only as measured experiments, each in its
  own pull request, because they affect every device.
- The CI debug APK is not minified and has no baseline profile, so UI baselines use the `benchmark` build
  type (release settings, debug key, `.debug` suffix).
- Branch state on 2026-10-16: 80 commits ahead of `upstream/main`, 0 behind; upstream's last commit was on
  2026-10-12, and it has merged one outside pull request. New item U1 plans one pull request per theme
  from `upstream/main`, after asking the maintainer.

## 2026-10-15: roadmap created

`docs/shield-todo.md` mixed the Nvidia defaults reference with open work. The open work moved into
`ROADMAP.md` as items R1–R3, Q1–Q4, S1–S4, M1–M3, E1 and D1. `docs/shield-todo.md` keeps the defaults, the
switches, the every-device changes and the quick reference.

A Codex review (gpt-6.1-sol, high) of the draft found, among others: the 1442 ms start jank was missing;
`DetailEntryTrace` already logs the details stages; a `playWhenReady` gate cannot cover the AFR hold;
several validation steps were tagged `desk` only; `PLAYBACK_STARTED` must exist before the playback
baseline; and the documented way to clear a switch fails on the device. Each claim was checked against
the code or the Shield before the roadmap changed.

### Corrections to docs/shield-todo.md (item D1)

| Where | Correction | Finding |
|---|---|---|
| `opaque_window` row, "Kept as they are" | `opaque_window` causes incident 02 and is to be reverted (R1). The Home check measured jank, not layer opacity. | W1, W2, W5 |
| `hbr_iec` row | The HAL refuses raw TrueHD; AudioFlinger's SPDIF encoder wraps it as IEC61937 at 192 kHz. "Kept" was a default, not an A/B. | A1, A7 |
| `hbr_buffer_scale` row and the switch header | Number switches revert with the listed value; `0` does not revert `hbr_buffer_scale`. | A-C02 |
| `lowram_from_ram` row | Mark *app*: `MemoryBudget` reads the switch once per process. | A-M1 |
| `libass_overlay_canvas` row | Drop *app*: a playback restart is enough. | A-M6 |
| `clear_audio_rejections` row | Any new token of 1–64 characters except `0`. | A-M5 |
| Switch parsing | `yes`/`no` work as well as `true`/`false` and `on`/`off`. | Lane A summary |
| Clearing a switch | `adb shell setprop debug.nuvio.<name> ""` fails with a usage error: adb drops the empty argument. Quote it inside the device command. | Checked on the Shield |
| Every-device changes | Add the audio changes that apply to every device: Bluetooth HDMI-route pin, passthrough-aware lossless default, decoder-keyed stall ledger (c36153c3a, d02a4efb5, 2e98bb12c, caaf081ae). | B6 |
| "translated in every locale" | New strings in every locale; `audio_tunneled_sub` is missing in pt, sr and zh. | A-M4 |
| Stabilize (round 3) | The 1969 ms came from the render thread waiting in `dequeueBuffer` during the HDMI re-link; the IO poll fixed the log line, not the start time. | P-10 |
| Switch-back delay (round 3) | The video layer was already gone before the hang; the delay does not touch the HDCP condition. | H1 |
| Pre-start hold (round 3) | It does not run on the Shield's non-tunneled start. | P-11 |
| Swap rule (round 3) | No session in the report was below 128 MB SwapFree, so the rule never applied. | P-05 |
| Media3 list | Three items labelled 1.11.1 shipped in 1.11.0; the Choreographer item is mostly covered by the fork's vsync re-sample. | C3, C12 |

## 2026-10-15: second assessment (after the device report)

A workflow read the device report, its raw logs and the code in four lanes: window and letterbox,
TrueHD audio, start/seek/memory, and incident 01. Skeptic agents checked the window, audio and incident
lanes; none of their findings was refuted. The start/seek/memory lane had no skeptic. A Codex review
(gpt-6.1-sol, high) of the plan added the corrections below.

| ID | Finding | Item |
|---|---|---|
| W1 | be0ce8ec9 sets a black window background in `onCreate` before the window attaches, so HWUI creates the renderer opaque and never clears; at player entry the window goes transparent and the bars keep stale pixels. | R1 |
| W2 | The default never made the app layer opaque in SurfaceFlinger: once a SurfaceView attaches, the window format is forced to TRANSLUCENT. | R1 |
| W3, W4 | Fix by reverting be0ce8ec9; confirm with `opaque_window 0` on the current APK. | R1 |
| W5 | Docs keep `opaque_window` as verified. | D1 |
| A1 | TrueHD ran as platform direct passthrough; the "HBR RAW" log line is the fork sink's forward path, not its packer. | Q1 |
| A2 | The 313 ms TrueHD discontinuity came from the MKV extractor's resync skip after the 4187 ms seek. | Q1 |
| A3 | The 251 ms discontinuity has no logged cause; the fork's audio components are ruled out. | Q1 |
| A4 | The HUD underrun row misses the client's `disabled due to previous underrun` restarts. | M3 |
| A5 | The `STHRESH` warning does nothing on API 30. | M3 |
| A6 | E-AC3 Atmos (JOC) is not detected in MKV. | Q1 |
| A7 | Round 3 addresses none of the audio-lane items. | Q1 |
| P-01 | After the IO stable poll, the result and the 1200 ms hold run on the main thread, which the HDMI re-link stalls. | S1 |
| P-02 | Track-AFR starts that miss the cache begin playing 1.8–2.7 s after the first frame. | S1 |
| P-03 | A cache-hit preflight is awaited before the player build (about 550 ms). Deferred. | Q3 |
| P-04 | The MKV Cues index parses at about 2.2 MB/s; the thumbnail worker parses it again. | S2 |
| P-05 | The 4K thumbnail sweep runs unpaced with 2 lanes on the Shield (strong box). | S2 |
| P-06 | Thumbnail work counts a paused player as idle and keeps fetching during a seek. | S2 |
| P-07 | The 4187 ms seek: a stalled read took 1249 ms to cancel, then a slow, damaged range. | S2 |
| P-08 | Stop holds the main thread 500–750 ms (memory snapshot, codec release). | S3 |
| P-09 | In scenario 03 the CPU and native-heap load more likely came from the thumbnail sweep than from tunneling. | S2 |
| P-10 | The stabilize root cause in the round-3 commit message is wrong. | D1 |
| P-11 | The pre-start settle hold is not applied on the non-tunneled start. | S1 |
| P-12 | The head and tail prewarm windows are not consumed by the default data source. | S1 |
| P-13 | Round 3 already covers the poll move, logs and swap rule. Closed. | — |
| H1 | The 750 ms delay moves the switch-back past the UI teardown but not past the HDCP condition. | R2 |
| H2 | The immediate restore when the activity is gone was not involved; prefer Skip. | R2 |
| H3 | No unprivileged leading sign; `READ_LOGS` over adb is the only direct one. Deferred. | R2 |
| H4 | Only skipping the switch-back removes the mode set at Stop. | R2 |
| H5 | 30 clean Stops cannot show the delay helps; condition the test on HDCP failures. | R2 |
| H6 | The 80 implausible-timestamp warnings before the hang are background noise. | Q3 |
| H7 | Only the composer's main thread was stuck. | D1 |

Corrections from the Codex review and from checking the code:

- (a) Fix incident 02 by reverting be0ce8ec9; nothing else reads `opaque_window`.
- (b) Home is TRANSLUCENT after profile entry, so `fmt=OPAQUE` is not an expected reading.
- (c) Hot-Stop arms A–C set `afr_restore_skip 0` explicitly; skip overrides every delay.
- (d) The detached-window branch also restores at once (`FrameRateUtils.kt:840-844`).
- (e) The Cues bulk read keeps the audio cue points.
- (f) The preflight overlap (P-03) stays deferred: `prepare()` can render through a live codec.
- (g) Stop and surface clearing already run before release; only the memory snapshot moves.
- (h) The re-entry test must hit an issued but unacknowledged restore.
- (i) The AFR work is split into reviewable commits.
- (j) The start/seek/memory lane was not skeptic-checked.

## 2026-10-15: first assessment

A workflow audited `docs/shield-todo.md` against the code, reviewed the riskiest branch code, triaged the
Media3 backports and researched known Shield issues. Skeptic agents checked the audit and the review.

- Every Nvidia default is gated by `DeviceProfile.isNvidia` (or `isShieldPro2019` for seek thumbnails),
  yields to a saved setting where one exists, and has a working revert switch (A-C01 to A-C27). The
  round-3 bullets, the every-device bullets and the "Still open" table matched the code. Nvidia gating
  covers every NVIDIA device, not only `mdarcy` (B7).

| ID | Finding | Item |
|---|---|---|
| A-M1 | `lowram_from_ram` is read once per process. | D1 |
| A-M2 | HDR video locked to Fit still cycles and saves the aspect mode. | Q4 |
| A-M3 | Four `PlayerSettings` data-class defaults ignore the Nvidia defaults. | R3 |
| A-M4 | `audio_tunneled_sub` untranslated in pt, sr, zh. | D1 |
| A-M5 | `clear_audio_rejections` ignores `0`, blank and tokens over 64 characters. | D1 |
| A-M6 | The libass *app* marker is over-conservative. | D1 |
| A-M7 | The mpv lifecycle pause does not save progress. | R3 |
| B1 | A restore in flight plus the "keep" plan leaves the new title at the UI rate. | Q2 |
| B2 | A pending restore is cancelled only when a new player plans a mode. | R2 |
| B3 | A finishing activity still restores at once on Nvidia. | R2 |
| B4 | The quiesce flips `isHdrVideo` and `tunnelingApplied` mid-switch. | Q2 |
| B5 | A large settle hold can use up the track-AFR deadline. | Q2 |
| B6 | Every-device audio changes are missing from the to-do. | D1 |
| B8 | The off-main stabilize poll and the quiesce lifecycle hold up. Closed. | — |
| C1 | 100 ms audio ready grace, app-side. | E1 |
| C2 | Display-listener leak: no upstream fix; heap dump before patching. | Q3 |
| C3 | The 1.11.0 Choreographer item is mostly in the fork. | E1 |
| C4 | cb0d86f25f fits in the rebuilt frame-release classes. | Q3 |
| C5–C11 | Further backports, only on a symptom. | E1 |
| C12 | Three 1.11.0 fixes labelled 1.11.1. | D1 |
| D1 | The Shield's own "Match frame rate" can fight in-app AFR. On SE 9.2.4 it is a one-shot quick-menu action, not a setting (device report). Closed. | — |
| D2 | No re-prepare when the HDMI sink stops accepting a bitstream mid-play. | Q1 |
| D3 | Passthrough mute or crash on seek/pause with high-bitrate TrueHD is a known Shield issue. | Q1 |
| D4 | Tunneling is inert on SE 9.2.4. No more tunneling work on `mdarcy`. Closed. | — |
| D5 | Re-handshake delay is handled by the settle hold and delayed restore. | Q2 |
| D6 | AI upscaling advice exists. Closed. | — |
| D7 | High-bitrate DV with Atmos over Wi-Fi is untested. | Q4 |
| D8 | Some users report YUV422 DV signalling (not profile 7). | Q4 |

## 2026-10-14: device report

The report ran on a debug build from before round 3 (CI run 38561775458) on SHIELD Experience 9.2.4. It is
kept locally; these are the numbers the roadmap relies on.

- **Device:** 3016 MB RAM; zram swap full at baseline (SwapFree 60 kB); app heap limit 512 MB (large
  heap). Display 3840x2160 with a 1920x1080 UI, 21 modes, UI mode 2160p 25 Hz; HDR types Dolby Vision and
  HDR10 only. The Shield's "Match frame rate" is a one-shot action, not a setting.
- **Decoders:** `OMX.Nvidia` HEVC, DOVI (profiles 4, 5, 7, 8), H.264, VP9, VP8, VC-1, MPEG-2 and others in
  hardware. No AV1 decoder. No decoder declares tunneled playback.
- **Scenarios** (HEVC HDR10 + TrueHD; DV profile 8 + E-AC3; DV profile 7 FEL + TrueHD):
  - video and app layers composed as DEVICE (overlay), no CLIENT layers; tunneling not applied;
  - click → first frame 3053, 2982 and 3389 ms;
  - AFR stabilize logged 1969 ms after the request in the first scenario, while the display had the
    mode after 383 ms; 482–562 ms in the others;
  - seek → first frame 81–4187 ms over 13 seeks in the first scenario;
  - TrueHD and E-AC3 as passthrough, 0 underruns on the HUD; two TrueHD timestamp discontinuities
    (251 and 313 ms) in the first scenario;
  - the third scenario: 43 % CPU, 387 MB native heap, 24 dropped frames and A/V jitter up to 522 ms at
    start, peak RSS 745 MB;
  - "Timestamp … seems implausible" on every frame of the first two scenarios (8886 and 6055 lines),
    none in the third;
  - one more leaked display listener per playback.
- **Home:** 867 frames, 0.23 % janky, p50 9 ms, p99 26 ms. JankStats on the player route: single frames
  up to 1442 ms at start (render thread blocked during the HDMI re-link, P-10) and 748 ms at Stop.
- **Memory:** 14 cached apps died as a 4K playback started.
- **Logs:** the 256 KiB main log buffer lost thousands of lines; start the next session with
  `adb logcat -G 4M`.
- **Incident 01:** 0.35 s after the player released, the switch back to the UI mode hung the NVIDIA
  composer HAL (`flush_work`); system_server was restarted six times by the Watchdog and only a power
  cycle recovered the box. `NvTsecHdcpReadCaps failed` had appeared 9 s before Stop. Earlier and later
  switch-backs completed.
- **Incident 02:** with "True black bars" on, the letterbox bars of a 2.40:1 title showed the previous
  screen and doubled subtitle lines. Turning the setting off fixed it; the upstream app does not do this.
