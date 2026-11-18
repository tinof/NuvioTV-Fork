#!/bin/sh
# Makes the HDR10 -> Dolby Vision 8.1 playback manifest fixture of ROADMAP DV4.10 (README.md) in
# this directory: a PQ clip muxed by mkvmerge, its `mkvdovi manifest`, mkvdovi's own 8.1 RPUs for
# the same clip, and the clip's AUs as Matroska stores them.
#
# Needs ffmpeg with libx265, mkvmerge, mkvdovi (with hdr_analyzer_mvp next to it) and dovi_tool;
# MKVDOVI and DOVI_TOOL name other binaries. Afterwards, in native/rpu_builder:
#   NUVIO_WRITE_FIXTURES=1 cargo test manifest_fixture
# writes hdr10_81.verdicts.txt from the real matcher.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
MKVDOVI=${MKVDOVI:-mkvdovi}
DOVI_TOOL=${DOVI_TOOL:-dovi_tool}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cd "$work"

# hdr-analyze mkvdovi/tests/support/playback.rs: dark before frame 20, bright from it (two shots),
# PQ with a 1000-nit mastering display and MaxCLL 1000, MaxFALL 400 in the SEI only.
PQ_TAGS="colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc:range=limited"
MASTERING="hdr10=1:master-display=G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L(10000000,1):max-cll=1000,400"
STEP="nullsrc=s=256x144:r=24,format=yuv420p10le,geq=lum='if(lt(N\,20)\,200\,700)+mod(X+N\,32)':cb='512':cr='512'"

ffmpeg -hide_banner -loglevel error -f lavfi -i "$STEP" -frames:v 48 -c:v libx265 -preset ultrafast \
  -x265-params "log-level=error:scenecut=0:repeat-headers=1:keyint=24:min-keyint=24:bframes=3:b-adapt=0:$PQ_TAGS:$MASTERING" \
  -pix_fmt yuv420p10le -f hevc -y pq.hevc
# A fixed seed for the UIDs and no date: the same tools give the same file.
mkvmerge -q --deterministic 1 -o hdr10_81.mkv pq.hevc

"$MKVDOVI" manifest hdr10_81.mkv --work-dir work >manifest.log 2>&1 || { cat manifest.log; exit 1; }

mkdir conv
cp hdr10_81.mkv conv/
(cd conv && "$MKVDOVI" hdr10_81.mkv --keep-source >../convert.log 2>&1) || { cat convert.log; exit 1; }
ffmpeg -hide_banner -loglevel error -i conv/hdr10_81.DV.mkv -map 0:v:0 -c:v copy -bsf:v hevc_mp4toannexb -f hevc -y dv.hevc
# One record per picture, in presentation order.
"$DOVI_TOOL" extract-rpu dv.hevc -o hdr10_81.mkvdovi.RPU.bin >/dev/null

# The stored AUs in decode order, NAL length fields included, as the extractor reads them.
ffmpeg -hide_banner -loglevel error -i hdr10_81.mkv -map 0:v:0 -c copy -f data -y hdr10_81.aus.bin

cp hdr10_81.mkv hdr10_81.mkvdovi.RPU.bin hdr10_81.aus.bin "$here/"
cp work/hdr10_81.dvmanifest.json "$here/"
