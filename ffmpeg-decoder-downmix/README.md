# FFmpeg Decoder Downmix

This repo supports two build modes for the patched FFmpeg decoder:

## Default mode

The app uses the patched prebuilt AAR in `app/libs/lib-decoder-ffmpeg-release.aar`.

This is the default. No local FFmpeg source tree is required.

The "Build FFmpeg decoder AAR" workflow (`.github/workflows/build-ffmpeg-decoder.yml`: FFmpeg n7.0.2, NDK
27.0.12077973, API 24, all four ABIs) built the AAR in `app/libs`. It is disabled in this fork since
2026-10-22, when the owner moved all APK and AAR builds to local machines; build with the local
development mode below.
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

With `setTranscodeAacToDtsHd(true)` (off by default) and a sink that plays the DTS-HD transcode format
(`dtsHdOutputFormat()`, marked with `DTS_HD_TRANSCODE_FORMAT_ID`) directly, it writes DTS-HD Master Audio
instead: each 512-sample unit is the aligned DTS core plus an ExSS and a lossless 24-bit XLL, written by
the vendored dcaxll library (`src/main/jni/dcaxll/`, see `PROVENANCE.md`). The PCM is rounded to 24
bits before dcaenc, so the core and the XLL code the same samples; a mono source goes out as dual mono.
`DtsTranscodeTargets.choose` picks the target; `setDtsTranscodeTargetOverride` forces one for testing.

All targets:

- report every encoded frame of a buffer to the sink through the app's `EncodedFrameCountingAudioSink`
  (Media3's `DecoderAudioRenderer` passes an access-unit count of 1);
- stamp output buffers from an anchored clock (`TranscodeClock`), not from the packet that completed them,
  taking a priming trim into account when looking for discontinuities;
- drop the AAC priming (`Format.encoderDelay`) only when decoding starts at the stream start;
- flush the decoder, resampler and encoder FIFO with the packet flagged as the last sample, since
  `SimpleDecoder` never hands the end-of-stream buffer to `decode()`.

The DTS targets also:

- drop dcaenc's pre-roll packet and flush its last frame with one zero frame at the end of stream, so
  the core is aligned with its input (dcaenc's output lags one 512-sample frame);
- drop, after a seek, the units an accepted packet brings out from before the seek target.

At the end of stream with the last-sample flag nothing is lost (the last unit is padded with silence).
Without the flag, up to 511 (FIFO) + about 108 (resampler) + 512 (the frame dcaenc holds) samples,
23.6 ms at 48 kHz, are not played.

`src/test/native` is a host test of the JNI transcode path against FFmpeg n7.0.2 (`README.md` there);
`src/test/java` holds the module's JVM unit tests (`./gradlew :ffmpeg-decoder-downmix:testDebugUnitTest`).
