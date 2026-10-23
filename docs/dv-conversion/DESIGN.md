# On-device Dolby Vision conversion: design

The plan, tiers, gates and steps are in [`ROADMAP.md` Appendix A](../../ROADMAP.md#appendix-a-on-device-dolby-vision-conversion).
This file holds the evidence, the architecture, the budget and the risks behind them. Fork paths are
relative to this repository; hdr-analyze paths are relative to `tinof/hdr-analyze`.

Reviews of this design are kept next to it:

- `codex-r0-prompt.txt`, `codex-r0-ideation.md`: Codex's independent design, made before any draft existed.
- `claude-factcheck-r1.md`: the citation check of draft 1.
- `codex-r1-review.md`: Codex's critique of draft 2, with what was changed for each point.
- `codex-r2-recheck.md`: Codex's re-check of those changes, with the fixes applied after it.
- `codex-dv7-review.md`: Codex's review of the Profile 7 additions (section 5, DV7), with the
  disposition of each point.

Device results go to `QUALIFICATION.md`.

## 1. What is established

Checked in code on 2026-10-16; fork references re-checked on 2026-10-23, after DV2.2 and DV2.4.

- **Fork pipeline.** Dolby Vision 7 → 8.1 runs per sample in the extractor (the NAL loop of
  `DolbyVisionMatroskaTransformer.kt:426-474`). The extractor rewrites the Format before Media3 picks a
  decoder. For Matroska, HEVC `initializationData` is the parameter sets from the hvcC only
  (`MatroskaExtractor.java:3521-3522`), so the Shield's Dolby Vision decoder already plays the fork's 8.1
  output with no dvcC: the MIME type and the codecs string select it. MP4 is different: Media3 appends
  the dvcC to `initializationData` (`BoxParser.java:1477-1493` in Media3 1.8.0), and the renderer sends it
  as csd-1. An HDR10 or HLG source has no dvcC to pass, which matches the Matroska case.
- **Signalling gap.** Both rewrite hooks run only for tracks that already carry Dolby Vision: a dvcC
  (`MatroskaExtractor.java:3696`) or a `dvhe.`/`dvh1.` codecs string (`:3741-3742`). HDR10, HDR10+ and HLG
  tracks need a new branch that sets `video/dolby-vision` and `dvhe.08.xx` (DV2.3); DV2.4 records the HLG
  decision on the track but does not rewrite the Format. The extractor publishes each
  track's Format at the end of its TrackEntry, before any frame (`MatroskaExtractor.java:1489-1498`), and
  its comment warns that holding the Format back can deadlock preparation (`:1494-1497`).
- **Sample framing.** The transformer sees length-prefixed NALs, but the Matroska extractor writes
  Annex B start codes into the sample queue (`MatroskaExtractor.java:2610-2611`, `:2681-2682`). At
  renderer input a sample is therefore Annex B for Matroska; other containers are for DV1.1 to read.
- **RPU generation.** The bundled libdovi C API parses, converts and writes RPUs; it cannot generate
  one per frame (`DV7/libdovi/android-arm64/include/libdovi/rpu_parser.h:551-741`). The pure-Rust crates
  can: `dolby_vision` 3.4 (`DoviRpu::profile81_config`, `profile84_config`, `write_hevc_unspec62_nalu`),
  `hdr10plus` 2.1.5 (`Hdr10PlusMetadata::parse` reads one ST 2094-40 payload) and hdr-analyze's
  `dovi84_composer` (a lib crate). None has C dependencies. Writing an RPU serializes it, computes its
  CRC and adds emulation prevention (dovi_tool `dolby_vision/src/rpu/dovi_rpu.rs:231-237`, `:293-309`),
  so a changed L1 is a newly written RPU, never a patch of the escaped bytes.
- **HDR10+ → L1** is arithmetic on metadata (dovi_tool `src/dovi/generator.rs:211-216`): min 0, max from
  the peak source (default `histogram`), average from `average_rgb / 10`, then the generator clamps
  (max in 2081..4095, avg ≥ 819 or 1229, min ≤ 12). dovi_tool takes these values from the first frame of
  each HDR10+ scene and holds them for the shot (`generator.rs:189`, `:205-209`); `hdr10plus_tool` starts
  a new scene when the metadata differs from the previous frame. A live port computes L1 from each AU's
  own SEI and holds it while the scene key stays the same: the fields `hdr10plus_tool` compares, not
  the whole payload (`fraction_bright_pixels` is not compared; hdr-analyze `docs/RPU_BUILDER.md`,
  `SceneKey` and `Hdr10PlusHold`).
- **hdr-analyze per frame.** The statistics (PQ histogram, max-RGB peak, the min at P0.1, means) need
  only the current frame (`hdr_analyzer_mvp/src/analysis/frame.rs:644-919`).
- **hdr-analyze per shot.** Scene detection looks 4 analyzed frames around each frame for the score and
  12 before and 12 after for the baseline (`analysis/scene.rs:6`, `:10`). It then sorts all candidates of
  the file, strongest first, and an accepted cut blocks candidates closer than the minimum scene length,
  counted in source frames (`scene.rs:110-147`). A suppression can chain through nearby candidates, so
  the detector has no fixed streaming delay. Shot L1 is the min of the frame minimums, the mean of the
  frame max-RGB means and the max of the frame peaks (`l1_sidecar.rs:278-295`), so a shot's L1 is known
  only after its last frame.
- **The L1 sidecar** indexes scenes by measured presentation frame, and since version 5 records the
  stream's coded pictures and the leading RASL pictures no decoder outputs (`l1_sidecar.rs:13-18`,
  `:92-103`). It has no timestamps and no content identity beyond file name and size.
- **Not needed for L1.** The optimizer, the hue histogram and the histogram smoothing feed only the madVR
  output. Crop detection seeks 7 times across the file; `--crop-probes 0` commits on the first usable
  frame, which is the causal route.
- **Shot holding.** In the nine retail RPUs hdr-analyze checked, L1 changes only at shot starts
  (`docs/CM_ANALYZE_PARITY.md:192-197`). Those samples have no fades or dissolves. This plan holds L1
  per shot as a conservative policy; how a display behaves under other policies is not measured.
- **HLG → 8.4.** The composer is fixed per title (`dovi84_composer/src/lib.rs:42-65`, `:107-145`), and
  so is everything else except L1. `mkvdovi` refuses HLG without L1 measured through the selected
  composer (`mkvdovi/src/metadata/rpu_config.rs:35-56`), so a static-L1 8.4 RPU is a new policy, not a
  port. The 8.4 RPU describes only limited-range BT.2020 NCL input with BT.2020 primaries, and
  `mkvdovi` refuses any source tag that contradicts this (`mkvdovi/src/metadata/format.rs:343-377`);
  changing tags cannot repair such a source.
- **8.4 on this Shield model.** Plex on a Shield TV Pro into an LG C9 showed the Dolby Vision badge for
  8.4 files, and a canary composer turned the unchanged HLG base layer green (hdr-analyze
  `docs/ROADMAP_LOG.md:237-262`). The Shield-to-TV chain therefore decodes 8.4 and applies the composer.
  The fork's path is untested. In the same test, an HLG source signalled only in the VUI played as SDR.
- **Fork HLG detection (DV2.4, PR #13).** At track init, `HlgContract.resolve`
  (`MatroskaExtractor.java:3884-3892`) decides from the Matroska Colour element, now with
  MatrixCoefficients and Range (`:1825-1832`), and the first SPS's VUI (`HevcInitNals.firstSps`,
  `HevcVuiColour`; `:3525-3529`). The alternative-transfer SEI, from the hvcC or the first sample
  (`:3529`, `:2414`), is logged only. The decision is kept on the track (`getHlgDecision`); nothing acts on
  it before DV2.3.
- **CUDA is not an option.** CUDA for Android ended with CodeWorks for Android (1R6 had CUDA 6.5 and
  7.0; 1R7 dropped CUDA). One NVIDIA forum thread (forums.developer.nvidia.com/t/77215, June 2019)
  reports `cudaErrorInsufficientDriver` on Shield TV with Android 8.0. hdr-analyze's GPU path needs
  CUDA 12 and NVRTC.
- **Vulkan on the Shield is not confirmed for this firmware.** The Shield TV reports on
  vulkan.gpuinfo.org are from Android 7.0 (driver 361.0.0.0): report 2952 at Vulkan 1.0.61 with
  `VK_KHR_sampler_ycbcr_conversion`, report 1977 at 1.0.56 without it. Both list `shaderInt64` and
  48 KB compute shared memory, and neither lists `VK_ANDROID_external_memory_android_hardware_buffer`.
  What counts is the import extension, the formats and the features on SE 9.2.4, not the version number.
- **wgpu cannot import decoder frames.** It has no YCbCr samplers or external formats
  (github.com/water-rs/wgpu-external-frame issue 16 and PR 17); the import needs raw Vulkan (`ash` or
  C++). wgpu could still run the reductions after a copy. The public `YCBCR_P010` image format starts at
  API 31, so on API 30 a P010 output is a capability to probe, not an assumption.
- **Renderer input.** In Media3 1.8.0, `onQueueInputBuffer` receives the codec's own input buffer
  (`MediaCodecRenderer.java:432`, `:1394`). The hook runs at `:1542`, and `queueInputBuffer` sends
  `limit()` bytes after it (`:1555-1560`); after that the renderer no longer owns the buffer. A codec
  reconfiguration can put parameter sets in front of the sample (`:1421-1430`). A subclass can rewrite
  bytes in place or append up to the buffer's capacity; it cannot grow the buffer or wait. Codecs are
  flushed and released without a renderer reset (`:1494-1499`); the fork's
  `DecodeOrderPtsVideoRenderer.kt:146-154` already handles every flush and release.
- **Look-ahead.** The player loads 15 to 45 s ahead of the playhead
  (`NuvioExoPlayerPerformanceHelper.kt:57-58`), within a byte budget of at most 500 MB on the 3 GB
  Shield Pro (`BufferSizing.kt:17`; the 2 GB Shield gets 150 MB). The budget is lowered for the back
  buffer and other uses (`BufferSizing.kt:75-99`, `ReadAheadReserve.kt:54-63`), so a reservation for
  analysis shortens the look-ahead it needs.
- **Second decode today.** The app has no Vulkan or GLES code. Seek thumbnails decode sparse keyframes
  in software through a dlopen'd FFmpeg; the device report measured that sweep at 43 % CPU and 387 MB
  native heap.
- **Output formats.** The 2019 Shield outputs Dolby Vision and HDR10, not HDR10+ (The Verge,
  2019-10-28; androidtvnews.com, "NVIDIA Shield differences").
- **Decoder.** NVIDIA specifies "up to 4K HDR playback at 60 FPS (H.265/HEVC)" (nvidia.com/shield). No
  source shows two concurrent 4K decodes. On the desktop, decode limits hdr-analyze, not analysis
  (1888 fps against 766 fps for 4K HLG, `docs/CUDA_PIPELINE.md:215-217`); on the Shield the second
  decode and the shared LPDDR4 are the scarce resources, not the 256 GPU cores.

## 2. Architecture

```text
extractor, per AU in decode order
 ├─ HDR10+ SEI ──────────────► HDR10+ provider ─────────────────────────────┐
 ├─ copy of the AU ──► analysis decoder ──► Vulkan statistics ──► shot builder ──┐
 │                                                                              ▼
 │                                              shot commitment (per presentation-order shot)
 ├─ RPU of the committed or fallback provider ──► SampleQueue ──► renderer input:
 │                                                  swap in the RPU committed for this AU's shot
 │                                                  ──► Dolby Vision decoder ──► TV
 └─ Format at track init: video/dolby-vision, dvhe.08.LL (decision table below)
shot lists (playback manifest: local sidecar, LAN server, cache) ──► shot-list provider ─┘
```

### 2.1 Providers

Every AU carries a complete, valid RPU. Providers are judged on three separate questions:

1. **Admissible.** The provider matches the content identity, the timeline, the colour and composer
   contract, and has recorded provenance. An inadmissible provider is never used.
2. **Complete.** The provider covers the whole shot.
3. **Quality class.** `full` (every frame, full resolution, the reference scene policy), `approximate`
   (decimated frames, a causal scene detector, an approximate crop), or `static`.

Default choice per shot: for HDR10+ content, the HDR10+ provider; replacing it with any measured shot
list, `full` included, needs an explicitly enabled policy accepted by G-E. For other content, an
admissible, complete `full` shot list, then a complete `approximate` measurement, then static. "Exact"
means a full-quality analysis of the same file, never just a matching file.

### 2.2 Shot commitment

L1 holds for a shot and changes only at its first frame, where `scene_refresh_flag` is set. Before any AU
of a presentation-order shot is queued to the decoder, the injector commits a provider, an L1 and a
policy revision for that shot, and the commitment is frozen. With B-frames the first AU submitted for a
shot need not be its first frame in presentation order, so the commitment must exist before that AU.
Results that arrive after the commitment go to the cache or to a later shot that is not committed yet. A
mid-shot refresh is not allowed in this plan; if it is wanted later, it is a separate, tested policy.

**Scene refresh at a start or seek (2026-10-23).** The default is `mkvdovi` parity: the flag is set
only on a shot's first frame in the linear stream. A static title therefore sets it on frame 0 only, and
the first AU after a mid-file start or a seek carries 0, as an `mkvdovi` file played from the same point
does. A `debug.nuvio.*` switch instead sets it on the first AU of every generation (section 2.3), which
is the one exception to the rule above. DV3.2 plays a mid-file start and seeks with both settings and
keeps the default unless the TV shows a difference. Media3 can seek inside samples it has already
extracted without running the extractor again; such samples keep the flag they were given (DV1.1 reads
that path). `scene_refresh_flag` is coded ue(v), so the two variants of an RPU can differ in size: the
fork's wrapper renders both once per shot, and the headroom check below uses both.

Before a late provider is committed for a shot, a size and headroom guarantee must hold for every AU of
that shot, including the scene-refresh variant and any reconfiguration prefix; checking only the first AU
is not enough. If the guarantee cannot be established, the whole shot keeps its fallback. A test covers a
later AU with less headroom than the first; no fallback inside a shot is allowed.

### 2.3 AU identity and invalidation

An AU is identified by content and track, configuration revision, analysis generation, decode-order
sequence number and original presentation time. A presentation time alone is not enough: a seek or
codec reuse can repeat it. Decoded analysis outputs are joined to these identities. The analysis
watermark is a presentation-order completeness watermark: it advances only when every earlier
presentation-order frame is analysed or known to be skipped, so a B-frame reorder gap holds it back.

Seek, codec flush, codec release, an incompatible format change and a discontinuity each start a new
analysis generation. Results of an older generation are dropped; only validated, immutable metadata (a
finished cached shot) carries over. Tests cover duplicate presentation times, B-frames across cuts,
backward seeks, late callbacks, end-of-stream drain, codec reuse and the drop-to-keyframe path.

The generation changes in the same critical section that stops issuing work for the old one, not after
it. AetherEngine changed its delivery epoch after the lock was released; a callback in that gap took a
new-epoch ticket, which was dropped as stale, and delivery stalled until its stall guard skipped the
hole (section 5.3). A test covers a new-generation result that arrives before the generation change is
installed.

### 2.4 Injection and replacement

- **Extractor injection** for providers that are ready when the AU is read: static, HDR10+ and a shot
  list loaded at start. It reuses the existing transformer.
- **Renderer replacement** for providers that become ready later: a LAN server or a measurement. Complete
  replacement RPUs are written off the renderer thread, CRC and emulation prevention included. At the
  synchronous `onQueueInputBuffer` hook the injector finds the placeholder RPU in the codec's actual
  framing (Annex B for Matroska, a length prefix where the container keeps one, with any reconfiguration
  prefix in front), checks the real serialized size against the buffer capacity, moves the following
  bytes, writes the RPU, updates a length prefix if there is one, and sets the limit. Parameter sets and
  every other NAL stay byte-identical. Codec-owned memory is never kept or changed after the buffer is
  queued.
- The hook does not wait. If a decision must wait for analysis, the wait belongs before the sample is
  copied into the codec buffer (a readiness gate on the sample stream), as in Codex's first design.
- Tests: growth and shrinkage of the RPU, CRC round trips, payloads that contain start-code patterns,
  reconfiguration prefixes, and byte-identical non-RPU data. The first implementation supports only the
  containers whose framing DV1.1 has verified.

### 2.5 Signalling decision table

The Matroska extractor publishes the Format before it reads a frame, so the decision uses only container
metadata and the SPS in the initialization data.

| Input at track init | Decision |
|---|---|
| Track already carries Dolby Vision (dvcC or `dv*` codecs) | Existing path; no conversion |
| Protected content, setting off, or no Dolby Vision display or decoder | Native playback |
| HEVC Main10, PQ from the container or SPS VUI, no contradicting tag | Dolby Vision 8.1 |
| HEVC Main10, HLG from the container or SPS VUI, limited range, BT.2020 NCL matrix and BT.2020 primaries (untagged fields accepted with a log line) | Dolby Vision 8.4 |
| HLG with a contradicting range, matrix or primaries tag | Native playback |
| Transfer unknown at track init (for example HLG signalled only in an alternative-transfer SEI) | Native playback, unless a separately tested Format-change path is enabled |

The Dolby Vision level comes from the stream's resolution and frame rate. If the decoder fails to
configure, playback falls back to native. The first implementation covers Matroska; MP4 and TS each need
their own signalling and injection step.

### 2.6 HLG measurement

Measured HLG L1 is taken in the PQ domain reconstructed by the selected composer: the luma curve, the
chroma MMR on downsampled luma, the upsampling at the stream's chroma siting, then the RPU matrix and
max-RGB (`frame.rs:682-710`, `:766-784`; `kernels.cu:178-289`). Generic HLG luminance is a different
signal and never stands in for it. The compose pass is therefore part of the first measured HLG tier.
Sampling may be approximate; the signal may not. An absent curve is the identity, not zero: AetherEngine
showed a green and purple picture when chroma curves with fewer than two pivots returned 0.

### 2.7 Shared Rust code

hdr-analyze provides three separate pieces, each in its own pull request:

1. **RPU builders** (8.1, 8.4, HDR10+, static), which the Android bridge needs first. `mkvdovi` output
   stays byte-identical. Done: `dovi_rpu_builder`, hdr-analyze E16.1 (PR #67, 2026-10-22), byte-identical
   to `dovi_tool generate` 2.3.4; contract in hdr-analyze `docs/RPU_BUILDER.md`. Not included: the HDR10
   MaxCLL/MaxFALL heuristic of DV3.3.
2. **Analyzer extraction**: per-frame statistics on plain planes and shot L1, with no behaviour change.
3. **A causal scene detector**, versioned on its own, with a chosen delay and a rule that commits a
   boundary irreversibly. It is a new policy: its disagreement with the global detector is measured, and
   the global detector stays the reference for offline parity.

JNI, Gradle and APK integration stay in the fork.

**Packaging (link test, 2026-10-23).** The vendored `libdovi.a` is itself a Rust staticlib (`dolby_vision`
3.3.2, rustc 1.93.0, not LTO-merged), so a second Rust staticlib cannot share `libdovi_bridge.so` with it:
- With fat LTO, the NDK 29 link fails on `duplicate symbol: rust_eh_personality`, an unmangled name in
  both copies of std. `panic = "abort"` does not remove it.
- Without LTO and with the exact rustc that built `libdovi.a`, the link succeeds, because both archives
  carry the same std object and the linker keeps one. That holds only while the two compilers match, so
  it is not used.

The builder therefore ships as its own `cdylib` (`System.loadLibrary`; 519 KB stripped for arm64-v8a in
the 2026-10-23 test build, needing only libc, libm and libdl). The first static 8.4 AU from such a test build, bt2100 and canary,
equalled hdr-analyze's committed `dovi_tool` fixtures. One combined staticlib (`dolby_vision` 3.4 with
`capi`, plus the builder) replacing `libdovi.a` is the later alternative; it moves the Profile 7 path to
3.4.0.

**Shipped in DV2.2 (PR #14).** The crate `native/rpu_builder` (`nuvio_rpu`, pinned to hdr-analyze
`f55c779`, the merge of its PR #68) builds `libnuvio_rpu.so` (558192 bytes) with
`scripts/build_rpu_builder.sh` into the gitignored `app/src/main/jniLibs/arm64-v8a/`;
`scripts/shield_build_install.sh` runs it first when cargo-ndk is installed. `DoviRpuBuilder.kt` loads it
and reports unavailable without it (armeabi-v7a APKs, JVM tests). CI builds no Rust: the disabled release
workflow verifies with `verify_native_apk.py --allow-missing-rpu-builder`. Codes, pin and tests:
`native/rpu_builder/README.md`.

### 2.8 Playback manifest

The L1 sidecar is not yet a playback protocol. hdr-analyze defines a manifest around the existing L1
payload with:

- the encoded track identity and incremental validation of AUs or chunks as they are read;
- the mapping from presentation index to AU and presentation time, the timebase and the leading-picture
  rule (no `time × fps` arithmetic);
- the analyzer version and settings, crop, composer and metadata policy;
- the coverage and quality class of each range.

An imported `RPU.bin` needs the same manifest. File size plus hashes of the first and last chunks only
identify a candidate; the AU checks during playback confirm it. Tests: variable frame rate, a non-zero
start time, an open-GOP start, a different cut or encode, and a mismatched composer.

### 2.9 Measuring on the Shield

Pixels from the playback decoder arrive after the input deadline of their own RPU, so they cannot give
same-frame L1. The live design therefore uses a second decoder. Whether the app can read pixels while
keeping Dolby Vision output is not verified. Two single-decoder modes stay open, because neither needs a
second concurrent decode (G-B):

- **Idle-time pre-analysis** of the next episode or a continue-watching item, while nothing plays. It
  needs the pixel path (G-A) and a thermal run (G-F), and fills the cache.
- **Native first play.** The title plays as native HDR10 while the playback output is analysed for the
  cache. An experiment only; it depends on reading the playback output at all.

The first Vulkan port is smaller than the CUDA pipeline: PQ statistics with workgroup-local histograms
and partial sums, finished by a small CPU or second-dispatch reduction, with no global 64-bit atomics.
Then every-frame cut statistics plus sparse full-resolution peak measurements, sampled more densely
around changes. This saves analysis work, not HEVC reference decoding. HLG composition follows as its own
step, checked against the two-pass desktop path. A tiled compose pass with a halo might avoid the scratch
write and read; it is a hypothesis to measure.

Two rules from AetherEngine's Metal composer apply to the Vulkan import (DV5.11): a GPU completion
callback releases its slot and does nothing that can block, and every other step moves to another
queue (its first asynchronous version deadlocked against the decoder lock); and an imported image keeps
both its view and its source buffer alive until the GPU has finished with it.

## 3. Budget

Derived, not measured. One 4K frame in P010 is 24.9 MB.

| Resource | 4K24 | 4K60 |
|---|---:|---:|
| Pixels analysed per second, every frame | 199 M | 498 M |
| One full read of every frame | 0.60 GB/s | 1.49 GB/s |
| HLG composed-chroma scratch (two f32 per chroma sample) | 16.6 MB | 16.6 MB |
| HLG scratch, one write and one read | 0.80 GB/s | 1.99 GB/s |
| Frame interval | 41.7 ms | 16.7 ms |

- **Bandwidth.** The frame read is 2 to 6 % of the Tegra X1's 25.6 GB/s LPDDR4 peak (the X1+ figure is
  unconfirmed); HLG adds the scratch traffic, then extra taps, two decodes, the display and the HDR
  composition. A stride of 2 in both directions quarters the statistics samples; the end-to-end
  bandwidth and time, composition included, are measured, not derived.
- **GPU.** Guess: 10 to 20 ms per 4K frame at stride 1 on the Maxwell GPU for PQ. The kernel runtime is
  measured on the device after DV5.3; DV1.2 qualifies only the pixel access.
- **Memory.** One budget covers the compressed playback and back buffer, the tee queue, the analysis
  decoder's surfaces, imported images held until the GPU finishes, the scratch buffers and driver
  allocations. Guess for the decoder side: 150 to 400 MB. Ten seconds of compressed video at 80 Mb/s is
  about 100 MB before overhead. Analysis is turned off when its reservation would cut the playback
  reserve; the time to reach the lead after start and after a seek is measured.
- **Decode.** Two full-rate 4K24 streams need 48 decoded frames per second, and the analysis decoder must
  run faster than playback to catch up. Two 4K60 streams are outside the validated envelope: they stay
  off until G-B measures them. The enable decision is a pure, unit-tested pixel-rate check (width ×
  height × frame rate of both decodes against a ceiling that G-B sets), as AetherEngine gates its
  dual-layer route at 311 Mpx/s. An unknown frame rate or size refuses the second decode until it is
  measured; budgeting it as 24 fps, as AetherEngine does, would admit a 60 fps stream.
- **Thermal.** Unknown. Under pressure the analysis lowers its rate first, then stops; playback keeps
  the committed or fallback RPUs.

## 4. Risks and rejected ideas

- Static HDR10 → 8.1 may look worse than the TV's own HDR10 processing. It stays opt-in until G-E.
- A per-frame L1 without shot holding risks visible pumping. Not used.
- Static L1 values are heuristics, not measurements: the 8.4 maximum of 3079 is the source-range ceiling,
  and MaxFALL is the brightest frame average of the title, not a shot average. Dark HLG material and
  missing, zero or implausible MaxCLL/MaxFALL are part of the quality gate.
- Peaks measured through GLES external sampling: unknown colour conversion, possibly 8 bits. Allowed
  for cut detection only.
- A full-rate second 4K60 decode. Off unless G-B shows headroom.
- An approximate measurement does not become exact by being cached. The cache stores finished ranges
  with their provenance and quality class and reuses them at that class.
- Shot lists shared with other users: a wrong match spoils a film, and the key reveals what a user plays.
  Own devices only.
- An untagged HLG stream signalled as 8.1 would get HDR10 colour info. The HLG contract (DV2.4, done in
  PR #13) comes before any HLG tier.
- Dolby Vision is a Dolby trademark; this is an unofficial conversion with no certification claim, as in
  hdr-analyze's `docs/PROVENANCE.md`.

## 5. Profile 7 input

Added on 2026-10-19 after reading AetherEngine's FEL design
(github.com/superuser404notfound/AetherEngine, `docs/dolby-vision-fel.md`) against this fork's code and
hdr-analyze `docs/FEL_PLAN.md`. The steps are DV7 in Appendix A.

### 5.1 What the fork does today

Checked in code on 2026-10-19.

- **Conversion per AU.** libdovi 3.3.2 rewrites each RPU: in-band Matroska at
  `DolbyVisionMatroskaTransformer.kt:453`, BlockAdditional through `convertRpuNal` (`:150`, `:399-414`),
  MP4 in `dovi_bridge.cpp:746-780` and TS in `dovi_bridge.cpp:862-900`. Enhancement-layer NAL units are
  dropped. Active Auto conversion uses mode 2 on Nvidia and on devices whose decoder capabilities select
  it, subject to the `dv7_manual` switch (`DolbyVisionBaseLayerPolicy.kt:56-66`, `:150-157`;
  `DolbyVisionExtractorsFactory.kt:308-324`); other devices keep Auto's mode 1.
- **What the modes keep.** For FEL, mode 2 sets the mapping to the identity and drops the residual; the
  display-management levels stay. Mode 1 (`ToMel`) keeps the authored mapping and produces a
  MEL-compatible RPU that keeps Profile 7 syntax; the fork carries it under its Profile 8 signalling
  (`DolbyVisionExtractorsFactory.kt:724-728`). That is a compatibility path, not a Profile 8.1 RPU with
  the mapping kept (`To81MappingPreserved`, which the 3.3.2 C API cannot reach,
  `DolbyVisionExtractorsFactory.kt:313-315`), and not FEL_PLAN candidate (b).
- **Fallback.** The per-RPU fallback from mode 2 to mode 1 exists only in `convertRpuNal`, the
  BlockAdditional path (`:405-411`). In-band Matroska (`:453`), MP4 (`dovi_bridge.cpp:757`) and TS
  (`:878`) use one mode. `docs/shield-todo.md:49` describes the fallback as general. Separately, when the
  decoder fails under mode 2 the whole playback retries once in mode 1
  (`PlayerRuntimeControllerInitialization.kt:2382-2402`, `retryCurrentStreamWithDolbyVisionFallback` in
  `PlayerRuntimeControllerObservers.kt:908`; mode 1 forced on the retry at
  `PlayerRuntimeControllerInitialization.kt:1261`).
- **Failure.** A failed RPU is dropped and the AU goes on without one (`DolbyVisionMatroskaTransformer.kt:461-466`,
  `dovi_bridge.cpp:773-780`, `:894-900`). Matroska abandons conversion after 60 consecutive failures
  (`:168-187`, `:607`); MP4 and TS keep trying and only count the drops (`dovi_bridge.cpp:102-113`). What
  the Shield's Dolby Vision decoder does with an AU without an RPU inside a Dolby Vision stream is not
  measured. The bundled C API cannot change a scene-refresh flag: apart from conversion it can only
  remove the mapping and set the L5 offsets (`DV7/libdovi/android-arm64/include/libdovi/rpu_parser.h`).
- **libdovi can end the process.** The `dolby_vision` 3.3.2 crate, libdovi's Rust core, calls
  `unimplemented!` on a polynomial piece with `poly_order_minus1 == 0` and `linear_interp_flag` set, in
  the parser (`src/rpu/rpu_data_mapping.rs:414`) and the writer (`:229`). Its cargo-c build sets
  `-Cpanic=abort` (`Cargo.toml:35`) and the C API has no `catch_unwind`, so the panic aborts the app.
  Whether the bundled binaries were built with that configuration is not recorded; without
  `catch_unwind` a panic that leaves an `extern "C"` function aborts anyway on Rust 1.81 and later. The
  `catch (...)` in `dovi_bridge.cpp:682` and `runCatching` in `DoviBridge.kt` cannot stop an abort.
  AetherEngine also reports a writer crash on a curve that mixes polynomial and MMR pieces, and that
  libdovi parses before it checks the CRC. The bridge checks no CRC. hdr-analyze found no such RPU on
  its eight FEL test cuts; a corrupt stream can still produce one.

### 5.2 Why the Shield cannot play the FEL residual

- AetherEngine composes the base layer, the enhancement layer and the RPU on the GPU of Apple devices
  and hands the frames to the display layer with a per-frame Profile 8.1 RPU attached, which tvOS
  applies as Dolby Vision display management. Android has no such route: a surface takes only static
  HDR metadata (`ASurfaceTransaction_setHdrMetadata_smpte2086`, `_cta861_3` in the NDK
  `surface_control.h`), and no dataspace or app API carries a Dolby Vision RPU. The gralloc
  `SMPTE2094_10` metadata type is a HAL field that no app API writes. Dolby Vision output comes only from
  a Dolby Vision bitstream into `OMX.Nvidia.DOVI.decode`.
- That decoder uses the base layer and the RPU and ignores the residual (`docs/shield-todo.md:10`;
  NVIDIA and AVS forum reports, no official NVIDIA statement found).
- Composing and re-encoding breaks the rule of A.1. The X1 encoder is specified at 4K30 HEVC, and
  10-bit encoding is not confirmed.
- Real FEL playback in this fork is the Amlogic route (`core/player/amlfel/`, on the AM9 FEL kernel).

### 5.3 What carries over from AetherEngine

| Lesson | Where |
|---|---|
| Check the RPU CRC and pre-scan for the known abort paths before libdovi; targeted tests and a CRC-repairing mutation run | DV7.1 |
| A missing or failed RPU borrows the previous one for at most 3 frames, then the base layer goes on alone; `use_prev_vdr_rpu_flag` is resolved in decode order at demux. Here the borrowed RPU must have its scene-refresh flag cleared, which needs a writer the bundled C API lacks | DV7.2, DV7.3 |
| One log line per load says whether the route was taken, with the first reason in a fixed order | DV7.5 |
| A pulse clip (pixels constant, only the RPU changes) shows that the TV uses the metadata, not only the Dolby Vision mode | G-C, DV7.6, DV7.7 |
| Change the generation inside the critical section that stops the old work | section 2.3, DV5.1 |
| A pure pixel-rate gate for a second decode (an unknown frame rate refuses here) | section 3, DV5.4 |
| Completion callbacks never block; imported images and their buffers live until the GPU finishes | section 2.9, DV5.11 |
| An absent curve is the identity | section 2.6, DV5.8 |

### 5.4 FEL quality through the RPU alone

hdr-analyze's FEL candidates (`docs/FEL_PLAN.md` section 5) all keep the base layer and change only the
RPU, so this injector can carry them with no pixels on the Shield:

- (a) mapping set to the identity, residual dropped: mode 2, what the Shield plays today.
- (b) authored mapping kept in a Profile 8.1 RPU, residual dropped. The fork's mode 1 keeps the
  mapping too, but in a Profile 7 MEL-compatible RPU (5.1), so a mode 1 against mode 2 comparison is a
  first look, not a test of (b). The fork prefers mode 2 because the curves "assume the enhancement
  layer this pipeline strips" (`DolbyVisionExtractorsFactory.kt:313-315`), which is FEL_PLAN's open
  question for (b). hdr-analyze's reference reconstruction decides it.
- (c) a mapping refitted per frame or shot against the reference reconstruction: computed on a PC,
  delivered as complete RPUs in the playback manifest (section 2.8).
- (d) residual statistics per title, for the HUD.

Order: the device question first (does the Shield-to-TV chain apply a Profile 8.1 mapping at all; the
8.4 canary shows that it applies an 8.4 composer, not that it reads the 8.1 mapping), then hdr-analyze's
FEL_PLAN gates: (b) needs gates 1 to 4, (c) gates 1 to 5, and a default change gate 6. A refitted
mapping can change the serialized RPU size (variable-length coefficients, emulation prevention), so
extractor injection from a manifest must accept a different RPU length per AU; it does not meet the
capacity limit of section 2.4.
