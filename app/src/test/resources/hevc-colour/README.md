# HEVC colour fixtures (ROADMAP DV2.4)

`nals.txt` holds the VPS, SPS, PPS and prefix SEI NALs, with their 2-byte NAL headers and emulation
prevention bytes as written, of eleven tiny x265 encodes. Each line is `<case>.<vps|sps|pps|sei>=<hex>`;
a `#` line before each case gives the exact x265 command. The tests of the HLG contract
(`HevcVuiColourTest`, `HevcInitNalsTest`, `HlgContractTest`, `MatroskaColourFormatTest`) read it.

Encoder: x265 4.3+1-e9b8812 (Homebrew, macOS arm64, 8bit+10bit+12bit build), 2026-10-23.

How it was made, in an empty directory:

```sh
ffmpeg -f lavfi -i testsrc2=size=64x64:rate=1 -frames:v 2 -pix_fmt yuv420p10le -f rawvideo in10.yuv
ffmpeg -f lavfi -i testsrc2=size=64x64:rate=1 -frames:v 2 -pix_fmt yuv420p -f rawvideo in8.yuv
bash gen.sh . > nals.txt
```

`gen.sh` runs the encodes; `nals.py` splits each Annex B output and prints its unique VPS, SPS, PPS and
prefix SEI NALs.

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

The encodes have two frames because x265 signals a one-frame encode as a still-picture profile:
`hlg_still10`, the one-frame case, is kept on purpose as the 10-bit RExt (profile 4) sample. The
`atc_sei` SEI (`4e0193011280`) is payloadType 147, alternative_transfer_characteristics, with
preferred_transfer_characteristics 18.
