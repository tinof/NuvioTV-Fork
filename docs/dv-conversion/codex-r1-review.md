# Codex pass 1: critique of draft 2 (2026-10-16)

Model gpt-6-astra, effort medium, read-only sandbox, 362 s. Draft 2 was the appendix plus `DESIGN.md` after the citation check. The prompt is in `codex-r1-prompt.txt`. The disposition of each point is at the end.

## Codex output (verbatim)

The overall direction is sound, but I would revise the metadata commitment rules, timeline identity, HLG contract, and gate dependencies before implementation. The placeholder scheme is feasible; the current description does not yet make it safe.

This review is read-only. These findings extend the existing factcheck rather than repeat its corrections. Evidence paths below use `H/` for the supplied hdr-analyze repository, `M/` for the supplied Media3 clone, and `D/` for the supplied dovi_tool clone.

1. **Major — DESIGN §1: the retail evidence does not establish what a display “expects.”**

   **Problem:** DESIGN:48–50 turns an observation from nine RPUs into a decoder/display requirement. The cited report explicitly says its samples contain no fades or dissolves.

   **Evidence:** `H/docs/CM_ANALYZE_PARITY.md:192–197`.

   **Change:** Replace the conclusion with:

   > “The nine tested retail RPUs held L1 within shots. We adopt shot holding as a conservative policy. Behavior through fades/dissolves and visible pumping under alternative policies remain unmeasured.”

   Keep shot holding; remove the unsupported claim about display expectations.

2. **Major — DESIGN §2: the 24-frame streaming-detector bound is not established.**

   **Problem:** Adding the baseline window and minimum scene length does not reproduce the existing detector. Global strongest-first suppression can propagate through a chain of nearby candidates: a later candidate suppresses one that would otherwise suppress an earlier candidate. Furthermore, minimum spacing is measured in **source frames**, while the baseline operates on analyzed frames.

   **Evidence:** `H/hdr_analyzer_mvp/src/analysis/scene.rs:110–147`, especially :117–118 and :135–145. This remains wrong after the factcheck’s correction of the baseline half-width.

   **Change:**

   > “Introduce a separately versioned causal detector with an explicitly chosen delay and irreversible boundary-commit rule. Measure its boundary and L1 disagreements against the existing global detector. Retain the global detector for offline parity.”

   The delay is a new policy, not a proven bound inherited from existing code.

3. **Major — DESIGN §2: renderer replacement needs a complete serialization contract.**

   **Problem:** The hook can replace bytes, but “only rewrites L1 and scene-refresh” must not mean patching escaped bytes. Changing those fields changes the CRC and potentially emulation-prevention bytes. The “few bytes” size difference is not a usable capacity guarantee.

   **Evidence:** `D/dolby_vision/src/rpu/dovi_rpu.rs:231–237` serializes and applies emulation prevention; :293–309 computes and writes CRC. The fork’s transformer handles length-prefixed NALs, while `MatroskaExtractor.java:2533–2534` writes Annex-B start codes. `M/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/mediacodec/MediaCodecRenderer.java:1537–1563` flips the buffer, calls the hook, queues `limit()` bytes, and relinquishes its reference. Reconfiguration can prepend data at :1421–1430.

   **Change:**

   > “Generate complete replacement RPUs off the renderer thread, including CRC and emulation prevention. At the synchronous hook, locate the generated RPU in the actual codec-input framing, preserve parameter sets and all other NAL bytes, check actual serialized size against capacity, and update the limit. Never retain or asynchronously mutate codec-owned memory after queueing.”

   Test growth, shrinkage, CRC round trips, start-code-like payloads, reconfiguration prefixes, and unchanged non-RPU bytes. For length-prefixed paths, update the NAL length too. Scope the first implementation to verified containers.

   If waiting for metadata is required, restore the pre-copy/`SampleStream` readiness gate from `codex-r0-ideation.md:24`; `onQueueInputBuffer` is a last-chance lookup, not an asynchronous waiting mechanism.

4. **Blocker — DESIGN §2 / A.3: per-AU promotion contradicts shot-constant L1.**

   **Problem:** “Best provider ready for this AU” permits static → measured halfway through a shot. Capacity fallback can then produce measured → static on another AU. T2’s “refreshed at most once” explicitly permits another mid-shot change. Neither satisfies the stated shot-holding policy.

   **Evidence:** DESIGN:107–114 versus :121–124; ROADMAP:525–528.

   **Change:**

   > “Commit a provider, L1 value and policy revision for each presentation-order shot before any AU belonging to that shot is queued. Freeze that decision for the shot. Late results populate the cache or apply to a subsequent uncommitted shot.”

   With B-frames, commitment must precede the **first submitted AU belonging to the shot**, which need not be its first presentation frame. Preflight replacement capacity or otherwise prevent capacity failure from causing within-shot policy oscillation. If mid-shot refresh is desired, define and validate it as a separate policy.

5. **Blocker — DESIGN §2: PTS alone is not sufficient identity or a completeness watermark.**

   **Problem:** A seek-only reset misses codec flushes, release/recreation, discontinuities and configuration changes. Old asynchronous results can match reused timestamps. With B-frames, maximum observed PTS does not mean all earlier presentation frames have been analyzed.

   **Evidence:** The fork’s `DecodeOrderPtsVideoRenderer.kt:146–154` already handles every flush and codec release. Media3 `MediaCodecRenderer.java:1494–1499` documents flush/reinstantiation without a renderer reset. The fuller identity was present in `codex-r0-ideation.md:22`.

   **Change:** Restore:

   > “AU identity comprises content/track, configuration revision, analysis generation, decode-order sequence and original PTS. Join decoded outputs to these identities. Advance a presentation-order completeness watermark only after reorder gaps are resolved.”

   Specify invalidation for seek, flush, release, incompatible format change and discontinuity; retain only validated immutable metadata across generations. Test duplicate PTS, B-frames across cuts, backward seeks, late callbacks, EOS drain, codec reuse and drop-to-keyframe.

6. **Major — DESIGN §2: completeness is being mistaken for metadata quality.**

   **Problem:** A completed measured shot automatically outranks source HDR10+, even when measurement used decimation, approximate cropping or a different scene policy. Exact content matching and complete coverage do not establish better metadata.

   **Evidence:** DESIGN:107–109; ROADMAP:520–528; `H/hdr_analyzer_mvp/src/l1_sidecar.rs:105–117` records sampling and mapping differences.

   **Change:** Separate admissibility, completeness and quality. Require matching identity, timeline, color/composer contract and provenance before considering a provider. Preserve HDR10+ as the default provider for HDR10+ content; make replacement with measured metadata an explicit A/B-tested policy. Rename “exact shot list” to distinguish exact file matching from full-analysis parity.

7. **Major — DESIGN §2 / DV4.1: the existing sidecar is not the proposed playback protocol.**

   **Problem:** Scenes are indexed by presentation-frame number, not PTS. The schema has no first-PTS field to check. It also accounts for undecodable leading RASL pictures. Raw `RPU.bin` supplies even less identity information. File size and endpoint hashes do not prove that interior video content matches.

   **Evidence:** `H/hdr_analyzer_mvp/src/l1_sidecar.rs:13–18`, :92–103 and :135–143. The dropped provenance requirements are in `codex-r0-ideation.md:88`.

   **Change:** Add an hdr-analyze-owned playback manifest around the existing L1 payload, specifying:

   - Encoded track identity and incremental AU/chunk validation.
   - Presentation-index-to-AU/PTS mapping, timebase and leading-picture policy.
   - Analyzer version/settings, crop, composer and metadata policy.
   - Coverage and quality classification.

   Require the same manifest for imported RPUs. Do not infer frame index using `PTS × fps`. Test VFR, nonzero start times, open-GOP starts, wrong cuts/encodes and mismatched composers. Treat endpoint hashing as provisional identification.

8. **Blocker — DESIGN §2 / A.3: HLG eligibility and measurement omit essential composer contracts.**

   **Problem:** Main10 plus HLG is insufficient for unchanged-base-layer conversion. Incompatible range, matrix or primaries cannot be repaired by changing Format tags. Also, T2 already offers measured HLG while the HLG compose pass appears deferred to T3.

   **Evidence:** `H/mkvdovi/src/metadata/format.rs:343–377` rejects incompatible colorimetry; `H/mkvdovi/src/metadata/rpu_config.rs:35–56` requires L1 measured through the selected composer. `H/hdr_analyzer_mvp/src/analysis/frame.rs:682–710,766–784` performs spatial composition and reconstruction. ROADMAP:529–530 places the compose pass in T3.

   **Change:** Restore the contract from `codex-r0-ideation.md:94–105`: compatible limited-range BT.2020 NCL input, supported chroma handling, explicit missing/conflicting-tag policy, and L1 measured in the selected composer’s reconstructed PQ domain.

   Composer reconstruction is required for **the first measured HLG tier**. Sampling may be approximate; generic HLG luminance is a different signal and cannot silently substitute.

9. **Major — DESIGN §2 / DV2.3–DV2.4: track-init signalling does not resolve late transfer information.**

   **Problem:** The early-Format decision is reasonable, but alternative-transfer SEI may arrive in samples after that decision. “Detect alternative-transfer SEI” does not explain what happens to a track already classified and configured. `dvhe.08.xx` also leaves level selection unspecified.

   **Evidence:** `MatroskaExtractor.java:1402–1415` publishes Format at TrackEntry completion; ROADMAP:585–589 separates signalling from VUI/SEI detection.

   **Change:** Establish a decision table before implementation:

   > “Use container metadata and initialization SPS information available before track publication. Reject contradictory known metadata. If required transfer information is unavailable, preserve native playback unless a separately tested later Format-change/reconfiguration path is enabled.”

   Specify valid DV level selection from stream characteristics, existing-DV exclusion, and native fallback when synthetic signalling fails. Start with MKV explicitly, or add actual MP4/TS signalling and injection steps; inspecting their framing does not implement support.

10. **Major — A.3/T0: static L1 policies need honest definitions.**

   **Problem:** “Bright broadcast content reaches [3079] in every scene” is unsupported. The source-range ceiling is not a measured scene maximum. Similarly, MaxFALL is the largest frame-average light level, not the shot average used by measured L1.

   **Evidence:** ROADMAP:511–515; `H/mkvdovi/src/metadata/rpu_config.rs:86–90` defines the 8.4 source range; `H/hdr_analyzer_mvp/src/l1_sidecar.rs:80–87` defines MaxFALL, while :278–295 aggregates shot measurements.

   **Change:**

   > “Static 8.4 uses a conservative nominal ceiling of 3079 and an explicitly chosen fixed average; neither is measured scene L1. Static 8.1 may derive heuristic values from MaxCLL/MaxFALL, with documented handling of missing, zero or implausible metadata.”

   Remove the every-scene assertion. Include dark HLG material and missing/bad static metadata in the quality gate.

11. **Minor — DESIGN §2: the rejection of playback-output analysis is too broad.**

   **Problem:** The decisive argument is causality: output pixels arrive after that picture’s input-RPU deadline. The claim that reading playback output necessarily loses DV is expressly unmeasured, and “every on-device measurement” excludes useful offline or future-play analysis.

   **Evidence:** DESIGN:134–137; `codex-r0-ideation.md:40,84–90`.

   **Change:**

   > “Playback-decoder output cannot supply same-frame L1 before its input deadline. The initial live-DV design therefore uses a second decoder. Pixel access while preserving DV output remains unverified. Single-decoder idle-time analysis, or a native-HDR first pass for future-play caching, remains a separate experiment.”

   This preserves the justified live-playback decision without prematurely eliminating cheaper modes.

12. **Major — G-A: a luma ramp does not qualify the complete pixel path.**

   **Problem:** Distinct 10-bit values do not establish correct chroma, range, siting, crop or HLG reconstruction. A single successful import also does not validate image lifetime and synchronization.

   **Evidence:** ROADMAP:536–542; the HLG spatial dependencies above. Vulkan’s conversion parameters separately control model, range and chroma reconstruction; `NEAREST` still reconstructs chroma to luma resolution. See the [Khronos conversion specification](https://docs.vulkan.org/refpages/latest/refpages/source/VkSamplerYcbcrConversionCreateInfo.html). Public `YCBCR_P010` begins at API 31, so it is not an assumed public-format fallback on API 30. See [Android ImageFormat](https://developer.android.com/reference/android/graphics/ImageFormat.html).

   **Change:** Qualify PQ and HLG separately. Add labeled Y/Cb/Cr ramps, endpoints, saturated patches, chroma edges/siting, stride/crop cases, repeated buffer reuse, acquire/release synchronization and GPU-completion lifetime tests. Compare against decoded code values, not merely the intended pre-encode ramp.

   Make P010 ByteBuffer output another capability probe. Gate on actual import/format/features, not the Vulkan version number alone.

13. **Major — A.4/DV1: several “gates” record activity without deciding eligibility.**

   **Problem:** DV1.2 passes as a completed step after either success or failure; DV1.3 requires a table without thresholds. Downstream `Needs:` entries therefore allow a failed probe to satisfy prerequisites. G-D records framing/headroom without proving safe replacement.

   **Evidence:** ROADMAP:551–568 and :619–624.

   **Change:** Separate **probe completed** from **configuration qualified**. Publish a qualification matrix for resolution, frame rate, decode mode and pixel path. Require a passing entry before enabling that configuration.

   G-D should require the mutation/lifecycle tests in points 3–5. G-B should specify playback regression, memory, reclaim/fallback and sustained throughput criteria. `KEY_MAX_INPUT_SIZE` is an input to the experiment; actual buffer capacity is the runtime check.

14. **Major — G-B/G-C/G-E/DV5.4: the gates miss useful metadata coverage and sustained behavior.**

   **Problem:** A DV badge and canary prove routing/composer activity, not numerical L1 behavior. Ten seconds of analysis lead after one minute does not prove that a complete shot is ready, nor that lead survives thermal throttling. Owner preference is useful but does not establish general quality.

   **Evidence:** ROADMAP:543–555 and :625–627; DESIGN:42–44 requires whole-shot information. The omitted sustained/long-shot tests were in `codex-r0-ideation.md:115–121`.

   **Change:** Record same-title, same-settings baseline deltas for drops, rebuffers, startup/seek latency, memory high-water, analysis lead distribution, and the percentage of AUs receiving finalized/provisional/static metadata. Add long shots, repeated seeks, network stalls, resource reclaim and a thermal-steady-state run.

   Extend G-C with controlled L1 variants and independent RPU validation. Extend G-E across dark scenes, highlights, fades and HLG cases. Define tolerance and corpus before declaring any quality gate passed.

15. **Major — DESIGN §3: the arithmetic is correct, but the HLG budget is incomplete.**

   **Problem:** The P010 frame size and single-read bandwidth are correct. They omit the HLG composed-chroma intermediate. Consequently, “stride 2 reads a quarter” and the quarter-runtime guess do not describe the complete HLG pipeline.

   **Evidence:** `H/hdr_analyzer_mvp/src/analysis/gpu.rs:684–731` allocates two floats per chroma sample and composes the cropped chroma rectangle independently of statistics stride.

   **Change:** Restore these rows from the independent design:

   | Additional HLG resource | 4K24 | 4K60 |
   |---|---:|---:|
   | Composed-chroma scratch | 16.6 MB | 16.6 MB |
   | One scratch write + one read | 0.80 GB/s | 1.99 GB/s |

   These exclude extra taps and decoder/display traffic. Replace the stride claim with:

   > “Stride 2 quarters statistics sample count. End-to-end bandwidth and time, including composition, remain measured quantities.”

   Include imported images retained until GPU completion, scratch, driver allocations and decoder surfaces in RAM accounting.

16. **Major — DESIGN §3 / DV5.4: memory reservation can consume the promised look-ahead.**

   **Problem:** Deducting 150–400 MB from the compressed-buffer budget may leave insufficient compressed data to establish ten seconds of analysis lead. Loading 15–45 seconds ahead is not a guarantee after that deduction. Catch-up also requires auxiliary throughput above playback consumption.

   **Evidence:** `BufferSizing.kt:75–99` reduces available budget and accounts for back buffer; `ReadAheadReserve.kt:54–63` trims the allocator; DESIGN:77–79,154–157.

   **Change:** Model the shared budget explicitly: compressed playback/back buffer, tee queue, decoder surfaces, imported images and scratch. At 80 Mb/s, ten seconds of compressed video is approximately 100 MB before overhead. Measure time-to-lead after startup/seek and disable analysis when its reservation undermines playback reserve.

   Describe dual 4K60 as **outside the validated throughput envelope**, rather than treating the advertised single-stream 4K60 capability as a proven aggregate hardware ceiling. Keeping it disabled pending measurement remains sensible.

17. **Major — A.3/DV5.4/DV5.5: approximate analysis does not become exact when cached.**

   **Problem:** IRAP-only, sparse measurements and a new causal detector cannot inherit unconditional desktop parity. Finished shots may still be approximate; a partial viewing cannot guarantee first-frame cache coverage next time.

   **Evidence:** ROADMAP:520–530,625–629; `H/hdr_analyzer_mvp/src/l1_sidecar.rs:278–295` aggregates the available measurements.

   **Change:** Separate three acceptance tests: identical-frame implementation parity, sampling/scene-policy error, and deadline coverage.

   Replace the cache promise with:

   > “Cache finalized covered ranges with provenance and quality classification. Reuse those ranges at their recorded quality; uncovered ranges retain fallback. Claim offline parity only for complete validated analysis.”

   Cache population should not depend on meeting first-view deadlines.

18. **Major — A.5: repair the dependency graph and PR boundaries.**

   **Problem:** T1 needs renderer replacement, but its implementation is placed in T2 and omitted from DV4.3’s dependencies. DV2.1 combines a parity-preserving refactor with a behavior-changing detector. Several device gates are attached to desk-only steps.

   **Evidence:** ROADMAP:579–611,617–629; DESIGN:110–112.

   **Exact changes:**

   | Existing step | Proposed revision |
   |---|---|
   | DV2.1 | Split RPU builders, parity-preserving analyzer extraction, and new causal detector. Do not require the causal detector to be byte-identical to global detection. |
   | DV2.2 | Let the Android RPU bridge depend on builders, not the entire measurement pipeline. Shared APIs belong in hdr-analyze; JNI/Gradle/APK integration belongs in the fork. |
   | DV4.1/DV4.2 | Add the manifest/timeline contract as an explicit prerequisite. |
   | DV4.3 | Add renderer replacement/readiness/lifecycle as a prerequisite. Place that functionality in the shared injector work, retaining DV5.1’s assigned ID if moved. |
   | DV5.2 | Split decoder feeding, dependency-safe skipping, memory governance and device stress validation. |
   | DV5.3 | Split import/synchronization, PQ statistics and HLG composition; add device validation steps. |
   | DV5.4 | Require qualified device configuration, shared injector/builders, accepted approximation policy/tolerances and applicable baseline. |
   | DV3.1 | Add `[owner]` for the TV reading, consistently with G-C. |

   Also carry the roadmap’s `DeviceProfile`, saved-setting precedence, debug revert switch and documentation requirements into the conversion steps explicitly.

19. **Major — DV4.2/DV4.3: the server’s reference and ten-second promise are ambiguous.**

   **Problem:** Starting from an arbitrary playhead can begin inside a long shot. An offline run of that truncated range can have different crop, scene context and aggregates from whole-file analysis. Ten seconds cannot universally guarantee exact whole-shot metadata.

   **Evidence:** ROADMAP:608–611; DESIGN:42–47.

   **Change:** Specify RAP preroll, timeline reconciliation, boundary warmup, crop policy and incomplete-first-shot handling. Compare against an offline reference using the same declared context and policy.

   Define “within 10 seconds” as provisional metadata or finalized metadata for an eligible subsequent shot. Test seeking into the middle of a long uncut scene. Add a simpler local-sidecar device gate before the server: it isolates identity and injection from network scheduling.

20. **Major opportunity — A.3/A.5: deliver measured L1 before the hardest infrastructure.**

   **Problem:** T0 currently waits on scene detection and plane-statistics extraction, and local measurement is tied to concurrent decoding. Both delay useful milestones unnecessarily.

   **Evidence:** ROADMAP:579–584; dropped alternatives in `codex-r0-ideation.md:80–90`.

   **Change:** Use this order:

   1. Pre-generated RPU compatibility fixtures and minimal static injector.
   2. Exact-content desktop sidecar, with an on-device end-to-end gate.
   3. HDR10+ provider and LAN delivery.
   4. Single-decoder Shield idle-time pre-analysis.
   5. Concurrent PQ measurement, then HLG measurement.

   Idle-time pre-analysis needs pixel-access and thermal qualification, but **does not need G-B**. It can deliver on-device measured L1 even if concurrent decoding fails.

21. **Minor opportunity — DESIGN §3/DV5.3: make the first Tegra port smaller than the CUDA pipeline.**

   **Problem:** `shaderInt64` is not evidence that the CUDA reduction strategy’s atomic operations are available or efficient. Porting every statistic and HLG pass together obscures the actual bottleneck.

   **Evidence:** `H/hdr_analyzer_mvp/src/analysis/kernels.cu:529–570` uses reductions and atomic sums; `analysis/gpu.rs:684–731` contains the separate HLG compose pass. The adaptive-analysis idea was in `codex-r0-ideation.md:74`.

   **Change:** Start with PQ, workgroup-local histograms and partial sums, followed by a small CPU or second-dispatch reduction. Avoid requiring global 64-bit atomics initially; return statistics only.

   Then benchmark cheap every-frame cut statistics plus sparse full-resolution peak measurements, increasing sampling around changes. This saves analysis work, not required HEVC reference decoding.

   **Hypothesis to test:** tiled HLG composition with the required halo could avoid the full scratch write/read. Compare it against the two-pass parity oracle; duplicated halo work and shared-memory pressure may erase the benefit. Do not prescribe division LUTs or claim speedups before measuring their error and runtime.

What I agree with and would preserve:

- One injector with interchangeable, independently optional providers.
- Track signalling before decoder selection; no loader-thread waits for future analysis.
- Complete valid fallback RPUs, with static HDR10 conversion opt-in.
- Cached/server metadata before concurrent on-device analysis.
- Reusing Rust builders and desktop reference calculations.
- A second decoder for same-frame live measurement, and conservative 4K60 enablement.
## Disposition

Each point's evidence was checked before acting on it (among others `MatroskaExtractor.java:2533-2534`,
`l1_sidecar.rs:13-18` and `:92-103`, `DecodeOrderPtsVideoRenderer.kt:146-154`, `scene.rs:110-118`,
`format.rs:343-350`, `gpu.rs:684-690`).

| # | Severity | Disposition | Where |
|---|---|---|---|
| 1 | major | Accepted: shot holding is a conservative policy based on nine RPUs without fades | DESIGN 1 "Shot holding" |
| 2 | major | Accepted: the causal detector is a new, separately versioned policy; the 24-frame bound is gone | DESIGN 1, 2.7; DV2.6 |
| 3 | major | Accepted: complete RPUs written off the renderer thread, swap in the real framing, size checked, the hook never waits | DESIGN 1, 2.4 |
| 4 | blocker | Accepted: per-shot commitment before the first submitted AU, frozen; capacity checked before committing; no mid-shot refresh | DESIGN 2.2; A.3 T3 |
| 5 | blocker | Accepted: AU identity, analysis generations, presentation-order completeness watermark, invalidation list and tests | DESIGN 2.3; DV5.1 |
| 6 | major | Accepted: admissible, complete and quality class judged separately; HDR10+ stays the default for HDR10+ content | DESIGN 2.1; G-E |
| 7 | major | Accepted: playback manifest owned by hdr-analyze; endpoint hashes only identify a candidate | DESIGN 2.8; DV4.4, DV4.1 |
| 8 | blocker | Accepted: HLG colour contract in the decision table; composer-domain measurement from the first measured HLG tier | DESIGN 1, 2.5, 2.6; DV2.4, DV5.8, DV5.9 |
| 9 | major | Accepted: decision table from container and SPS at track init; unknown transfer means native; level from the stream; Matroska first | DESIGN 2.5; DV2.3 |
| 10 | major | Accepted: static L1 values described as heuristics; the "every scene" claim removed | A.3 T0; DESIGN 4 |
| 11 | minor | Accepted: the rejection rests on causality; native first play and idle analysis kept as separate modes | DESIGN 2.9 |
| 12 | major | Accepted: PQ and HLG qualified separately with code-value comparisons, lifetime and sync tests; P010 output is its own probe | A.4 G-A |
| 13 | major | Accepted: probes separate from qualification; `QUALIFICATION.md` added | A.4; DV1.2, DV1.3, DV1.6 |
| 14 | major | Accepted: common device metrics, L1 variants and `dovi_tool info` in G-C, a fixed G-E corpus, G-F thermal run | A.4; DV1.4, DV1.6 |
| 15 | major | Accepted: HLG scratch rows, stride wording, memory items | DESIGN 3 |
| 16 | major | Accepted: one memory budget; 4K60 dual decode "outside the validated envelope" | DESIGN 1, 3; DV5.7 |
| 17 | major | Accepted: cache stores ranges at their quality class; three separate acceptance tests | DESIGN 4; DV5.5 |
| 18 | major | Accepted: DV2.1 split (DV2.5, DV2.6), DV2.2 on builders only, DV5.1 moved to DV2 with its ID, DV5.2 and DV5.3 split (DV5.7, DV5.8), DV4.3 needs DV5.1, `[owner]` on DV3.1, DeviceProfile and revert switch | A.5 |
| 19 | major | Accepted: random-access warm-up, incomplete first shot, "within 10 s" defined for the next uncommitted shot, a local sidecar gate (DV4.5) before the server | DV4.2, DV4.3, DV4.5 |
| 20 | major | Mostly accepted: idle-time analysis on one decoder (T2) comes before playback-time analysis (T3) and needs no G-B. Not taken: Codex's order put HDR10+ after the desktop sidecar; it stays in T0 because its L1 comes from the AU's own SEI and needs neither a manifest nor renderer replacement | A.3; DV5.6 |
| 21 | minor | Accepted: smaller first Vulkan port (PQ, local histograms, no global 64-bit atomics); division LUTs no longer prescribed; tiled HLG compose kept as a hypothesis | DESIGN 2.9; DV5.3, DV5.8 |
