# Nvidia Shield: defaults, on-device checks and open items

This covers the Shield pass on branch `perf/shield-pass` (October 2026), tuned for a Shield TV Pro 2019
(`mdarcy`, Android 11 / API 30). Facts about the box:
- Shield Experience 9.x is Android 11 on every model.
- The system "Match frame rate" exists only from 9.2 (beta).
- AI upscaling covers 60 Hz HDR10 on the Pro from 9.1.
- There is no AV1 hardware, no HDR10+, and no DV profile 7 FEL.

## Installing a debug build next to the release app

A manual run of "PR Full Debug Build" builds `com.nuvio.tv.test.shield`. The `app_id_suffix` input defaults to
`.shield`. The APK installs beside the release app, shows as "Nuvio Arc Debug", and keeps its own settings and
logins.

When the repo secret `DEBUG_KEYSTORE_BASE64` is set, every run signs with the same key, so a newer debug build
updates the last one in place. The run log prints each APK's package and certificate digest.

Install the `arm64-v8a` APK from the `full-debug-apks` artifact.

## Nvidia defaults set without measuring

The owner chose to take the likely-better side of each open question on Nvidia now. Each one:
- applies only when `Build.MANUFACTURER` is NVIDIA (or only on `mdarcy`, where the table says so);
- yields to a saved user setting where one exists;
- can be reverted with its switch.

Set a switch with `adb shell setprop debug.nuvio.<name> <0|1>` and clear it with
`adb shell setprop debug.nuvio.<name> ""`. Either value works on any box. Restart playback, or the app for the
rows marked *app*.

| What changed on Nvidia | Switch (revert value) | What to look at |
|---|---|---|
| TrueHD/DTS-HD go to the platform sink natively, not through the fork's IEC61937 packer. The "System passthrough" setting now defaults to on (a saved value wins). | `hbr_iec` (`1` = packer) | The AVR's Atmos / DTS:X indicator, A/V sync, the HUD underrun row |
| TrueHD/DTS-HD passthrough buffer caps ×2 | `hbr_buffer_scale` (`1`) | Underruns, `dumpsys media.audio_flinger` |
| A TrueHD/DTS-HD track that the platform sink plays keeps tunneling | `hbr_tunnel` (`0`) | `PLAYBACK_TUNNELING applied=`, stalls |
| The surround decision uses the stereo / 5.1 / 7.1 probe union and the HDMI channel count | `audio_probe_union` (`0`) | Settings > Advanced > Source & hardware |
| A track-AFR mode switch under tunneling with TrueHD/DTS-HD takes audio and video down and up together | `afr_quiesce_av` (`0`) | First play of a 24p TrueHD title: `Track AFR settle complete … quiesced=AUDIO_AND_VIDEO`, and no stuck loading screen |
| AFR settle hold of 1.2 s instead of 2 s | `afr_settle_ms` (`2000`) | Logcat `Switching display mode` → `stabilized in N ms` |
| A tunneled start waits for the real first frame (only for decoders seen reporting frames) | `tunnel_real_first_frame` (`0`) | The loading screen ends with the picture |
| Dolby Vision plays untunneled | `dv_no_tunnel` (`0`) | DV signalling on the TV, sync |
| Hardware DV decoders are tried first on DV displays | `dv_decoder_first` (`0`) | HUD "DV decoder" and "Decoder" rows on P8.1 and converted P7 |
| DV7 Auto converts with mode 2 (8.1 with no-op mapping; falls back per RPU to mode 1) | `dv7_manual` (`0` = mode 1) | The TV's DV info on one FEL and one MEL title |
| HDR10 metadata injection on DV strip is on by default (a saved value wins) | Setting "Inject HDR10 metadata on strip" | The TV's HDR info screen: mastering / MaxCLL |
| HDR video ignores the view-scaling aspect modes and plays at Fit (ExoPlayer) | `hdr_zoom` (`1` = allow) | `dumpsys SurfaceFlinger` keeps the video on an overlay plane |
| mpv decodes with direct MediaCodec (`mediacodec,mediacodec-copy`; a saved mode wins) | Setting "Hardware decoding" | HUD `hwdec` row shows `mediacodec` |
| mpv tone mapping `bt.2446a` with no peak detection | `mpv_tonemap` (`0`) | HDR on an SDR TV through mpv |
| Seek thumbnails start on (`mdarcy` only, when no mode was chosen) | `thumb_default_on` (`0`) | Logcat `ThumbMem … strongBox=`, the HUD dropped-frame row with thumbnails on vs off |
| Full swap no longer refuses strong thumbnails on `mdarcy` when MemAvailable is at least 900 MB | `thumb_swap_rule` (`1`) | Logcat `ThumbMem` reasons |
| Low-RAM tier comes from device RAM when buffers are off-heap | `lowram_from_ram` (`0`) | Logcat `BACK_BUFFER_RESOLVED lowRam=` |
| libass renders with the canvas overlay (when no type is saved). The settings page still labels Overlay OpenGL "recommended". | `libass_overlay_canvas` (`0`) *app* | `dumpsys SurfaceFlinger` with subtitles and the HUD: no CLIENT composition, no UI stutter |
| Opaque black window background outside playback | `opaque_window` (`0`) *app* | `dumpsys SurfaceFlinger` on Home; one possible frame of stutter when the player opens or closes |
| Video codec queueing is async (from the first pass) | `async_codec` (`0`) | HUD dropped-frame row on 4K60 HEVC and an 80+ Mbit/s DV remux |
| Auto frame-rate matching starts in Start/Stop mode (from the first pass) | Setting "Frame rate matching" | Turn off the Shield's own "Match frame rate" while Nuvio AFR is on |

Changes for every device in this round:
- Controller buttons in the player get default jobs: L1/R1 seek, X play/pause, Y subtitles. They can be
  rebound on the remote-buttons page.
- Native libraries load straight from the APK (`extractNativeLibs=false`). This saves eMMC space; the APK
  download is larger. Device check: mpv playback and seek thumbnails still work.
- `libnuviothumb` is tuned for Cortex-A57 on arm64.
- Localhost streams report the real HTTP status, so expired links (404/410) and 416 retries are handled.
- When the decoder list cannot be read, a DV5 MKV takes the HEVC remap.
- The HUD queue row says `default (async)` on Fire TV API 28+.
- The episode-options dialog no longer draws the page's pre-blurred glass.
- New and changed strings are translated in every locale.
- Two races found by flaky tests are fixed: a failed meta lookup could stick for the whole session, and a
  profile switch right after start could keep the old profile's watched list.

## Still open

| Item | Why it is open |
|---|---|
| Shield thermal threshold in `PlaybackThermalSampler.SKIN_SEVERE_C_BY_MODEL` | It needs the vendor value from `adb shell dumpsys thermalservice` on the Shield. Guessing one would put invented data in the HUD. |
| Baseline profile in PERFORMANCE visual mode | The generator needs a device on a 60 Hz mode and the AM9 test inputs. After installing a release build, run `adb shell cmd package compile -m speed-profile -f <package>`. |
| Focused-card width animation | It can't be done without changing the look. Later cards slide because the row relays out. Keeping the slide without relayout would need every later item to animate its own offset, which is a row redesign. |
| CEC sleep | Covered in code, but not checked on the device. When the TV turns off, the activity pauses and `pauseForLifecycle()` pauses playback; the pause saves progress. Check it on the device. |
| Settings strings for libass | Overlay OpenGL is still labelled "recommended" although Nvidia now starts on canvas. Change the label after measuring. |
| `audio_tunneled_sub` in `values-pt`, `values-sr`, `values-zh` | Those locales never had the key and fall back to their regional or English text. |

## Not important for this fork

These are skipped on purpose. They do not matter for the owner's setup. Revisit them if the fork is ever
published to a wider audience.

| Item | Notes |
|---|---|
| HDR10+ → Dolby Vision 8.1 | Separate project, see `docs/hdr10plus-to-dv81/`. |
| VC-1 reader for MPEG-TS / M2TS | A Blu-ray `.m2ts` VC-1 remux goes to mpv (software VC-1). A VC-1 PES reader would plug in as an `ExtractorsFactory` wrapper like `AviTrackFormatExtractorsFactory`, replacing the `TsExtractor` built at `PlayerRuntimeControllerInitialization.kt` (~1212) with a custom `TsPayloadReader.Factory` for stream type 0xEA. The decoder side (`video/wvc1` → `video/vc1` bridge) already exists. |
| Ethernet-aware network defaults | Read `NetworkCapabilities.TRANSPORT_ETHERNET` and suggest performance mode with 4 connections in the Device Assessment on a wired, non-small box. `NetworkSettingsScreen` already classifies the link. |
| VOD cache on USB storage | The cache is fixed to `cacheDir` (`PlayerMediaSourceFactory.kt` ~807). It would need a picker over `context.getExternalCacheDirs()`. |

## Media3 fixes after 1.8.0 worth backporting

The engine ships as halibiram/media AARs with selected backports (`app/libs/README.md`). From the release notes
of 1.8.x–1.11.1 (androidx/media `RELEASENOTES.md`, read 2026-10-14), not yet in our AARs:
- 1.11.1: the AudioTrack setup retry always tries a buffer of at least 1 s (passthrough init failures,
  androidx/media#3207).
- 1.11.1: a codec flushed before its first input no longer drops every sample (seek and flush on the async
  path).
- 1.11.1: when the stream has no valid operating rate, it falls back to the frame-rate estimator or the output
  format (fewer codec resets).
- 1.11.0: the frame-rate estimate counts dropped and skipped frames (feeds `Surface.setFrameRate` and AFR).
- 1.11.0: `VideoFrameReleaseHelper` no longer wakes the main thread with extra Choreographer callbacks. This
  lands in a file our 1.9.0 frame-release backport already rebuilds.
- 1.11.0: immediate rendering on a new surface no longer drops frames.
- 1.11.0: audio renderers wait 100 ms before going from ready to not-ready (androidx/media#3210).
- 1.11.0: MPEG-TS DTS-HD core and extension go out as one sample (androidx/media#3147); the last MPEG-TS frame
  renders (androidx/media#3206).
- 1.10.1: a race in audio session ID generation under tunneling (androidx/media#3099), and a crash in prewarming
  recovery.
- 1.9.1: the DV fallback to an AVC/HEVC codec no longer fails some files.
- 1.9.0: MPEG-TS PES packets without a timestamp no longer throw in `ReorderingBufferQueue`
  (androidx/media#2764).

Nothing in these notes mentions NVIDIA, Tegra, Shield, VC-1, TrueHD or IEC61937.

## Quick reference

```sh
adb logcat -s NuvioCodecs                       # decoder list, once per app start
adb shell setprop debug.nuvio.<name> <0|1>      # switches above; "" clears
adb shell getprop | grep debug.nuvio            # what is set
adb shell dumpsys media.audio_flinger           # audio tracks, underruns
adb shell dumpsys SurfaceFlinger                # composition per layer
adb shell dumpsys display | grep -i mode        # current display mode
adb shell dumpsys thermalservice                # thermal thresholds (for the open item above)
```
