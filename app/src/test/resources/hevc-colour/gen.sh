#!/bin/bash
# Encodes two 64x64 frames per case with x265 and prints its parameter-set and SEI NALs as hex.
# Inputs (in the fixture dir):
#   ffmpeg -f lavfi -i testsrc2=size=64x64:rate=1 -frames:v 2 -pix_fmt yuv420p10le -f rawvideo in10.yuv
#   ffmpeg -f lavfi -i testsrc2=size=64x64:rate=1 -frames:v 2 -pix_fmt yuv420p -f rawvideo in8.yuv
set -euo pipefail
TOOLS="$(cd "$(dirname "$0")" && pwd)"
cd "$1"
X265=/opt/homebrew/bin/x265
COMMON=(--input-res 64x64 --fps 1 --frames 2 --no-info --log-level error)
TEN=(--input in10.yuv --input-depth 10 --output-depth 10 --profile main10)
EIGHT=(--input in8.yuv --input-depth 8 --output-depth 8 --profile main)

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
