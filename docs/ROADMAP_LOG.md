# Roadmap log

History for [`ROADMAP.md`](../ROADMAP.md): the full text of each progress-log step, the measurements and the
finding IDs the roadmap items cite. `ROADMAP.md` keeps the current status, the open work and a one-line log;
the detail is here, newest first.

Finding IDs: `A-M*`, `A-C*`, `B*`, `C*`, `D*` come from the first assessment; `W*`, `A1`–`A7`, `P-*`, `H*`
from the second. Line numbers are as of commit 8db45982c.

From 2026-10-16, roadmap steps carry stable IDs (`<item>.<n>`, for example `R2.1`), and entries from then
on name the step ID and the PR.

## 2026-11-13: R2.1 Skip the switch-back when the activity is gone (PR #65)

- [R2.1][desk] When the activity is finishing or destroyed, or the window is detached, return Skip plus
  `clearOriginalDisplayMode()` on Nvidia instead of restoring at once (`FrameRateUtils.kt:808`, `:840-844`),
  with a unit test for `displayModeRestorePlan`. Gate: test green.

- **Evidence:** `FrameRateUtilsAfrTest` ("a gone activity skips on nvidia and restores at once on other
  devices", "isActivityGone is true when finishing, destroyed, or decor is detached", and the skip-switch
  cases on non-Nvidia). Full unit gate green (9573 tests, 0 failures). Tier 2 review on PR #65: codex and
  claude both LGTM. Built by Gemini (agy).
- **Resolution:** `displayModeRestorePlan` returns Skip for a gone activity on Nvidia and Immediate
  elsewhere; the new `isActivityGone` adds a detached decor view to finishing and destroyed, so the
  delayed path no longer restores at once for a detached window. The log line names why it skipped.
  Whether a normal in-app Stop (activity alive) still hangs is left to the hot-Stop test (R2.4).

## 2026-11-12: U1.5 sync of 2026-11-11 onto upstream 1.1.0-arc.2 (PR #62)

- [U1.5][desk] Rebased `perf/shield-pass` from `1576c65e1` onto `96f0ca1bf` (1.1.0-arc.2, official NuvioTV 1.1.0):
  132 upstream commits behind (33 first-parent, among them "Merge official NuvioTV 1.1.0"), 346 fork commits. All 346
  were replayed and none dropped. The new tip is `fe3917d32`, with 351 fork commits: the 346 and 5 new ones. Tags:
  `sync/2026-11-11/old-{perf,main,base}`. Published on 2026-11-12 with one atomic leased push: `main`
  `d1d47c8ca` → `96f0ca1bf`, `perf/shield-pass` `1576c65e1` → `fe3917d32`. Upstream had moved on by 12 first-parent
  commits (to `ecaa2c379`, 1.1.0-arc.3); the next sync takes them.

**Classify (tier `fanout`, 9 analysts and a critic):** 54 files conflicted in the trial merge, among them the
protected `MatroskaExtractor.java`, `README.md` and the `strings.xml` of 38 locales. 108 fork commits touched files
upstream changed. Verdicts: 0 drops, 0 protected asks, 26 keep-resolve, 1 keep-adapt (`4f0e49b6f`), 81 keep-clean;
238 commits touched nothing upstream changed.

**Dry-run (U1.12):** 34 predicted stops, 26 real. 24 of the predicted stops came true, and 10 applied clean. Two
protected commits stopped unpredicted, `5276d2504` and `be0bb62fc`. Each conflict came only from a line this sync
added, and every fork line was kept.

**Owner's decisions:**

1. The AAC-to-DTS core transcode stays off upstream's new plain-DTS own-IEC path (5acc8e739). The transcode's
   sink format carries an id marker, and `tryDtsCoreIec` skips it (`61bc391c0`). Its buffers hold several core
   frames, which that path refuses, so every playback start fell back after an `iec_fallback_to_raw`.
2. The AAC-to-DTS-HD MA transcode is exempt from upstream's move to the 8-channel carrier, by its `Format.id`
   (`1282ea139`). It keeps the 2-channel carrier (period 2048) proven on the HT-A9.
3. Local Shield builds pass a version above the installed one (`51839059b`). Upstream's `app/build.gradle.kts` now
   falls back to 1568 / "1.1.0-nt.9.2" without `-ParcVersionCode` / `-ParcVersionName`. The fork-only
   `scripts/shield_build_install.sh` passes 1580 / "1.1.0-nt.10.shield"; `build.gradle.kts` follows upstream.
4. The old attribution trailers in earlier fork commits stay; no history rewrite for them. Five messages were
   amended for this sync's resolutions (`4f0e49b6f`, `27d752bd7`, `a54f30a56`, `bba3b6edf`, `7e632f0c3`). Each
   gained a "Sync 2026-11-11" paragraph and kept its author, date and trailers.
5. `IecDtsHdTranscodeSplitTest` configures the sink with `AacToDtsRouting.DTS_HD_OUTPUT_FORMAT`, the format the
   transcode really hands it (`b33377cb4`). This replaces "test unchanged" from decision 2: an id-less format
   cannot take the exemption, and 4 of 6 tests failed.

Settled by repo rules, with no owner question: the protected `7e632f0c3` uses its guidance's aliases, so every
protected line survives verbatim. The lossless default stays an every-box fork rule, with upstream's transcode
exception (fc879c6da) on top. `4f0e49b6f` was adapted: upstream's Hi10P route to mpv wins, the fork's duplicates
went, and `mpeg4` left `mpvHwdecCodecs` as in 8868e2efe. `27d752bd7` ports upstream's `friendlyVideoHdrType`
change into `VideoHdrLabel.kt`. A fifth new commit, `fe3917d32`, adds four fork rows to upstream's new settings
search index (b858c96aa), whose test failed without them.

**Setting decisions:** the one value this pass sets that upstream touched is the lossless default. It stays the
fork's every-box rule, with upstream's exception on top. Plain DTS on the own IEC track is a new upstream default;
the plan set no route for it, and `hbr_iec` now covers it. The settle holds needed nothing: upstream keeps its 2 s and the fork's
`afr_settle_ms` row stands. The other upstream defaults apply on the Shield with no switch. They are listed in
`docs/shield-todo.md` under "Upstream defaults taken with the sync of 2026-11-11". Four older rows there were
corrected: `hbr_iec`, `dts_hd_tv_passthrough`, the lossless default and the 2026-10-31 decoder release, now opt-in
(3412e2803).

**Evidence**

- `verify` (`1576c65e1` → `fe3917d32`): range-diff 310 identical, 36 changed, 0 dropped, 5 new. Protected lines:
  aac-to-dts 45 commits, 5565 lines, 0 lost; hlg-to-dv 107 commits, 17030 lines, 0 lost. Result: all checks pass.
- Waivers: `--allow-same` on `MpvHi10pFallback.kt` (the adapt takes upstream's file). `--allow-changed` on
  `AacToDtsRouting.kt`, `EncodedFrameCountingAudioSink.kt`, `VideoHdrLabel.kt`, `VideoHdrLabelTest.kt`,
  `IecDtsHdTranscodeSplitTest.kt`, `scripts/shield_build_install.sh` and `scripts/tests/test_upstream_sync.py`.
  `--allow-new` on the 5 new commits `61bc391c0`, `1282ea139`, `51839059b`, `b33377cb4` and `fe3917d32`.
- `judgement`: 65 paths differ from the mechanical merge, all 65 declared.
- Tests before the push: two local Gradle runs of the touched suites (426 tests, then 646 with 0 failed). The
  `ShieldBuildScript` pytest cases passed, 14 of 14. The first run's 4 failures led to decision 5.
- APK: a local build passed `protected-apk` for `libffmpegJNI.so` (with the DTS-HD MA writer) and
  `libnuvio_rpu.so`.
- Independent review: no high findings, two low follow-ups. The README had lost upstream's subtitle-font and
  TheTVDB credits; this PR restores them. The fork README has no badge row, so the licence badge stays out. The
  second follow-up is open below.
- CI: `Checks` run 43192825898 on `b33377cb4` failed the compare job. `SettingsSearchTest` was a new failure,
  fixed by `fe3917d32`. `ParallelRangeReopenAtStartTest` failed on timing; its code equals upstream's, and it passed
  locally and on a rerun. Run 43195893622 green on `fe3917d32`: no new failures, both runs complete. No device run
  is on record for this sync.

**Open, for the owner**

- Review follow-up, issue #61: `MatroskaExtractor`'s early audio scan counters (`trueHdSamplesToAnalyze`,
  `dtsTypeBlocksToRead`) are not reset when the DV2.10 retry runs the scan again.
- CI and release builds pass no version, so they get upstream's 1568 / "1.1.0-nt.9.2". Only the local script is
  fixed.

**Resolution:** the fork is on upstream 1.1.0-arc.2, and `main` mirrors `96f0ca1bf`. Upstream's 12 newer
first-parent commits wait for the next sync.

## 2026-11-10: DV4.9 A full-length HLG title through the real path

- [DV4.9][device][owner][new-build] A full-length HLG title through the real path: the dashboard button, the
  PC worker over the NzbDAV mount, the manifest served by the subtitles add-on, the URL
  `https://subs.m4ntic.tech/dv/` from DV4.8.
  Gate: the fetch lands inside the wait, every AU of a played stretch carries the manifest's L1, two seeks
  and a resume keep it (or log the fallback for AUs before the IRAP), no added dropped frames, the
  `ShotListDvRpuSource` count line in the log. Needs: DV4.8, subtitles add-on H1.4 (which needs hdr-analyze
  `mkvdovi manifest` on the PC: a v0.9.0 release or a build of main).

- **Path:** the owner pressed "Generate HLG dynamic metadata" on the subtitles add-on dashboard for a played
  HLG WEB-DL episode (8,057,515,478 bytes, 58 min, 4K25) streamed through AIOStreams. The release was on no
  mount, so it was fetched by hand from the job's stream URL to the PC; the add-on's H1.6 makes that step
  automatic. The PC worker ran `mkvdovi manifest` (CUDA) and uploaded the 903,768-byte manifest, lookup
  `87f60216ba1d…`, served at `https://subs.m4ntic.tech/dv/`.
- **Build:** the DV4.8 build (APK sha256 `7985cd4b…`, `1.1.0-nt.10`) on the Shield TV Pro 2019, toggle on
  with the default folder, setprop blank. Earlier that day the display reported HDR10 only
  (`mSupportedHdrTypes=[2]`), so `DV_CONVERT` was off and no manifest was fetched; the owner fixed the TV
  side and the next start logged `DV_CONVERT: on`.
- **Signalling:** the hvcC is 23 bytes with no parameter-set arrays; the container states transfer 18, the
  in-band SPS 14 and the alternative-transfer SEI 18. The extractor logged "HLG for 8.4 from the
  alternative transfer SEI (SPS from the first AU)" (DV2.10, DV2.11).
- **First run (19:54):** fetch 733 ms, "manifest 87f60216ba1d accepted for the track, 3840x2160"; played from
  the start, several seeks between 11:28 and 28:44 (each ready in 40–552 ms), then closed: "21151 AUs from
  the manifest, 0 from the fallback". `dropped=0` in every captured 10 s line; the lines from about 19:55 to
  20:05 rotated out of the ring buffer before the capture started.
- **Resume (20:09):** captured to a file from a cleared buffer. Resumed at 28:50, fetch 639 ms, accepted,
  then fast scrubbing towards the end and steady playback to the credits: "14707 AUs from the manifest, 0
  from the fallback". 2 single drops (`maxConsecDropped=1`), both between seeks around 55:44–57:16 with 1 s of buffer;
  0 in steady playback. The owner accepted these as not added by the manifest path.
- **Next episode:** autoplay moved to the next episode, which has no manifest: "no manifest for lookup
  5b460f9dce6f (HTTP 404)", and it played as 8.4 with the static fallback.
- **Not run:** the RPU dump (`debug.nuvio.rpu_dump` blank), so the injected L1 was not compared per AU with
  the manifest; DV4.5 showed that comparison byte for byte on the same code path.
- **Captures:** `~/shield-snapshots/20261110-dv49-s03e04/` on the Mac (not committed).

## 2026-11-08: DV4.8 A saved setting for the manifest folder (PR #58, PR #59)

- [DV4.8][desk] A saved setting for the manifest folder (owner, 2026-11-07): `debug.nuvio.dv_manifest_url`
  is lost at every Shield reboot. Add an on/off and an `http(s)://` folder URL to the player settings next to
  `convertHdrToDolbyVision` (`PlayerSettingsDataStore`, per profile), read by `ShotListManifest` at track init;
  the setprop stays as a debug override that wins when set. Gate: DataStore and settings-screen tests, red
  first; a reboot test on the Shield keeps the URL and the next HLG playback logs the manifest fetch. Needs:
  DV4.1.

- **Setting (PR #58):** Player settings → DV/HDR gains "Scene metadata from a manifest folder" and
  "Manifest folder" (`http(s)://`, no query or fragment), per profile and device-local (not synced),
  greyed unless the HLG/HDR10+ conversion is on. A non-blank `debug.nuvio.dv_manifest_url` still wins.
  Reviewed by Codex and Gemini.
- **Default folder (PR #59, owner 2026-11-08):** typing a URL on the Shield remote is slow and the owner
  is the only user, so the folder defaults to the subtitles add-on's `https://subs.m4ntic.tech/dv/`
  (`DvManifestFolder.DEFAULT_URL`); saving an empty folder restores it. The toggle still starts off.
  `DvManifestSettingTest` was red first (no default) and the 15 DV manifest unit tests pass.
- **Reboot gate:** local build of the PR #59 branch (APK sha256 `7985cd4b…`, cert `c22ec9…`) on the
  Shield TV Pro 2019. The owner turned the toggle on with the default folder; `adb reboot`, setprop
  blank after boot. The owner saw the toggle and folder kept, then played an HLG WEB-DL episode streamed
  through AIOStreams: `DvShotList` logged "manifest folder from the player setting" and then "no manifest
  for lookup 87f60216ba1d (HTTP 404)", so the playback used the static fallback. The add-on folder
  answers; it held no manifest for that release.
- **Not run:** a fetch that returns a manifest from the add-on (DV4.9).

## 2026-11-07: DV4.5 A local sidecar end to end, on the device (PR #55)

- [DV4.5][device][owner][new-build] A local sidecar end to end. Gate: every AU of the title carries the
  sidecar's L1 (debug log), no added dropped frames, the owner's G-E reading. Needs: DV4.1, DV1.8.

- **Build and chain:** local build of `a90ec160a` (APK sha256 `87fd2b91…`), Shield TV Pro 2019 → LG OLED
  C9, decoder `OMX.Nvidia.DOVI.decode`, "Convert HLG and HDR10+ to Dolby Vision" on. The title is DV1.8's
  D1 source: the plain 1-minute 4K25 HLG documentary cut of the hdr-analyze dev corpus (1526 pictures, not
  Dolby Vision), added to the owner's Jellyfin test library as "D1 HLG source" and played from there.
- **Manifest:** hdr-analyze main at `f318efe` (P15.5), `mkvdovi manifest` with the defaults (8.4
  bt2100, CM v4.0, accurate quality) on the Mac's CPU: 87 s, 14 shots over 1526 pictures, coverage
  `full`, `l1_policy` measured. Its `lookup_key` (`a083a508…`) was recomputed from the file on the
  Jellyfin host and matched. Served as `<lookup_key>.dvmanifest.json` from a plain HTTP folder on the
  Mac on the LAN (`debug.nuvio.dv_manifest_url`); `debug.nuvio.rpu_dump` 6000.
- **Run A, manifest on, from frame 0 to the end:** the fetch took 458 ms (17220 bytes), the manifest was
  accepted for the track, and `DvShotList` logged "1526 AUs from the manifest, 0 from the fallback". The
  dump (`rpu_dump_to_bin.py --order pts`, 1524 records; the last anchor's 2 B-frames fall after the
  gap) equals the RPUs of DV1.8's `mkvdovi` 8.4 file from the same source: `rpu_l1_compare.py` 0 L1 and
  0 other differences. Against the manifest's shots, avg and max are equal on every frame; min is 12 on
  every frame, because the builder clamps L1 min to at most 12 as `dovi_tool` does
  (`dovi_rpu_builder/src/static_l1.rs:21`), while the manifest stores the unclamped value.
- **Run B, manifest off (static 8.4), same file from frame 0:** every dumped RPU carries the static L1
  (0, 1283, 3079), as expected.
- **Dropped frames:** every 10 s `BUFFER:` line of both runs shows `dropped=0 maxConsecDropped=0
  skipped=0`, one `counterScope` per run.
- **G-E (owner, LG C9):** run A, with the manifest's per-shot L1, looked better than run B with static L1.
- **Not run:** seeks, a resume or a start inside a shot with the manifest, and a server on the owner's
  Oracle host.
- **Found on the way:** the Shield reaches the Oracle host only through its public reverse proxy, not
  its private or tailnet address, so serving manifests from Oracle needs a proxy route (DV4.3). Oracle's
  `mkvdovi` and `hdr-analyze` builds predate the `manifest` command. The Jellyfin test library held only
  the converted D1 and D2 files; the plain HLG source now sits beside them.
- **Session:** M2 snapshot and restore clean (`~/shield-snapshots/20261107-1409-dv4-5/`, local only:
  DRIFT 0, OWNER 0; the one INFO line is the installed APK, now the build above), with the logcat, the
  manifest, both rebuilt `RPU.bin` files and the reference.

## 2026-11-07: M3.3 Video frame counters on the BUFFER line (PR #54)

- [M3.3][desk] Log `droppedBufferCount`, `skippedOutputBufferCount` and `maxConsecutiveDroppedBufferCount`
  with the 10 s `BUFFER:` line.
  - `onDroppedVideoFrames` fires only after 50 drops (`PlayerRuntimeControllerInitialization.kt:3462`), so
    single drops never reach logcat today.
  - Gate: unit test.

- **Evidence:** `PlaybackDecoderCounterFieldsTest` (5: a single drop below the callback threshold, a
  replaced renderer, tunnelled output, modes without counters, no video). Full unit gate green (8550
  tests). Tier 1 review on PR #54: codex 1 P1 (tunnelled output left out the skipped count), FIXED with
  the test red first.
- **Resolution:** `PlaybackDecoderCounterFields` appends `dropped=`, `maxConsecDropped=`, `skipped=` and
  `counterScope=` for MediaCodec and tunnelled output, and `frameCounters=<mode>` for native or
  unsupported output. The counts are cumulative per renderer; `counterScope` changes when a new renderer
  starts them again from zero. This gives DV4.5 its "no added dropped frames" evidence in logcat.

## 2026-11-06: DV1.8 G-C, precise half, on the device (PR #53)

- [DV1.8][device][owner][new-build] G-C, precise half (DV1.4 closed on the owner's eye check): the
  `mkvdovi` files (8.4 bt2100 and canary, 8.1 from HDR10 and HDR10+, the L1 variants) played through
  Nuvio with the DV2.8 dump on, from the start, from a mid-file resume point and with seeks. Gate: the
  dumped RPUs validate with `dovi_tool info` and equal the files' RPUs; the owner's TV readings and the
  playback chain in the log; the G-C rows of the qualification matrix set. Needs: DV2.8.

- **Build and chain:** local build of `6009d0979` (APK sha256 `5459d21c…`, cargo-ndk `libnuvio_rpu.so`),
  Shield TV Pro 2019 → LG OLED C9. Files from the owner's Jellyfin on the Oracle host, opened in Nuvio's
  Jellyfin source. Decoder `OMX.Nvidia.DOVI.decode`; the display manager logged a Dolby Vision source.
  `debug.nuvio.rpu_dump` 6000, so each playback dumped every AU it queued.
- **Files made for this step:** `mkvdovi` 0.8.0 (binary sha256 `d2d1eb2f…`), `dovi_tool` 2.3.4, from 1-minute
  4K25 cuts of the hdr-analyze dev corpus: D1 = HLG documentary → 8.4 (`--hlg-composer bt2100`, 1526
  frames, CM v4.0, L9 BT.2020); D2 = HDR10+ Profile B series → 8.1 (`--hdr10plus-l1 hdr10plus`, 1552
  frames, L9 P3-D65). Reference RPUs from `ffmpeg -c:v copy -bsf hevc_mp4toannexb | dovi_tool extract-rpu`.
- **Method:** `rpu_dump_to_bin.py` rebuilt one `RPU.bin` per generation (start, each seek, each resume),
  and each record was matched to the reference frame with the same PTS. `dovi_tool extract-rpu` writes
  display order, so reference entry k belongs to the k-th smallest packet PTS (a decode-order mapping
  shows false differences on every B-frame).

| File | Sessions | AUs dumped | Equal to the file's RPU | Coverage | Owner on the TV |
|---|---|---|---|---|---|
| D1, 8.4 bt2100 from HLG | 2 | 2020 | 2020 | start from frame 0, 5 seeks, a second start | Dolby Vision, neutral colour |
| D2, 8.1 from HDR10+ | 4 | 2165 | 2165 | start from frame 0, 3 seeks, three more starts | Dolby Vision, all fine |
| Native Profile 8.1 web series, 3-minute cut | 1 | 2224 | 2224 | start from frame 0 (1671 AUs), 8 seeks, resume at 2:04 | Dolby Vision, all fine |

  All 24 rebuilt generations parse with `dovi_tool info`; no record was lost or failed its CRC.
- **Earlier readings counted toward the step** (hdr-analyze `docs/ROADMAP_LOG.md`, 2026-11-04, P14): an
  8.1 canary (chroma constants −1/16) turned the picture green on the C9 in Nuvio over Jellyfin, and the
  owner judged three dynamic-contrast strengths of one title. Those variants change the reshaping as
  well as L1/L3, and their RPUs were not dumped. DV1.4 (2026-10-23) is the eye check of the `mkvdovi`
  8.4 bt2100 and canary files.
- **Not run:** no `mkvdovi` file from plain HDR10, no variants that differ in L1 alone, and no 8.4 canary
  file (`mkvdovi` 0.8.0 offers only the `preset` and `bt2100` composers). The owner closed the step
  without them: every HDR10+ and HLG file tested so far played correctly.
- **Session:** M2 snapshot and restore clean (`~/shield-snapshots/20261106-1206-dv18/`, local only), with
  the logcat, the per-generation `RPU.bin` files and the references. adb first failed with "No route to
  host" while `nc` reached the port: macOS Local Network privacy blocked the terminal host app.

## 2026-11-06: DV4.1 Shot-list provider in the fork (PR #52)

- [DV4.1][desk] Shot-list provider in the fork: load a manifest from a file or URL, check identity
  incrementally as AUs are read, inject in the extractor. Gate: unit tests; a different encode of the
  same title is rejected. Needs: DV4.4, DV4.6, DV4.7, DV2.2, DV2.3; for HLG also DV2.4. The manifest is
  found by `lookup_key` (size plus SHA-256 of the first and last MiB, two range reads through the
  existing DataSource stack) on a configured HTTP folder (the owner's Oracle host). The 8.1 path needs a
  fallback RPU for AUs before the first validated IRAP and after a rejection, so it waits for DV3.3; HLG
  first. Tests can use hand-made manifests before P15.3 lands.

- **Evidence:** `ShotListManifestTest` (12: the `lookup_key` against digests computed with Python's
  hashlib, the two reads, a chunked server through `Content-Range`, a missing manifest, the wait, a
  stalled fetch that does not hold up the next file), `ShotListDvRpuSourceTest` (15: every check, a
  different encode refused at the source or track check and rejected by the matcher mid-stream, the
  drop kept across a second prepare) and three real-extractor cases in `MatroskaRpuInjectionTest`
  (the track identity, Tracks before Info, AUs with the manifest's RPUs until a rejection). Full unit
  gate green (8545 tests). Tier 2 review on PR #52: codex 4 P1 findings, all FIXED with tests red first;
  gemini LGTM.
- **Resolution:** opt-in by `debug.nuvio.dv_manifest_url` (an HTTP or `file://` folder) and
  `debug.nuvio.dv_manifest_wait_ms` (3 s). `ShotListManifests` reads the identity through the playback
  `DataSource` stack and fetches `<lookup_key>.dvmanifest.json` without the stream's headers;
  `ShotListDvRpuSource` checks source, track and composer, and `ShotListTrackRpus` feeds every AU to the
  matcher, falling back to the static 8.4 RPU with a provider switch. 8.1 waits for DV3.3; the
  single-video-track check is not made (DESIGN 2.8).

## 2026-11-06: DV4.4 The playback manifest in hdr-analyze (hdr-analyze PR #96)

- [DV4.4][desk] In hdr-analyze, the playback manifest of `DESIGN.md` 2.8 around the L1 payload, written
  by `mkvdovi` and accepted for imported `RPU.bin` files. Gate: tests with variable frame rate, a
  non-zero start, an open-GOP start, a different encode and a mismatched composer. Needs: DV2.1.
  Done when hdr-analyze P15.3 passes. The `RPU.bin` import (P15.4) is deferred (owner, 2026-11-05):
  the owner's flow makes measured manifests only.

- **Evidence:** hdr-analyze P15.3 merged as its PR #96 (2026-11-05). Its gate, logged in hdr-analyze
  `docs/ROADMAP_LOG.md`: VFR, a non-zero start and open GOP accepted; builder RPUs from the manifest
  equal mkvdovi's `--keep-source` output byte for byte for 8.4 bt2100 and 8.1; a different encode and a
  mismatched composer rejected; `extra.json` goldens and RPU baselines unchanged. Closed in the DV4.1 run
  of 2026-11-06, which found the fork's line still open.
- **Resolution:** the fork's `nuvio_rpu` pin (`eb727a0`, DV4.6) differs from hdr-analyze main only by
  three lines in `dovi_rpu_builder/src/lib.rs`, so it parses P15.3's manifests. hdr-analyze P15.5 (an
  FFmpeg-cut open-GOP file with negative leading timestamps is refused by the producer) is open there
  and does not block the player. P15.4 (`RPU.bin` import) stays deferred.

## 2026-11-06: DV4.7 Extractor inputs for the matcher (PR #51)

- [DV4.7][desk] Extractor inputs for the matcher: raw Matroska ticks (Cluster timecode + block timecode,
  not rounded µs), the block's lace count and a decode index per AU, passed to `TrackRpus.next`; and the
  rule for a rejection inside a shot (DESIGN 2.2, refresh on the lowest-pts AU queued after it). Gate:
  real-extractor tests, red first, including a rejection on a future reference followed by earlier
  B-pictures. Needs: DV4.6.

- **Evidence:** three `MatroskaRpuInjectionTest` cases through the vendored extractor, red before the
  change (ticks came out as rounded µs, the decode index stayed 0, B5 had no refresh): raw ticks at a
  TimestampScale of 11 111 ns with a negative block offset and a laced block; the decode index restarting
  at a seek; a rejection on P8 decoded before B6, B5 and B7, where B5 (first fallback picture on screen)
  carries the refresh. Two `ConvertedTrackInjectorTest` cases: a seek ends the window; a refused AU keeps
  its decode index. Full unit gate green (8515 tests). Tier 1 review on PR #51: codex LGTM.
- **Resolution:** `AuInput` carries the presentation time, ticks (Cluster plus signed block timestamp,
  one value for every frame of a laced block), lace count and decode index (per extractor generation,
  counted in `ConvertedTrackInjector`). `AuRpu.providerSwitch` marks the first fallback AU; the injector
  refreshes it and every later AU presented before all AUs written since, until a seek (DESIGN 2.2).
  The DV4.1 provider passes `ticks` as the matcher's pts and `laceCount > 1` as `laced`; the matcher
  keeps its own decode index (hdr-analyze `matcher.rs:150`).

## 2026-11-05: DV4.6 JNI bridge to hdr-analyze's playback manifest (PR #50)

- [DV4.6][desk] JNI bridge to hdr-analyze's `dovi_rpu_builder::manifest` (P15.2): bump the `nuvio_rpu`
  pin, expose parse, `check_source`, `check_track` and `AuMatcher::next` / `reset` with their stable codes.
  Gate: JVM tests through a fake native plus a device-free Rust test of the bridge; the APK still builds.
  Needs: hdr-analyze P15.2 merged.

- **Evidence:** `native/rpu_builder`: `cargo test` (31) and `cargo test --features jvm-tests` (43, a real JVM
  with `-Xcheck:jni`, every new `Java_*` export called on a manifest built in code); Kotlin
  `DoviRpuBuilderTest` (13) and `PlaybackManifestSessionTest` (8, through a fake native); full unit gate
  green (8510 tests); the arm64 `libnuvio_rpu.so` builds (665656 bytes, 17 `Java_*` exports, 16 KB
  aligned) and the APK assembles. Tier 2 cross-review on PR #50: codex and gemini, both LGTM.
- **Resolution:** the pin moved from `f55c779` to `eb727a0` with all 32 fixtures unchanged. Eight exports
  wrap `PlaybackManifest::parse`, `check_source` (plus the `lookup_key` comparison the crate leaves to the
  player), `check_track` (the Matroska time base reduced to lowest terms), `check_composer`, the
  manifest's `RpuBuilder` and `AuMatcher::new` / `next` / `reset`. Builders, manifests and matchers share
  one handle counter, so a wrong-kind handle is code 21; crate refusals are code 8 and the reject reasons
  1 to 6 come in the `long[9]` verdict. Calls and codes: `native/rpu_builder/README.md`.

## 2026-11-04: U1.5 sync onto upstream 1.1.0-nt.10 (PR #47)

- [U1.5][desk] Rebased `perf/shield-pass` from `17b2029e3` onto `d1d47c8ca`: 122 upstream commits behind (14
  first-parent, among them the merge of official 1.1.0-beta.10), 320 fork commits. The new tip is `3ec934aae`, with
  321 fork commits. Tags: `sync/2026-11-04/old-{perf,main,base}`. `sync.py due` said `wait` (the official merge was
  2.8 h old); the owner synced anyway.

**Classify (tier `fanout`, 9 analysts and a critic):** 3 files conflicted in the trial merge, one of them the protected
`MatroskaExtractor.java`; 83 fork commits touched files upstream changed. Verdicts: 0 drops, 0 protected asks,
3 keep-resolve, 0 keep-adapt, 80 keep-clean; 237 commits touched nothing upstream changed.

**Dry-run (U1.12):** 3 predicted stops, 3 real: `c0ff750d9` (`MetaRepositoryImpl.kt`: upstream's `flightKey` with the
fork's lazy start), `e034d8f03` (pt-BR strings: upstream's moved line first, then the Shield notes) and the protected
`42f4e5ac1` (`MatroskaExtractor.java`: upstream's alternative-transfer SEI block, then the fork's logging block).

**Owner's decisions:** none were needed. The one new commit, `3ec934aae`, is test-only. Upstream's SEI peek
(553548679) logs through Media3's `Log`, which calls `TextUtils.isEmpty`, a stub on the JVM. Nine HLG SEI tests failed
on a native track, so the two test classes now mock `TextUtils`, as the zlib case already did.

**Evidence**

- `verify`: 317 identical, 3 changed, 0 dropped, 1 new. Protected lines on `299f598ad` (before the test commit):
  aac-to-dts 45 commits, 5951 lines, 0 lost; hlg-to-dv 99 commits, 14750 lines, 0 lost. On `3ec934aae` with
  `--allow-new 3ec934aae` and `--allow-changed` on `MatroskaSampleSeiTest.kt` and `MatroskaSeiHlgScanTest.kt`:
  13958 hlg-to-dv lines, 0 lost. The waiver leaves the two test files out of the line check; the test commit only
  adds lines to them (23 added, 0 removed).
- `judgement`: 5 paths differ from the mechanical merge, all declared: the 3 resolutions and the 2 test files.
- APK: `protected-apk` passes for `libffmpegJNI.so` and `libnuvio_rpu.so` (tree `3e8e2e9cb`) on `299f598ad`; the
  test commit changes no APK input.
- Independent review: all 3 resolutions are correct, and no fork hunk in those files was lost, by comparing each
  commit's added and removed lines before and after the rebase. Upstream's peek runs only on a track the fork leaves
  native, so a converted track still publishes only Dolby Vision formats.
- CI: `Checks` run 41829298674 on `299f598ad` failed the compare job with the 9 new failures above. Run 41832356577
  green on `3ec934aae`: no new failures against upstream's baseline, both runs complete. Locally the `dvmkv` suite
  passed, 143 tests. Not run on the Shield.

**Open, for the owner**

- The fork's DV2.4 block (`42f4e5ac1`) and upstream's peek both read the first sample's alternative-transfer SEI.
  The fork's log could reuse upstream's peek. The fork's block is protected, so it stays as it is for now.
- On a native HEVC track with HLG only in a first-sample SEI, upstream now publishes the format again as HLG. The
  fork's decision at track init stays as it was. It is not checked on the TV.

**Resolution:** the fork is level with upstream 1.1.0-nt.10, and `main` mirrors `d1d47c8ca`.

## 2026-11-04: M1.2 `shield_measure.py` and its Shield dry run (PR #45)

- [M1.2][desk][device] Write `scripts/shield_measure.py`. It takes `--serial` and wraps every adb call in a
  timeout. It runs D-pad journeys with `input keyevent` (Home row, rows down, sidebar, details, settings) and
  reads `dumpsys gfxinfo <pkg> reset` / `framestats`, `am start -W` and a JankStats summary. For playback it
  reads click → `PLAYBACK_STARTED`, seek → first frame, drops, underruns and `restartIfDisabled`. On a daily
  app it never compiles, force-stops or clears: it measures a cold start only when the app is not running. No
  `dumpsys meminfo` during audio (it caused an underrun restart in the report). The details journey reads the
  `DetailEntryTrace` stages the app already logs (`viewmodel_setup` to `controls_focus`, tag `NuvioDetails`).
  Gate: dry run against the Shield. Needs: M2.1. Landed: PR #44; gate pending.

- **Evidence:** dry run of the merged script on `com.nuvio.tv.test.shield` (the installed local build of
  55f446193), in `~/shield-snapshots/20261104-1245-m1-2-dry-run-merged/`. The run on the night of 2026-11-03 used the
  head before the review fixes, so it did not count. M2 snapshot first; `shield_restore.sh` verdict clean
  (drift 0, owner 0). Every command exited 0, also a first playback run that measured nothing (CENTER
  opened the stream picker; its `missing` named the gap). No Watchdog or `flush_work`.
- **Cold start** (`start --force-cold`): 2406 ms (`am start -W` TotalTime, COLD).
- **UI** (`ui --journey all --shots --details-trace`, one repeat). Each shot shows Home with focus on the
  first Continue watching card.

  | Journey | Frames | Janky % | p50 ms | p90 ms | p99 ms | JANK lines |
  |---|---|---|---|---|---|---|
  | home-row | 377 | 2.39 | 10 | 21 | 61 | 8 |
  | rows-down | 134 | 0.75 | 9 | 21 | 32 | 1 |
  | sidebar | 84 | 11.9 | 14 | 48 | 150 | 8 |
  | details | 84 | 13.1 | 7 | 57 | 150 | 11 |
  | settings | 66 | 15.15 | 13 | 97 | 350 | 11 |

- **Details stages missing.** The details journey (DOWN, CENTER) lands on the second Home row, which on this
  Home is a collection: CENTER opens `folder_detail`, not a title's details, so `DetailEntryTrace.begin()`
  (`NuvioNavHost.kt:245`) never runs. The device logcat had no NuvioDetails line; a second details-only run
  gave the same. The script's `missing` reason names only "trace off" or "no DetailEntryTrace". This is a
  Home-layout fit problem for M1.5, like the one-card Home.
- **Playback** (`playback --seeks 3`): a 4K Dolby Vision, DD+ 5.1 episode, started from Continue watching
  and its stream picker. Click → `PLAYBACK_STARTED` 3537 ms (first frame → playing 613 ms, AFR track hold
  off). Seek → first frame 75, 66, 69 ms (median 69, all `via=first_frame`, none READY-only). Dropped frames 3;
  `restartIfDisabled` 0; freezes 0. IEC underruns null: DD+ does not use the packer.
- **Not covered:** a daily app (`com.nuvio.tv.test`), the upstream app, and repeats; those are M1.4.
- **Resolution:** the script landed in PR #44 and its gate is met here (PR #45). The details stages wait for
  M1.5, whose step now names the details journey.

## 2026-11-03: Q2.5 settle hold default (PR #43)

- [Q2.5][device][owner] Settle hold 600 against 1200 ms, three starts each of a TrueHD title whose rate
  differs from the UI mode. Gate: no AudioSink write errors, no `restartIfDisabled`, picture and sound locked;
  default decided.

- **Evidence:** the Q2.5 table in "Q1.2, Q1.3 and Q2.5 device session" below
  (`~/shield-snapshots/20261103-1131-q1-q2-session/`): a 25 fps DTS-HD MA title stood in for TrueHD; unit test
  `TrackAfrSettleHoldTest`; full unit gate green (8107 tests).
- **Resolution:** the owner chose 600 ms on Nvidia; other boxes keep 2 s and `debug.nuvio.afr_settle_ms` still
  overrides it.

## 2026-11-03: Q1.2 `hbr_iec` default (PR #42)

- [Q1.2][device][owner][new-build] `hbr_iec` 0 against 1 on a TrueHD Atmos title and a DTS-HD MA title
  (tunneling off), each with a seek and a pause: upstream 1.1.0-nt.7 keeps the TrueHD stream alive through
  them on the packer path only (3aeb1e180). Record the AVR indicator and whether it relocks, `HBR active
  payload=` against `HBR DIRECT`, `iec_keepalive_armed`, and `restartIfDisabled`. Gate: readings in the log; default decided. Needs: Q1.1.

- **Evidence:** the readings table and the AAC check in "Q1.2, Q1.3 and Q2.5 device session" below
  (`~/shield-snapshots/20261103-1131-q1-q2-session/`, `20261103-1303-q12-aac-check/`); unit test
  `SystemPassthroughDefaultTest`; full unit gate green (8107 tests).
- **Resolution:** the owner chose the packer. "System passthrough" reads as off when nothing is saved, as
  upstream has it; a saved choice and `debug.nuvio.hbr_iec` still win. Q1.1's log renames had not landed;
  `HBR RAW` stood in for `HBR DIRECT`.

## 2026-11-03: Q1.2, Q1.3 and Q2.5 device session

Chain: Shield (HEAD 36dcd7046 debug build, 1.1.0-nt.9.2) → Sony HT-A9 → LG OLED C9; UI at 3840x2160 23.976 Hz
(mode 21); ExoPlayer, tunnelling off. Snapshot and logcat: `~/shield-snapshots/20261103-1131-q1-q2-session/`;
the restore ended clean (drift 0).

- [Q1.3][device][owner] E-AC3 Atmos: play an E-AC3 JOC stream and record the AVR indicator. Gate: reading in
  the log.
  - **Done.** E-AC-3 passthrough, 6 ch, 48 kHz (`AUDIO_START … Passthrough (E-AC-3)`; the HAL logs "treat the
    format EAC3_JOC as EAC3"). The HT-A9 showed **Dolby Atmos**; the owner heard no fault. The HT-A9 shows the
    same "Dolby Atmos" for TrueHD Atmos, so its indicator cannot tell the two apart.
- [Q1.2] `hbr_iec` 0 against 1, each arm with one seek and at least one pause:

  | Title | `hbr_iec` | Path in logcat | HT-A9 | Seek ready | Pause | `restartIfDisabled` | Owner |
  |---|---|---|---|---|---|---|---|
  | TrueHD Atmos, 23.976 | 0 | `HBR RAW` (platform) | Dolby Atmos | 86 ms | 2, clean | 0 | fine |
  | TrueHD Atmos, 23.976 | 1 | `HBR active payload=IEC_BURST` | Dolby Atmos | 323 ms | 2, keep-alive filler up to 1487 bursts | 0 | fine |
  | DTS-HD MA, 25 fps | 1 | `HBR active payload=IEC_BURST` | DTS-HD MSTR | 94 ms | 2, clean | 0 | fine |
  | DTS-HD MA, 25 fps | 0 | `HBR RAW` (platform) | DTS-HD MSTR | 43 ms | stutter on resume | 3 (start, seek, resume) | stutter |

  - Packer: 0 underruns on every `iec_health` line. Keep-alive arms for TrueHD only; DTS-HD keeps about 1 s queued
    against about 0.25 s for TrueHD. On the platform path the DTS-HD audio ran on 224 ms after the pause, and the
    HAL reconfigured about 0.8 s after the resume.
  - **Decision (owner):** the packer becomes the default ("System passthrough" off unless saved), on
    `fix/Q1.2-packer-default`. It merges after an AAC title plays as DTS-HD MSTR on that build, because the
    protected AAC→DTS-HD MA transcode then takes the packer route (part of Q6.11).
  - **AAC check passed** on the `fix/Q1.2-packer-default` build (a61c9c99e), no debug switch set: an AAC title
    (Blue Lights) went out as `HBR active payload=IEC_BURST mime=audio/vnd.dts.hd` at 192 kHz, 2 ch IEC; the
    HT-A9 showed DTS-HD MA. 0 underruns, 0 `dtshdOverflows`, no `restartIfDisabled`, seek ready in 88 ms. The
    owner has played many AAC titles this way without fault. Snapshot: `20261103-1303-q12-aac-check`.
  - Q1.1's log renames did not land first; `HBR RAW` is the line Q1.1 renames to `HBR DIRECT`.
- [Q2.5] Settle hold 600 against 1200 ms, three starts each. No TrueHD title off 23.976 was at hand, so
  the 25 fps DTS-HD MA title (H.264 1080p) stood in, on the packer path. Each start switched 23.976 → 25 Hz;
  Android reported the mode settled in 846–965 ms.

  | Hold | Click to playing (ms) | AudioSink errors | `restartIfDisabled` | HT-A9 |
  |---|---|---|---|---|
  | 600 | 3453, 3524, 3378 | 0 | 0 | DTS-HD MSTR ×3 |
  | 1200 | 3694, 4216, 4554 | 0 | 0 | DTS-HD MSTR ×3 |

  - With both holds the LG C9 showed no picture for 2–4 s after the switch while sound played: the TV relocks
    HDMI after Android reports the mode settled. The owner does not count it as a fault.
  - **Decision (owner):** 600 ms on Nvidia, on `fix/Q2.5-settle-600`.
- Side findings, now Q2.6 and Q2.7: a 24.000 fps E-AC3 title stayed at mode 21 (23.976 Hz) after AFR asked for
  24 Hz; and the preflight "AFR settle complete" line logs after `PLAYBACK_STARTED`.

## 2026-11-03: U1.1 dropped, merge-into-main entry removed (owner)

- [U1.1][owner] Ask the upstream maintainer whether Shield work is wanted, and whether as one pull request per
  theme. Official NuvioTV (`NuvioMedia/NuvioTV`) merged 284 of its last 400 PRs and ysosrs merged 1 of 4, so
  try one small every-device fix on official first, though 18 of 20 candidates need porting there. Gate: the
  answer recorded in the log.
- **Resolution:** dropped by the owner on 2026-11-03 as not relevant. U1.3 no longer needs it.
- The "Merge into `main`" entry left the owner's list the same day. Since U1.7 (PR #25), `main` mirrors
  upstream at the last synced base and `perf/shield-pass` is the default branch, so there is nothing to merge.

## 2026-11-03: U1.5 sync onto upstream 1.1.0-nt.9.2 (PR #41)

- [U1.5][desk] Rebased `perf/shield-pass` from `105b247e4` onto `ccfd1d7b5`: 22 upstream commits behind, 304 fork
  commits. The new tip is `0941cd2b8`, with 304 fork commits. Tags: `sync/2026-11-03/old-{perf,main,base}`.
  `sync.py due` said `wait` (upstream quiet for 10 h); the owner synced anyway.

**Classify (tier `fanout`, 9 analysts and a critic):** 5 files conflicted in the trial merge, one of them the protected
`MatroskaExtractor.java`; 83 fork commits touched files upstream changed. Verdicts: 0 drops, 0 protected asks,
8 keep-resolve, 1 keep-adapt, 74 keep-clean; 221 commits touched nothing upstream changed.

**Dry-run (U1.12):** 9 predicted stops, 6 real. The protected `ff413586a`, `95422f7e4` and `71f5a813c` applied cleanly
once `ed475941c` placed upstream's `DolbyVisionBaseLayerColor` import after the fork's imports.

**Owner's decisions:** both went into the rebased commits.

- `770b9c65d` (was `d6b53c406`, owner rule, keep the fork's value): upstream's `MemoryGovernor.afterHardwareClose`
  (f28fb06c6) lifted a swap refusal without `swapRoom`. Its public overload now returns early when `swapRoomHardware`
  is off, with a fourth `SwapRoomEntryPointsTest` case. On `mdarcy` the lift is off by default; it needs a low-RAM box,
  so it never ran there after init.
- `729cfbb39` (was `f12a8792d`): `pausedAtFirstFrame` reads `pausedByUser`. Upstream's 23ed72cfc made
  `userPausedManually` true during a thumbnails hold too.

**Evidence**

- `verify`: 286 identical, 18 changed, 0 dropped, 0 new. Protected lines: aac-to-dts 45 commits, 5951 lines, 0 lost;
  hlg-to-dv 99 commits, 14750 lines, 0 lost. Waived: `--allow-changed` on `PlayerRuntimeControllerPlaybackStarted.kt`
  and `SwapRoomEntryPointsTest.kt`, the two owner decisions.
- `judgement`: 8 paths differ from the mechanical merge, all declared resolutions.
- APK: `protected-apk` passes for `libffmpegJNI.so` and `libnuvio_rpu.so`. The main checkout had no
  `libnuvio_rpu.so.stamp`, so the library was rebuilt in the worktree (tree `3e8e2e9cb`).
- Independent review: no fork hunk lost and no upstream change lost, by a per-file line-set comparison over the 64
  files both sides changed. Its one finding was a wrong "default unchanged" note on `d6b53c406`, corrected above.
- CI: `Checks` run 41541317062 green on `0941cd2b8`: no new failures against upstream's baseline, both runs complete.
  Not run on the Shield.

**Open, for the owner**

- Upstream's 7abbc5ee3 adds its own colour helper (`DolbyVisionBaseLayerColor`) for Dolby Vision played as its HDR10
  base layer. It does not overlap the fork's HDR10/HLG to Dolby Vision conversion, which runs only without a dvcC.
- Upstream's ccfd1d7b5 can defer the player open behind the thumbnails screen. By reading, it is safe with the
  track-AFR hold; it is not checked on the Shield.

**Resolution:** the fork is level with upstream 1.1.0-nt.9.2, and `main` mirrors `ccfd1d7b5`.

## 2026-11-02: M1.1 PLAYBACK_STARTED stage (PR #40)

- [M1.1][desk] Log a `PLAYBACK_STARTED` stage with the time since the click, next to the first frame, so the
  playback baseline measures what the viewer sees. Gate: unit test on the stage helper.

Resolution: `PlaybackStartedStage` logs `PLAYBACK_STARTED: clickToPlayingMs=… firstFrameToPlayingMs=…
afrTrackHeld=… pausedAtFirstFrame=… seq=…` to logcat tag `TTFF_STAGE` and the playback report buffer, once
per playback session, at the first ExoPlayer `isPlaying` after the first frame. The first frame alone hides
the track-AFR hold (1.8–2.7 s, S1). A later session on the same player (next episode) reports
`clickToPlayingMs=-1`, because the click clock does not re-arm. ExoPlayer only, by the owner's scope; mpv is
not wired. Evidence: `PlaybackStartedStageTest` (13 cases) and the full gate
`:app:testFullDebugUnitTest` (7797 tests, 0 failures). Review: Gemini (substitute for Codex, which was at
its usage limit), LGTM.

## 2026-11-02: U1.9 CI is the gate (PR #36)

- [U1.9][desk] CI is the gate: Checks runs on pushed sync candidates with an upstream baseline job and a
  compare job that fails on new or incomplete results; `shield_build_install.sh` runs `protected-apk` on every
  local build. Gate: a scratch candidate with an injected failure, and one with a crashed test run, fail.

Resolution: passed on three scratch candidates dispatched on 2026-10-31, before the rebase onto PRs #34 and #35
(the rebase did not change the compare code):

| Candidate | Run | Compare with upstream |
|---|---|---|
| `sync/ci-trial-clean` | 41249304077 | success |
| `sync/ci-trial-inject` | 41249306321 | failure: `new failure: com.nuvio.tv.CiTrialInjectedTest.injectedFailure` |
| `sync/ci-trial-crash` | 41249308132 | failure: the test JVM crashed, no JUnit XML, Gradle exited 1 with no failed test |

The same PR landed U1.12 (`sync.py` `dry-run`, `due`, `official-preview`, `judgement`, `docs-entry`, the test heap
hint, the rebase in one subagent; 289 script tests pass) and the U1.15 triage workflow. Both keep their lines with
the gate pending: U1.12 needs the next sync to use the tools, and U1.15 needs a passing blind test and a shadow run.

Review finding F1 (Codex, NEEDS-HUMAN): `compare_results` lets a candidate pass with up to 5 % of upstream's test
classes missing, recorded only as a note. With 99 of 100 classes the comparison returned no problem. The test
`test_one_upstream_class_absent_from_the_candidate_is_only_a_note` asserts that tolerance, so the owner decides.

## 2026-11-02: U1.14 Behavioural tests for fork attachments to upstream paths (PR #35)

- [U1.14][desk] Behavioural tests for how fork code attaches to upstream paths: the IEC route key on a live
  source switch, `swapRoom` through the public entry points, and Tracks-after-Clusters. Gate: each test fails
  when its case is reverted.

Resolution: passed. Each test went red when its case was reverted, and `:app:testFullDebugUnitTest` passed with
7,784 tests and 0 failures after the rebase onto PR #34.

- `SinkOutputRouteKeyTest`: the sink's route-key lambda moved out of `initializePlayer` into
  `sinkOutputRouteKey()`. A url captured at build failed two of the four tests: a pin for B after a switch from
  A to B gave the Bluetooth key, and a pin of the other stream gave the HDMI key.
- `SwapRoomEntryPointsTest`: `swapRoom` is now a required argument of the memory governor's rule functions, so a
  production call without it does not compile. Passing a literal `true` at `pressure()`, `allow()` and
  `swapAllows()` failed all three tests; dropping the argument failed `compileFullDebugKotlin`. Upstream's tests
  keep their old call shapes through test-source extensions and stay unchanged.
- `MatroskaRpuInjectionTest`: a reader that follows `RESULT_SEEK` as Media3 does, over a fixture with Tracks
  behind two Clusters. Three temporary extractor changes each turned it red: no detour, the Cluster position
  set after the detour check, and the detour clearing `readingFromStart`.

Not covered: the one `initializePlayer` call line, which no unit test reaches. The fixture exposed DV3.5, an
older frame 0 loss on a seek back; it is a separate step.

## 2026-11-01: DV3.4 HDR10+ static metadata from the stream's SEI (PR #37)

- [DV3.4][device][owner][new-build] HDR10+ static metadata from the stream's SEI. The DV3.1 title states no
  MasteringMetadata in the container, and S01E02's SEI has P3, 0.0001/1000 nits, MaxCLL 108, MaxFALL 53, so
  its RPUs carry the defaults (L6 0.005/1000 nits, MaxCLL 1000, MaxFALL 400, source range 62..3079, L9
  BT.2020). Read the mastering display (payloadType 137) and content light level (144) SEI of the hvcC or
  the first AU into `DvTrackFacts` for what the container does not state. Gate: for S01E02 the debug
  log and `rpu_l1_compare.py` show the SEI's values against a reference made with them, and the owner's A/B
  on the TV is in the log. Needs: DV3.1.

Resolution: passed on 2026-11-01 on build `38b3c09dd`, Shield to the owner's LG C9 (firmware 05.50.00).
The RPUs equal real `mkvdovi` on all 7231 compared frames, and the owner saw no large difference on the TV.
Commits after that build change only the roadmap and this log, so no rebuild was needed.

**Against real `mkvdovi`.** The reference comes from `mkvdovi --keep-source --verify`, built from
hdr-analyze `def6f03e6` (binary sha256 `af6f18f5…`), with `dovi_tool` 2.3.4, `hdr10plus_tool` 1.7.1,
MediaInfo 26.01 and `mkvmerge` 103.0. The log shows the HDR10+ path, CM v4.0 with L9 primaries 0, source
range 7..3079 on every frame and `Verification passed.` No mastering warning appears. Peak source histogram is
`mkvdovi`'s default. The first row below was repeated on the Mac with its own `dovi_tool`, with the same result.

| Compared with the `mkvdovi` RPU | Frames | L1 differences | Other differences | Flag differences |
|---|---|---|---|---|
| App, switch on | 7231 | **0** | **0** | 97 |
| App, switch off (control) | 7030 | 0 | 7030 | 90 |
| DV3.1 `dovi_tool` reference for S01E02 | 20055 | 0 | 20055 | 0 |

- The last row's other differences are the static fields, because that reference used the defaults. Its 0
  flag differences show `mkvdovi`'s scene starts equal `dovi_tool`'s.
- All 97 flag differences are extra flags in the app, 1 to 4 frames after a reference scene start, with L1
  unchanged. None of the 83 reference starts lacks a flag.
- MediaInfo reports Display P3, 0.0001/1000 nits, MaxCLL 108 and MaxFALL 53, each with source Stream.

**App, switch on against off.** Only L6 minimum, MaxCLL, MaxFALL, L9 and `source_min_pq` differ, plus the CRC,
on all 7030 common frames. L1 and the flags have 0 differences. Off equals the DV3.1 RPUs on 6115 frames.

| Field | On | Off |
|---|---|---|
| L6 max/min/MaxCLL/MaxFALL | 1000/1/108/53 | 1000/50/1000/400 |
| Source range | 7..3079 | 62..3079 |
| L9 primaries | 0 (P3) | 2 (BT.2020) |

**Owner A/B.** Sighted, three rounds in the order off, on, off, from 0:00 and then a seek to 01:06. The
`DV_INJECT` line confirms S1E2, the 8.1 conversion and the side for each. The first round's line had left the
256 KiB log buffer, so its start was repeated to record it. The owner: "I did not see large differences between
these two." The Dolby Vision picture mode was not recorded. A null shows no visible effect on this title and TV.
It cannot show the values are right; the comparison above does that.

**Owner observation, not part of the gate.** Around 04:10 to 04:25 the owner saw green seem to bleed into faces
and a building wall in an outdoor scene. The source shows the same tilt before any Dolby Vision step. In 11
sampled frames, in linear BT.2020, a white shirt sits 0.016 to 0.020 above D65 in `y`, and green is 1.40 to 1.43
times the mean of red and blue across the frame, against 1.05 to 1.23 in two other scenes. The scene's RPU has the
standard matrices and equals `mkvdovi`. This fits light from the grass or the grade; the file cannot show which.
Whether the TV's Dolby Vision mode strengthens it against HDR10 was not tested: the owner chose not to run
`debug.nuvio.dv_hdr10plus 0`.

**Limits of this evidence.**

- The reference comes from the first 1.5 GiB of S01E02, remuxed, 20055 frames. It covers every compared
  frame. No full file ran through `mkvdovi`, and Oracle holds no full copy.
- The comparison covers playback from 0:00. Post-seek RPUs were not compared with `dovi_tool`.
- The known differences from `mkvdovi` in `DESIGN.md` 2.4 still apply to other titles.
- The `DV_INJECT` files in the first Oracle bundle held tool help text. The values above come from the session
  logcats and the A/B rounds.

The session followed M2: snapshot `20261031-2029-dv34-sei`, both switches cleared. `shield_restore.sh` ended
with exit 3 on one item: `system/nv_ir_non_audio_device_connected` went from 1 to 0 during the session. The
session never wrote it, so it stays as the Shield set it. The logcat prune list was put back by hand, and
`adb logcat -p` read `~! ~1000/!` afterwards.

## 2026-10-31: sync cost study (U1.12 to U1.15)

The owner asked how to cut the upkeep of a sync. Five read-only measurements on scratch clones, two designers, a
judge who reproduced the numbers, and a Codex critique.

**Measured**

- The sync of 2026-10-31 took 136 min: 48.6 blocked on two owner answers, 10 of CI, about 77 of other machine work.
  New tokens were about 1.66M, 1.38M of them classify.
- 246 of 262 commits replayed to the exact real tree. Four syncs had 38 stops and 76 hunks. `merge-tree -X ours`
  finds all 38 stops in 2 to 7 s, with 16 false alarms.
- All 5 real issues of 2026-10-31 merged cleanly, so conflict-only analysis would miss them.
- The light path fired on 0 of 4 syncs. Measured cumulatively, 6 of 129 upstream steps qualify, none on 2026-10-31.
- rerere had 0 hits: 76 of 76 conflict texts were unique.
- The 2026-10-27 commit set gives 13 stops, 18 hunks and 130 lines whether synced once or twice. Codex notes that
  replay used resolved parents, so "no cost" is not fully proven.
- Fork size does not predict conflicts. `MatroskaExtractor.java` carries +697 lines and had 2 hunks in four syncs.
  Moving every 8+ line hunk of it and `PlayerRuntimeControllerInitialization.kt` into hooks saved 0 regions.
- Real fork code in upstream files is about 3,700 added lines in 89 files. No fork commit is patch-equivalent to
  upstream (264 of 264 by `git cherry`). Overlap shows up in behaviour, as in the 2026-10-31 adapts.
- Replaying the fork onto official beta.9 gives 68 stops and 244 hunks, against 8 and 12 on ysosrs. The fork depends
  on ysosrs's 213-commit layer.

**Decisions**

- Syncs follow settled batches, at least weekly (the owner's floor).
- Before a new fork feature, check official and upstream. When upstream covers a fork change, prefer dropping the
  fork's version, except for the protected features.
- U1.10 is held. The stack cleanup waits for the next sync's rebase.

**Rejected on the numbers:** squash, theme regrouping, merge instead of rebase, patch queues, large Init and Matroska
seams, a fork Gradle module, retuned light rules, tracking official directly.

**Open:** the 30 to 45 min sync target is an estimate until a pilot sync measures it.

**Blind eval of classify by exception (2026-11-01):** fail, 4 of 5 known adapts. It missed the Video Effect rebuild
during the A/V quiesce, and the critic did not catch it. It found 7 further adapts, and one critic contradiction stopped a
fix that would lose a protected line. Cost 1,085,378 tokens and 29.9 min against 1,382,704 and 25.1 min, far from the
study's estimate of 0.5M and 12 min. Per-commit classify stays the default.

## 2026-10-31: U1.5 sync onto upstream 1.1.0-nt.9 (PR #33)

- [U1.5][desk] Rebased `perf/shield-pass` from `2b51aba36` onto `d9039df69`: 271 upstream commits behind, 262 fork
  commits. The new tip is `1ef760ac3`, with 263 fork commits. Tags: `sync/2026-10-31/old-{perf,main,base}`.

**Classify (tier `fanout`, 9 analysts and a critic):** 7 files conflicted in the trial merge, 81 fork commits touched
files upstream changed. Verdicts: 0 drops, 0 protected asks, 11 keep-resolve, 5 keep-adapt, 65 keep-clean; 181 commits
touched nothing upstream changed.

**Owner's decisions:** all five adapts went into their own commits.

- `9b616ffbc`: the IEC sink gets the route corrected by the idle-Bluetooth HDMI pin, or upstream's route gate refuses
  the packer on that path. It matters with `hbr_iec=1` or System passthrough off.
- `2f14acf40` (protected): `tunnelingRequested` also skips a USB-audio playback. One line added, none lost.
- `13b982274`: upstream's Video Effect rebuild check waits for the A/V quiesce.
- `ad6d7d8e8`: upstream's `noteJoined` tests `isCompleted`, because a lazy lookup that has not started is not active.
- `9e0478852` (owner rule, keep the fork's value): `swapRoom` is a separate argument, so `mdarcy` keeps refusing and
  other small boxes keep upstream's room.

**New commit:** `4dcf97401` gives the unit test JVM 1 GB of heap (`--allow-new`).

- The first gate failed with `OutOfMemoryError` in `AutoSpeedupDataSourceTest`, which passes alone. A second run failed
  a concurrency assertion in `SpeedTestDownloadsMatchPlaybackTest` instead.
- GC logs of the same run on both trees: live heap after a full GC reaches 488 of 512 MB on plain upstream and 503 MB
  on the fork, with the same step at about 96 s, beside the `ParallelRange*` tests. No main file of those tests differs
  from upstream, so the retention is upstream's. It was not traced to a single test.

**Evidence**

- `verify`: 241 identical, 21 changed, 0 dropped, 1 new. Protected lines: aac-to-dts 45 commits, 5970 lines, 0 lost;
  hlg-to-dv 92 commits, 13279 lines, 0 lost. Waived: `--allow-same` on `PlayerRuntimeControllerTrackSelection.kt`
  (upstream's `player.isTunnelingEnabled` replaces the fork's one line), `--allow-changed` on `AudioShieldPassTest.kt`.
- Gate on `1ef760ac3`: 919 classes, 7726 tests, no failures. The baseline on plain upstream fails only the known
  `testHttpError404`. APK: `protected-apk` passes for `libffmpegJNI.so` and `libnuvio_rpu.so`.
- Independent review of the changed hunks: no fork hunk lost, by a line-set comparison and a three-way merge of the old
  tip with the new base. It found one defect: the route lambda compared the pin with the url captured at build, which
  misses a source switch on a live player. It now reads a `@Volatile currentStreamUrl`. No unit test reaches that
  lambda.
- CI: `Checks` run 41207331028 green on `1ef760ac3`. Not run on the Shield.

**Open, for the owner**

- Should growth stay off on `mdarcy` until Q5.7 reports? Upstream's growth went in as a default.
- `BatchRangeReader` can hold 48 MB on a 384 MB heap, and the strong entry check keeps only 64 MB free. No reading yet.
- In a Tracks-after-Clusters file the early scan starts after Tracks, so a track signalled only in SEI (HLG, HDR10+)
  stays native. That is the safe failure, and it was found by reading the code only.
- The `audio_tunneled_sub` text says "Fit or Fill", but upstream's aspect labels have no Fill. `values-bg` also uses
  older labels than upstream's. Its reworded sentences had no native-speaker check.
- 80 fork commits carry a `Co-Authored-By` trailer from before the attribution rule. The rebase left them as they are.

**Resolution:** the fork is level with upstream 1.1.0-nt.9, and `main` mirrors `d9039df69`.

## 2026-10-31: DV3.1 HDR10+ → 8.1 provider, device gate (PR #30)

- [DV3.1][device][owner][new-build] HDR10+ → 8.1 provider. Gate: an HDR10+ title plays as Dolby Vision
  on the owner's TV; the debug log shows an RPU in every AU and L1 equal to `dovi_tool` for the same
  frames. First write into `DESIGN.md` 2.4 the HDR10+ rules of hdr-analyze `docs/RPU_BUILDER.md`: the
  scene rule runs in presentation order while the extractor reads in decode order; an AU without a
  payload reuses the previous payload in decode order; non-HDR10+ T.35 messages are skipped. Needs:
  DV2.7, DV2.8.

Resolution: passed on 2026-10-30 on build `36e0e192a`, Shield to the owner's LG C9, one 4K 23.976 fps
HDR10+ web series in Matroska. L1 equals `dovi_tool` on all 6115 compared frames.

**Dolby Vision on the TV.** The owner read Dolby Vision on the TV while playing S01E03 of the series. For
the compared playback, S01E02, the log shows the same path: `HDR10+ SEI scan, track 1: 1 HDR10+ SEI in the
first AU`, `DV_INJECT track 1: HDR10+ 8.1`, `Dolby Vision 8.1 as dvhe.08.06`, the decoder
`OMX.Nvidia.DOVI.decode`.

**An RPU in every AU.** `debug.nuvio.rpu_dump` 100000 from 0:00, 255 s of playback, then seeks. The dump's
`au` counter counts every AU the renderer queues, and with N above the AUs played every AU with an RPU
leaves a record. AU ordinals have no gap: 0..6116 in the playback from the start and in each of the five
generations after the seeks. `rpu_dump_to_bin.py --strict` reports 0 problems. `dovi_tool info` 2.3.4 reads
6115 frames of Profile 8, CM v4.0. The dump's last two AUs are left out: playback stopped before their
B-frames were queued.

**L1 equal to `dovi_tool`.** The reference ran on the owner's Oracle host, which has the file:
`hdr10plus_tool extract` (1.7.1, not the 1.7.2 of the handoff; a re-extraction is identical),
`dovi_tool generate --hdr10plus-json` 2.3.4 with the app's params, `rpu_l1_compare.py`:

| Reference | Frames | L1 differences | Other differences | `scene_refresh_flag` differences |
|---|---|---|---|---|
| S01E02, the episode played | 6115 | **0** | 0 | 88 |
| S01E03 | 6115 | 4918 | 0 | 189 |
| S01E04 | 6115 | 4723 | 0 | 175 |

- The run first failed on S01E03. The handoff named that file from the log of an earlier playback, and the
  owner had moved on: `PLAYER_START_REQUEST` shows S1E3 at 11:17:12, S1E4 at 11:17:43 and S1E2 at 11:18:51
  (`startPositionMs=0`), one second before the dumped generation. The durations differ too (3472800 ms
  and 3296608 ms). An offset search matches 100 % only at offset 0 on S01E02. The other episodes agree on
  the shared 9.7 s opening and then diverge, first at frame 232; the best offset match on S01E03 is
  27.8 %. Next time, name the file from the dumped playback's own `PLAYER_START_REQUEST`.
- All 88 flag differences are extra flags in the app, 1 to 4 frames after a reference scene start, with L1
  unchanged. None of the 73 reference starts lacks a flag. A simulation of the 2.4 rule in decode order from
  the slice POCs reproduces all 161 of the app's flags. Every L1 change carries a flag (61 of 61).

**Limits of this evidence.**

- "0 other differences" compares against a config with the app's params, not against what `mkvdovi` writes
  for this title. The title states no MasteringMetadata in the container. The SEI of S01E02 has P3,
  0.0001/1000 nits, MaxCLL 108, MaxFALL 53 (MaxFALL 81 on S01E03); the app's RPUs carry 0.005/1000 nits, MaxCLL 1000, MaxFALL 400, source range
  62..3079 and L9 BT.2020. `mkvdovi` did not run. New step DV3.4.
- One playback from 0:00 was compared. Seeks show an RPU on every AU but were not compared with
  `dovi_tool`; a missing payload and the A-B-A case did not occur. `debug.nuvio.dv_refresh_every_start` 1
  did not run; DV3.2's seek session tests the same switch.

The session followed M2: snapshot `20261030-1106-dv31-gate`, restored clean with 0 drift. The snapshot does
not cover the logcat prune list, which the session cleared; it was put back by hand, and `adb logcat -p`
read `~! ~1000/!` afterwards.

## 2026-10-29: DV3.1 HDR10+ → Dolby Vision 8.1, code (PR #30, step stays open)

- [DV3.1][device][owner][new-build] HDR10+ → 8.1 provider. Gate: an HDR10+ title plays as Dolby Vision
  on the owner's TV; the debug log shows an RPU in every AU and L1 equal to `dovi_tool` for the same
  frames.

**Spec first.** The HDR10+ rules of hdr-analyze `docs/RPU_BUILDER.md` are now in `DESIGN.md` 2.4, with
what the extractor's decode order changes: L1 from each AU's own payload (L1 is a function of the scene
key's fields alone, so it equals the scene L1 `dovi_tool` holds, in any read order), the previous payload
in decode order for an AU without one, and the scene refresh where the key differs from the already-read
AU with the largest presentation time below.

**Codex review of the rules** (gpt-6.1-sol, high, read-only, 2026-10-29). Five findings, with what was
done:

1. The L1-from-the-key claim holds (`hdr10plus.rs` `l1()`, `peak_brightness_nits` in hdr10plus 2.1.5).
   Kept.
2. The refresh rule is a heuristic: it misses a flag only for keys A, B, A within an anchor's display lead, and
   adds flags with an unchanged L1. Codex proposed a flag on every AU whose predecessor is not known to be
   read. Not taken: with x265's default B-pyramid that flags most frames of every scene. Both cases are
   written into 2.4.
3. A seek onto a scene's first AU gets no flag where the `mkvdovi` file has one. Kept as DV2.7's
   parity rule, written into 2.4; `debug.nuvio.dv_refresh_every_start` tests whether the TV cares.
4. The spec turned a zero mastering minimum into 0.005 nits; `mkvdovi` keeps it. Fixed: only a zero
   MaxCLL or MaxFALL counts as not stated.
5. Every AU of a Dolby Vision track needs an RPU. The provider always returns one (the last shot on a
   failure), and the track converts only with a NAL length field of 2 bytes or more.

**Claude review of the diff** (3 Opus finders by lens, one Fable whole-diff reviewer, one Opus skeptic per
finding). Confirmed and fixed:

- L9 was always BT.2020; `mkvdovi` detects P3-D65 for a P3-mastered title. `nativeCreate` now takes the
  source primaries, read from the Matroska mastering chromaticities, then the container's Primaries.
- `mkvdovi`'s MaxFALL <= MaxCLL fix-up for a half-stated pair was missing.
- A gap carried in one generation flagged the first AU of the next after a second seek.
- The extractor seek test passed with the reset unwired; its fixture now changes scene at the seek
  point (checked by removing the reset: the test fails).
- `rpu_dump_to_bin.py --order pts` misaligned frames past the B-frames a stopped playback never queued;
  it now stops at the first gap in presentation time.

Refuted: an implausible stated mastering minimum gets a source range after the 10 000 cap; that follows
2.4's rule (the range is taken over the final L6 values, where `mkvdovi` refuses the title).

**What shipped (PR #30):**

- `native/rpu_builder`: `nativeHdr10PlusFrame` (L1 and a 128-bit digest of the `SceneKey`) and
  `nativeSourceRange81` (`source_range_81`). No hdr-analyze change and no pin move: `SceneKey` is `Hash`
  and `Eq`, and `git log f55c779..HEAD -- dovi_rpu_builder` in hdr-analyze is empty. The new fixture test
  writes the 16 HDR10+ `dovi_tool` outputs from per-AU L1 and key changes and agrees with `Hdr10PlusHold`
  frame by frame.
- `Hdr10PlusDvRpuSource` and `Hdr10PlusTrackRpus`, `DvRpuSources` (8.4 static, 8.1 HDR10+), `TrackRpus`
  behind `ConvertedTrackInjector`, the extractor's early scan for HDR10+ in a PQ track's first AU, the
  AU's presentation time to the injector, and `release()` reaching the transformer.
- The setting reads "Convert HLG and HDR10+ to Dolby Vision" (one toggle; plain HDR10 still plays as it
  is). `debug.nuvio.dv_hdr10plus 0` keeps every PQ track native.
- `scripts/rpu_dump_to_bin.py --order pts` and `scripts/rpu_l1_compare.py` for the gate's L1 check.

**Device run sheet (owner).** Build from this branch with cargo-ndk installed (the APK needs
`libnuvio_rpu.so`), follow M2, then for one HDR10+ MKV:

1. Setting on; `adb shell setprop debug.nuvio.rpu_dump 100000`; play from the start for a minute.
   Logcat must show `HDR10+ SEI scan, track N: … HDR10+ SEI in the first AU`, `DV signalling, track N:
   Dolby Vision 8.1 as dvhe.08.LL` and `DV_INJECT track N: HDR10+ 8.1, …`; the TV must show Dolby Vision.
2. On the desk: `hdr10plus_tool extract title.mkv -o title.json`, then `dovi_tool generate` with
   `mkvdovi`'s HDR10+ `extra.json` (or `mkvdovi` itself) for `reference.RPU.bin`;
   `scripts/rpu_dump_to_bin.py dump.logcat -o ours.RPU.bin --session 1 --gen 0 --order pts --strict`;
   `scripts/rpu_l1_compare.py ours.RPU.bin reference.RPU.bin`. Pass: 0 L1 and 0 other differences.
3. A seek and a mid-file resume, each with `debug.nuvio.dv_refresh_every_start` 0 and 1: the picture must
   not flash or shift, the dump must still have an RPU on every AU.
4. Clear every `debug.nuvio.*` the session set.

## 2026-10-29: U1.8 Light path (PR #28)

- [U1.8][desk] Light path: a sync with at most 3 conflicts, none protected and no upstream change to
  workflows, binaries, settings stores or both-sided Tier 2 paths (a version bump alone is allowed) skips the
  classify workflow; a rebase stop that breaks those limits aborts. Gate: script tests; a replay of the
  2026-10-27 sync.

**Evidence**

- Script tests: `python3 -I -m unittest discover -s scripts/tests -p test_upstream_sync.py`, 52 tests green,
  among them `StopCheckRepoTest`, `MapOverlapRepoTest`, `test_light_blockers_name_every_reason` and
  `test_every_settings_store_blocks_the_light_path`. The unit-test gate stayed green.
- Replay against each upstream step of the 2026-10-27 sync: the first three steps are light, and a light rebase
  onto `3baaf5c4d` verifies (234 identical, 0 protected lines lost). From `77eb738f3` (DTS-HD passthrough in the
  IEC sink) on, the sync is not light.
- Cross-review (codex, gemini) found four P1s. Three were fixed with tests: `stop-check` now counts a conflict
  that rerere autoupdate already staged, every store in `data/local` blocks the light path, and a light sync keeps
  its classify group for the fallback after an abort. The owner kept the version-bump exemption, because
  upstream bumps `app/build.gradle.kts` every release.

**Resolution:** `sync.py map-overlap` reports `light.eligible` and its blockers, the light tier skips classify,
and `sync.py stop-check` aborts a light rebase whose stops break the limits.

## 2026-10-29: U1.6 spikes for sync v2 (issue #26)

- [U1.6][owner] Spikes before building. Gate: results in the log; the nightly run (U1.10) stays off until a
  server-side guard exists.

**A and D: ruleset with a deploy-key bypass (2026-10-28, local, owner's go)**

- A ruleset on `spike/**` (Restrict updates, Block force pushes, Restrict deletions) with the bypass actor
  `DeployKey` was accepted on this personal repo. GitHub reports `current_user_can_bypass: never` for the
  owner. No move to an organization is needed.
- Owner fast-forward, force-push and delete: rejected (GH013, "Cannot update this protected ref"). A new
  `spike/` branch was accepted, because the ruleset had no creation rule.
- Deploy key: fast-forward, leased force-push, a commit that edits `.github/workflows/`, and an atomic two-ref
  push with leases were all accepted ("Bypassed rule violations"). A stale lease was refused, and with
  `--atomic` one stale lease refused both refs.
- The key, the ruleset and the branches were deleted afterwards.

**B, C, E, F: two one-off routines (Opus, default environment, no connectors)**

| Call from the cloud session | Result | Stopped by |
|---|---|---|
| `git push` to `claude/*`, to `spike/*`, and a commit adding a workflow file | accepted | nothing |
| `git push` of a tag | dropped ("remote end hung up") | proxy |
| `workflow_dispatch` (REST) | HTTP 204 | nothing |
| Create a PR, then squash-merge it (REST) | HTTP 200, merged | classifier until the owner approved |
| Approve or reject a pending deployment | HTTP 403 "Resource not accessible by integration" | GitHub (App token) |
| Create a ruleset; delete or force-update a branch (REST) | HTTP 403 "not permitted through this proxy" | proxy |
| Change an environment setting | not sent | classifier |

- The token reports `admin: true`, and `pending_deployments` said `current_user_can_approve: true`, yet GitHub
  refused the review itself. An environment with the owner as required reviewer is therefore a server-side
  guard against a cloud publish. The owner's own token rejected the same run.
- The auto-mode classifier blocked, before any request left the session: self-approval, "Interfere With
  Workloads", "Merge Without Review", "Git Destructive" and "Modify Shared Resources". After three blocks a
  routine stops at a permission prompt. A nightly prompt must need none of these.
- Clone: shallow, 50 commits. `sync.py preflight` fails with exit 2 until `git fetch --unshallow origin`; then
  it runs (exit 1 here, blocked by the spike's own `claude/spike/f`).
- A routine prompt that starts with `/upstream-sync` loads the skill despite `disable-model-invocation`.
- Commits are authored `Claude <noreply@anthropic.com>` and get no trailer. The harness appends
  "_Generated by Claude Code_" to PR bodies (with a session link) and to issue comments, even when the agent
  writes the body.
- The routine API attached every claude.ai connector (Gmail, Drive and others) on create, even with
  `mcp_connections: []`. An update with `clear_mcp_connections: true` removes them.

**Consequences for the later steps**

- U1.10: unshallow and add the `upstream` remote before preflight; check the footer with the repo
  `.claude/settings.json` attribution settings, and post the morning verdict from the CI compare job rather
  than from the routine if it stays; clear connectors after creating the routine.
- U1.11: give `sync-publish` a deployment branch policy (only `perf/shield-pass`), because the cloud can push
  workflow files that run on push; keep repository-level secrets free of anything that can publish.
- U1.11: a REST merge from the cloud works server-side, so "Require a pull request" on `perf/shield-pass` does
  not stop a cloud merge. Today only the classifier does. Test a ruleset that lets the owner's merges through
  and refuses the Claude App token before the nightly run goes on.

## 2026-10-27: U1.5 sync onto upstream 1.1.0-nt.8.2 (PR #24)

- [U1.5][desk] Keep `perf/shield-pass` rebased on `upstream/main` while upstream moves. Gate: 0 commits behind
  at each pull request.

**Counts**

- Upstream: 13 commits since the merge base `3b1643198` (13 first-parent), up to 1.1.0-nt.8.2. Fork: 15
  base-layer and 219 Shield commits.
- Map: tier `fanout`, 48 fork commits touch files upstream changed, 2 files conflicted in a trial merge
  (`PlayerSettingsDataStore.kt`, `PlayerRuntimeControllerInitialization.kt`), 186 fork commits replayed with
  no overlap. Classify: 7 Opus analysts and a critic; 45 keep-clean, 3 keep-resolve, no drop, adapt or
  protected ask.
- Result: `main` = upstream + 16 commits (15 identical, 1 new test fix), `perf/shield-pass` = `main` + 219
  commits (213 identical, 6 changed only in context or line numbers, 0 dropped).

**Drops**

- None.

**Setting decisions**

- No upstream change touched a value the Shield plan sets, so there is no new Nvidia gate. Every upstream
  default is taken; the list is in `docs/shield-todo.md`, "Upstream defaults taken with the sync of 2026-10-27".
- The new DTS-HD TV passthrough setting (`dts_hd_tv_passthrough`) only acts on a TV panel on ARC/eARC
  (`ro.hdmi.device_type` "0"; the Shield reports "4"), so the DTS-HD MA transcode on the Shield is unchanged.
  On such a panel the transcode would go to Android's own path without the fork's burst split; not a Shield
  case.

**Resolutions**

- Two conflict stops, both next to upstream's new `dtsHdTvPassthrough` lines: 4f5d7705a kept the fork's
  `defaultUseSystemPassthrough()` read and upstream's new read after it; d2b07e187 (protected) kept upstream's
  `dtsHdTvPassthroughActive` and then the fork's `isTranscodeAacToDtsActive`. 2a1101e41 (protected) merged
  without a stop.

**New base-layer commit**

- `Checks` on the first perf candidate failed twice on upstream's `ParallelRangeProductionWorkloadTest`
  ("pieces refused together wait as one", from 3baaf5c4d). The fork does not touch that code. 35 targeted
  local runs passed, including 1 CPU under full load.
- Cause: the test refused piece 1 before the test thread had scheduled pieces 2 and 3, so the rate gate held
  them back unopened. A 300 ms delay after piece 1 failed it 3 of 3 on plain `upstream/main`.
- 512bef15e makes each first request wait until all three are sent. With the delay it passed 5 of 5. Owner
  approved it with the publish. Upstream PR candidate (U1).
- `sync.py verify` failed any new commit; this PR adds `--allow-new` and a trap in `sync.json`.

**Evidence**

- `sync.py verify` layer 1: 15 identical; with `--allow-new 512bef15e`, all checks pass. Layer 2: all checks
  pass, no `!` hunks.
- Protected features: aac-to-dts 44 commits, 5971 lines checked, 0 lost; hlg-to-dv 83 commits, 11359 lines
  checked, 0 lost. `protected-apk` on the APK built from 7146d36c9: `libffmpegJNI.so` (dcaxll) and
  `libnuvio_rpu.so` present.
- Gate `:app:testFullDebugUnitTest`: perf 7388 tests, 0 failures; main 0 failures; baseline on plain upstream
  only `LocalhostZeroCopyDataSourceTest.testHttpError404`.
- `Checks` run 40502334715 green on 7146d36c9.
- Review: one independent Opus reviewer read the resolutions and the 6 changed commits; no findings. The
  fork's delta over upstream has the same changed lines before and after (375 files, +32181 / -1082).
- Published with one atomic leased push: `main` 17d04cac9 → 512bef15e, `perf/shield-pass` 317f4b803 →
  7146d36c9. Drift issue #23 closed.

## 2026-10-26: resource headroom during 4K playback (DV5, Q6.5)

The owner asked whether the Shield has room for a live HLG dynamic-metadata generator next to the two
conversions. This was a read-only measurement during 4K DV playback with the DTS-HD MA transcode.

- **CPU:** 19.3 % of the four cores. The transcode thread used 19.5 % of one core, and the Loader thread
  (static RPU injection, network and parsing) 4.0 %.
- **GPU:** close to idle. The load is unreadable without root, but both layers were DEVICE overlays.
- **Thermals:** 49 °C, fan 0.
- **Memory:** the limit. SwapFree 31 MB, NvMap 785 MB used and 0 free. The seek-thumbnail worker's
  `4K-E reserve` turned off twice in 5 min on low swap.
- **Reading:** a second 4K decode for live analysis (DV design §3: 150–400 MB) has no room now. Idle-time
  pre-analysis (§2.9) does not need one.
- **Open:** the title's source was not confirmed as HLG, and the input rate was not logged, so Q6.5 stays
  open.
- Full numbers and the method are in the local Shield reference (`docs/nvidia-shield.md`, gitignored), section
  "Resource use during playback (2026-10-26)". The raw files are in `~/shield-snapshots/20261026-resource-check/`.

## 2026-10-26: U1.5 sync onto upstream 1.1.0-nt.8.1 (PR #22)

- [U1.5][desk] Keep `perf/shield-pass` rebased on `upstream/main` while upstream moves. Gate: 0 commits behind
  at each pull request.

**Counts**

- Upstream: 219 commits since the merge base `d857068ee` (53 first-parent), 300 files, up to 1.1.0-nt.8.1
  (official NuvioTV 1.1.0-beta.8). Fork: 15 base-layer and 218 Shield commits.
- Map: tier `fanout`, 97 fork commits touch files upstream changed, 25 files conflicted in a trial merge,
  136 fork commits replayed with no overlap. Classify: 9 Opus analysts and a critic; 78 keep-clean,
  14 keep-resolve, 2 adapt, 1 partial, 2 drops proposed.
- Result: `main` = upstream + 15 commits (all identical), `perf/shield-pass` = `main` + 215 commits
  (194 identical, 21 changed in the rebase, 3 dropped).

**Drops (owner-approved)**

- 605e1512d native libraries load from the APK: upstream does it (`useLegacyPackaging = false`, 1929fe0d7,
  aa9ef82d1).
- 8ead59db2 seek thumbnails start on on the Shield TV Pro 2019: upstream's 2,600 MB floor (25ac9a184)
  already covers its 2946 MB, and the revert switch could no longer turn them off.
- a6abe6ddf libass starts with the canvas overlay on Nvidia: upstream's atlas pipeline (4f2fd58e2) makes the
  render type inert.

**Setting decisions**

- Image disk cache: upstream's rule in the rebase (the Shield would get 200 MB); the owner chose an
  Nvidia-gated 300 MB tier on top, `debug.nuvio.image_cache_tier` (this PR).
- FFmpeg decoder AAR: the fork's binary stays (DTS target). Its Java API is a superset of upstream's
  (javap). Upstream's LFE stereo-downmix change is in the module source only.
- Every other upstream default is taken; the list is in `docs/shield-todo.md`, "Upstream defaults taken
  with the sync of 2026-10-26".

**Resolutions**

- 14 conflict stops, each recorded with the file, what was kept and why: Silo start body, AudioCapabilityReport
  probes, DecodeOrderPtsVideoRenderer, PlayerScreen aspect mode, eight locales and the renamed Hungarian file,
  README and app/libs README, FfmpegForkRoutingTest, AudioRoutingSnapshot, the Audio path row, the DV failure
  helper, and the libass build path for the RPU dump.
- One resolution error found in review of the range-diff and fixed before the push: the
  "Previous FFmpeg AAR (0.9.3-beta-nt1)" section had taken upstream's hash.

**Evidence**

- `sync.py verify` layer 1: 15 identical, all checks pass. Layer 2 (with `--allow-same` for the image cache
  files and the renamed Hungarian file): all checks pass at range-diff creation factor 100; at git's default
  of 60, 00293d374 showed as dropped and new although its subject, files and AAR blob match.
- Protected features (verify of this PR on the same sync): aac-to-dts 43 commits, 5687 lines checked, 0 lost;
  hlg-to-dv 82 commits, 11075 lines checked, 0 lost.
- Gate `:app:testFullDebugUnitTest` on the perf candidate: 7315 tests, 0 failures (baseline on plain upstream:
  only `LocalhostZeroCopyDataSourceTest.testHttpError404`, which the fork's datasource AAR fixes). Gate on the
  main candidate: 0 failures. `assembleFullDebug` built the arm64 and armv7 APKs.
- Review: one independent Opus reviewer read the 21 changed commits and the tip-to-tip diff of all 368 fork
  files; no blockers, three doc findings fixed in this PR.
- CI: `Checks` green on the published perf SHA (run 40288021616).
- Upstream rewrote its last 20 commits after the first push (same trees, AI trailers removed). Both layers
  were rebased onto the new tip `3b1643198`, the trees checked identical, CI run again, and pushed a second
  time with a lease. Drift issue #10 closed.

**This PR**

- feat(images): the Nvidia 300 MB image disk cache tier.
- Docs: shield-todo rows for the dropped commits removed, the upstream defaults listed, README and
  app/libs README updated, R3.1, D1.2 and Q5.4 updated.
- `/upstream-sync`: the AAC-to-DTS transcode and the HLG-to-Dolby-Vision conversion are protected features
  (owner, 2026-10-26); verify checks their lines, binaries and drops; `protected-apk` checks the APK;
  creation factor 100; step 8 uses `Checks`; `main` is gated locally; JDK 17; the publish checks for an
  upstream history rewrite.

**Open**

- The committed decoder AAR lacks upstream's LFE downmix change; a rebuild without the dcaxll writer is the
  owner's call.
- `main` has no CI workflow; whether `checks.yml` moves to the base layer is the owner's call.
- README's section on GitHub Actions APK builds predates PR #11 and needs a rewrite.
- Device check on the Shield from a build of the new tip: one AAC title (DTS-HD MA on the HT-A9) and one HLG
  title (Dolby Vision on the TV).

## 2026-10-26: M2.1 Snapshot and restore scripts (PR #21)

- [M2.1][desk][device] Scripts `scripts/shield_snapshot.sh` and `scripts/shield_restore.sh` (serial and
  timeouts as in M1; `scripts/` is gitignored, and `.gitignore` already lists these and `shield_measure.py` as
  exceptions). Gate: one snapshot and restore round trip on the Shield with an empty diff.

**Evidence**

- Device gate on 2026-10-26 with the final scripts (clean tree at the PR head): `shield_snapshot.sh
  --label m21-gate4` completed, the session set the test property `debug.nuvio.m21_probe=1`, and
  `shield_restore.sh` cleared it with `setprop debug.nuvio.m21_probe ''`, exited 0 with verdict clean and
  an empty `restore/diff.txt` (DRIFT 0, OWNER 0, INFO 0, IGNORED 0). Folder
  `~/shield-snapshots/20261026-0025-m21-gate4/`. An earlier round trip on 2026-10-25 with the scripts
  before the review fixes was also clean (`20261025-2349-m21-gate3`).
- `scripts/tests/test_shield_snapshot_restore.py`: 42 tests against a fake adb that records every command
  and fails a test on any forbidden one (install, clear, force-stop, compile, a daily app, a setprop
  outside `debug.nuvio.*`, the address or the raw hardware serial in any output or saved file).
- Gate `:app:testFullDebugUnitTest` green on the final head (no app code changed).
- Review (Tier 1, codex gpt-6.1-sol/high): F1 FIXED (the health check matched only Watchdog lines saying
  KILLING or Blocked; it now matches the Watchdog tag, and a healthy Looper line naming
  `com.android.server.Watchdog$HandlerChecker` still passes), F2 FIXED (a multi-line setting was cut at its
  first line and the snapshot still completed), F3 FIXED (a listed test build whose `pm path` failed was
  skipped silently). Each new test was red on the reviewed head. No P2 notes.
- CI: the `Checks` workflow on PR #21.

Resolution: the M2 snapshot and restore steps are scripted and checked once on the Shield. M2 has no
open step left but stays in the roadmap, because it is the protocol every device session follows.

## 2026-10-25: DV2.11 HLG tracks whose hvcC carries no parameter sets (PR #20)

- [DV2.11][desk] HLG tracks whose hvcC carries no parameter sets (found in the DV3.2 device session on
  2026-10-25 with the DV2.10 build: the BBC broadcast HLG samples have an hvcC with no arrays, so the
  VPS, SPS and PPS travel only in the access units; with no SPS at track init the HLG contract refused
  and the SEI was never acted on). A track whose hvcC has no SPS waits for the early scan, which reads
  the parameter sets and the alternative-transfer SEI from the first AU before the formats are
  published; the contract decides again with that SPS, and a converted track gets the AU's parameter
  sets as its initialization data. The extractor logs the decision's inputs (container Colour, the
  parsed SPS, and for an unreadable SPS the hvcC's NAL types and SPS bytes). Gate: unit tests through the
  vendored extractor: an empty hvcC with the device's container Colour (9/14/9) and the SPS and HLG SEI
  in the first AU converts before `endTracks` with one RPU per AU and the AU's parameter sets as
  initialization data; an in-band SPS that states HLG converts without an SEI; an in-band SPS that is
  not HLG, a first AU without parameter sets and conversion off stay native. Needs: DV2.10.

**Evidence**

- Gate `:app:testFullDebugUnitTest`: 6797 tests, 0 failures, 10 skipped, on the final head.
- New tests through the vendored extractor in `MatroskaSeiHlgScanTest`: an empty hvcC with the device's
  container Colour (9/14/9) and the SPS and HLG SEI in the first AU converts before `endTracks` with one
  RPU per AU and the AU's parameter sets as initialization data; an in-band SPS that states HLG converts
  without an SEI; an in-band SPS that is not HLG, a first AU without parameter sets and conversion off
  stay native; an hvcC with only an HLG SEI gets the AU's parameter sets ahead of its SEI. The prefix
  walker keeps the parameter sets in order with the SEI in `HevcInitNalsTest`.
- Review (Tier 1, codex gpt-6.1-sol/high): F1 FIXED (an hvcC holding only an SEI kept the SEI alone as
  the converted track's initialization data and dropped the first AU's parameter sets; red on the
  reviewed head). No P2 notes.
- CI: the `Checks` workflow on PR #20.

Resolution: the early scan now supplies the SPS and the initialization data a parameter-set-free hvcC
lacks; the Shield check of the BBC samples stays with DV3.2.

## 2026-10-25: DV3.2 device session, part 1 (step stays open)

The first device session of DV3.2 (HLG → 8.4 static provider) on the Shield (mdarcy) and the owner's LG
OLED C9 (measured peak about 680 nits), with local builds of `perf/shield-pass` and the DV2.11 branch
(`scripts/shield_build_install.sh --install`, `new-build` probe passing for each install). M2 snapshot
before and after in `~/shield-snapshots/20261024-dv32/` (local); captures in its `runs/`.

**Findings that became steps**

- A BBC broadcast HLG sample played native as SDR with "Convert HLG to Dolby Vision" on: the SPS states
  BT.2020 10-bit (14, also logged by the Nvidia decoder as primaries 9, transfer 14, matrix 9) and HLG is
  only in the alternative-transfer SEI (`HLG contract, track 1: not HLG; alternative transfer SEI 18 (not
  acted on)`). Became DV2.10 (PR #19).
- With the DV2.10 build the sample still played native: its hvcC carries no parameter sets (the new
  `HLG inputs` line: container Colour 9/14/9 without Range, `SPS unreadable, hvcC 0 buffers`), so track
  init had no SPS and the contract refused. Became DV2.11.

**With the DV2.11 build** (a resume about 9.6 minutes in, conversion on, `debug.nuvio.rpu_dump` 8)

- Log: `HLG for 8.4 from the alternative transfer SEI (SPS from the first AU)`; `DV_INJECT track 1:
  static 8.4, BT.2100 composer, L1 0/1283/3079, RPU 418/419 bytes, scene refresh on frame 0 only`;
  `Dolby Vision 8.4 as dvhe.08.07`.
- Decoder: `OMX.Nvidia.DOVI.decode`, `RPU Decoder init success`, no fallback or codec error.
- Dump: 8 AUs through `scripts/rpu_dump_to_bin.py --strict` (0 problems); `dovi_tool info` 2.3.4: Profile
  8, CM v4.0, L1 max 1000.6 nits and average 11.86 nits, L6 1000/0.005 nits with MaxCLL 1000 and MaxFALL
  400, L2 trims 100/600/1000, L9 BT.2020; scene count 0, as a resume should have. Every AU is
  byte-identical to frame 1 (scene refresh 0) of the builder fixture `static_84_bt2100.RPU.bin`
  (`dovi_tool generate`); against its frame 0 only `scene_refresh_flag` and the CRC differ.
- Owner on the TV: the Dolby Vision badge, colours normal (no tint). Highlights in some scenes look a
  little too bright, slightly clipped.

**Brightness note.** The code does not take the display's peak into account, and in Dolby Vision it
should not: the RPU describes the content and the TV's display management maps it to the panel. What
the static provider tells the TV is the same for every scene: L1 max 3079 (about 1000 nits, the HLG
source-range ceiling, not a measurement) and a fixed average of 1283 (about 12 nits), with L6 1000
nits. A bright scene described as averaging about 12 nits can be mapped too bright. Measured per-shot
L1 (T1 shot lists, T2/T3 analysis) is the fix; whether static 8.4 beats native HLG on this TV is G-E,
and the L1 average (1283, owner 2026-10-22) and the trims can be revisited there.

**Still open for DV3.2:** the canary through the app (`debug.nuvio.dv_hlg_canary` 1, a new playback); a
start from frame 0 (scene refresh on the first AU); seeks with `debug.nuvio.dv_refresh_every_start` at 0
and 1; the comparison with the `mkvdovi` reference RPUs (on the owner's hdr-analyze host).

## 2026-10-25: DV2.10 HLG signalled in the alternative-transfer SEI (PR #19)

- [DV2.10][desk] HLG signalled in the alternative-transfer SEI (BBC broadcast HLG, found in the DV3.2
  device session on 2026-10-25: a BBC HLG sample states a non-HLG transfer in the SPS and carries
  preferred transfer 18 in the SEI of its first AU, so it played native as SDR). When that SEI
  says 18 and every transfer the container and the SPS state is one with the HLG-compatible curve (1,
  6, 14 or 15, H.273) or unspecified, the HLG contract reads the track as HLG; the other field rules
  stay. An SEI in the hvcC decides at track init; an SEI in the first AU is found by the extractor's
  early scan (the DTS one) before the formats are published, and the Dolby Vision Format replaces the
  provisional HEVC one before `endTracks` and any sample. An SEI found later, a laced, empty, compressed
  or encrypted first block, or an SEI past the scan cap leaves the track native, with a `HLG SEI scan`
  log line; a read error during the scan is rethrown so the loader's retry scans again. Gate: unit tests: the contract rows (14 and 1 lifted, a container 18 over
  SPS 14 accepted, SPS 16 with SEI 18 not HLG, the unspecified case); through the vendored extractor, an
  SEI in the hvcC and in the first block converts with one RPU per AU and the Dolby Vision Format
  before `endTracks`, an audio block before the first video block, an SEI only in a later AU, a laced
  first block, zlib and header-strip compression, conversion off (no scan) and a non-VCL prefix past
  the cap stay native. Needs: DV2.4, DV2.7.

**Evidence**

- Gate `:app:testFullDebugUnitTest`: 6790 tests, 0 failures, 10 skipped, on the final head.
- New tests: `MatroskaSeiHlgScanTest` (17, through the vendored extractor: an SEI in the first block
  converts with the Dolby Vision Format before `endTracks` and one RPU per AU; an SEI in the hvcC converts
  at track init with one RPU per AU and no scan; an audio block before the first video block; a mid-file
  start; an SEI only in a later AU, a non-HLG SEI, laced, empty, header-strip and zlib first blocks, a
  prefix past the cap and conversion off stay native, each asserting its logged reason; deep nesting
  without a stack overflow; a read error with a resumed retry, three read errors in a row, and a retry
  from the file start; a Cluster after an unknown-size Cluster). Contract rows in `HlgContractTest` (the
  compatible transfers 1, 6, 14, 15 lifted, container HLG over SPS 14, PQ not lifted, the unspecified
  case, the other field rules, a refusal credited to the SEI only when the SEI caused it, the log
  wording) and `MatroskaHlgContractTest`; the prefix walker in `HevcInitNalsTest`. Two DV2.4 tests that
  asserted the SEI never changes the decision follow the new rule.
- Before `/ship`, three Claude reviewers (Opus) and a skeptic read the branch, then a second Opus pass
  read the fixes; fixed on the branch before the cold review: an unbounded scan recursion, a transient read
  error leaving the track native for the playback, an empty first block letting a later AU decide, a retry
  of a stream of unknown length (Media3 restarts at 0), missing log lines for compressed blocks, negative
  tests that passed for the wrong reason, and source-specific wording. Each fix was mutation-checked.
- Review (Tier 1, codex gpt-6.1-sol/high): F1 FIXED (a resumed retry read past the first AU before the
  pending scan; red on the reviewed head with three scan read errors) and F2 FIXED (a Cluster after an
  unknown-size Cluster was read as nested and the depth guard skipped its BlockGroup). No P2 notes.
- CI: the `Checks` workflow on PR #19.
- Found in the DV3.2 device session on 2026-10-25: a BBC broadcast HLG sample logged
  `HLG contract, track 1: not HLG; alternative transfer SEI 18 (not acted on)` and played as SDR. The
  device check of the conversion is DV3.2.

**Resolution:** when an alternative-transfer SEI says HLG over an HLG-compatible or unspecified stated
transfer, the HLG contract reads the track as HLG; an SEI in the hvcC decides at track init and one in the
first AU is read by the early scan before the formats are published, so the Dolby Vision Format replaces
the provisional HEVC one before `endTracks` and any sample.

## 2026-10-24: DV2.7 Extractor injection for tracks that carry no Dolby Vision (PR #17)

- [DV2.7][desk] Extractor injection for tracks that carry no Dolby Vision: when DV2.3 converts a
  Matroska track, the existing transformer (`DolbyVisionMatroskaTransformer.kt`) adds the provider's RPU
  NAL to each AU, with the scene-refresh rule of `DESIGN.md` 2.2 and its debug switch. Gate: unit tests:
  one RPU per AU, every other NAL byte-identical, refresh as the rule says, native tracks untouched.
  Needs: DV2.2, DV2.3. Calling `DoviRpuBuilder` (`DoviRpuBuilder.kt`): per converted track,
  `createStatic84(BT2100)` (or `create(params)`) once; per shot, `renderShot(handle, l1)` once (static
  8.4: one shot with `static84L1()`); per AU, `ShotRpu.copyInto(..., sceneRefresh)`; `release(handle)`
  when the track is released. The library is thread-safe and `ShotRpu` is immutable; the extractor's
  loading thread owns the handle.

**Evidence**

- Gate `:app:testFullDebugUnitTest`: 6756 tests, 0 failures, 10 skipped, on the reviewed head.
- New tests: `ConvertedTrackInjectorTest` (11: byte-identical NALs and one RPU last, a stray RPU dropped,
  frame-0 refresh with and without the switch, the switch waiting for an IRAP AU, length fields of 1 to 4
  bytes and the fit check, malformed lengths refused) and `MatroskaRpuInjectionTest` (15, through the
  vendored extractor: converted output equals the native output plus one RPU per AU; refresh on frame 0
  only, after seeks, after Media3's opening `seek(0, 0)`, on a mid-file start, in a laced first block and
  with an audio-only first Cluster; a PQ track and a missing library stay native; conversion off and a
  native Dolby Vision track untouched; the HDR10+ strip mode keeps the RPU; the length-field veto).
  `MatroskaDvSignallingTest` tests the table with a source that has an RPU for both targets.
- CI: the `Checks` workflow on PR #17.
- Before `/ship`, three Claude reviewers (Opus) read the branch. Five findings were fixed on it before the
  cold review: frame 0 missed when the first Cluster holds no AU of the track; the switch flagged an AU
  Media3 drops after a seek; an RPU too long for a 1- or 2-byte NAL length field left a Dolby Vision
  track without RPUs; the setting stayed enabled in the strip modes; a re-entered Dolby Vision sample
  with BlockAdditions lost its supplemental size prefix (no JVM reproduction).
- Review (Tier 1): codex failed on the ChatGPT usage limit; gemini, substituted by the owner, said LGTM.
  P2 note: the length-field rejection message names the scene-refresh size even when the other variant
  is the one that does not fit.
- Decisions that narrow the step: only the static 8.4 provider exists, so PQ tracks stay native until
  DV3.1 or DV3.3; the static shot is rendered at track init and the builder handle released at once
  (`ShotRpu` holds its own bytes; a per-shot provider will need a release hook on the extractor); the
  owner chose to show the setting, on Nvidia, as "Convert HLG to Dolby Vision".

**Resolution:** a Matroska HEVC track that DV2.3 converts gets its RPUs prepared at track init (native
when there is none) and one RPU NAL appended to every AU by `ConvertedTrackInjector`, with
`scene_refresh_flag` on frame 0 only and `debug.nuvio.dv_refresh_every_start` for every start; HLG plays
as 8.4 with the setting on, PQ stays native.

## 2026-10-24: DV2.8 RPU debug dump (PR #16)

- [DV2.8][desk] RPU debug dump behind a `debug.nuvio.*` switch: the RPU of the first N AUs after a start
  or seek and of every scene-refresh AU, for the existing Dolby Vision path and for converted tracks,
  written to logcat with its AU's presentation time; a script in `scripts/` (added to the `.gitignore`
  exceptions) rebuilds an `RPU.bin` from it for `dovi_tool info`. Gate: unit test of the encoder; a
  fixture round trip through the script.

**Evidence**

- Gate `:app:testFullDebugUnitTest`: 6729 tests, 0 failures, 10 skipped, on the final head; `scripts/tests`
  (`cd scripts && python3 -m unittest discover -s tests`): 68 tests, OK, 1 skipped (`dovi_tool info`, not
  installed). CI does not run `scripts/tests`.
- Encoder: `RpuDumpFormatTest` (6: chunk boundaries, line length, hex and CRC round trip, fields) and
  `RpuDumpRecorderTest` (17: first N per generation then refresh-only, a seek inside the buffer, a Format
  change, the AU from the buffer's position to its limit, parameter sets before the sample, trailing zero
  bytes, encrypted and non-HEVC AUs, length-delimited samples).
- Fixture round trip: `p81_v29_shots.RPU.bin` (three shots, `dovi_tool generate` 2.3.4) through the
  recorder into the committed `scripts/tests/fixtures/rpu_dump_p81_v29.logcat` (`RpuDumpFixtureTest`) and
  back through the script into a byte-identical `RPU.bin` (`test_rpu_dump_to_bin.py`).
- CI: the `Checks` workflow on PR #16.
- Review (Tier 2, codex and gemini, two rounds). Round 1 (the dump at the extractor output): F1 FIXED (a
  laced Matroska BlockGroup with supplemental data lost both RPUs; red on the reviewed head in a temporary
  worktree); F2 (a seek Media3 serves from the buffer did not restart the first N) and F3 (HLS and DASH were
  never tapped) NEEDS-HUMAN. The owner chose to move the dump to the video renderer, which fixes all three;
  round 2 reviewed that move: F4 (renderer time with Media3's stream offset was logged as `pts=`) and F5
  (native FEL playback was no longer dumped) fixed in code, NEEDS-HUMAN. F2 to F5 have no JVM
  reproduction; DV2.9 checks them on the device. Gemini P2 notes: a repeated assertion in the script test;
  a reused ExoPlayer keeps its recorder, so `seq` continues across playbacks.

**Resolution:** `RpuDumpRecorder`, fed by `DecodeOrderPtsVideoRenderer` (and `AmlDvFelVideoRenderer` on the
native FEL path) with every queued input buffer and position reset in media time, logs the RPU NAL of the
first N AUs of each generation and of every scene-refresh AU as hex records under `NuvioRpuDump`;
`scripts/rpu_dump_to_bin.py` checks and reassembles them into an `RPU.bin` in `dovi_tool`'s layout.

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
