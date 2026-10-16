# Codex pass 0: independent design (2026-10-16)

Model gpt-6-astra, effort medium, read-only sandbox. Codex saw no draft. The prompt is in `codex-r0-prompt.txt`.

## A. Architecture

**I would build one RPU injection layer with interchangeable metadata providers: static, cached/server-generated, and experimental local measurement.** Local measurement should be optional: the largest uncertainty is concurrent decoding and pixel access, not RPU generation.

### Playback and analysis paths

```text
Demux original HEVC access units ─┬─► Media3 compressed buffer
                                 │          ↓
                                 │   metadata readiness gate → append RPU → DV decoder → TV
                                 │          ↑
                                 └─► auxiliary HEVC decoder → GPU statistics → L1 provider
                                                        cache/server ───────┘
```

1. **Rewrite the track Format before decoder selection.** The current Matroska rewrite hook runs only for already-DV tracks; ordinary HDR10/HLG needs a general HEVC Format hook. Also, initialization data is **derived from hvcC**, rather than the literal hvcC blob. See [MatroskaExtractor.java:3446](/Users/konstantinosfotiou/Documents/GitHub/NuvioTV-Fork/app/src/main/java/com/nuvio/tv/core/player/dvmkv/MatroskaExtractor.java:3446) and [3274](/Users/konstantinosfotiou/Documents/GitHub/NuvioTV-Fork/app/src/main/java/com/nuvio/tv/core/player/dvmkv/MatroskaExtractor.java:3274).

2. **Tee original compressed samples into an asynchronous analysis worker.** Identify samples by track/configuration, seek generation, decode-order sequence and original presentation timestamp. Decode order and presentation order must remain distinct.

3. **Insert measured RPUs late**, through a `SampleStream` wrapper or an explicit renderer-input gate before copying/queuing into codec-owned input memory. Allow “not ready,” preserve a pending AU, and budget RPU capacity. The existing [`onQueueInputBuffer` override:82](/Users/konstantinosfotiou/Documents/GitHub/NuvioTV-Fork/app/src/main/java/com/nuvio/tv/ui/screens/player/DecodeOrderPtsVideoRenderer.kt:82) only records timestamps; it is not an asynchronous waiting mechanism. Inspect the bundled Media3 implementation before changing it: the app uses [local AARs:465](/Users/konstantinosfotiou/Documents/GitHub/NuvioTV-Fork/app/build.gradle.kts:465).

4. **Keep extractor-time insertion for immediately available metadata**, including static RPUs, cached RPUs and pipeline 1. Blocking the current [synchronous sample transformer:2395](/Users/konstantinosfotiou/Documents/GitHub/NuvioTV-Fork/app/src/main/java/com/nuvio/tv/core/player/dvmkv/MatroskaExtractor.java:2395) until future frames arrive would block the loader that must read those frames.

### Getting pixels into Vulkan

My proposed first implementation is:

**Auxiliary ordinary HEVC `MediaCodec` → `ImageReader(PRIVATE, GPU_SAMPLED_IMAGE)` surface → `AHardwareBuffer` → native Vulkan sampling/reduction.**

Query the actual buffer’s format, external-format identifier and sampling capabilities. Import through `VK_ANDROID_external_memory_android_hardware_buffer`; use `VkSamplerYcbcrConversion` when required. Implement acquire/release fences and retain each image until GPU completion. These mechanisms exist, but their availability and precision on this Shield need testing. Android supports the PRIVATE/GPU-sampled combination; public `YCBCR_P010` starts at API 31, so **API 30 cannot assume public P010 output**. [Android ImageReader](https://developer.android.com/reference/android/media/ImageReader), [ImageFormat](https://developer.android.com/reference/android/graphics/ImageFormat), [Khronos import specification](https://docs.vulkan.org/refpages/latest/refpages/source/VK_ANDROID_external_memory_android_hardware_buffer.html).

**Choose C++ Vulkan beside the existing JNI bridge, or Rust `ash`; I slightly prefer C++ for the first device probe.** Keep reusable metadata/aggregation code in Rust. wgpu can run the reductions, but Android import requires backend interop; external-only YCbCr formats may require an extra conversion pass. It is possible, not a clean portability abstraction for this particular boundary. [wgpu Android import implementation](https://docs.rs/wgpu-external-frame/latest/src/wgpu_external_frame/lib.rs.html).

For HLG, ordinary RGB sampling may already interpolate chroma or alter precision in ways that prevent desktop parity. Require recoverable code-domain Y/Cb/Cr sampling, or label that analysis approximate.

**A second decoder is necessary for same-frame local measurement under this architecture.** Pixels from the playback decoder arrive after its RPU deadline. Software-decoding a genuinely separate low-resolution proxy is another option; resizing decoded 4K output does not make the underlying decode cheap. **Guess:** full-resolution Main10 software decode on four A57 cores is not a practical concurrent playback path.

### Where lookahead comes from

Compressed load-ahead supplies future bytes; the auxiliary decoder turns them into an **analyzed-through watermark**. Startup waits for a bounded lead, and the video gate consumes only metadata ready for its AU.

[`BitrateAwareLoadControl`:62](/Users/konstantinosfotiou/Documents/GitHub/NuvioTV-Fork/app/src/main/java/com/nuvio/tv/core/player/BitrateAwareLoadControl.kt:62) budgets compressed data. [`ReadAheadReserve`:54](/Users/konstantinosfotiou/Documents/GitHub/NuvioTV-Fork/app/src/main/java/com/nuvio/tv/core/player/ReadAheadReserve.kt:54) reclaims that budget for other work—it does not create lookahead. Integrate decoder/GPU memory into the same governor.

**No finite playback buffer guarantees exact whole-scene L1.** A long scene can exceed it. Offer explicit policies:

- Cached/pre-analyzed: exact desktop metadata.
- Bounded local analysis: approximate window/scene metadata.
- Deadline missed: static metadata, with promotion at a later scene boundary.

Never retain seconds of decoded frames, block the playback thread, or expect later metadata to repair already-decoded pictures.

## B. What can stream, and what approximation costs

| Component | Actual dependency |
|---|---|
| PQ histogram, max-RGB, sums, spatial robust peak | Per-frame; suitable for GPU reduction and tiny CPU readback. [frame.rs:750](/Users/konstantinosfotiou/Documents/GitHub/hdr-analyze/hdr_analyzer_mvp/src/analysis/frame.rs:750) |
| Histogram EMA / temporal median | Causal history, once scene boundaries are known. [pipeline.rs:1330](/Users/konstantinosfotiou/Documents/GitHub/hdr-analyze/hdr_analyzer_mvp/src/pipeline.rs:1330) |
| Scene detector | Future context: flash rejection through four analyzed frames, baseline through twelve; final strongest-first suppression is global. Exact current behavior is not simply a twelve-frame streaming delay. [scene.rs:63](/Users/konstantinosfotiou/Documents/GitHub/hdr-analyze/hdr_analyzer_mvp/src/analysis/scene.rs:63), [125](/Users/konstantinosfotiou/Documents/GitHub/hdr-analyze/hdr_analyzer_mvp/src/analysis/scene.rs:125) |
| Scene L1 | Whole scene: minimum, mean of unfiltered frame means, maximum of frame peaks. [l1_sidecar.rs:278](/Users/konstantinosfotiou/Documents/GitHub/hdr-analyze/hdr_analyzer_mvp/src/l1_sidecar.rs:278) |
| Optimizer | Trailing 240-frame history plus complete-scene information; optional backward smoothing needs the scene. **Not intrinsically whole-file, and unnecessary for normal measured L1.** [optimizer.rs:109](/Users/konstantinosfotiou/Documents/GitHub/hdr-analyze/hdr_analyzer_mvp/src/optimizer.rs:109), [cli.rs:128](/Users/konstantinosfotiou/Documents/GitHub/hdr-analyze/mkvdovi/src/cli.rs:128) |
| Crop | Individual detection is frame-local; current selection samples across the file and commits a voted crop. Streaming needs another policy. [ffmpeg_io.rs:80](/Users/konstantinosfotiou/Documents/GitHub/hdr-analyze/hdr_analyzer_mvp/src/ffmpeg_io.rs:80) |

**Accuracy implications, inferred from those algorithms:**

- **1-in-N:** misses brief peaks, flashes and cuts; biases averages. The existing sampling decision occurs [after decoding:913](/Users/konstantinosfotiou/Documents/GitHub/hdr-analyze/hdr_analyzer_mvp/src/pipeline.rs:913), so it saves analysis work, not normal HEVC reference decoding.
- **Keyframes only:** useful for previews or coarse pre-analysis, poor final L1. GOP boundaries are not reliable scene boundaries.
- **Downscaled planes:** small highlights disappear; filtering changes nonlinear-PQ statistics and the robust-peak noise model. Existing CPU resizing and GPU striding are already [non-equivalent:255](/Users/konstantinosfotiou/Documents/GitHub/hdr-analyze/docs/CUDA_PIPELINE.md:255).
- **Discarding temporal layers:** potentially saves decode work only when the actual bitstream dependencies permit it. Inspect temporal IDs/reference structure first; do not equate dropping decoder outputs with skipping decode.

My approximate tier would combine cheap every-frame scene statistics with sparse **full-resolution** peak measurements, increasing sampling near changes. No numerical error bound is defensible without representative content; a skipped one-frame highlight can be missed completely.

## C. Options ranked by value/effort

| Rank | Option | Value / effort |
|---|---|---|
| **1** | Common injector + static RPU tier | Low effort, establishes playback compatibility. Pipeline 1 fits this infrastructure. Static HDR10 conversion is not automatically a picture-quality improvement. |
| **2** | Exact-content sidecar cache | High value, modest effort; desktop-quality metadata with almost no playback analysis cost. Store scene records or RPUs plus an index. |
| **3** | LAN CUDA analysis server | Best route to measured quality without Shield contention. Server reads the same source and returns metadata only; seek requests reprioritize analysis. |
| **4** | Server/NAS watchlist pre-analysis | Excellent first-playback experience when content is available ahead of time. Reuse the same cache protocol. |
| **5** | Shield idle-time pre-analysis | Avoids simultaneous playback decoding; still needs pixel-export support, storage/network access and sustained thermal testing. |
| **6** | Local bounded dual-decoder analysis | Highest integration and hardware risk; worthwhile as an optional tier after probes succeed. |
| **7** | Community sharing | Useful only with exact stream identity and trustworthy provenance; matching different cuts/encodes is substantial work. Start self-hosted. |

Cache keys should cover **encoded video identity, timeline/frame mapping, analyzer version/settings, crop, composer and metadata policy**. A title or filename is insufficient. For immediate lookup, use a provisional fingerprint, then validate chunk/AU hashes as playback advances; a complete content hash is not available instantly on a new stream.

A useful extra: **record finalized analysis during one viewing for the next viewing**, even when first-pass deadlines forced static metadata. Separate “metadata useful now” from “metadata worth caching.”

## D. HLG → 8.4

**The composer itself needs no pixels.** `Bt2100V1` is the default and its coefficients are fixed: [lib.rs:42](/Users/konstantinosfotiou/Documents/GitHub/hdr-analyze/dovi84_composer/src/lib.rs:42), [107](/Users/konstantinosfotiou/Documents/GitHub/hdr-analyze/dovi84_composer/src/lib.rs:107). Mobile generation can reuse those coefficients without fitting anything.

However, **static 8.4 is a new metadata policy**: existing mkvdovi explicitly [rejects HLG without measured L1:35](/Users/konstantinosfotiou/Documents/GitHub/hdr-analyze/mkvdovi/src/metadata/rpu_config.rs:35). Choose and validate nominal L1 separately; do not call it measured. Preserve the composer’s input contract—limited-range BT.2020 NCL—and reject incompatible pixels rather than merely changing tags. [format.rs:343](/Users/konstantinosfotiou/Documents/GitHub/hdr-analyze/mkvdovi/src/metadata/format.rs:343).

Measured L1 must analyze the **composer-reconstructed PQ signal**, not generic HLG luminance:

1. Luma polynomial/LUT.
2. Spatial luma downsampling and chroma MMR.
3. Composed-chroma upsampling with matching siting.
4. DM matrix, source-range policy, max-RGB/histograms/reductions.

The existing [CUDA kernels:178](/Users/konstantinosfotiou/Documents/GitHub/hdr-analyze/hdr_analyzer_mvp/src/analysis/kernels.cu:178) are the port blueprint. Match code normalization, interpolation and floating-point behavior before optimizing.

**Shield support has stronger evidence than “likely”:** the repository records a custom-composer canary producing green output with the DV badge through **Plex on NVIDIA Shield TV Pro → LG C9**, while bt2100 looked neutral. [HLG_COMPOSER.md:254](/Users/konstantinosfotiou/Documents/GitHub/hdr-analyze/docs/HLG_COMPOSER.md:254). That establishes a working Shield playback path, **not this firmware/Nuvio signaling path or numerical decoder parity**.

## E. Risks and cheapest experiments

Run these in order; stop expensive porting when a prerequisite fails.

| Risk | Cheapest decisive experiment |
|---|---|
| Nuvio signals 8.1/8.4 incorrectly | Inject pre-generated RPUs into short HDR10/HLG clips. For HLG compare bt2100, preset and changed-chroma canary. Check picture behavior as well as the DV badge. |
| No usable 10-bit export | Single auxiliary decoder → PRIVATE ImageReader → AHB import. Read back only a test ramp/statistics; verify distinct 10-bit values, range and chroma behavior. Log actual Vulkan extensions/formats. |
| Concurrent decoder/resource limits | Run playback DV plus auxiliary Main10, first 4K24 then 4K60. Measure auxiliary throughput, playback drops, allocation failures and resource reclaim. Advertised instance counts are only upper bounds. [Android capabilities](https://developer.android.google.cn/reference/android/media/MediaCodecInfo.CodecCapabilities) |
| Incorrect pixel math | Compare CPU desktop and SPIR-V results on identical decoded frames: ramps, tiny highlights, grain, saturated colors, chroma edges and letterboxing. Separate decoder-output differences from shader differences. |
| RPU attached to wrong picture | B-frame/VFR clip with frame-specific metadata; exercise seeks, discontinuities and decoder resets. Check AU identity through every stage, not timestamps alone. |
| Lookahead stalls or memory pressure | Long uncut scene plus high-bitrate input; confirm bounded memory and deterministic static fallback. |
| Thermal failure | After short probes pass, sustained worst-case playback plus analysis; track frame drops, analysis lead, memory and thermal status. |

Initial scope should remain unencrypted media; the existing [extractor factory:500](/Users/konstantinosfotiou/Documents/GitHub/NuvioTV-Fork/app/src/main/java/com/nuvio/tv/core/player/DolbyVisionExtractorsFactory.kt:500) already excludes protected processing.

## F. Budget

These are **derived traffic/storage figures, not Shield benchmarks**. Assume 3840×2160 P010-equivalent storage; actual tiled allocations and decoder surfaces vary.

| Resource | 4K24 | 4K60 |
|---|---:|---:|
| Pixels analyzed each second | 199 million | 498 million |
| One decoded frame | 24.9 MB | 24.9 MB |
| One complete frame read per frame | 0.60 GB/s | 1.49 GB/s |
| HLG composed-chroma scratch | 16.6 MB | 16.6 MB |
| HLG scratch write + one read | 0.80 GB/s | 1.99 GB/s |
| Ten seconds of decoded frames | **5.97 GB** | **14.93 GB** |
| Playback frame interval | 41.7 ms | 16.7 ms |

The HLG traffic is additional to input sampling, decoder reference traffic, display and reductions. Avoid an intermediate full-resolution RGBA image: RGBA16F alone is 66.4 MB per frame.

- **RAM:** an illustrative 8–16 auxiliary surfaces consume roughly **199–398 MB**, before scratch and driver overhead. Count native/GPU allocations, not only Java heap. Compressed ten-second lookahead at 80 Mb/s is another **100 MB**.
- **GPU:** fuse sampling and reduction where practical; use workgroup-local histograms followed by reduction. HLG is materially heavier than HDR10. Do not extrapolate RTX 4070 timings.
- **CPU:** retain scene aggregation and RPU serialization on CPU; return statistics, never full frames through JNI. Software 4K decode is the wrong budget assumption.
- **Decoder:** two full-rate streams demand roughly 48 or 120 decoded 4K pictures/s respectively, before catching up after seeks. **Guess:** 4K24 may be workable; dual 4K60 is the highest-risk tier.
- **Thermals:** no credible utilization percentage or watt estimate exists without measurement. Throttle analysis first, reduce sampling, then fall back to static/cached metadata.

**My implementation choice:** ship the injector, static tiers and sidecar/server protocol first. Make on-device measured L1 contingent on the 10-bit export and concurrent-decoder probes; treat 4K60 local analysis as experimental.