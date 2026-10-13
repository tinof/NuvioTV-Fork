# Nvidia Shield: to-do and on-device checks

What the Shield pass (branch `perf/shield-pass`, October 2026) left open, and how to settle each item on a
Shield TV Pro 2019 (`mdarcy`, Android 11 / API 30). Facts about the box come from the audit's fact sheet:
Shield Experience 9.x is Android 11 on every model, the system "Match frame rate" exists only from 9.2 (beta),
AI upscaling covers 60 Hz HDR10 on the Pro from 9.1, there is no AV1 hardware, no HDR10+ and no DV profile 7 FEL.

## Deferred on purpose

| Item | Why it waits |
|---|---|
| HDR10+ → Dolby Vision 8.1 | Separate project, see `docs/hdr10plus-to-dv81/`. |
| VC-1 reader for MPEG-TS / M2TS | Large. Today a Blu-ray `.m2ts` VC-1 remux goes to mpv, which decodes VC-1 in software (FFmpeg has no VC-1 MediaCodec wrapper). A VC-1 PES reader would keep it on the Shield's hardware decoder in ExoPlayer. |
| Ethernet-aware network defaults | Read `NetworkCapabilities.TRANSPORT_ETHERNET`; on a wired, non-small box suggest performance mode with 4 connections. Measure the debrid CDN's per-connection cap with the Device Assessment sweep first. |
| VOD cache on USB storage | Offer `context.getExternalCacheDirs()` volumes; the Shield Pro has 16 GB eMMC and USB 3.0. |
| Seek-thumbnail swap rule | `MemoryGovernor` refuses the strong path when swap is full even with 900 MB available. The test `shieldWithSwapFullIsProtectedThoughItHas3Gb` says this protection is deliberate. Measure first (below). |

## Decide after measuring on the Shield

| Question | How to test | Change if the test says so |
|---|---|---|
| Native TrueHD/DTS-HD passthrough vs the fork's IEC61937 packer | Play a high-bitrate Atmos TrueHD and a DTS:X title with the audio setting System Passthrough off, then on. Compare A/V sync, the AVR's Atmos / DTS:X indicator, the HUD underrun row and logcat `HBR active payload=`. | Default `hbrIecEnabled=false` on Nvidia (`buildAudioSink`). |
| Passthrough buffer size for TrueHD/DTS-HD | System passthrough on, `adb shell setprop debug.nuvio.hbr_buffer_scale 4`, watch underruns and `dumpsys media.audio_flinger`. | Raise the caps in `passthroughBufferCapBytes`. |
| Tunneling for lossless audio | Tunneling on, `setprop debug.nuvio.hbr_tunnel 1`, play TrueHD with system passthrough on; check `PLAYBACK_TUNNELING applied=` and stalls. | Drop the `hbr-format` demotion on Nvidia. |
| Tunneled first frame | Tunneling on, look for `TUNNEL_AV_SYNC: tunnelled frames reported by` in logcat. If present, `setprop debug.nuvio.tunnel_real_first_frame 1` and check the loading screen ends with the picture. | Make it the default for decoders that report frames. |
| Dolby Vision with tunneling | Tunneling on, DV P8.1 + E-AC-3. If DV signalling or sync breaks, `setprop debug.nuvio.dv_no_tunnel 1`. | Make the DV tunnel demotion default on Nvidia. |
| Which decoder plays DV 8.1 and converted P7 | `adb logcat -s NuvioCodecs` for the DV profile list; HUD "DV decoder" and "Decoder" rows on a P8.1 and a P7 title. If the HEVC decoder wins, `setprop debug.nuvio.dv_decoder_first 1` and compare. | Make DV-decoder-first the default on DV displays. |
| DV7 conversion mode 1 vs 2 | the DV7 "Conversion mode" override set to Mode 1, then Mode 2, on one FEL and one MEL title; compare the TV's DV info and the picture. | `usesManualConversion = true` for Nvidia in `DolbyVisionBaseLayerPolicy`. |
| HDR10 metadata on stripped DV (HDR10 TV) | turn on "Inject HDR10 metadata on strip" (Profile 8 only, with DV handling set to Strip DV or HDR10 Base Layer); check the TV's HDR info screen for mastering/MaxCLL values. `ColorInfo.hdrStaticInfo` cannot be set at format time (no RPU yet). | Default the injection on for Nvidia. |
| Async MediaCodec queueing (on by default on Nvidia) | `setprop debug.nuvio.async_codec 0` vs `1` on a 4K60 HEVC and an 80+ Mbit/s DV remux; compare the HUD dropped-frame row. | Remove the switch, or widen beyond Nvidia. |
| AFR settle hold | logcat `Switching display mode` → `stabilized in N ms`; try `setprop debug.nuvio.afr_settle_ms 800`. | Shorter `TRACK_AFR_SETTLE_HOLD_MS` on Nvidia. |
| AFR switch during a tunneled TrueHD session | Tunneling on, Nuvio AFR on, first play of a 24p TrueHD title. | Quiesce video and audio together on Nvidia. |
| Audio probe masks and max PCM channels | Settings > Advanced > Source & hardware shows the stereo / 5.1 / 7.1 direct answers and the HDMI plug channel count. If they differ, `setprop debug.nuvio.audio_probe_union 1`. | Use them in `SurroundFormatResolver` by default. |
| mpv hardware decode mode | mpv engine, 4K HEVC Main10, HUD `hwdec` row. If it is not `mediacodec`, test the Hardware direct mode. | Change the TV default of `mpvHardwareDecodeMode`. |
| mpv HDR on an SDR TV | `setprop debug.nuvio.mpv_tonemap 1` (bt.2446a, no peak detection). | Default it on. |
| Seek thumbnails on the Pro | Thumbnails on, a few 4K remux sessions, logcat `ThumbMem` `session strongBox=... reason`, and the HUD dropped-frame row with thumbnails on vs off. | Default on for `mdarcy`; relax the swap rule if it is the only thing that trips. |
| Low-RAM tier boundary | `adb shell getprop dalvik.vm.heapsize`; logcat `BACK_BUFFER_RESOLVED lowRam=`. | Decide `MemoryBudget.isLowRamTier` from device RAM when buffers are off-heap. |
| libass overlay and HUD composition at 4K | `adb shell dumpsys SurfaceFlinger` during playback with subtitles and the HUD; look for CLIENT composition. | Make `OVERLAY_CANVAS` the Nvidia default. |
| Zoom aspect modes with HDR | SLIGHT_ZOOM / CINEMA_ZOOM on an HDR10 title, check `dumpsys SurfaceFlinger` for an overlay plane. | Disable view-scale aspect modes for HDR on Nvidia. |
| Transparent window background | `dumpsys SurfaceFlinger` on Home: is the app layer CLIENT composed? | Opaque black window background outside playback. |
| Focused-card width animation | `adb shell dumpsys gfxinfo com.nuvio.tv framestats` while moving across expanding cards. | Reserve the slot and animate with `graphicsLayer`. |
| Shield thermal threshold | `adb shell dumpsys thermalservice`. | Add `SHIELD Android TV` to `SKIN_SEVERE_C_BY_MODEL`. |

## Other open items

- Baseline profile: run the generator once with the visual quality mode on PERFORMANCE so static-glass paths are
  profiled too. After installing, `adb shell cmd package compile -m speed-profile -f <package>`.
- `extractNativeLibs=false`: saves eMMC space but changes APK size and packaging; owner decision.
- `-mtune=cortex-a57` for arm64 in the thumbnail `CMakeLists.txt`: tunes only the shim code, low value.
- Shield controller buttons (X/Y, shoulder, triggers) and the remote's custom button: check what reaches the player.
- CEC sleep: when the TV turns off, check that playback pauses and the position is saved.
- Media3 past 1.8.0: check the release notes for decoder and async-queue changes.
- A unit test that keeps Media3's Nvidia `no-post-process` / `auto-frc=0` workaround working through
  `DecodeOrderPtsVideoRenderer.getMediaFormat`.
- Pre-blurred glass inside the raw `Dialog` in `EpisodeOptionsOverlay.kt` still reads the page artwork. The dialog
  is full screen, so the placement is close to right; add `LocalPreblurredGlass provides null` there if it looks off.
- DV5 MKV on a box whose decoder list could not be read counts the DV decoder as present, so the HEVC remap is
  skipped. Only matters on boxes where `MediaCodecList` fails.
- The HUD row "Video codec queue (requested)" says `default (sync)` on Fire TV API 28–30, where Media3 already runs
  async by default.
- Translations: new or changed English strings `assessment_grounds_afr_shield`, `assessment_grounds_res_shield`,
  `assessment_grounds_shield_dolby`, `assessment_item_shield_dolby`, `audio_tunneled_sub`,
  `playback_engine_mvplayer_shield_note`, `playback_engine_mvplayer_shield_note_no_ai`.

## Quick reference

```sh
adb logcat -s NuvioCodecs                       # decoder list, once per app start
adb shell setprop debug.nuvio.<name> <value>    # switches above; 'adb shell setprop debug.nuvio.<name> ""' clears
adb shell getprop | grep debug.nuvio            # what is set
adb shell dumpsys media.audio_flinger           # audio tracks, underruns
adb shell dumpsys SurfaceFlinger                # composition per layer
adb shell dumpsys display | grep -i mode        # current display mode
adb shell wm size; adb shell wm density         # UI window size
```
