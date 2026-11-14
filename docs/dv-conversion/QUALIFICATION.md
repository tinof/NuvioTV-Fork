# Qualification matrix

Probes and implementation may run before qualification. Enabling a configuration needs a passing result
for every gate that applies to its tier (`ROADMAP.md` Appendix A.4). A missing, failed or unrun result
keeps that configuration off; other configurations stay usable. Each result names the device and
firmware, the transfer and composer, the resolution, frame rate, decode mode, pixel path and the
playback and display chain, and links the log entry with the measurements.

| Gate | Tiers | Configuration | Result | Date | Evidence |
|---|---|---|---|---|---|
| G-A | T2, T3 | PQ, 4K, ImageReader PRIVATE → AHardwareBuffer → Vulkan | not run; the driver lists the import, YCbCr sampling and compute (capability list, 2026-11-14, log) | | DV1.2 |
| G-A | T2, T3 | HLG, 4K, ImageReader PRIVATE → AHardwareBuffer → Vulkan | not run; capability list as for PQ | | DV1.2 |
| G-A | T2, T3 | PQ, 4K, P010 ByteBuffer read on the CPU | not run | | DV1.2 |
| G-B | T3 | 4K24, every frame | not run | | DV1.3 |
| G-B | T3 | 4K24, without top-layer `_N` pictures | not run | | DV1.3 |
| G-B | T3 | 4K24, IRAP only | not run | | DV1.3 |
| G-B | T3 | 4K60, every frame | not run | | DV1.3 |
| G-B | T3 | 4K60, without top-layer `_N` pictures | not run | | DV1.3 |
| G-B | T3 | 4K60, IRAP only | not run | | DV1.3 |
| G-C | all | 8.1 from HDR10, `mkvdovi` file, Shield → owner's TV | not run: no `mkvdovi` HDR10 file was made; the owner closed DV1.8 without it. A native 8.1 file played with every dumped RPU equal to the file's (2224 AUs, start, seeks, resume) | 2026-11-06 | DV1.8 in the log |
| G-C | all | 8.1 from HDR10+, `mkvdovi` file | pass: 4K25 HDR10+ Profile B cut, Shield → LG C9 shows Dolby Vision (owner); 2165 dumped RPUs equal the file's, `dovi_tool info` ok; start from frame 0, seeks, restarts | 2026-11-06 | DV1.8 in the log |
| G-C | all | 8.4 bt2100 and canary, `mkvdovi` file | pass: bt2100, 4K25 HLG cut, Dolby Vision with neutral colour (owner), 2020 dumped RPUs equal the file's, `dovi_tool info` ok, start from frame 0 and seeks (2026-11-06); canary: eye check only (2026-10-23), no `mkvdovi` canary file | 2026-11-06 | DV1.4 and DV1.8 in the log |
| G-C | all | L1 variants of one title | not run as L1-only variants; the owner closed DV1.8 on three dynamic-contrast variants of one title (reshaping and L1/L3 change), judged on the C9, RPUs not dumped | 2026-11-04 | hdr-analyze log P14; DV1.8 in the log |
| G-C | all | Pulse clip (L1 alternating, pixels constant) | not run | | DV7.7 |
| G-C | DV7 candidates b, c | 8.1 mapping canary against identity control, on the recorded playback chain | not run | | DV7.7 |
| G-C | all | Conversion in the app, per source type | partial: HLG → 8.4 bt2100 (BBC broadcast HLG, Matroska, 4K25, Shield → LG C9) shows Dolby Vision without tint (owner), RPUs validate and match `dovi_tool`; canary, frame-0 start and seeks not run; HDR10+ in the next row; static HDR10 not run | 2026-10-25 | DV3.2 session 1 in the log; DV3.3 |
| G-C | all | Conversion in the app, HDR10+ → 8.1 | pass: 4K 23.976 fps HDR10+ web series, Matroska, Shield → LG C9 shows Dolby Vision (owner); an RPU in every AU; L1 equal to `dovi_tool` on 6115 frames from 0:00; L6, source range and L9 from the SEI, the whole RPU equal to `mkvdovi` on 7231 frames from 0:00, the owner's A/B shows no large difference (DV3.4); seeks not compared with `dovi_tool` | 2026-11-01 | DV3.1 and DV3.4 in the log |
| G-D | T1 server, T3 | Renderer replacement, Matroska | not run | | DV1.1, DV5.1 |
| G-E | T0 HDR10, T1, T3 | Static HDR10 → 8.1 against native HDR10 | not run | | DV3.3 |
| G-E | T1, T3 | Shot list against static | T1 pass: 4K25 HLG cut, `mkvdovi manifest` (14 shots) against static 8.4, both from frame 0 on the LG C9; the owner preferred the shot list; 0 dropped frames in both; T3 not run | 2026-11-07 | DV4.5 in the log (PR #55) |
| G-E | T3 | HDR10+ against measured | not run | | DV5.4 |
| G-F | T2 | Idle analysis, two hours | not run | | DV1.6 |
| G-F | T3 | Playback with analysis, two hours | not run | | DV1.7 |
