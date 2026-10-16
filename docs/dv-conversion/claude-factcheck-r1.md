# Citation check of Appendix A, draft 1 (2026-10-16)

A Claude agent (Opus, medium) checked every file:line, URL and number in the first draft of
`ROADMAP.md` Appendix A against the code, cloned Media3 1.8.0, dovi_tool and hdr10plus_tool sources,
and the fetched pages. The appendix was corrected as listed below.

| Claim in draft 1 | Verdict | Change made |
|---|---|---|
| HEVC `initializationData` is the hvcC only, so the decoder never receives a dvcC | Wrong for MP4: Media3 `BoxParser.java:1477-1493` appends the dvcC and the renderer sends it as csd-1. True for Matroska (`MatroskaExtractor.java:3277`, parameter sets in Annex B) | Rewritten: Matroska passes no dvcC and already works; MP4 passes it; HDR10/HLG sources have none |
| HDR10+ → L1 is arithmetic on one frame | Imprecise: dovi_tool uses the first frame of each HDR10+ scene (`generator.rs:189`, `:205-209`); default peak source `histogram`; max ≤ 4095 also clamped | Rewritten, with the live per-AU hold rule |
| vulkan.gpuinfo.org 2952 and 1977 at Vulkan 1.0.61 | Imprecise: 2952 is 1.0.61 with `VK_KHR_sampler_ycbcr_conversion`; 1977 is 1.0.56 without it; neither lists the AHB import extension | Rewritten |
| Forum reports of `cudaErrorInsufficientDriver` from Android 8 on | One thread (forums.developer.nvidia.com/t/77215), Android 8.0, CUDA 7.0; CodeWorks 1R7 dropped CUDA | Rewritten |
| `DolbyVisionMatroskaTransformer.kt:435-465` | Loop is 426-474 | Fixed |
| `MediaCodecRenderer.java:1537-1559` | Hook at 1542, `queueInputBuffer` at 1555-1560 | Fixed |
| Baseline "12 ahead" | 12 before and 12 after (`scene.rs:10`, half-width) | Fixed |
| 500 MB budget on a 3 GB box | A ceiling; only the 3 GB Shield Pro; the 2 GB Shield gets 150 MB | Fixed |
| `NuvioExoPlayerPerformanceHelper.kt:66-68` | Min and max at 66-67 | Fixed |
| Retail RPUs change L1 only at shot starts | Based on nine RPUs | Qualified |
| `MatroskaExtractor.java:3488` | Off by one: 3489 | Fixed |
| Delay the track Format until the first block | The extractor's comment at `:1411-1414` warns this can deadlock preparation | Option dropped |
| Drop sub-layer non-reference NAL types | Safe only for `_N` pictures at the highest TemporalId (RFC 7798 §1.1.4) | Qualified |
| Uncited: HDR10+ output, 4K60 decode, wgpu limits, X1+ bandwidth, TV HDR10 processing, DV decoder output unreadable, 24-frame delay | | Citations added where they exist; the rest marked as guesses or reasoning; the 24-frame delay derived |

Confirmed as written: the track Format is published at the end of the TrackEntry
(`MatroskaExtractor.java:1402-1415`); the rewrite hooks at `:3446`; HLG detection only from the Colour
element (`:1699-1704`); the libdovi C API list; the `dolby_vision` 3.4, `hdr10plus` 2.1.5 and
`dovi84_composer` APIs; the hdr-analyze code and log references; the budget arithmetic; `KEY_PRIORITY`
(API 23, 1 = best effort) and `getMaxSupportedInstances` (API 23, an upper bound).
