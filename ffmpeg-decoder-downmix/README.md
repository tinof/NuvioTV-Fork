# FFmpeg Decoder Downmix

This repo supports two build modes for the patched FFmpeg decoder:

## Default mode

The app uses the patched prebuilt AAR in `app/libs/lib-decoder-ffmpeg-release.aar`.

This is the default. No local FFmpeg source tree is required.

To build the AAR without a local FFmpeg tree, start the "Build FFmpeg decoder AAR" workflow
(`.github/workflows/build-ffmpeg-decoder.yml`, manual only: FFmpeg n7.0.2, NDK 27.0.12077973, API 24, all four ABIs)
on GitHub Actions. It builds this module against that FFmpeg and uploads the release AAR as an artifact.
`app/libs/README.md` records how the shipped AAR was built and its SHA-256.

## Local development mode

Use the source module `:ffmpeg-decoder-downmix` only when you need to rebuild or modify the patched decoder.

Enable it with one of:

- Gradle property: `-PuseLocalFfmpegDecoder=true`
- Environment variable: `USE_LOCAL_FFMPEG_DECODER=true`
- `local.properties`: `USE_LOCAL_FFMPEG_DECODER=true`

When local development mode is enabled, you must also provide:

- `FFMPEG_SOURCE_DIR`
- `FFMPEG_BUILD_DIR`

These can be set in `local.properties` or environment variables. See `local.example.properties`.

## Typical workflow

1. Build and test normally with the prebuilt AAR.
2. Switch on `USE_LOCAL_FFMPEG_DECODER` only when working on native decoder changes.
3. Rebuild the patched AAR after validating local decoder changes with `:ffmpeg-decoder-downmix:assembleRelease`.
4. Replace `app/libs/lib-decoder-ffmpeg-release.aar` with `ffmpeg-decoder-downmix/build/outputs/aar/ffmpeg-decoder-downmix-release.aar`.

## Transcode targets

With Force AC-3 or denied-codec handling, the renderer encodes multichannel audio to AC-3 5.1
(640 kbit/s). With Transcode AAC to DTS (`setTranscodeAacToDts`), it encodes mono and stereo AAC to DTS
core with FFmpeg's `dca` encoder: 48 kHz, 1509 kbit/s (2012-byte frames, inside an IEC 61937 DTS type I
burst), packed S32 input, opened with `strict_std_compliance` set to experimental. The DTS path resamples
with `filter_size` 144, `cutoff` 0.875 (scaled down below 44.1 kHz input) and `kaiser_beta` 13.

Both targets:

- report every encoded frame of a buffer to the sink through the app's `EncodedFrameCountingAudioSink`
  (Media3's `DecoderAudioRenderer` passes an access-unit count of 1);
- stamp output buffers from an anchored clock (`TranscodeClock`), not from the packet that completed them;
- drop the AAC priming (`Format.encoderDelay`) only when decoding starts at the stream start;
- flush the decoder, resampler and encoder FIFO with the packet flagged as the last sample, since
  `SimpleDecoder` never hands the end-of-stream buffer to `decode()`. When that flag is missing, up to
  511 + 108 samples (12.9 ms at 48 kHz) at the very end are not played.

