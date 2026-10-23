#!/bin/bash
# Encodes two 64x64 frames per case with x265 and prints its parameter-set and SEI NALs as hex.
# Inputs (in the given dir):
#   ffmpeg -f lavfi -i testsrc2=size=64x64:rate=1 -frames:v 2 -pix_fmt yuv420p10le -f rawvideo in10.yuv
#   ffmpeg -f lavfi -i testsrc2=size=64x64:rate=1 -frames:v 2 -pix_fmt yuv420p -f rawvideo in8.yuv
#   ffmpeg -f lavfi -i testsrc2=size=64x64:rate=1 -frames:v 8 -pix_fmt yuv420p10le -f rawvideo in10_8f.yuv
#   ffmpeg -f lavfi -i testsrc2=size=66x66:rate=1 -frames:v 2 -pix_fmt yuv420p10le -f rawvideo in10_66.yuv
#   ffmpeg -f lavfi -i testsrc2=size=64x64:rate=1 -frames:v 2 -pix_fmt yuv422p10le -f rawvideo in422.yuv
#   ffmpeg -f lavfi -i testsrc2=size=64x64:rate=1 -frames:v 2 -pix_fmt yuv444p10le -f rawvideo in444.yuv
#   python3 -I scaling_list.py > scaling_list.txt
set -euo pipefail
TOOLS="$(cd "$(dirname "$0")" && pwd)"
cd "$1"
X265=/opt/homebrew/bin/x265
COMMON=(--input-res 64x64 --fps 1 --frames 2 --no-info --log-level error)
TEN=(--input in10.yuv --input-depth 10 --output-depth 10 --profile main10)
EIGHT=(--input in8.yuv --input-depth 8 --output-depth 8 --profile main)

HLG=(--colorprim bt2020 --transfer arib-std-b67 --colormatrix bt2020nc --range limited)

enc() {
  local name="$1"; shift
  "$X265" "${COMMON[@]}" "$@" -o "$name.hevc" >/dev/null 2>&1
  echo "# $name: x265 ${COMMON[*]} $* -o $name.hevc"
  python3 -I "$TOOLS/nals.py" "$name.hevc" | sed "s/^/$name./"
}

enc hlg_tagged     "${TEN[@]}" --colorprim bt2020 --transfer arib-std-b67 --colormatrix bt2020nc --range limited
enc untagged10     "${TEN[@]}"
enc pq             "${TEN[@]}" --colorprim bt2020 --transfer smpte2084 --colormatrix bt2020nc --range limited
enc hlg_matrix709  "${TEN[@]}" --colorprim bt2020 --transfer arib-std-b67 --colormatrix bt709 --range limited
enc hlg_full_range "${TEN[@]}" --colorprim bt2020 --transfer arib-std-b67 --colormatrix bt2020nc --range full
enc hlg_prim709    "${TEN[@]}" --colorprim bt709 --transfer arib-std-b67 --colormatrix bt2020nc --range limited
enc hlg_matrix_cl  "${TEN[@]}" --colorprim bt2020 --transfer arib-std-b67 --colormatrix bt2020c --range limited
enc hlg_8bit       "${EIGHT[@]}" --colorprim bt2020 --transfer arib-std-b67 --colormatrix bt2020nc --range limited
enc range_only10   "${TEN[@]}" --range full
enc hlg_still10    "${TEN[@]}" --frames 1 --colorprim bt2020 --transfer arib-std-b67 --colormatrix bt2020nc --range limited
enc atc_sei        "${TEN[@]}" --colorprim bt2020 --transfer bt2020-10 --colormatrix bt2020nc --range limited --atc-sei 18

# SPS layouts the cases above share none of (README, "SPS layouts"); all are 4:2:0 10-bit HLG but the last two.
enc hlg_sublayers  "${TEN[@]}" --input in10_8f.yuv --frames 8 --bframes 3 --temporal-layers 3 "${HLG[@]}"
enc hlg_sar_ext    "${TEN[@]}" --sar 33:32 "${HLG[@]}"
enc hlg_conf_win   "${TEN[@]}" --input in10_66.yuv --input-res 66x66 "${HLG[@]}"
enc hlg_scaling_default "${TEN[@]}" --scaling-list default "${HLG[@]}"
enc hlg_scaling_list    "${TEN[@]}" --scaling-list scaling_list.txt "${HLG[@]}"
# Two-pass with --multi-pass-opt-rps: the second pass codes its short-term RPSs in the SPS.
"$X265" "${COMMON[@]}" "${TEN[@]}" --input in10_8f.yuv --frames 8 --bframes 3 --bitrate 50 --pass 1 \
  --stats sps_rps.stats --multi-pass-opt-rps "${HLG[@]}" -o /dev/null >/dev/null 2>&1
enc hlg_sps_rps    "${TEN[@]}" --input in10_8f.yuv --frames 8 --bframes 3 --bitrate 50 --pass 2 \
  --stats sps_rps.stats --multi-pass-opt-rps "${HLG[@]}"
enc hlg_422        --input in422.yuv --input-csp i422 --input-depth 10 --output-depth 10 --profile main422-10 "${HLG[@]}"
enc hlg_444        --input in444.yuv --input-csp i444 --input-depth 10 --output-depth 10 --profile main444-10 "${HLG[@]}"
