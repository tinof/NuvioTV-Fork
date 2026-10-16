# On-device Dolby Vision conversion: design

The plan, tiers, gates and steps are in [`ROADMAP.md` Appendix A](../../ROADMAP.md#appendix-a-on-device-dolby-vision-conversion).
This file holds the evidence, the architecture, the budget and the risks behind them. Fork paths are
relative to this repository; hdr-analyze paths are relative to `tinof/hdr-analyze`.

Reviews of this design are kept next to it:

- `codex-r0-prompt.txt`, `codex-r0-ideation.md`: Codex's independent design, made before any draft existed.
- `claude-factcheck-r1.md`: the citation check of draft 1.
- `codex-r1-review.md`: Codex's critique of draft 2, with what was changed for each point.
- `codex-r2-recheck.md`: Codex's re-check of those changes, with the fixes applied after it.

Device results go to `QUALIFICATION.md`.

## 1. What is established

Checked in code on 2026-10-16.

- **Fork pipeline.** Dolby Vision 7 → 8.1 runs per sample in the extractor (the NAL loop of
  `DolbyVisionMatroskaTransformer.kt:426-474`). The extractor rewrites the Format before Media3 picks a
  decoder. For Matroska, HEVC `initializationData` is the parameter sets from the hvcC only
  (`MatroskaExtractor.java:3277`), so the Shield's Dolby Vision decoder already plays the fork's 8.1
  output with no dvcC: the MIME type and the codecs string select it. MP4 is different: Media3 appends
  the dvcC to `initializationData` (`BoxParser.java:1477-1493` in Media3 1.8.0), and the renderer sends it
  as csd-1. An HDR10 or HLG source has no dvcC to pass, which matches the Matroska case.
- **Signalling gap.** Both rewrite hooks run only for tracks that already carry Dolby Vision: a dvcC
  (`MatroskaExtractor.java:3446`) or a `dvhe.`/`dvh1.` codecs string (`:3489`). HDR10, HDR10+ and HLG
  tracks need a new branch that sets `video/dolby-vision` and `dvhe.08.xx`. The extractor publishes each
  track's Format at the end of its TrackEntry, before any frame (`MatroskaExtractor.java:1402-1415`), and
  its comment warns that holding the Format back can deadlock preparation (`:1411-1414`).
- **Sample framing.** The transformer sees length-prefixed NALs, but the Matroska extractor writes
  Annex B start codes into the sample queue (`MatroskaExtractor.java:2533-2534`). At renderer input a
  sample is therefore Annex B for Matroska; other containers are for DV1.1 to read.
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
  own SEI and holds it while the payload stays the same.
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
- **Fork HLG detection** reads only the Matroska Colour element (`MatroskaExtractor.java:1699-1705`); the
  SPS VUI and the alternative-transfer SEI are ignored.
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
  (`NuvioExoPlayerPerformanceHelper.kt:66-67`), within a byte budget of at most 500 MB on the 3 GB
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
Sampling may be approximate; the signal may not.

### 2.7 Shared Rust code

hdr-analyze provides three separate pieces, each in its own pull request:

1. **RPU builders** (8.1, 8.4, HDR10+, static), which the Android bridge needs first. `mkvdovi` output
   stays byte-identical.
2. **Analyzer extraction**: per-frame statistics on plain planes and shot L1, with no behaviour change.
3. **A causal scene detector**, versioned on its own, with a chosen delay and a rule that commits a
   boundary irreversibly. It is a new policy: its disagreement with the global detector is measured, and
   the global detector stays the reference for offline parity.

JNI, Gradle and APK integration stay in the fork.

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
  off until G-B measures them.
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
- An untagged HLG stream signalled as 8.1 would get HDR10 colour info. The HLG contract (DV2.4) comes
  before any HLG tier.
- Dolby Vision is a Dolby trademark; this is an unofficial conversion with no certification claim, as in
  hdr-analyze's `docs/PROVENANCE.md`.
