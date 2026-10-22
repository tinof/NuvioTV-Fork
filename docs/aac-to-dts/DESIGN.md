# Transcode AAC to DTS: design and findings

The plan, status and open steps are roadmap item [Q6](../../ROADMAP.md#q6-transcode-aac-to-dts). This file
keeps what the first session (2026-10-21) established, so later sessions do not have to re-derive it:

- the evidence from the FFmpeg and Media3 sources;
- the architecture as built;
- the resampler measurements;
- the review findings and the device result;
- how to build and test it.

The DTS-HD Master Audio target (2026-10-22) has its own design and implementation record in
[DTSHD_PORT.md](DTSHD_PORT.md); §10 below summarizes what it changed here.

Work branch: `feat/Q6-aac-dts` (based on `perf/shield-pass`, not merged). Paths are relative to this
repository. FFmpeg citations are to tag n7.0.2 and Media3 citations to tag 1.8.0, the versions the app links.

## 1. Why, and the owner's decisions

- **Setup.** The owner's Shield is connected over HDMI to a Sony HT-A9. The HDMI profile (`dumpsys
  media.audio_policy`, 2026-10-21) lists PCM 16-bit up to 7.1 at 32–192 kHz, plus AC-3, E-AC3, E-AC3 JOC,
  TrueHD, DTS, DTS-HD and IEC61937.
- **Why DTS.** The owner prefers DTS input, because the HT-A9 upmixes it with Neural:X. Before this feature
  they converted files offline with `docs/todts`, a local script that is not committed (`/docs/*` is
  gitignored). Its settings: `-c:a dca -strict -2 -b:a 1509k`, resampled to 48 kHz with
  `aresample=48000:resampler=soxr:precision=28`. It keeps mono and stereo, turns 3–8 channels into
  5.1(side), and folds 7.1 at −3 dB per surround pair.
- **What the owner decided** (2026-10-21):
  - **Scope:** AAC with 1 or 2 channels only. Multichannel AAC keeps its 5.1 PCM path. Other codecs are
    unchanged.
  - **Resampler:** keep swresample and tune it. No libsoxr dependency.
  - **Setting:** a toggle in Audio settings, per profile and device-local, off by default, on every device.
    It is active only when the output accepts DTS.
- **Quality caveat** (said once, the owner chose DTS anyway). Over HDMI, AAC already reaches the AVR as
  PCM with no loss beyond the AAC decode. DTS adds a second lossy step, made with an encoder FFmpeg flags as
  experimental.

## 2. FFmpeg `dca` encoder facts (n7.0.2)

All of these are from `libavcodec/dcaenc.c` and `dcaenc.h`:

- **Input:** packed `AV_SAMPLE_FMT_S32` only (`dcaenc.c:1325`). The AC-3 path uses planar float, so the
  JNI needs a format-aware buffer path.
- **Sample rates:** 8, 11.025, 12, 16, 22.05, 24, 32, 44.1 and 48 kHz (`dcaenc.h:34-36`). 88.2 kHz and up
  are rejected.
- **Layouts:** mono, stereo, 2_2, 5POINT0 and 5POINT1, the side variant (`dcaenc.c:1328-1335`).
  `av_channel_layout_default(6)` returns 5.1(back), and the encoder rejects it. That only matters if 5.1 is
  ever added.
- **Frame size:** 512 samples (`dcaenc.c:290`), against 1536 for AC-3. One AAC-LC frame of 1024 samples is
  2 DTS frames, and one HE-AAC frame is 4.
- **Experimental flag:** the encoder carries `AV_CODEC_CAP_EXPERIMENTAL` (`dcaenc.c:1318`).
  `avcodec_open2` refuses it unless `strict_std_compliance = FF_COMPLIANCE_EXPERIMENTAL` (`avcodec.c:267-281`).
- **Bitrate:**
  - The default is 1411200 and the accepted range is 32k to 3.84M (`dcaenc.c:285-288`).
  - Frame bytes = FFALIGN(ceil(rate × 512 / sr), 32) / 8.
  - **1509k at 48 kHz gives 2012 B.** An IEC 61937 DTS type I burst carries at most 2040 B, so the ceiling
    is 1530000 bps at 48 kHz and 1405687 bps at 44.1 kHz. That is why the output is always 48 kHz.
  - 5.1 at 48 kHz needs at least about 669 kbps, so the AC-3 path's 640k would fail there.
- **Bitstream:** core-only DTS, 16-bit big-endian, sync `7F FE 80 01`, CBR, no `AV_CODEC_CAP_DELAY`.
  `DtsUtil` in Media3 parses it: NBLKS 15 gives 512 samples.
- **Licence:** FFmpeg's own LGPL code. `configure` needs neither gpl nor nonfree.
- **Quality, from reading the code:**
  - It has a psychoacoustic model.
  - All channels share one masking curve.
  - There is no transient handling and no joint or sum-difference coding.
  - It possibly takes the LFE history from the wrong channel when encoding 5.1 (`dcaenc.c:970-975`, not
    verified; irrelevant for mono and stereo).

## 3. Media3 1.8.0 facts that shaped the design

- **Access-unit count.** `DecoderAudioRenderer` always passes `encodedAccessUnitCount = 1` to
  `handleBuffer`. `DefaultAudioSink` counts written frames as frames-per-unit × count, and
  `getCurrentPositionUs` clamps to the written frames. With 2–4 DTS frames per buffer, the audio clock would
  run at half speed or slower. This was confirmed in the shipped engine AAR's bytecode, not only in the
  1.8.0 source.
- **End of stream.** `SimpleDecoder.decode()` (`SimpleDecoder.java:258`) handles an end-of-stream input
  buffer without calling the subclass, so a native flush keyed on `isEndOfStream()` never runs. That
  includes the old AC-3 path.
- **Priming.** Bitstream output skips the sink's `TrimmingAudioProcessor`, so MP4 AAC priming (usually
  2112 samples, about 44 ms) plays unless the decoder trims it.
- **Two sink questions.** `DecoderAudioRenderer.sinkSupportsFormat` calls `AudioSink.supportsFormat`.
  `PlaybackSpeedAwareAudioSink` overrides only `getFormatSupport`, which applies Bluetooth, speed, the
  session PCM latch and the passthrough policy. The DTS decision therefore uses `getSinkFormatSupport`.
  **The existing denied-codec AC-3 path still uses `sinkSupportsFormat`, so it bypasses those checks.**
  This was not changed; it is an open item.
- **Errors.** When `onPlayerError` runs for an AudioTrack init failure, the player has already disabled the
  renderers and `player.audioFormat` is null.
- **Threads.** A `DefaultAudioSink` built with a Context pins its playback looper. Calling
  `getFormatSupport` from the main thread throws "accessed on multiple threads". The sink chain is only
  asked on the playback thread.

## 4. Architecture as built

**Routing (app)**
- `core/player/AacToDtsRouting.kt` decides which tracks qualify:
  - AAC with 1–2 channels, not encrypted, not xHE-AAC (`mp4a.40.42`, which FFmpeg 7.0 cannot decode);
  - and the sink can play DTS 48 kHz stereo directly.
- `PlaybackSpeedAwareAudioSink.prefersDtsTranscode` asks that through `getFormatSupport`.
- `PlaybackSpeedAwareAudioRenderer.getDecoderInfos` returns no MediaCodec decoder for such a track, so track
  selection falls to the FFmpeg renderer. E-AC3, TrueHD and DTS keep their passthrough path.
- The flag needs `decoderPriority != 0`. It is part of `ExoConstructionFingerprint` and is skipped for
  streams in `aacToDtsOffStreamUrls`.

**Frame counting (app)**
- The FFmpeg renderer is rebuilt in `buildAudioRenderers` with an `EncodedFrameCountingAudioSink`. The
  rebuild happens before the MediaCodec renderer, which must stay the last owner of the shared sink listener.
- The wrapper walks the AC-3 or DTS sync frames of each buffer from index 0, so a partly consumed buffer
  gives the same count.
- This also fixes HE-AAC 5.1 → AC-3, which can put 2 AC-3 frames in one buffer.

**Renderer (module `ffmpeg-decoder-downmix`)**
- `FfmpegAudioRenderer.setTranscodeAacToDts` turns the transcode on.
- `shouldTranscodeToDts` is checked before the AC-3 rules.
- The output format is `audio/vnd.dts`, 48 kHz, 2 channels, even for a mono bitstream, so the AudioTrack
  gets a stereo mask that every HDMI DTS profile lists.
- New public API: `isTranscodingToDts`, `getDtsTranscodeChannelCount`, `isDtsTranscodeSource` and
  `dtsOutputFormat`.

**Decoder and JNI**
- The JNI takes a transcode target (none, AC-3 or DTS) instead of a boolean.
- **DTS:** `AV_CODEC_ID_DTS`, packed S32, 48000 Hz, 1509000 bit/s, opened with the experimental flag. The
  channel count of the first decoded frame (1 or 2) is locked for the decoder's life, because HE-AACv2 is
  signalled as 1 channel but decodes as 2.
- **Encoder lifetime:** `clearEncoder` is split from `clearResampler`. A mid-stream resampler reconfigure
  keeps the encoder and drains the old resampler into the FIFO.
- **Buffers:** `av_samples_alloc_array_and_samples` and `av_samples_set_silence`, so packed formats work.
  The limiter runs on planar float only.
- One info line per decoder:
  `DTS transcode: <in Hz> Hz <ch> ch -> 48000 Hz <ch> ch, 1509000 bit/s, frame 512 samples`.

**Timing (both targets)**
- `TranscodeClock` stamps output as the anchor plus the encoded samples, where the anchor is the first input
  after a reset.
- An input more than 200 ms away from that clock re-anchors it, but not before the first output after the
  anchor (a long priming trim).
- The priming trim (`Format.encoderDelay`) runs only when the first input after a reset is at media time
  ≤ one AAC frame, using `getStreamOffsetUs` and `onStreamChanged`. A resume or a track switch trims nothing.
- The end-of-stream flush runs with the packet flagged `BUFFER_FLAG_LAST_SAMPLE`.
- **Corrected 2026-10-22 (DTSHD_PORT.md F3, §3.4).** dcaenc's output lags its input by one frame (512
  samples, measured on n7.0.2) and it has no delay capability. Until commit `799767060` every DTS unit
  played 10.67 ms late and the last frame sent to dcaenc was never emitted, even with the flag, so the
  "511 + 108 samples (12.9 ms)" given here before understated the loss by 512 samples. Now the
  pre-roll packet is dropped and a zero frame flushes the last real frame:

  | end of stream | lost at the end |
  |---|---|
  | flagged | nothing; the last unit is padded with up to 511 samples of silence |
  | flag missing | FIFO remainder (≤ 511) + resampler (about 82, ≤ 108) + the held frame (512): ≤ 1131 samples, 23.6 ms |

  The host test (`ffmpeg-decoder-downmix/src/test/native`) checks both rows.

**Resampler (DTS target only, when the input is not 48 kHz)**
- `filter_size` 144, `cutoff` 0.875 (× in_rate / 44100 below 44.1 kHz) and `kaiser_beta` 13. See §5.

**Failures and live changes (app)**
- A refused DTS AudioTrack whose source is not DTS is not learned as a DTS denial. Learning it would also
  block real DTS on the route. The source MIME comes from `lastAudioInputMime`, which the analytics listener
  caches per player. The stream goes into `aacToDtsOffStreamUrls` and plays PCM.
- When the FFmpeg renderer comes first ("Prefer app decoders", Force AC-3, or a stream that prefers FFmpeg
  audio), it keeps an AAC track when Bluetooth, the speed or the policy changes.
  `recreateDtsTranscodeDecoderIfNeeded` handles that:
  - it compares, on the playback thread, the decoder's output with what the sink allows;
  - when they differ, it turns the audio track off and then on again, with the re-enable queued behind the
    disable;
  - it covers both directions and stands down during an AFR quiesce;
  - the AC-3 equivalent is not covered.
- The HUD reads "DTS transcode". The stats routing mode is `TRANSCODE_DTS`, "transcode DTS", shown as a
  good path, not a fallback.
- The issue report's raw events line gets `aacToDts=<setting>`. The structured report is unchanged: it goes
  to a server whose schema is unknown.

**Build**
- `--enable-encoder=dca` in `.github/workflows/build-ffmpeg-decoder.yml` and `jni/build_ffmpeg.sh`.
- The decoder AAR is built by CI from the module. The provenance is in `app/libs/README.md`:
  - `d8e211b4…` (run 39589348150): the first build from the module source, before the DTS work;
  - `a1152a6d…` (run 39594133508): with the DTS target.

## 5. Resampler measurements

Measured on 2026-10-21 by a host build of the exact n7.0.2 libswresample (arm64 NEON). Only the soxr
reference used Homebrew FFmpeg 9.0.2. The tools are in `swrtune/` (§8).

44.1 → 48 kHz. Spur levels are dB relative to the carrier. CPU is for 60 s of stereo.

| setting | ripple to 20 kHz | worst spur, tones ≤ 20k / 21k / 21.8k | empty band 22.35–23.9 kHz | delay (48k samples) | CPU |
|---|---|---|---|---|---|
| default swr (fs 32, cutoff 0.97, β 9) | 0.497 dB | −25.1 / −13.7 / −7.5 | −12.2 | 17 | 1× |
| fs 128, cutoff 0.875, β 12 | 0.0085 dB | −128.7 / −123.7 / −119.5 | −122.9 | 73 | 3.7× |
| **fs 144, cutoff 0.875, β 13 (shipped)** | **0.0033 dB** | **−135.3 / −132.0 / −130.7** | **−131.5** | **82** | **4.1×** |
| fs 144, cutoff 0.875, β 14 | 0.0067 dB | −144.9 / −142.8 / −152.9 | −136.8 | 82 | 4.1× |
| soxr precision 28 | 0.0062 dB | −165.7 / −159.2 / −170.1 | −152.5 | 266 | 3.4× |

**Notes**
- **What `cutoff` does in n7.0.2.** It puts the −6 dB edge at cutoff × 24 kHz, clamped at the input
  Nyquist. For 44.1k input every cutoff ≥ 0.919 gives the same filter, and the default leaves images.
- **Defaults and limits.** `exact_rational` is on by default. The maximum `kaiser_beta` is 16. No setting
  with `filter_size` ≤ 64 passes.
- **Other input rates** with the shipped setting:
  - 88.2k: ripple 0.0033 dB, images at or below −137 dB.
  - 96k: ripple 0.0034 dB, images at or below −134 dB.
  - 32k: needs cutoff 0.635 (scaled), images at or below −134 dB. With the unscaled 0.875, a tone near
    15.5 kHz images at −33 dB.
- **Delay.** swresample compensates the filter delay (impulse peak at 0 samples). About 82 output samples
  stay in the filter at end of stream.
- **CPU on the Shield:** not measured yet (Q6.5).

## 6. Reviews

**Plan review by Codex** (the `ExitPlanMode` hook, two rounds). Six concerns, all handled in the plan:
- Round 1: end-of-stream never reaches `decode()`; output timestamps; priming trimmed only at the stream
  start.
- Round 2: the clock must take the trimmed priming into account (hence the anchored clock); PREFER mode
  must re-create the decoder in both directions; the end-of-stream loss bound must be measured, not
  assumed to be one frame.

**Code review** (2026-10-21). A workflow ran 4 Opus finders (native, module Java, app routing,
settings/tests), each followed by an Opus verifier. Ten findings were confirmed, and all except the
first (the unshipped AAR, the planned Q6.1 step) were fixed in the branch:

1. The app called renderer methods that the shipped AAR did not have yet. This was the planned Q6.1
   step: the DTS AAR is now in `app/libs`.
2. The rejection guard read `player.audioFormat`, which is null at error time. A refused transcode was
   learned as a DTS denial. Fixed with `lastAudioInputMime`.
3. The in-place surround resolve ignored `aacToDtsOffStreamUrls`.
4. Re-creation was gated on PREFER only. Force AC-3 and prefer-FFmpeg streams also put FFmpeg first.
5. The re-creation check called the sink from the main thread. It now runs on the playback thread.
6. The re-enable was a blind post that could merge with the disable. It is now queued behind the disable.
7. The DTS decision used `sinkSupportsFormat`, bypassing speed, policy and the PCM latch. It now uses
   `getSinkFormatSupport`.
8. The clock re-anchored during a priming trim longer than 200 ms (input rates below about 10 kHz).
9. A TOCTOU race on `activeDecoder` in `isTranscodingToDts`, `isTranscodingToAc3` and the channel-count
   getter.
10. A double free in the JNI when an allocation after `avcodec_open2` fails. This predates the branch.

The review prompt is in the session's workflow script and is not kept here. Before merging, `/ship` runs the
cross-family (Codex) review. Fable was not used.

## 7. Device results

**2026-10-21**, `.shield` debug build with the local module, owner present:
- Title: *Blue Lights* S1E1, "2160p iP WEB-DL H265 HLG AAC 2.0", 48 kHz.
- **Logcat:** `DTS transcode: 48000 Hz 2 ch -> 48000 Hz 2 ch, 1509000 bit/s, frame 512 samples`. The
  encoder was opened twice, the second time by the AFR quiesce after the display mode switch. That is
  expected.
- **AudioFlinger:** a DIRECT output thread at 48000 Hz with processing format `AUDIO_FORMAT_DTS` (HAL format
  PCM_16_BIT, 2 channels: the IEC61937 carriage).
- **Errors:** one start-of-track `pause because of UNDERRUN, framesReady = 0` on the DIRECT thread, then no
  underruns, sink errors or discontinuities.
- **Owner reading:** the HT-A9 shows DTS 2.0 and it plays perfectly.
- **Not tested yet:** 44.1 kHz, mono, HE-AACv2, a lip-sync clip, seeks, speed changes, track switches,
  Bluetooth, end of stream, and the AC-3 regression (Q6.2–Q6.5).

The session snapshot (M2) is in `~/shield-snapshots/20261021-aacdts/`: settings, `debug.nuvio` props
(none), display mode, the package list, the previous APK `shield-before.apk` (SHA-256 `c7c3b0a9…`) and the
logcat. The installed build is APK SHA-256 `4106320a…`, built at the code state before the CI AAR commit.
The session raised the log buffer to 4 MB and set it back to 256 KiB.

## 8. How to build and test

**CI AAR.** Runs on the fork, not upstream.
- Plain `gh` resolves to `ysosrs123/NuvioTV-Fork` and fails with HTTP 403. Always pass
  `-R tinof/NuvioTV-Fork`.
- The local `perf/shield-pass` carries roadmap commits that are never pushed, so the build goes through a
  branch cut from `origin/perf/shield-pass` that carries only the module: `ci/Q6-ffmpeg-aar`. Delete it
  after merging.

```sh
git switch ci/Q6-ffmpeg-aar
git checkout feat/Q6-aac-dts -- ffmpeg-decoder-downmix
git commit -m "ci: ..."
git push origin ci/Q6-ffmpeg-aar
gh workflow run build-ffmpeg-decoder.yml -R tinof/NuvioTV-Fork --ref ci/Q6-ffmpeg-aar
gh run download <id> -R tinof/NuvioTV-Fork -D <dir>   # AAR + ffmpeg-build-static-libs
```

The run takes about 4 minutes. Copy the AAR to `app/libs/lib-decoder-ffmpeg-release.aar` and record the
SHA-256 and the run ID in `app/libs/README.md`.

**Local module build.** Iterate on native code without CI:
1. Make a configured n7.0.2 header tree: clone the n7.0.2 tag, then run `./configure --disable-everything
   --disable-programs --disable-doc --disable-asm` and `sh ffbuild/version.sh . libavutil/ffversion.h`.
2. Use the `ffmpeg-build-static-libs` artifact of a CI run (it already has `dca`).
3. Build:

Gradle needs JDK 17 and the Android SDK: `JAVA_HOME=$(/usr/libexec/java_home -v 17)` and
`ANDROID_HOME=$HOME/Library/Android/sdk` (or `sdk.dir` in `local.properties`). Without the SDK the
build stops before compiling anything.

```sh
export USE_LOCAL_FFMPEG_DECODER=true FFMPEG_SOURCE_DIR=<configured tree> FFMPEG_BUILD_DIR=<static libs>
./gradlew :ffmpeg-decoder-downmix:assembleRelease      # module only
scripts/shield_build_install.sh                        # .shield APK with the local module
```

The env variables also switch the app to the local module. Unset them for the gate, which must run against
the AAR in `app/libs`.

**Tests.**
- Module JVM tests: `./gradlew :ffmpeg-decoder-downmix:testDebugUnitTest` (no env needed).
- App: `./gradlew :app:testFullDebugUnitTest`, once without and once with the env above.
- Host test of the JNI transcode path: `ffmpeg-decoder-downmix/src/test/native/README.md` (a host FFmpeg
  n7.0.2 tree with the `dca` encoder and decoder and the `aac` decoder, Homebrew `ffmpeg-full`).

**Shield.**
- Claude's sandboxed Bash cannot reach the Shield ("No route to host"); run adb with the sandbox
  disabled.
- Follow M2: snapshot first, `adb install -r` with the CI key only, and restore what the session changed.
- The app has no direct-URL playback, so the owner plays a title from their addons.
- Useful commands:

```sh
adb logcat -v time 'ffmpeg_jni:V' 'FfmpegAudioRenderer:V' 'PlayerViewModel:V' 'AudioTrack:V' \
  'DefaultAudioSink:V' 'AudioFlinger:W' '*:S'          # quote the filters in zsh
adb shell dumpsys media.audio_flinger | rg -A12 "type 1 \(DIRECT\)"
```

**Resampler tools** (`swrtune/`):
- `swrh.c` is a raw-f32 harness around `swr_convert`. It reports the delay and the timing.
- `swrtune.py` makes the signals and runs the grids (modes `grid`, `refine`, `rates`).
- `summarize.py` prints the tables.
- `results/rates.jsonl` holds the shortlisted settings at 32/44.1/88.2/96 kHz.
- Build `swrh` against a host n7.0.2 build (`./configure --disable-everything --disable-programs
  --disable-doc --enable-swresample`, then `make libswresample/libswresample.a libavutil/libavutil.a`), and
  `swrh_brew` against Homebrew FFmpeg for soxr.
- Run Python with `python3 -I`.

## 9. Open questions

- **Issue reports:** add `transcodeAacToDts` to the structured DTO? Owner decision, since the report
  server's schema is unknown.
- **Denied-codec AC-3:** move it to `getSinkFormatSupport` like DTS (§3)? It predates this branch.
- **AC-3 re-creation:** extend the PREFER/FFmpeg-first decoder re-creation to Force AC-3?
- **Mono:** the HT-A9 behaviour with mono DTS (a stereo AudioTrack carrying a 1-channel bitstream) is
  untested.
- **Compiler warnings:** a warning-clean native compile was not checked (the builds ran with `-q`).

## 10. DTS-HD MA target (2026-10-22)

Summary; the details, the Codex review and every deviation from the port design are in
[DTSHD_PORT.md](DTSHD_PORT.md) §11.

- **Target per stream:** DTS-HD MA when the output's pinned capabilities list DTS-HD (on both routes: the
  platform sink and the own IEC engine), else the DTS core, else PCM. A refused DTS-HD AudioTrack steps the
  stream to the core, a refused core to PCM, never learned as a denial of real DTS (D5).
  `debug.nuvio.aac_dts_target=hd|core` forces the target (D6).
- **JNI:** `TRANSCODE_TARGET_DTS_HD`; the vendored dcaxll writer pairs core packet p with the 24-bit PCM
  of frame p − 1; mono is dual mono at unity.
- **Fixes to the core target that came with it:** alignment and the last frame (§4), the priming trim in
  `TranscodeClock`, the discard of pre-target units after a seek, the step-down before the PCM recovery,
  no latch change while a same-configuration retry is scheduled, the tunnel guard.
- **Not yet:** the CI AAR (the app reaches the DTS-HD API by reflection and stays on the core with the
  current AAR), the device run.

