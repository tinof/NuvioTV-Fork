# HEVC colour fixtures (ROADMAP DV2.4)

`nals.txt` holds the VPS, SPS, PPS and prefix SEI NALs, with their 2-byte NAL headers and emulation
prevention bytes as written, of nineteen tiny x265 encodes. Each line is `<case>.<vps|sps|pps|sei>=<hex>`;
a `#` line before each case gives the exact x265 command. `sps_more.txt` holds single SPS NALs
(`<case>=<hex>`) that `gen.sh` does not make: vectors from Media3's tests and two hand-built ones. The
tests of the HLG contract (`HevcVuiColourTest`, `HevcInitNalsTest`, `HlgContractTest`,
`MatroskaColourFormatTest`, `MatroskaHlgContractTest`) read them.

Encoder: x265 4.3+1-e9b8812 (Homebrew, macOS arm64, 8bit+10bit+12bit build), 2026-10-23.

How `nals.txt` was made, in an empty directory:

```sh
ffmpeg -f lavfi -i testsrc2=size=64x64:rate=1 -frames:v 2 -pix_fmt yuv420p10le -f rawvideo in10.yuv
ffmpeg -f lavfi -i testsrc2=size=64x64:rate=1 -frames:v 2 -pix_fmt yuv420p -f rawvideo in8.yuv
ffmpeg -f lavfi -i testsrc2=size=64x64:rate=1 -frames:v 8 -pix_fmt yuv420p10le -f rawvideo in10_8f.yuv
ffmpeg -f lavfi -i testsrc2=size=66x66:rate=1 -frames:v 2 -pix_fmt yuv420p10le -f rawvideo in10_66.yuv
ffmpeg -f lavfi -i testsrc2=size=64x64:rate=1 -frames:v 2 -pix_fmt yuv422p10le -f rawvideo in422.yuv
ffmpeg -f lavfi -i testsrc2=size=64x64:rate=1 -frames:v 2 -pix_fmt yuv444p10le -f rawvideo in444.yuv
python3 -I scaling_list.py > scaling_list.txt
bash gen.sh . > nals.txt
```

`gen.sh` runs the encodes; `nals.py` splits each Annex B output and prints its unique VPS, SPS, PPS and
prefix SEI NALs; `scaling_list.py` writes the HM-style scaling list of `hlg_scaling_list`.

| Case | VUI colour | general_profile_idc, bit depth |
|---|---|---|
| `hlg_tagged` | BT.2020 primaries (9), HLG (18), BT.2020 NCL (9), limited | 2 (Main 10), 10 |
| `untagged10` | no video_signal_type | 2, 10 |
| `pq` | 9, PQ (16), 9, limited | 2, 10 |
| `hlg_matrix709` | 9, 18, BT.709 matrix (1), limited | 2, 10 |
| `hlg_full_range` | 9, 18, 9, full | 2, 10 |
| `hlg_prim709` | BT.709 primaries (1), 18, 9, limited | 2, 10 |
| `hlg_matrix_cl` | 9, 18, BT.2020 CL (10), limited | 2, 10 |
| `hlg_8bit` | 9, 18, 9, limited | 1 (Main), 8 |
| `range_only10` | video_signal_type full range, no colour description | 2, 10 |
| `hlg_still10` | 9, 18, 9, limited | 4 (RExt), 10 |
| `atc_sei` | 9, BT.2020 10-bit (14), 9, limited; prefix SEI with alternative transfer 18 | 2, 10 |
| `hlg_sublayers` … `hlg_sps_rps` | 9, 18, 9, limited (see "SPS layouts") | 2, 10 |
| `hlg_422` | 9, 18, 9, limited; chroma_format_idc 2 (4:2:2) | 4, 10 |
| `hlg_444` | 9, 18, 9, limited; chroma_format_idc 3 (4:4:4) | 4, 10 |

The encodes have two frames because x265 signals a one-frame encode as a still-picture profile:
`hlg_still10`, the one-frame case, is kept on purpose as the 10-bit RExt (profile 4) sample. The
`atc_sei` SEI (`4e0193011280`) is payloadType 147, alternative_transfer_characteristics, with
preferred_transfer_characteristics 18.

## SPS layouts

The first eleven cases share one SPS layout up to the VUI: no sub-layers, no conformance window, no
scaling list, PCM or reference picture sets in the SPS, no aspect ratio. The other vectors take the
`HevcVuiColour` walk through the branches that layout skips; `HevcVuiColourTest` checks every one
against Media3's `H265SpsData` and against the colour values its source states (the matrix, which Media3
does not read, included). `python3 -I sps_tool.py branches < nals.txt` (or `< sps_more.txt`) prints the
branches each SPS takes, from a walk of its own.

| Case | Source | Branches it covers |
|---|---|---|
| `hlg_sublayers` | x265 `--temporal-layers 3` | sps_max_sub_layers_minus1 2: reserved_zero_2bits, the sub-layer ordering loop over three sub-layers |
| `hlg_sar_ext` | x265 `--sar 33:32` | aspect_ratio_idc 255 (EXTENDED_SAR), sar_width and sar_height |
| `hlg_conf_win` | x265, 66x66 input | conformance window (offsets 0, 3, 0, 3 on a 72x72 picture) |
| `hlg_scaling_default` | x265 `--scaling-list default` | scaling_list_enabled_flag 1, no scaling list data |
| `hlg_scaling_list` | x265 `--scaling-list scaling_list.txt` | scaling list data: 17 coded lists (with the 16x16 and 32x32 DC coefficients), 3 predicted |
| `hlg_sps_rps` | x265 two-pass `--multi-pass-opt-rps` | 7 explicitly coded short-term RPSs in the SPS |
| `hlg_422` | x265 `--input-csp i422 --profile main422-10` | chroma_format_idc 2 |
| `hlg_444` | x265 `--input-csp i444 --profile main444-10` | chroma_format_idc 3: separate_colour_plane_flag |
| `m3_lpcm_sample` | Media3 1.8.0 `NalUnitUtilTest` `H265_SPS_TEST_DATA` | PCM, sps_sub_layer_ordering_info_present_flag 0, scaling list enabled without data; HLG |
| `m3_mvhevc_hdr_view0` | Media3 1.8.0 `NalUnitUtilTest` `H265_SPS_TEST_DATA_2VIEWS_HDR_VIEW_0` | scaling list data from another encoder (3 coded, 17 predicted lists); VUI without video_signal_type |
| `m3_exoghi_10316` | Media3 1.8.0 `NalUnitUtilTest` `parseH265SpsNalUnitPayload_exoghi_10316` | inter-RPS prediction (8 of 9 sets), use_delta_flag 0; PQ |
| `m3_st_rps` | Media3 1.8.0 `NalUnitUtilTest` `parseH265SpsNalUnitPayload_withShortTermRefPicSets` | inter-RPS prediction (3 of 6 sets), use_delta_flag 0; PQ |
| `m3_hlg_1080p` | Media3 1.8.0 test asset `mp4/hlg-1080p.mp4` | real HLG stream: conformance window, ordering info 0, 1 short-term RPS |
| `m3_rps_pred_ts` | Media3 1.8.0 test asset `ts/sample_h265_rps_pred.ts` | sps_max_sub_layers_minus1 3, inter-RPS prediction (26 of 28 sets), EXTENDED_SAR 2740:2739, conformance window; 8-bit Main, no colour |
| `hand_long_term_refs` | hand-built from `hlg_tagged` | long_term_ref_pics_present_flag 1 with two lt_ref_pic_poc_lsb_sps entries |
| `hand_sub_layer_ptl` | hand-built from `hlg_sublayers` | sub_layer_profile_present_flag and sub_layer_level_present_flag 1 (88 + 8 bits per sub-layer) |

The two `hand_` vectors are not from an encoder: neither x265 nor any HEVC encoder at hand (x265,
VideoToolbox) writes long-term reference pictures or sub-layer profile and level into an SPS, and no
Media3 vector has them. `sps_tool.py build < nals.txt` inserts the fields bit by bit into an x265 SPS
and re-adds the emulation prevention bytes; Media3's parser reads both to the same values as ours.

Not covered by any vector: sps_sub_layer_ordering_info_present_flag 0 together with sub-layers (every
vector with sub-layers sends the ordering info per sub-layer), separate_colour_plane_flag 1 and
chroma_format_idc 0.

The Media3 vectors are copied from androidx/media at tag 1.8.0. The two media assets were read with
`ffmpeg -i <asset> -map 0:v -c:v copy -frames:v 3 -bsf:v hevc_mp4toannexb -f hevc out.hevc` and
`nals.py`; ffprobe gives the same colour (BT.2020 / HLG / BT.2020 NCL, limited, for `hlg-1080p.mp4`;
none for `sample_h265_rps_pred.ts`).
