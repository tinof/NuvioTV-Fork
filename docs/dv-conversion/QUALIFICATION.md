# Qualification matrix

Probes and implementation may run before qualification. Enabling a configuration needs a passing result
for every gate that applies to its tier (`ROADMAP.md` Appendix A.4). A missing, failed or unrun result
keeps that configuration off; other configurations stay usable. Each result names the device and
firmware, the transfer and composer, the resolution, frame rate, decode mode, pixel path and the
playback and display chain, and links the log entry with the measurements.

| Gate | Tiers | Configuration | Result | Date | Evidence |
|---|---|---|---|---|---|
| G-A | T2, T3 | PQ, 4K, ImageReader PRIVATE → AHardwareBuffer → Vulkan | not run | | DV1.2 |
| G-A | T2, T3 | HLG, 4K, ImageReader PRIVATE → AHardwareBuffer → Vulkan | not run | | DV1.2 |
| G-A | T2, T3 | PQ, 4K, P010 ByteBuffer read on the CPU | not run | | DV1.2 |
| G-B | T3 | 4K24, every frame | not run | | DV1.3 |
| G-B | T3 | 4K24, without top-layer `_N` pictures | not run | | DV1.3 |
| G-B | T3 | 4K24, IRAP only | not run | | DV1.3 |
| G-B | T3 | 4K60, every frame | not run | | DV1.3 |
| G-B | T3 | 4K60, without top-layer `_N` pictures | not run | | DV1.3 |
| G-B | T3 | 4K60, IRAP only | not run | | DV1.3 |
| G-C | all | 8.1 from HDR10, `mkvdovi` file, Shield → owner's TV | not run | | DV1.8 |
| G-C | all | 8.1 from HDR10+, `mkvdovi` file | not run | | DV1.8 |
| G-C | all | 8.4 bt2100 and canary, `mkvdovi` file | pass, eye check only (owner) | 2026-10-23 | DV1.4; `dovi_tool` checks in DV1.8 |
| G-C | all | L1 variants of one title | not run | | DV1.8 |
| G-C | all | Pulse clip (L1 alternating, pixels constant) | not run | | DV7.7 |
| G-C | DV7 candidates b, c | 8.1 mapping canary against identity control, on the recorded playback chain | not run | | DV7.7 |
| G-C | all | Conversion in the app, per source type | partial: HLG → 8.4 bt2100 (BBC broadcast HLG, Matroska, 4K25, Shield → LG C9) shows Dolby Vision without tint (owner), RPUs validate and match `dovi_tool`; canary, frame-0 start and seeks not run; HDR10+ in the next row; static HDR10 not run | 2026-10-25 | DV3.2 session 1 in the log; DV3.3 |
| G-C | all | Conversion in the app, HDR10+ → 8.1 | pass: 4K 23.976 fps HDR10+ web series, Matroska, Shield → LG C9 shows Dolby Vision (owner); an RPU in every AU; L1 equal to `dovi_tool` on 6115 frames from 0:00; L6, source range and L9 are the defaults for a title with static metadata only in its SEI (DV3.4); seeks not compared with `dovi_tool` | 2026-10-30 | DV3.1 in the log |
| G-D | T1 server, T3 | Renderer replacement, Matroska | not run | | DV1.1, DV5.1 |
| G-E | T0 HDR10, T1, T3 | Static HDR10 → 8.1 against native HDR10 | not run | | DV3.3 |
| G-E | T1, T3 | Shot list against static | not run | | DV4.5 |
| G-E | T3 | HDR10+ against measured | not run | | DV5.4 |
| G-F | T2 | Idle analysis, two hours | not run | | DV1.6 |
| G-F | T3 | Playback with analysis, two hours | not run | | DV1.7 |
