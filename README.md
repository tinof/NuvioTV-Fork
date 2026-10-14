<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset=".github/branding/nuvio-arc-logo-on-dark.png">
    <img src=".github/branding/nuvio-arc-logo-on-light.png" alt="Nuvio Arc" width="440">
  </picture>
</p>

## What this is

This is a personal branch of Nuvio Arc, tuned for the NVIDIA SHIELD TV Pro 2019.

| | |
|---|---|
| Lineage | [NuvioTV](https://github.com/NuvioMedia/NuvioTV) -> [Nuvio Arc](https://github.com/ysosrs123/NuvioTV-Fork) by ysosrs123 -> this fork, [tinof/NuvioTV-Fork](https://github.com/tinof/NuvioTV-Fork), maintained by tinof |
| Branch | `perf/shield-pass`, a performance pass on top of this fork's `main` (`git log --oneline main..perf/shield-pass`) |
| Target device | SHIELD TV Pro 2019: Tegra X1+, codename `mdarcy`, Android 11 (API 30), SHIELD Experience 9.2.4 |
| Other devices | Keep their behaviour. The risky changes apply only on Nvidia, gated by `DeviceProfile`. Some plain fixes apply everywhere. |

This is a personal build, not an official release. Issues are turned off and there is no support channel.
Please do not report problems from this build to NuvioTV or to Nuvio Arc: they did not make these changes.

## What is different on the Shield

Every change below is a default, not a lock. Each Nvidia default can be reverted, by a `debug.nuvio.*` switch or
by its setting, and a saved user setting wins over the default where one exists. The full table, with the switch names, the
revert values and what to look at on the device, is in [docs/shield-todo.md](docs/shield-todo.md).

| Area | Change on Nvidia |
|---|---|
| Audio passthrough | TrueHD and DTS-HD go to the platform sink natively, not through the fork's IEC61937 packer ("System passthrough" defaults to on). Their passthrough buffer caps are doubled. The surround decision uses the stereo, 5.1 and 7.1 probe union and the HDMI channel count. |
| Frame-rate matching | Starts in Start/Stop mode when nothing is saved. The settle hold after a switch is 1.2 s instead of 2 s, before start and on a track switch. Stabilize detection reads the display off the main thread. After Stop, the switch back waits 750 ms, until the video surface is gone. |
| Video decoding | Video codec queueing is async (Media3 does this by itself only from API 31). |
| HDR and Dolby Vision display | Dolby Vision plays untunnelled. Hardware DV decoders are tried first on DV displays. HDR10 metadata injection on DV strip is on. HDR video plays at Fit instead of a view-scaled aspect mode, so it can stay on an overlay plane. Dolby Vision Profile 7 is handled separately and is still in progress. |
| mpv | Decodes with direct MediaCodec (`mediacodec,mediacodec-copy`) and tone maps with `bt.2446a`, no peak detection. |
| Seek thumbnails and memory | Thumbnails start on for `mdarcy` when no mode was chosen. The low-RAM tier comes from device RAM when the buffers are off-heap. |
| UI | Opaque black window background outside playback. libass starts with the canvas overlay. The device assessment has Shield notes (the system "Match frame rate", resolution matching and AI upscaling, Dolby audio processing). |
| Logs and diagnostics | The `NuvioCodecs` decoder inventory (secure decoders included), an optional render trace, and quieter network and thumbnail logs. |

Changes for every device in this branch include:
- Controller buttons in the player get default jobs: L1/R1 seek, X play/pause, Y subtitles. You can rebind them
  on the remote-buttons page.
- Native libraries load straight from the APK (`extractNativeLibs=false`). This saves eMMC space; the APK
  download is larger.
- `libnuviothumb` is tuned for Cortex-A57 on arm64.
- Pre-blurred Cinematic glass on boxes without live blur (Android 11).
- Localhost streams report the real HTTP status, so expired links (404/410) and 416 retries are handled
  (on fork main).
- New and changed strings are translated in every locale.

What the device report of 2026-10-14 (results in [docs/shield-todo.md](docs/shield-todo.md), Round 3) found the
Shield cannot do:
- No decoder declares tunneled playback on SHIELD Experience 9.2.4. The tunneling defaults stay in the code
  but do nothing on this firmware.
- The display reports only two HDR types: Dolby Vision and HDR10 (no HLG, no HDR10+).
- There is no AV1 decoder.
- Thermal thresholds are not readable without root, so the HUD shows no Shield throttle limit.

## Install a build next to your Nuvio Arc

| | Release Nuvio Arc | This branch's debug build |
|---|---|---|
| Package | `com.nuvio.tv.test` | `com.nuvio.tv.test.shield` |
| Launcher label | Nuvio Arc | Nuvio Arc Debug |
| Settings and logins | its own | its own |

The debug build installs beside the release app and does not touch it.

1. Download the `full-debug-apks` artifact of a successful "PR Full Debug Build" run, from the Actions tab or
   with `gh`. Artifacts are kept for 7 days.

   ```sh
   gh run list -R tinof/NuvioTV-Fork --workflow pr-full-debug-build.yml --branch perf/shield-pass
   gh run download <run-id> -R tinof/NuvioTV-Fork -n full-debug-apks
   ```

2. Install the arm64 APK on the Shield:

   ```sh
   adb install -r app-full-arm64-v8a-debug.apk
   ```

The builds are signed with a fixed debug key, so each new build updates the previous one in place and keeps its
data. The run log prints each APK's package and certificate digest.

## Build it yourself (GitHub Actions)

The maintainer's host cannot build Android, so builds run on GitHub Actions. The workflow is
[.github/workflows/pr-full-debug-build.yml](.github/workflows/pr-full-debug-build.yml) ("PR Full Debug Build").
It builds `fullDebug` as per-ABI split APKs (`armeabi-v7a`, `arm64-v8a`), uploads them as `full-debug-apks`,
and then runs the whole unit test suite. The tests run after the upload, so a failing test does not remove the
APKs.

It runs by hand (workflow_dispatch) and for every pull request into `main`. Both get the `.shield` package
unless a manual run sets another suffix.

Input:

| Input | Default | Meaning |
|---|---|---|
| `app_id_suffix` | `.shield` | Package suffix, so the build installs next to the release app. Empty means the same package. |

Repository secrets (names only; never commit the values):

| Secret | Content |
|---|---|
| `LOCAL_PROPERTIES_BASE64` | `local.properties`, base64-encoded. It holds `NUVIO_SUPABASE_URL` and `NUVIO_SUPABASE_ANON_KEY` plus the TMDB, Trakt, Simkl, MDBList, IntroDB and IMDb keys. The build reads the `NUVIO_`-prefixed Supabase names. [local.example.properties](local.example.properties) lists every key. Without `NUVIO_SUPABASE_URL` a manual run fails on purpose, because account login (QR / short code) would not work. |
| `DEBUG_KEYSTORE_BASE64` | A debug keystore, base64-encoded, with alias `androiddebugkey` and store password `android`. The workflow passes it to Gradle as `NUVIO_DEBUG_STORE_FILE`. Without it, every run gets a throwaway key and a new build cannot update the old one. |

Set the secrets and start a run with `gh`:

```sh
base64 -w0 local.properties | gh secret set LOCAL_PROPERTIES_BASE64 -R tinof/NuvioTV-Fork
base64 -w0 debug.keystore  | gh secret set DEBUG_KEYSTORE_BASE64  -R tinof/NuvioTV-Fork
gh workflow run pr-full-debug-build.yml -R tinof/NuvioTV-Fork --ref perf/shield-pass -f app_id_suffix=.shield
```

The workflow logs list only the names of the properties that have values, and mask the credentials.

Other workflows:

| Workflow | Job |
|---|---|
| [patch-datasource-aar.yml](.github/workflows/patch-datasource-aar.yml) | Rebuilds the patched Media3 datasource AAR. The recipe is in [app/libs/README.md](app/libs/README.md). |
| [android-release.yml](.github/workflows/android-release.yml) | Release builds |
| [build-ffmpeg-decoder.yml](.github/workflows/build-ffmpeg-decoder.yml) | Builds the FFmpeg audio decoder |

Local build: with a filled-in `local.properties`, `./gradlew :app:assembleFullDebug -PnuvioAppIdSuffix=.shield`
builds the same debug APKs. `-PnuvioAppIdSuffix=.name` installs a local build next to the main app.

## Testing on the Shield

Set a switch, clear it and list what is set:

```sh
adb shell setprop debug.nuvio.<name> <0|1>
adb shell setprop debug.nuvio.<name> ""
adb shell getprop | grep debug.nuvio
```

Restart playback after a change, or restart the app for the switches marked *app* in
[docs/shield-todo.md](docs/shield-todo.md).

Log tags and lines worth filtering:

| Tag or line | What it shows |
|---|---|
| `NuvioCodecs` | Every video decoder once per app start: hardware or software, tunneled, secure, DV profiles |
| `FrameRateUtils` | Display mode switches: `Switching display mode`, `Display mode switch stabilized at`, `Restoring display mode after` |
| `PlayerViewModel` | `Track AFR settle complete:`, `Preflight AFR settle complete`, `BACK_BUFFER_RESOLVED: … lowRam=` |
| `TTFF_STAGE` | `PLAYBACK_TUNNELING: requested=… applied=…` and the other start-up stages |
| `ThumbMem` | Seek-thumbnail memory decisions (`strongBox=`, heap) |
| `NuvioNet` | `NET_CONN` lines, only for slow headers or time to first byte (1 s or more) and errors (HTTP 400 or higher, failed, canceled). `adb shell setprop log.tag.NuvioNet D` shows every line. |
| `ThumbWorker` | Per-thumbnail lines, only for decodes of 500 ms or more. `adb shell setprop log.tag.ThumbWorker D` shows every line. |
| `NuvioRender` | `RENDER_TRACE: pts= releaseInMs=` every 240th video frame, with `debug.nuvio.render_trace 1` |

[docs/shield-todo.md](docs/shield-todo.md) has the device checklist for each round, the items that are still
open and a quick reference of `adb` and `dumpsys` commands.

## Features from Nuvio Arc

Everything else comes from Nuvio Arc: seek thumbnails, media-server libraries (Jellyfin, Emby, Silo), watch
party, the calendar, remote buttons, the surround engine with per-format passthrough, Dolby Vision conversion,
source ranking, the device assessment, the speed test and the stats overlay. The
[Nuvio Arc README](https://github.com/ysosrs123/NuvioTV-Fork#readme) describes them, with screenshots. Nuvio
Arc is built on Nuvio; you can support the Nuvio team at [nuvio.tv/support](https://nuvio.tv/support).

---

## Licence

This is a modified version of [NuvioTV](https://github.com/NuvioMedia/NuvioTV), changed by ysosrs123
from July 2026 onwards. Every change is in this repository's history on top of the official release
it builds on (currently 1.1.0-beta.5). tinof changed it further from October 2026 onwards, for the
`perf/shield-pass` branch; those changes are in the same history (`git log main..perf/shield-pass`). It is
licensed under the [GNU General Public License v3.0](./LICENSE), the same as NuvioTV. All upstream copyright
notices, licence headers and author attributions are kept. Full credit to the NuvioTV maintainers and
contributors, and to ysosrs123 for Nuvio Arc.

Parts from other projects keep their own licences:
- TrueHD MAT packing and the downmix path: ported from Kodi (GPL-2.0-or-later), see
  `ffmpeg-decoder-downmix/NOTICE.md`.
- Audio passthrough engine: official pull request 3107 by halibiram, with ram130. Media servers:
  official pull request 3728 by tapframe.
- FFmpeg (LGPL-2.1-or-later): audio decoder built from FFmpeg 7.0.2, recipe in
  `.github/workflows/build-ffmpeg-decoder.yml`; headers only under `app/src/main/cpp/third_party/`.
- dav1d by VideoLAN and the dav1d authors (BSD-2-Clause) for software AV1 decoding, with
  cpu_features by Google (Apache-2.0).
- libdovi by quietvoid (MIT). Matroska extractor files from AndroidX Media3 (Apache-2.0), changed.
- Release group lists from TRaSH Guides (MIT).
- Fonts Atkinson Hyperlegible Next and Source Sans 3 (SIL Open Font License 1.1, texts in
  `app/src/main/assets/licenses/`).
- Watch party avatars: made with DiceBear and Twemoji, under CC BY 4.0, CC0 1.0 and the Bottts terms.
  They are credited in the app under Settings > About > Licenses & attribution and are not covered by the GPL.
- Calendar episode times come from TVmaze's schedule service, credited in the app under
  Settings > About > Licenses & attribution.
- Other service logos and names belong to their owners and only show which service the app connects to.
- Prebuilt libraries that come unchanged from the official app are covered by the official project's
  sources and notices.

This fork is not affiliated with or endorsed by the NuvioTV project, by Nuvio Arc or by any of the services
named.
