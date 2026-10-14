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
updates the last one in place. Without it the run uses a throwaway key. The run log prints each APK's package and
SHA-256 certificate digest. A manual run stops with an error when `NUVIO_SUPABASE_URL` is missing from the
`LOCAL_PROPERTIES_BASE64` secret, because account login would not work in that APK.

Install `app-full-arm64-v8a-debug.apk` from the `full-debug-apks` artifact with `adb install -r`.

## Nvidia defaults set without measuring

The owner chose to take the likely-better side of each open question on Nvidia now. Each one:
- applies only when `Build.MANUFACTURER` is NVIDIA (or only on `mdarcy`, where the table says so);
- yields to a saved user setting where one exists;
- can be reverted with its switch.

Set a switch with `adb shell setprop debug.nuvio.<name> <0|1>` and clear it with
`adb shell setprop debug.nuvio.<name> ""`. Either value works on any box (for on/off switches `true`/`false` and
`on`/`off` work too; the number switches take a number). Restart playback, or the app for the rows marked *app*.

| What changed on Nvidia | Switch (revert value) | What to look at |
|---|---|---|
| TrueHD/DTS-HD go to the platform sink natively, not through the fork's IEC61937 packer. The "System Passthrough" setting now defaults to on (a saved value wins). | `hbr_iec` (`1` = packer) | The AVR's Atmos / DTS:X indicator, A/V sync, the HUD underrun row |
| TrueHD/DTS-HD passthrough buffer caps ×2 | `hbr_buffer_scale` (`1`) | Underruns, `dumpsys media.audio_flinger` |
| A TrueHD/DTS-HD track that the platform sink plays keeps tunneling. Inert on mdarcy SE 9.2.4 (no decoder tunnels) | `hbr_tunnel` (`0`) | `PLAYBACK_TUNNELING: requested=… applied=`, stalls |
| The surround decision uses the stereo / 5.1 / 7.1 probe union and the HDMI channel count | `audio_probe_union` (`0`) | Settings > Advanced > Source & hardware |
| A track-AFR mode switch under tunneling with TrueHD/DTS-HD takes audio and video down and up together. Inert on mdarcy SE 9.2.4 (no decoder tunnels); `1` now forces it without tunneling too | `afr_quiesce_av` (`0`) | First play of a 24p TrueHD title: `Track AFR settle complete: … quiesced=AUDIO_AND_VIDEO`, and no stuck loading screen |
| AFR settle hold of 1.2 s instead of 2 s, on the track-AFR path and (since round 3) the pre-start path | `afr_settle_ms` (`2000`) | Logcat `Switching display mode` → `Display mode switch stabilized at … after N ms` → `Track AFR settle complete:` / `Preflight AFR settle complete: holdMs=` |
| A tunneled start waits for the real first frame (only for decoders seen reporting frames) | `tunnel_real_first_frame` (`0`) | The loading screen ends with the picture |
| Dolby Vision plays untunneled | `dv_no_tunnel` (`0`) | DV signalling on the TV, sync |
| Hardware DV decoders are tried first on DV displays | `dv_decoder_first` (`0`) | HUD "DV decoder" and "Decoder" rows on P8.1 and converted P7 |
| DV7 Auto converts with mode 2 (8.1 with no-op mapping; falls back per RPU to mode 1) | `dv7_manual` (`0` = mode 1) | The TV's DV info on one FEL and one MEL title |
| HDR10 metadata injection on DV strip is on by default (a saved value wins) | Setting "Inject HDR10 metadata on strip" | The TV's HDR info screen: mastering / MaxCLL |
| HDR video ignores the view-scaling aspect modes and plays at Fit (ExoPlayer) | `hdr_zoom` (`1` = allow) | `dumpsys SurfaceFlinger` keeps the video on an overlay plane |
| mpv decodes with direct MediaCodec (`mediacodec,mediacodec-copy`; a saved mode wins) | Setting "Hardware decoding (MPV-only)" | HUD `hwdec` row shows `mediacodec` |
| mpv tone mapping `bt.2446a` with no peak detection | `mpv_tonemap` (`0`) | HDR on an SDR TV through mpv |
| Seek thumbnails start on (`mdarcy` only, when no mode was chosen) | `thumb_default_on` (`0`) | Logcat `ThumbMem … strongBox=`, the HUD dropped-frame row with thumbnails on vs off |
| Low-RAM tier comes from device RAM when buffers are off-heap | `lowram_from_ram` (`0`) | Logcat `BACK_BUFFER_RESOLVED: … lowRam=` |
| libass renders with the canvas overlay (when no type is saved). The settings page still labels Overlay OpenGL "recommended". | `libass_overlay_canvas` (`0`) *app* | `dumpsys SurfaceFlinger` with subtitles and the HUD: no CLIENT composition, no UI stutter |
| Opaque black window background outside playback | `opaque_window` (`0`) *app* | `dumpsys SurfaceFlinger` on Home; one possible frame of stutter when the player opens or closes |
| Video codec queueing is async (from the first pass) | `async_codec` (`0`) | HUD dropped-frame row on 4K60 HEVC and an 80+ Mbit/s DV remux |
| Auto frame-rate matching starts in Start/Stop mode (from the first pass) | Setting "Frame Rate Matching" | Turn off the Shield's own "Match frame rate" while Nuvio AFR is on |
| The switch back after Stop waits 750 ms, until the player surface is gone (round 3) | `afr_restore_delay_ms` (`0` = at once; up to 5000); `afr_restore_skip` (`1` = stay on the content rate) | Logcat `Restoring display mode after 750ms`; no black screen or ANR after Stop |

### Switches that apply on every box

These are not Nvidia defaults. They are test and repair switches that work the same on any box.

| Switch | Default | Values | What it does |
|---|---|---|---|
| `thumb_swap_rule` | on (round 3, the Shield too) | `0` relaxes it | Full swap refuses strong seek thumbnails. With `0`, full swap refuses only below 900 MB MemAvailable. Logcat `ThumbMem` `session strongBox=…: <reason>` |
| `render_trace` | off | `1` | Logs `RENDER_TRACE: pts=… releaseInMs=…` (tag `NuvioRender`) every 240th video frame |
| `sw_av1` | off | only the literal `1` | AV1 takes the software decoder even when a hardware one exists |
| `aml_unstable_pts` | off | only the literal `1` | Asks Amlogic decoders to rebuild decode-order timestamps themselves; ignored on other chips |
| `clear_audio_rejections` | unset | any new token | Clears the learned audio-format rejections once per profile for each new token. Logcat `Learned audio rejections reset` |
| `thumb.backend` | auto (mpv first, then ff60) | `ff60` or `mpv` | Forces the FFmpeg backend of `libnuviothumb`. Read once per process, so set it before the app starts |

Changes for every device in this round:
- Controller buttons in the player get default jobs: L1/R1 seek, X play/pause, Y subtitles. They can be
  rebound on the remote-buttons page.
- Native libraries load straight from the APK (`extractNativeLibs=false`). This saves eMMC space; the APK
  download is larger. Device check: mpv playback and seek thumbnails still work.
- `libnuviothumb` is tuned for Cortex-A57 on arm64.
- Localhost streams report the real HTTP status, so expired links (404/410) and 416 retries are handled (fix on fork
  main).
- When the decoder list cannot be read, a DV5 MKV takes the HEVC remap.
- The HUD queue row says `default (async)` on Fire TV API 28+.
- The episode-options dialog no longer draws the page's pre-blurred glass.
- New and changed strings are translated in every locale.
- Two races found by flaky tests are fixed: a failed meta lookup could stick for the whole session, and a
  profile switch right after start could keep the old profile's watched list.

## Round 3: what the Shield report (2026-10-14) decided

The report (kept locally as `docs/SUMMARY.md`, not in the repo; CI run 38561775458, SHIELD Experience 9.2.4) covered HDR10 + TrueHD, DV P8 +
E-AC-3 and DV7 FEL, plus the Home screen. Dolby Vision Profile 7 findings are handled in a separate session.

Kept as they are: `hbr_iec` off (TrueHD played as direct passthrough, 0 underruns), `hbr_buffer_scale` ×2,
`audio_probe_union`, `thumb_default_on`, `opaque_window` (Home: 867 frames, 0.23 % janky), START_STOP AFR,
`afr_settle_ms` 1200 (an A/B with 600 ms is still to do). `hbr_tunnel`, `tunnel_real_first_frame` and
`dv_no_tunnel` stay, but do nothing on this firmware: no decoder declares tunneled playback.

Changed in round 3:
- **Swap rule back on `mdarcy`.** Swap was full before the test and 14 cached apps died when 4K started, so
  full swap refuses strong thumbnails again, as on every box. `thumb_swap_rule` `0` brings back the relaxed
  rule (refuse only below 900 MB MemAvailable).
- **Stabilize detection off the main thread.** In one start the app logged "stabilized" 1969 ms after the
  request while DisplayManager had the mode at 383 ms; the poll now reads the display on a background thread.
- **The switch-back after Stop waits** (row above). One switch-back during player teardown hung the NVIDIA
  composer HAL until a power cycle. The delay cannot prove the driver fault gone; only many Stops can.
- **Pre-start settle hold** uses `afr_settle_ms` (1200 ms on Nvidia) and logs `Preflight AFR settle complete`.
- **Logs:** NET_CONN reaches logcat only for slow headers or time to first byte (≥ 1 s) and errors (HTTP ≥ 400,
  failed, canceled);
  per-thumbnail ThumbWorker lines only for decodes ≥ 500 ms. `setprop log.tag.NuvioNet D` and
  `setprop log.tag.ThumbWorker D` bring every line back. The NuvioCodecs inventory lists the `.secure`
  decoders too.
- **`render_trace` switch (new, off by default):** `1` logs `RENDER_TRACE: pts= releaseInMs=` (tag
  `NuvioRender`) every 240th video frame.

Device checks for round 3:
- *Implausible timestamps.* SurfaceFlinger logged `Timestamp 1000…000 seems implausible` on every frame in the
  HDR10 and DV P8 runs, not in the DV7 run. Media3 and the app pass a `System.nanoTime` release time (bytecode
  read), so the PTS-based value comes from below Java. The DV7 run switched the display mode before prepare;
  the other two switched under a live codec. Test: `render_trace 1` and check that `releaseInMs` is 0–50 ms
  while the warning shows. Then play the HDR10 title with `afr_quiesce_av 1` (the codec is rebuilt after the
  switch): if the warnings stop, the live switch is the trigger. `dumpsys SurfaceFlinger --latency '<SurfaceView
  layer>'` shows desired against actual present times.
- *Leaked display listeners* (`sending message to a Handler on a dead thread`, one more per playback). The app
  registers none; the only registrant is Media3's `VideoFrameReleaseHelper$DisplayHelper`. Take
  `am dumpheap com.nuvio.tv.test.shield /data/local/tmp/h.hprof` after 3 playbacks and see which player owns
  the `DisplayListenerDelegate` objects. A fix is an AAR patch.
- *Stop switch-back:* about 30 Stops from 24p HDR/DV titles with the 750 ms delay; watch for ANR, Watchdog,
  `NvTsecHdcpReadCaps failed` and `flush_work`.
- *AFR settle:* 01-type starts with `afr_settle_ms 600` against 1200: AudioSink write errors, underruns.

## Still open

| Item | Why it is open |
|---|---|
| Shield thermal threshold in `PlaybackThermalSampler.SKIN_SEVERE_C_BY_MODEL` | Not readable without root: on SE 9.2.4 `dumpsys thermalservice` (Thermal HAL 1.0) lists no thresholds, and `/sys/class/thermal` is denied. It stays unset until NVIDIA documents one; guessing would put invented data in the HUD. |
| Baseline profile in PERFORMANCE visual mode | The generator needs a device on a 60 Hz mode and the AM9 test inputs. After installing a release build, run `adb shell cmd package compile -m speed-profile -f <package>`. |
| Focused-card width animation | It can't be done without changing the look. Later cards slide because the row relays out. Keeping the slide without relayout would need every later item to animate its own offset, which is a row redesign. |
| CEC sleep, controller buttons | Not run in the 2026-10-14 report. CEC sleep is covered in code, but not checked on the device. When the TV turns off, the activity pauses and `pauseForLifecycle()` pauses playback; the pause saves progress. Check it on the device. |
| Settings strings for libass | Overlay OpenGL is still labelled "recommended" although Nvidia now starts on canvas. Change the label after measuring. |

## Not important for this fork

These are skipped on purpose. They do not matter for the owner's setup. Revisit them if the fork is ever
published to a wider audience.

| Item | Notes |
|---|---|
| HDR10+ → Dolby Vision 8.1 | Separate project, see `docs/hdr10plus-to-dv81/`. |
| VC-1 reader for MPEG-TS / M2TS | A Blu-ray `.m2ts` VC-1 remux goes to mpv (software VC-1). A VC-1 PES reader would plug in as an `ExtractorsFactory` wrapper like `AviTrackFormatExtractorsFactory`, replacing the `TsExtractor` that the `DefaultExtractorsFactory` at `PlayerRuntimeControllerInitialization.kt` (~1226) builds with a custom `TsPayloadReader.Factory` for stream type 0xEA. The decoder side (`video/wvc1` → `video/vc1` bridge) already exists. |
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
```
