#!/usr/bin/env bash
# Builds the host driver (ASan + UBSan) and runs every case. See README.md.
#   FFMPEG_HOST_DIR=<host n7.0.2 tree> ./run.sh [work dir]
set -euo pipefail
cd "$(dirname "$0")"
: "${FFMPEG_HOST_DIR:?set FFMPEG_HOST_DIR to the host FFmpeg n7.0.2 build tree}"
make -s FFMPEG_HOST_DIR="$FFMPEG_HOST_DIR"
python3 -I check.py --driver build/host_jni_test --work "${1:-out}"
