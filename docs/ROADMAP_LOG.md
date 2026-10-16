# Roadmap log

History for [`ROADMAP.md`](../ROADMAP.md): the full text of each progress-log step, the measurements and the
finding IDs the roadmap items cite. `ROADMAP.md` keeps the current status, the open work and a one-line log;
the detail is here, newest first.

Finding IDs: `A-M*`, `A-C*`, `B*`, `C*`, `D*` come from the first assessment; `W*`, `A1`–`A7`, `P-*`, `H*`
from the second. Line numbers are as of commit 8db45982c.

From 2026-10-16, roadmap steps carry stable IDs (`<item>.<n>`, for example `R2.1`), and entries from then
on name the step ID and the PR.

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
