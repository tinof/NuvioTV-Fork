# Nvidia Shield: defaults, switches and device reference

This is the reference for the Shield pass on branch `perf/shield-pass` (October 2026): what changes on Nvidia,
how to revert it, and how to check it on the device. Open work is in [`ROADMAP.md`](../ROADMAP.md) and its
history in [`ROADMAP_LOG.md`](ROADMAP_LOG.md). The pass is tuned for a Shield TV Pro 2019
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
`adb shell "setprop debug.nuvio.<name> ''"` (the quotes must reach the device: adb drops a bare `""`
argument and `setprop` fails). Either value works on any box (for on/off switches `true`/`false`,
`on`/`off` and `yes`/`no` work too; the number switches take a number and revert only with the value the table
lists). Restart playback, or the app for the rows marked *app*.

| What changed on Nvidia | Switch (revert value) | What to look at |
|---|---|---|
| TrueHD/DTS-HD go to the platform sink, not through the fork's IEC61937 packer. The HAL refuses raw TrueHD, so AudioFlinger's SPDIF encoder wraps it as IEC61937 at 192 kHz. The "System Passthrough" setting now defaults to on (a saved value wins). | `hbr_iec` (`1` = packer) | The AVR's Atmos / DTS:X indicator, A/V sync, the HUD underrun row |
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
| Low-RAM tier comes from device RAM when buffers are off-heap | `lowram_from_ram` (`0`) *app* | Logcat `BACK_BUFFER_RESOLVED: … lowRam=` |
| libass renders with the canvas overlay (when no type is saved). The settings page still labels Overlay OpenGL "recommended". | `libass_overlay_canvas` (`0`) | `dumpsys SurfaceFlinger` with subtitles and the HUD: no CLIENT composition, no UI stutter |
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
| `clear_audio_rejections` | unset | any new token of 1–64 characters except `0` | Clears the learned audio-format rejections once per profile for each new token. Logcat `Learned audio rejections reset` |
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
- New strings are translated in every locale; the changed `audio_tunneled_sub` is still missing in pt, sr and zh.
- Audio for every device (not Nvidia-gated): on leanback boxes below API 33 the HDMI route holds under an idle
  Bluetooth pin, the lossless track default follows the passthrough policy, and the stall ledger is keyed by
  decoder (c36153c3a, d02a4efb5, 2e98bb12c, caaf081ae). Device check: with a paired but idle Bluetooth
  headset, TrueHD still bitstreams (logcat `PASSTHROUGH_RETURN hdmi-under-bluetooth-pin`).
- Two races found by flaky tests are fixed: a failed meta lookup could stick for the whole session, and a
  profile switch right after start could keep the old profile's watched list.

## History

Round 3 (2026-10-14) followed the first device report: the swap rule came back on `mdarcy`, stabilize detection
moved off the main thread, the switch-back after Stop waits 750 ms, the pre-start settle hold uses
`afr_settle_ms`, NET_CONN and ThumbWorker log only slow or failed work, and the `render_trace` switch was added.
A second reading of the report on 2026-10-15 found that several round-3 conclusions do not hold (the
`opaque_window` default, the switch-back delay as a fix, the stabilize root cause, `hbr_iec` "kept" without an
A/B). The corrections and the device numbers are in [`ROADMAP_LOG.md`](ROADMAP_LOG.md); the open device checks
and the former "Still open" items are in [`ROADMAP.md`](../ROADMAP.md).

## Not important for this fork

These are skipped on purpose. They do not matter for the owner's setup. Revisit them if the fork is ever
published to a wider audience.

| Item | Notes |
|---|---|
| HDR10+ → Dolby Vision 8.1 | Separate project, see `docs/hdr10plus-to-dv81/`. |
| VC-1 reader for MPEG-TS / M2TS | A Blu-ray `.m2ts` VC-1 remux goes to mpv (software VC-1). A VC-1 PES reader would plug in as an `ExtractorsFactory` wrapper like `AviTrackFormatExtractorsFactory`, replacing the `TsExtractor` that the `DefaultExtractorsFactory` at `PlayerRuntimeControllerInitialization.kt` (~1226) builds with a custom `TsPayloadReader.Factory` for stream type 0xEA. The decoder side (`video/wvc1` → `video/vc1` bridge) already exists. |
| Ethernet-aware network defaults | Read `NetworkCapabilities.TRANSPORT_ETHERNET` and suggest performance mode with 4 connections in the Device Assessment on a wired, non-small box. `NetworkSettingsScreen` already classifies the link. |
| VOD cache on USB storage | The cache is fixed to `cacheDir` (`PlayerMediaSourceFactory.kt` ~807). It would need a picker over `context.getExternalCacheDirs()`. |

## Media3 backports

The candidate list and its triage are roadmap item E1 in [`ROADMAP.md`](../ROADMAP.md).

## Quick reference

```sh
adb logcat -G 4M                                # bigger log buffer before a session
adb logcat -s 'NuvioCodecs:*'                   # decoder list, once per app start (quote it in zsh)
adb shell setprop debug.nuvio.<name> <0|1>      # switches above
adb shell "setprop debug.nuvio.<name> ''"       # clears one (quoted for the device shell)
adb shell getprop | grep debug.nuvio            # what is set
adb shell dumpsys media.audio_flinger           # audio tracks, underruns
adb shell dumpsys SurfaceFlinger                # composition per layer
adb shell dumpsys display | grep -i mode        # current display mode
```
