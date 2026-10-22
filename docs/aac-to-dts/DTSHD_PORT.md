# Transcode AAC to DTS-HD MA: port design

Roadmap item [Q6](../../ROADMAP.md#q6-transcode-aac-to-dts). Read [DESIGN.md](DESIGN.md) first: this file
builds on its facts and does not repeat them. It is the implementation design for moving the encoder from
`~/Documents/GitHub/dts-hdma` (our own C17 DTS-HD MA writer) into `ffmpeg-decoder-downmix`, so that mono and
stereo AAC reach the HT-A9 as DTS-HD Master Audio.

Code citations are to branch `feat/Q6-aac-dts` at `6d0f0554b`, relative to this repository, unless they
start with `dts-hdma/` (that repository at `567cde03200bef966a4d97f8336c8152a21747bd`). Media3 facts are
from the engine AARs in `app/libs` (`lib-exoplayer-release.aar`, `lib-extractor-release.aar`,
`lib-common-release.aar`), read with `javap`, as DESIGN.md §3 did.

## 0. Decisions this design takes as fixed

- **D1. Target per stream:** DTS-HD MA when the sink plays `audio/vnd.dts.hd` 48 kHz stereo directly
  (`getSinkFormatSupport`, as for DTS today), else DTS core, else PCM. Same toggle; its text names DTS-HD MA.
- **D2. Mono** goes out as dual-mono stereo DTS-HD MA: a 2-channel core and a 2-channel XLL, never a
  1-channel mask.
- **D3. Core aligned to the XLL** by one unit of lookahead, as in the dts-hdma CLI.
- **D4. Library copied verbatim** into the JNI folder with a provenance note. Fixes also go back to
  dts-hdma.
- **D5. Fallback per stream** when an AudioTrack is refused: DTS-HD, then DTS core, then PCM. A refused
  transcode is never learned as a denial of real DTS-HD or DTS.
- **D6. Debug switch** `debug.nuvio.aac_dts_target=hd|core` forces the target for testing.

No hard blocker was found. Findings F1 to F10 below change the work list. F3, F5 and F6 are bugs in
today's branch.

**Status (2026-10-22):** implemented on the branch (steps 1 to 11 and 13, without the CI AAR of step 6),
amended by a Codex review of this design (C1 to C6). What was built differently from the text below,
and why, is in §11. Steps 0 and 12 (hardware) are open.

## 1. Findings that shape the port

- **F1. The own IEC engine packs each `handleBuffer` call as one access unit.**
  `IecPassthroughAudioSink.handleDtsHd` (`app/.../player/iec/IecPassthroughAudioSink.kt:641-653`) wraps
  the whole buffer in one burst. The burst period comes from the first sync word only (`:646-647`). A
  transcode buffer holds 2 units (AAC-LC) or 4 units (HE-AAC) (DESIGN.md §2). Through IEC, that gives one
  burst with 2 to 4 units in it, which is wrong. The second half is also cut off:
  - `packDtsHdInto` truncates silently with `coerceAtMost(burstSize - DATA_OFFSET)`
    (`iec/Iec61937Packer.kt:67-69`).
  - The burst is 8192 B for 2 channels (`dtsHdIecPeriod` `:81-84`, period 2048).
  - Real DTS-HD tracks come one unit per sample, so this never showed up before.
- **F2. The frame counter does not know DTS-HD.** `EncodedFrameCountingAudioSink.countEncodedFrames`
  (`app/.../player/EncodedFrameCountingAudioSink.kt:49-65`) returns null for `audio/vnd.dts.hd`, so the
  renderer's count of 1 goes through. `DefaultAudioSink.getFramesPerEncodedSample` uses
  `DtsUtil.parseDtsAudioSampleCount(ByteBuffer)` for both `ENCODING_DTS` (7) and `ENCODING_DTS_HD` (8)
  (bytecode, lookupswitch cases 7 and 8). For our units it returns 512 from the core NBLKS. With 2 to 4
  units per buffer, the audio clock would run at 1/2 to 1/4 speed: the bug DESIGN.md §3 fixed for the
  core.
- **F3. The DTS core output is one frame (512 samples, 10.67 ms) late today, and its last frame is lost.**
  - dcaenc's decoded output lags its input by exactly 512 samples. `dts-hdma/tests/run.sh` measures this
    on every run (impulse at 10000 decodes at 10512; `dts-hdma/README.md`, "Core / XLL alignment").
  - dcaenc has no `AV_CODEC_CAP_DELAY` (DESIGN.md §2).
  - `receiveEncoderPackets` (`ffmpeg-decoder-downmix/src/main/jni/ffmpeg_jni.cc:556-586`) sends packet 0,
    which holds only the encoder's pre-roll, and stamps every packet with the time of the frame after the
    one it carries.
  - At the end of stream, `avcodec_send_frame(NULL)` (`:688-694`) gets nothing back. So the last unit
    sent to dcaenc is never heard, even with `BUFFER_FLAG_LAST_SAMPLE`.
  - DESIGN.md §4 gives the end-of-stream loss as "511 + 108". That figure leaves out these 512 samples
    (see §2.4).
  - The 512 was measured with FFmpeg 9.0.2. It must be confirmed on n7.0.2 (§7, step 1).
- **F4. A switch between HD and core needs a decoder re-creation in every renderer order.**
  - `recreateDtsTranscodeDecoderIfNeeded` (`app/.../player/PlayerRuntimeControllerDtsTranscode.kt:38-55`)
    runs only when FFmpeg comes first (`:40`).
  - With MediaCodec first, Media3 moves an AAC track between renderers when the sink's DTS answer
    changes. Between HD and core, though, MediaCodec still declines (`prefersDtsTranscode` stays true),
    so the FFmpeg renderer keeps the track and its old decoder.
- **F5. A refused DTS-HD transcode would be learned as a real DTS-HD denial.**
  - `isDtsTranscode` (`app/.../player/AudioRoutingSnapshot.kt:138-139`) matches only
    `sinkMime == audio/vnd.dts`.
  - `noteAudioTrackRejection` (`app/.../player/PlayerRuntimeControllerInitialization.kt:3912-3929`)
    depends on it. Without a fix, an HD refusal falls through to the learning code (`:3930-3958`).
  - This fix must land before, or together with, the commit that lets the app pick HD.
- **F6. The HUD would call our stream "passthrough (IEC)".** In `resolveAudioRoutingSnapshot`
  (`AudioRoutingSnapshot.kt:49-71`), `isIecActive` is checked before `isTranscodingDts`. When the
  transcode goes through the own IEC engine, it is shown as real passthrough.
- **F7. The tunnel guard judges the FFmpeg renderer as PCM.**
  - `demoteAudioTunnelingWhereItCannotBeClocked`
    (`PlayerRuntimeControllerInitialization.kt:4206-4245`) checks the FFmpeg renderer only against the
    PCM dead-clock class (`:4226-4236`).
  - The ledger records the sink's input class (`PlayerRuntimeControllerObservers.kt:1323`,
    `PlaybackSpeedAwareAudioSink.kt:170`), which is `audio/vnd.dts` today and `audio/vnd.dts.hd` after
    the port.
  - The "iec-hbr" and "hbr-format" reasons (`:4238-4242`) never apply to a transcode.
  - This already affects the core path. It matters more for HD, an HBR format.
- **F8. The size bound in the library header is larger than one 2-channel IEC burst.**
  - `DTSHDMA_MAX_UNIT_BYTES(2, 2012)` is 8332 B (`dts-hdma/src/dtshdma_enc.h`). A 2-channel DTS-HD burst
    carries at most 8192 − 8 (preamble) − 12 (start code) = 8172 B (`Iec61937Packer.kt:58-69`).
  - The header bound assumes 32 bits per sample. The writer never needs that many:
    - every residual it codes fits in 24 bits (orders above that are skipped);
    - a signed 24-bit residual zigzags to at most 2^24 − 1, so `mbl` ≤ 24;
    - `choose_code` always has Rice k = `mbl` (quotients 0) available, at 1 + `mbl` bits per sample
      (`dts-hdma/src/dtshdma_enc.c:225-246`);
    - so the largest unit is 25 bits × 512 × 2 / 8 = 3200 B of samples, plus about 90 B of headers, plus
      the 2012 B core: about 5.30 KB.
  - The README's measured worst case is 5284 B. So a unit always fits, but the header bound does not
    show it (§4.4, step 2).
- **F9. On the Shield the default route is the platform sink, not the own IEC engine.**
  - `hbrIecEnabled` is `switch ?: !useSystemPassthrough` (`PlayerRuntimeControllerInitialization.kt:3348-3357`).
  - `defaultUseSystemPassthrough()` is true on Nvidia (`data/local/PlayerSettingsDataStore.kt:660-661`).
  - So by default the DTS-HD output goes to `DefaultAudioSink` as `ENCODING_DTS_HD` and the platform does
    the IEC 61937 packing. `hbrTunnelAllowed` (`:3363-3364`) lets it stay tunnelled on Nvidia.
  - Both routes must work. F1 only breaks the non-default one.
- **F10. dts-hdma has no licence file.** The module is GPL-3.0-only (`ffmpeg-decoder-downmix/NOTICE.md`).
  The owner wrote the library, so this is a one-line owner decision: a licence grant in dts-hdma and in the
  provenance note. The library has no Swift-derived code (`dts-hdma/README.md`, last bullet).

## 2. JNI (`ffmpeg-decoder-downmix/src/main/jni/ffmpeg_jni.cc`)

### 2.1 Where the DTS target lives today

| Piece | Where | What HD changes |
|---|---|---|
| Target constants | `:94-97` (LINT with `FfmpegAudioDecoder.java:49-52`) | add `TRANSCODE_TARGET_DTS_HD = 3` on both sides |
| Bit rate, rate, resampler tuning | `:103-113` | unchanged; HD reuses all of it |
| Context fields | `:142-156` (`transcode_target`, `dts_channels`, `start_trim_samples`, `encoded_samples`, encoder, FIFO, frame, packet) | new HD fields (§2.3) |
| Output channel and format lock | `configureResampler` `:875-880` (DTS: channels of the first frame, packed S32) | HD: the encoder is always stereo; the resampler keeps the source layout (§2.5) |
| Output rate | `:894-903` (DTS: 48000) | unchanged |
| Resampler setup, DTS filter | `:942-963` | unchanged |
| Encoder open | `:1005-1079` (`AV_CODEC_ID_DTS`, `FF_COMPLIANCE_EXPERIMENTAL` `:1026-1030`, FIFO `:1042`, frame `:1051-1069`, packet `:1071`) | HD opens dcaenc as stereo (AMODE 2) whatever the source |
| Info line | `:1080-1085` | HD line (§2.9) |
| Encode loop | `encodeFullFrames` `:588-612`, `receiveEncoderPackets` `:556-586` (`encoded_samples += frame_size` `:583`) | quantize before sending; pair packets with units (§2.6) |
| Transcode decode branch | `decodePacket` `:760-805` (allocates `converted_data` `:763-771`, FIFO write `:794`) | HD expands mono to stereo before the FIFO (§2.5) |
| Mid-stream resampler change | `:930-940` keeps the encoder; `flushResamplerToFifo` `:614-648` | expand mono in the drained samples too |
| End of stream | decoder drain `:725-735`; `flushEncoder` `:650-695` (silence pad `:666-687`, NULL `:688-694`); called at `:851-857` | one extra zero frame (§2.7) |
| Reset (seek) | `ffmpegReset` `:459-463` calls `clearResampler` `:1105-1124` and `clearEncoder` `:1128-1150` | `clearEncoder` also clears the lookahead (§2.8) |
| Release | `releaseContext` `:1263-1277` | frees the writer |
| Channel count to Java | `ffmpegGetChannelCount` `:376-389` (returns the resampler's output layout) | for a transcode, return the encoder's channel count |

### 2.2 Files and build

- **Copy verbatim** `dts-hdma/src/dtshdma_enc.c` and `dtshdma_enc.h` to
  `ffmpeg-decoder-downmix/src/main/jni/dtshdma/`.
  - Add `PROVENANCE.md` with: the repository, commit `567cde03200bef966a4d97f8336c8152a21747bd`, the
    SHA-256 of both files, the copy date, the rule "edit in dts-hdma first, then re-copy", and the licence
    grant (F10).
  - Add a paragraph to `ffmpeg-decoder-downmix/NOTICE.md`.
- **New file `dtshd_stage.c` and `dtshd_stage.h`** (C17, same folder) holds the HD-only logic: quantize,
  lookahead ring and pairing. It has no JNI and no FFmpeg in it, so a host test can drive it (§6.3). The JNI
  calls it.
- **`CMakeLists.txt`.** The project already enables C (`:24`), and C++ stays at 11 (`:21-22`). Changes:
  - add both `.c` files to `add_library(ffmpegJNI ...)` (`:52-54`);
  - `set_target_properties(ffmpegJNI PROPERTIES C_STANDARD 17 C_STANDARD_REQUIRED ON)`. `C_STANDARD` is a
    target property: it applies to the target's C sources only, so `CXX_STANDARD 11` is untouched;
  - `set_source_files_properties(dtshdma/dtshdma_enc.c dtshdma/dtshd_stage.c PROPERTIES COMPILE_OPTIONS
    "-Wall;-Wextra;-Wpedantic;-Werror")`, so the copied code stays warning-clean (DESIGN.md §9 lists the
    unchecked warnings). The copy is verbatim (D4), so a warning can only be fixed upstream. Step 1's host
    build therefore compiles both files with exactly these flags (clang, `-std=c17`) before step 3
    commits them, or the CI AAR could fail on a warning;
  - the `-idirafter` FFmpeg include (`:59-61`) also reaches the C files, which is harmless.
- **Other build files.** The gradle CMake version 3.22.1 (`ffmpeg-decoder-downmix/build.gradle.kts:51-57`)
  supports `C_STANDARD 17`, and NDK 29 clang supports `-std=c17`.
  - `dtshdma_enc.h` already has `extern "C"` guards. `ffmpeg_jni.cc` includes it after the FFmpeg block
    (`:30-46`).
  - **`build_ffmpeg.sh` does not change.** It already has `--enable-encoder=dca` (`:45`), and the writer
    uses only libc. The CI workflow does not change either (`.github/workflows/build-ffmpeg-decoder.yml:98`
    builds the module).

### 2.3 New state (allocated once, at create)

```c
/* in DecoderContext (ffmpeg_jni.cc:122-157) */
dtshd_stage hd;                 /* writer pointer + ring, below */

/* dtshd_stage.h */
typedef struct dtshd_stage {
    dtshdma_enc *writer;                         /* dtshdma_enc_create: the only allocation */
    int32_t pcm[2][2][DTSHDMA_UNIT_SAMPLES];     /* ring of two units: [unit & 1][channel][sample], 24-bit */
    int64_t units_sent;                          /* frames sent to dcaenc since the last reset */
    int64_t packets_seen;                        /* dcaenc packets seen since the last reset */
    long writer_errors;
} dtshd_stage;
```

- **Create.** `createContext` (`:483-543`) calls `dtshd_stage_init` when `transcodeTarget ==
  TRANSCODE_TARGET_DTS_HD`.
  - Init runs `dtshdma_config_default(&cfg, 2)` and `dtshdma_enc_create`. The defaults are the grammar
    the owner's hardware test passed with: official ExSS shape, fixed orders 0 and 1, Rice only, unary
    ≤ 22, patched core header.
  - If init fails, `createContext` returns NULL and the Java side throws "Initialization failed", as for
    any other create failure. The writer struct is about 30 KB.
- **Memory.** The ring adds 8 KB to the `calloc`'d context (`:490-491`). Nothing is allocated per unit.
- **The writer survives resets.** It keeps no state between units: `struct dtshdma_enc` is scratch only
  (`dts-hdma/src/dtshdma_enc.c:38-49`). Only `units_sent` and `packets_seen` are reset (§2.8).
- **Release.** `releaseContext` calls `dtshdma_enc_destroy`.

### 2.4 The PCM the XLL gets is the PCM the core gets

The XLL must code exactly what the core encoder saw, or a receiver that falls back to the core would play
a different signal. For a lossless XLL that difference does not matter, but it would make the stream
impossible to check bit for bit. So the 24-bit value is made once, on the packed S32 frame, after
`av_audio_fifo_read` and before `avcodec_send_frame` in `encodeFullFrames` (`:595-600`):

```c
/* dtshd_stage_take_frame: interleaved stereo S32, 512 frames, modified in place */
for (i = 0; i < 512; i++)
    for (c = 0; c < 2; c++) {
        int64_t v = ((int64_t)s[2 * i + c] + 128) >> 8;   /* round half up to 24 bits */
        if (v > 8388607) v = 8388607;                     /* only s >= 0x7FFFFF80 */
        s[2 * i + c] = (int32_t)((uint32_t)v << 8);       /* core input: same value, low 8 bits 0 */
        st->pcm[st->units_sent & 1][c][i] = (int32_t)v;   /* XLL input */
    }
st->units_sent++;
```

- **No lower clamp is needed:** (−2^31 + 128) >> 8 = −8388608. The arithmetic shift of a negative
  `int64_t` is implementation-defined in C17. clang on ARM and x86 shifts arithmetically, and the host
  test (§6.3) checks it.
- **Why round:** the CLI truncates (`dts-hdma/tools/dtshdma_encode.c:136`), but its input is already 24-bit
  WAV. Here the S32 comes from swresample's float conversion, so rounding avoids a −0.5 LSB bias. The
  difference is below −140 dBFS. What matters is that both encoders get the same value, and they do.
- **No dither:** the 24-bit noise floor is below anything the AAC source carries, and the output must be
  deterministic for the bit-exact tests.
- **Clipping:** swresample already clips float to S32. The writer's own 24-bit clamp then never fires
  (`info->clipped` stays 0), and the host test asserts that.

### 2.5 Dual mono (D2)

- **Where:** in the JNI after resampling, not in swresample's matrix, so the duplication is explicit and
  bit-exact.
- **Resampler:** for HD it keeps the source layout (1 or 2 channels, packed S32, 48 kHz). The
  `dts_channels` lock (`:875-880`) is not used for HD.
- **Encoder:** opened as stereo, and the FIFO is stereo.
- **Expansion:** when the resampler outputs mono, `converted_data` (`:763-771`) is allocated for 2
  channels. `swr_convert` writes the mono samples into the first half, and
  `expandMonoToStereoS32(buf, n)` copies backwards (`dst[2i+1] = dst[2i] = src[i]`, i from n−1 down to 0),
  so it works in place. `flushResamplerToFifo` (`:631-640`) does the same.
- **Source channel changes:** a mono to stereo change (HE-AACv2 PS, DESIGN.md §4) only reconfigures the
  resampler (`:910-922`). The encoder stays stereo, so it never has to reopen.
- **`ffmpegGetChannelCount`** (`:376-389`) returns `encoder_context->ch_layout.nb_channels` when a
  transcode encoder is open, so Java sees 2.
- **Level:** unity on both sides. This is a listening check, not a fact. A 1-channel core (AMODE 0) is a
  centre channel, and a receiver with no centre usually plays it at −3 dB per side. So dual mono at unity
  may sound about 3 dB louder than today's mono core. If the owner wants the old level, the expansion
  scales by 0.7071 before quantizing. It stays bit-identical between L and R.
- **Proposal P1 (owner decision):** use the same dual-mono stereo core for the core-only target too. That
  gives one code path, the encoder is always stereo, the `dts_channels` lock goes away, and the untested
  mono-core case in DESIGN.md §9 disappears. The cost is that a 1509 kbps stereo core spends its bits on two
  identical channels, which is the normal DTS stereo rate. The step list keeps this separable (step 4b).

### 2.6 Lookahead and pairing (D3)

dcaenc emits exactly one packet per frame, synchronously. So packet p, received after frame p was sent,
carries the audio of frame p−1:

```c
/* dtshd_stage_core_packet: called for every packet in receiveEncoderPackets */
long p = st->packets_seen++;
if (p == 0) return 0;                                  /* pre-roll: dropped, not counted */
const int32_t *pcm[2] = { st->pcm[(p - 1) & 1][0], st->pcm[(p - 1) & 1][1] };
return dtshdma_enc_encode_unit(st->writer, pcm, core, core_size, out, cap, NULL);
```

- **Ring size:** two slots are enough. When packet p arrives, frame p is in slot p & 1 and the unit it is
  paired with is in slot (p−1) & 1. No copying is needed.
- **`receiveEncoderPackets` (`:556-586`), HD branch:**
  - Before each call, make sure `outputSize - outSize >= DTSHDMA_MAX_UNIT_BYTES(2, packetSize)`, else call
    `growBuffer(outSize + bound)`, the same pattern as `:570-579`.
  - Write the unit straight into `*outputBuffer`; there is no scratch copy.
  - Add 512 to `encoded_samples` only when a unit was written (a return > 0), not per packet as at `:583`.
- **Output buffer size:**
  - One AAC-LC input packet makes at most 3 units, and an HE-AAC packet at most 5 (2048 samples at
    44.1 kHz is about 2229 at 48 kHz, plus FIFO carry). The end-of-stream flush adds 2.
  - The current bound is 8332 B per unit (F8: in practice ≤ 5.30 KB), so 7 × 8332 = 58 KB.
  - `INITIAL_OUTPUT_BUFFER_SIZE` is 131070 (`FfmpegAudioDecoder.java:44`), so the grow path should never
    run. It stays as the safety net.
- **Sanity checks per packet:** `packetSize == dtshdma_core_frame_size(...)` (2012 at 1509 kbps). The
  writer also checks sync, 48 kHz, NBLKS, AMODE 2 and no LFE (`DTSHDMA_ERR_CORE_FMT`).
- **Writer error policy:**
  - On a negative return, log the first error with its code and unit index, write the core packet alone
    for that unit, and count it.
  - A core-only unit inside a DTS-HD stream still decodes: the receiver plays the core for 10.67 ms.
  - This should never happen: the core comes from our own encoder and the buffer is sized to the bound.
    A hard decoder error would stop playback, and D5 cannot catch it (it is not an AudioTrack refusal).
    The host test asserts `writer_errors == 0`.
- **Core target, same alignment (fixes F3):** apply the drop of packet 0 and the end-of-stream zero frame
  to `TRANSCODE_TARGET_DTS` too (`packets_seen` is shared). The core then needs no PCM hold. HD and its
  core fallback (D5) then have the same timing.

### 2.7 End of stream

`flushEncoder` (`:650-695`) runs when Java passes `endOfStream` (the `BUFFER_FLAG_LAST_SAMPLE` packet,
`FfmpegAudioDecoder.java:167-168`):

1. `flushResamplerToFifo` (unchanged, plus the mono expansion).
2. `encodeFullFrames` (quantizes and pairs as above).
3. The existing silence pad of the last partial frame (`:666-687`). Its frame also goes through
   `dtshd_stage_take_frame`.
4. **New:** if `units_sent > 0`, fill `encoder_frame` with silence (`av_samples_set_silence`), send it
   without keeping a PCM unit for it (do not call `take_frame`; zeros need no quantizing), and receive its
   packet. That packet carries the last real unit, and `core_packet` pairs it with that unit. For the core
   target, the same zero frame flushes the last real frame.
5. `avcodec_send_frame(NULL)` as now (`:688-694`): harmless, since there is no delay capability.

After the zero frame, the encoder's history holds silence. That does not matter: the stream has ended, and
a seek resets everything.

### 2.8 Flush, seek and resampler changes

- **Seek:** `ffmpegReset` → `clearEncoder` (`:1128-1150`) frees dcaenc. The next `configureResampler`
  opens a new one (`:1005`), whose first packet is pre-roll again. So `clearEncoder` calls
  `dtshd_stage_reset` (`units_sent = packets_seen = 0`). The held unit is dropped with the rest of the
  FIFO, as it must be after a seek.
- **Mid-stream resampler change** (`:930-940`): the encoder and the lookahead stay. Correct: the timeline
  continues.
- **TrueHD reset** (`:430-457`): this branch re-creates the whole context. It never runs for AAC.

### 2.9 Info line

One per encoder open, next to `:1080-1085`:

`DTS-HD MA transcode: <in Hz> Hz <ch> ch -> 48000 Hz 2 ch (<stereo|dual mono>), core 1509000 bit/s, XLL 24-bit, unit 512 samples, lookahead 1 unit`

The core target keeps its line. Add `aligned` to it once the F3 fix is in, so a logcat shows which build
is running.

## 3. Timing

### 3.1 TranscodeClock does not change

- `TranscodeClock.getOutputTimeUs` (`ffmpeg-decoder-downmix/.../TranscodeClock.java:44-62`) stamps a
  buffer as anchor + samples encoded before it (`FfmpegAudioDecoder.java:160`, `:180-184`).
- With the lookahead, the nth emitted unit carries PCM unit n. `encoded_samples` counts emitted units
  only, so unit n is stamped anchor + 512 n, which is exactly where its PCM belongs.
- Today's core path stamps the pre-roll packet at the anchor and every later packet one frame early. That
  is F3.

Worked example, 48 kHz AAC-LC, no trim:

| decode call | frames sent (total) | packets | units emitted | buffer stamp |
|---|---|---|---|---|
| 1 | 2 | 0 (dropped), 1 | unit 0 | anchor + 0 |
| 2 | 4 | 2, 3 | units 1, 2 | anchor + 512 |
| 3 | 6 | 4, 5 | units 3, 4 | anchor + 1536 |

### 3.2 Priming trim

- `trimFrameStart` (`ffmpeg_jni.cc:1154-1174`, called at `:742-748`) drops decoded samples before the
  resampler and the FIFO, so it never touches the lookahead.
- A 2112-sample trim can leave the first decode calls with no output. The clock already handles that: it
  does not re-anchor before the first output after the anchor (`TranscodeClock.java:55-59`).
- The lookahead adds one more call with no output at most. Nothing else changes, including the stream-start
  test `startsStream` (`:68-73`).

### 3.3 Re-anchoring

The clock re-anchors when an input is more than 200 ms from it (`TranscodeClock.java:23`, `:57-61`). By
design the input runs ahead of the clock by:
- the FIFO (≤ 511 samples);
- the resampler delay (≤ about 108, DESIGN.md §4);
- now also the held unit (512);
- in all, ≤ 1131 samples, 23.6 ms.

That is far under 200 ms, so the lookahead causes no false re-anchor. On a real discontinuity without a
reset, up to 1131 samples from before it are stamped on the new timeline. Today this is up to 619 samples
plus dcaenc's hidden frame, so the same 1131 in practice.

### 3.4 End-of-stream loss, re-derived

Let r be the FIFO remainder at the end (0 to 511) and R the samples still inside the resampler
(about 82 at 44.1 kHz in DESIGN.md §5, up to about 108).

| case | today (core, unaligned) | after the port (HD and aligned core) |
|---|---|---|
| `BUFFER_FLAG_LAST_SAMPLE` present (flush runs) | the last frame sent to dcaenc is never emitted: r samples lost when r > 0, else 512 (≤ 10.7 ms); the rest plays 10.67 ms late | 0 samples lost; (512 − r) mod 512 samples of silence added at the end |
| flag missing (no flush) | r + R + 512 (dcaenc's frame) ≤ 1131 samples, 23.6 ms | r + R + 512 (the held unit) ≤ 1131 samples, 23.6 ms |

So DESIGN.md §4's "511 + 108 (12.9 ms)" understates today's loss by 512 samples. The port makes the
flagged case lossless and leaves the unflagged case where it already was. Update DESIGN.md §4 with this
table once the device build confirms it.

### 3.5 Latency and A/V sync

- **Latency:** the lookahead adds 10.67 ms of decode latency (one unit is held), not an A/V offset, since
  the timestamps are aligned.
- **Sync improves:** the core path loses its hidden 10.67 ms late offset (F3).
- **Check on the device:** lip-sync with a clap clip (Q6.4) on both routes (§4).

## 4. Renderer and decoder Java (module)

### 4.1 `FfmpegAudioDecoder.java`

- **Constants:** `TRANSCODE_TARGET_DTS_HD = 3` (`:49-52`, LINT block).
- **`transcoding`** (`:95`) includes `C.ENCODING_DTS_HD`. `transcodeTarget()` (`:254-263`) maps
  `ENCODING_DTS_HD` to 3.
- **Nothing else changes.** Trim, clock, end of stream and the output-format latch (`:198-210`) apply as
  they are.

### 4.2 `FfmpegAudioRenderer.java`

- **Output format** in `getOutputFormat` (`:231-257`): for `ENCODING_DTS_HD`, MIME
  `MimeTypes.AUDIO_DTS_HD` (`audio/vnd.dts.hd`), 2 channels, `decoder.getSampleRate()` (48000). Leave
  `codecs` unset:
  - `MimeTypes.getEncoding("audio/vnd.dts.hd", codecs)` returns 8 (`ENCODING_DTS_HD`) and does not read
    `codecs` for this MIME (bytecode, tableswitch index 7 returns 8 directly).
  - `DecoderAudioRenderer` adds the encoder delay and padding to this format. The passthrough sink
    ignores them, which is why the decoder trims (DESIGN.md §3).
- **Target choice:** `shouldTranscodeToDts` (`:377-381`) becomes `dtsTranscodeTarget(Format)`, returning
  `DTS_TRANSCODE_TARGET_NONE / CORE / HD` (new public int constants 0/1/2, separate from the JNI numbers):

  ```java
  if (!transcodeAacToDts || !isDtsTranscodeSource(format)) return NONE;
  boolean hdDirect = getSinkFormatSupport(dtsHdOutputFormat()) == SINK_FORMAT_SUPPORTED_DIRECTLY;
  boolean coreDirect = getSinkFormatSupport(dtsOutputFormat()) == SINK_FORMAT_SUPPORTED_DIRECTLY;
  boolean hdAllowed = transcodeAacToDtsHd && targetOverride != CORE;
  if (hdAllowed && (hdDirect || (targetOverride == HD && coreDirect))) return HD;
  return coreDirect ? CORE : NONE;
  ```

  The forced `hd` only skips the DTS-HD capability check. It still needs the sink to take DTS at all, so
  Bluetooth, a speed other than 1x, the PCM latch and a policy that denies DTS still give PCM. It also
  honours the per-stream HD-off latch (D5), so a refused HD cannot loop.
- **`supportsFormatInternal`** (`:149-161`): `transcodeToDts = target != NONE`. **`createDecoder`**
  (`:189-200`): HD gives `outputEncoding = C.ENCODING_DTS_HD`, `nativeOutputChannelCount = 0`.
- **Public API (new):**
  - `dtsHdOutputFormat()`, next to `dtsOutputFormat()` (`:355-361`);
  - `setTranscodeAacToDtsHd(boolean allowed)` and `setDtsTranscodeTargetOverride(int)` (−1 = auto).
    **`transcodeAacToDtsHd` defaults to false**, so HD is opt-in. An AAR with the HD target that reaches
    `app/libs` before the app's guards (step 7) then behaves exactly like today's core transcode. Only
    step 8 makes the app call `setTranscodeAacToDtsHd(true)`;
  - `getDtsTranscodeTarget()` (reads `activeDecoder` once, as `:326-343` do);
  - `isTranscodingToDtsHd()`.
- **Public API (kept):**
  - `isTranscodingToDts()` keeps its meaning, "any DTS target", which the stats and the re-creation use;
  - `getDtsTranscodeChannelCount()` (`:338-343`) returns 2 for HD;
  - `setTranscodeAacToDts`'s javadoc (`:301-307`) is updated.
- **Why the app passes the switch in:** the module cannot read `DebugSwitches`, so the app passes the
  forced target in through `applyDownmixSettings` (§5.1).
- **The AAR changes:** the public API grows, so `app/libs/README.md` records the new AAR (DESIGN.md §8).

### 4.3 Live changes (re-creation, F4)

- **`dtsTranscodeNeedsDecoderRecreate`** (`PlayerRuntimeControllerDtsTranscode.kt:14-18`) compares
  targets:

  ```kotlin
  internal fun dtsTranscodeNeedsDecoderRecreate(
      ffmpegActive: Boolean, current: Target, wanted: Target, ffmpegFirst: Boolean
  ): Boolean = ffmpegActive && current != wanted &&
      (ffmpegFirst || (current != Target.NONE && wanted != Target.NONE))
  ```
- **The gate at `:40`** keeps `transcodeAacToDtsActive` and moves `ffmpegAudioRendererFirst` into the
  predicate. The playback-thread message (`:48-54`) reads `renderer.getDtsTranscodeTarget()` and
  `sink.dtsTranscodeTarget(source)`.
- **`cycleAudioTrack`** (`:57-74`) is unchanged.
- **What this covers:**
  - HD to core: DTS-HD denied by policy, or the own IEC failing mid-session;
  - core to HD;
  - HD or core to NONE: Bluetooth, speed;
  - NONE to HD or core, when FFmpeg comes first.

## 5. Sink path

### 5.1 What the sink chain sees

The FFmpeg renderer's sink chain:
1. `EncodedFrameCountingAudioSink` (`PlayerRuntimeControllerInitialization.kt:3632-3640`);
2. `PlaybackSpeedAwareAudioSink` (`:3586-3599`);
3. `IecPassthroughAudioSink` (`:3570-3585`);
4. `DefaultAudioSink`.

`configure` gets the DTS-HD format from §4.2.

### 5.2 DefaultAudioSink with `ENCODING_DTS_HD` (the default route on the Shield, F9)

- **Frames per unit:** `getFramesPerEncodedSample(8, buffer)` calls `DtsUtil.parseDtsAudioSampleCount`
  at the buffer position. That position is our core sync, so it returns 512 (NBLKS 15). The value is
  latched once per configuration.
- **Written frames** are 512 × `encodedAccessUnitCount`. So the counter must pass the number of units
  (§5.3).
- **Several units in one `write`** are fine on this route. Compressed passthrough is a byte stream, and
  Media3 itself can write partial buffers.
- **Buffer:**
  - `cappedPassthroughBufferSizeProvider` (`PlayerRuntimeControllerInitialization.kt:4392-4417`) caps
    `ENCODING_DTS_HD` at 4 × 30720 × `hbrScale`. That is 245760 B on Nvidia
    (`core/player/PassthroughBufferCaps.kt:13-16`, `:34`), against 32192 B for core DTS (`:35`).
  - The stream is VBR (about 1.56 Mbit/s for silence, about 3.4 Mbit/s for AAC music, at most about
    3.98 Mbit/s by F8). So the cap holds 0.49 to 1.26 s.
  - The cap does not set the latency. `PassthroughWaterLevelPacer` admits buffers by PTS, at most
    `MAX_WATER_LEVEL_US` = 200 ms ahead of the wall clock (`PassthroughWaterLevelPacer.kt:74-94`,
    `:122-128`, `:141`), since `audio/vnd.dts.hd` is a passthrough MIME (`:146-158`).
  - No change is needed. Check the start and the seek latency on the device.
- **Tunnelling:**
  - `hbrTunnelAllowed` keeps a DTS-HD track tunnelled on Nvidia when the sink plays it directly
    (`:3360-3364`). F7 explains why that check never sees the transcode today (§5.6).

### 5.3 `EncodedFrameCountingAudioSink`: count units

- **What changes** (`EncodedFrameCountingAudioSink.kt:48-78`): add a branch for `MimeTypes.AUDIO_DTS_HD`.
  The unit size is:
  - `c` = the core size: `DtsUtil.getDtsFrameSize` on the 16 bytes at the unit start, when
    `DtsUtil.getFrameType(sync) == FRAME_TYPE_CORE`;
  - `e` = `ALIGN4(c)`;
  - unless the 4 bytes at `e` are `64 58 20 25` (`FRAME_TYPE_EXTENSION_SUBSTREAM`), the unit is a core
    only, of size `e`;
  - otherwise read 12 bytes at `e`, as a big-endian bit field from the sync:
    - `bHeaderSizeType` is bit 42;
    - if 0: `nuExtSSFsize − 1` is bits 51..66 (16 bits);
    - if 1: it is bits 55..74 (20 bits);
  - the unit size is `e + nuExtSSFsize`.
- **Where the field offsets come from:**
  - the field table in `dts-hdma/docs/WRITER_SPEC.md` §3.1: SYNC 32, UserDefinedBits 8, nExtSSIndex 2,
    bHeaderSizeType 1, header size 8 or 12, then nuExtSSFsize − 1 in 16 or 20 bits;
  - the size relations in §3.3;
  - **checked on `dts-hdma/out/smoke/st.dts`:** four units, core 2012, ExSS header 36, nuExtSSFsize 2204
    to 2216, units chained exactly.
- **Stopping rule:** the walker stops, as now, at a size ≤ 0 or one larger than what is left
  (`:73`). A truncated ExSS ends the count, and the caller's 1 is used.
- **Keep the AC-3 and DTS branches as they are.** Update the class comment.
- **Shared helper:** put the walker in an `internal object DtsHdAccessUnits` (in `ui/screens/player/iec/`).
  It returns the unit sizes from absolute index 0, and §5.4 uses it too.

Not chosen: splitting each buffer into one-unit `handleBuffer` calls in this wrapper. `DefaultAudioSink`
asserts that a buffer it did not finish comes back as the same object (`checkArgument(inputBuffer == null
|| buffer == inputBuffer)`, bytecode `handleBuffer` offsets 1-20). So the wrapper would have to keep slice
objects across calls, flushes and resets. Counting is stateless, and the IEC sink can split by itself
(§5.4).

### 5.4 The own IEC engine (`hbrIecEnabled` on, or "System passthrough" off)

- **Does our output go through it?**
  - `IecPassthroughAudioSink.configure` takes the IEC path for `audio/vnd.dts.hd`
    (`isHbrPassthrough`, `:1192-1194`; `PassthroughWaterLevelPacer.isHbrMime`, `:160-166`) when
    `hbrIecEnabled`, the probe is ready and no tunnelling was asked for (`:169-189`).
  - On the Shield with default settings, no (F9). With "System passthrough" off or
    `debug.nuvio.hbr_iec=1`, yes.
- **Channel count, period and size:**
  - `hbrIecChannelCount` gives 2 (`:546-550`, `dtsHdChannelMask(2) = 2`). So the track opens at 192 kHz
    with 2 channels, period 2048, burst 8192 B, subtype 2 (`Iec61937Packer.kt:81-100`, `openIecAt`
    `:521-544`).
  - A real DTS-HD MA 2.0 track takes the same path.
  - A unit fits: at most about 5.30 KB, against the 8172 B payload (F8).
- **Split by unit (fixes F1).** `handleDtsHd` (`:641-653`) loops over `DtsHdAccessUnits` sizes:
  - each unit is a `duplicate()` view with its own position and limit;
  - each gets its own `resolveDtsSampleCount` (512) and its own burst;
  - a buffer with one unit (real DTS-HD) behaves exactly as before.
- **Replay bookkeeping:**
  - `rememberSource` and `noteFrameQueued` (`:1056-1069`) assume one burst per source. With N bursts,
    queue the first N−1 with `sourcesOnFrame.add(0)` and call `noteFrameQueued()` on the last.
  - Then a source leaves `uncommitted` only when its last burst is written (`noteFrameWritten`
    `:1071-1078`).
  - A fallback replay may repeat at most 3 already-played units of one source. That is better than
    losing them.
- **Overflow guard:** in `packDtsHdInto` (`:51-79`), when `accessUnitSize + 12 > burstSize - 8`,
  report it through a counter the health line prints. Do not truncate silently. F8 says this cannot happen
  with the default grammar, so a non-zero count means a library change broke the bound.
- **Tunnelling:** `claimsHbr` (`:134-136`) makes the selector drop tunnelling ("iec-hbr", `:4238`) for
  real DTS-HD tracks. For the transcode, see §5.6.

### 5.5 `PlaybackSpeedAwareAudioSink`, pacer and buffer caps

- **Gates:** `getFormatSupport` (`:280-293`) and `shouldRejectDirectPlayback` (`:306-319`) already gate
  `audio/vnd.dts.hd` by Bluetooth, speed, the session PCM latch and the policy's `Group.DTS_HD`
  (`core/player/AudioPassthroughPolicy.kt:15`, `:26`, `:42`).
- **Pacer:** `PassthroughWaterLevelPacer` applies as above. With the own IEC engine, its 1 s ceiling
  applies instead (`IEC_WRITE_AHEAD_US`, `:143`).
- **Buffer caps:** `PassthroughBufferCaps` needs no change. The HBR scale (`hbrScale`, `:23-24`) already
  covers `ENCODING_DTS_HD`.

### 5.6 Tunnel guard (fixes F7)

- **What changes:** in `demoteAudioTunnelingWhereItCannotBeClocked` (`:4206-4245`), for the FFmpeg renderer
  (`:4226`), take `out = audioSink.dtsTranscodeOutputFormat(format)`. It is null when the AAC track gets no
  transcode. When it is not null, apply the reasons for real bitstream tracks to `out`:
  - `demandsNonTunnelledVideo(out)` → "iec-hbr";
  - `hbrDemandsNonTunnelledVideo(out) && !(hbrTunnelAllowed && getFormatSupport(out) == DIRECTLY)`
    → "hbr-format";
  - the dead-clock ledger with `tunnelAudioClass(out)` instead of the PCM class.
- **Threads:** the selector runs on the playback thread (comment at `:1049-1050`), where the sink may be
  asked.
- **`PlayerTunnelAvSyncPolicy` does not change.** Its ledger keys (`PlayerTunnelAvSyncPolicy.kt:51-66`)
  then match what `PlayerRuntimeControllerObservers.kt:1323` records.

## 6. Routing, policy, UI

### 6.1 Target choice in the app

- **`core/player/AacToDtsRouting.kt`:**
  - add `DTS_HD_OUTPUT_FORMAT` (`audio/vnd.dts.hd`, 2 ch, 48 kHz) next to `:20-24`;
  - add `enum class Target { NONE, CORE, HD }`;
  - add `parseTargetOverride(String?)` (`hd` → HD, `core` → CORE, else null);
  - add a pure `chooseTarget(eligible, hdAllowed, forced, hdDirect: () -> Boolean, coreDirect: () -> Boolean)`.
    It is the same logic as §4.2, kept in step by a comment on both sides.
- **`PlaybackSpeedAwareAudioSink`:**
  - new constructor parameters `transcodeAacToDtsHd: Boolean = true` and
    `dtsTargetOverride: AacToDtsRouting.Target? = null` (next to `:37-38`);
  - `dtsTranscodeTarget(format)`, which calls `chooseTarget` with `getFormatSupport` for both formats;
  - `prefersDtsTranscode` (`:330-334`) becomes `dtsTranscodeTarget(format) != Target.NONE`, so
    `PlaybackSpeedAwareAudioRenderer.getDecoderInfos` (`:121-126`) is unchanged;
  - `dtsTranscodeOutputFormat(format)` for §5.6.
- **D6:**
  - read `DebugSwitches.property("debug.nuvio.aac_dts_target")` once per build, next to
    `isTranscodeAacToDtsActive` (`PlayerRuntimeControllerInitialization.kt:1330-1332`);
  - pass it to the sink (`:3595`) and to the renderer through `applyDownmixSettings` (`:3712-3722`, both
    callers `:1509-1516`, `:3699-3707`, and `PlayerRuntimeControllerSurroundResolve.kt:222-230`);
  - log it in the build line.

### 6.2 Fallback chain (D5) and learned denials

- **Storage** (`PlayerRuntimeController.kt:898-900`): keep `aacToDtsOffStreamUrls` (fully off: PCM) and
  add `aacToDtsHdOffStreamUrls` (HD off: core only). Two sets keep every existing reader unchanged.
- **`isDtsTranscode`** (`AudioRoutingSnapshot.kt:138-139`) becomes `sourceMime != null && (sinkMime ==
  AUDIO_DTS || sinkMime == AUDIO_DTS_HD) && !sourceMime.startsWith(MimeTypes.AUDIO_DTS)`.
  `startsWith("audio/vnd.dts")` still excludes real DTS, DTS-HD, DTS Express and DTS:X sources.
- **`noteAudioTrackRejection`** (`PlayerRuntimeControllerInitialization.kt:3912-3929`), in the transcode
  branch:
  - if `mime == AUDIO_DTS_HD`, add the URL to `aacToDtsHdOffStreamUrls` and log
    `AUDIO_TRACK_REJECTION_NOT_LEARNED DTS-HD transcode ... ; DTS core next`;
  - else add it to `aacToDtsOffStreamUrls`, as now;
  - return before learning, as now.
  - The same-configuration retry (`:2338-2340`) still runs first, so a transient refusal does not step
    down.
- **The rebuild after the refusal reads both sets:**
  - `isTranscodeAacToDtsActive` (`:1330-1332`) is unchanged;
  - new `aacToDtsHdAllowed = url !in aacToDtsHdOffStreamUrls && override != CORE` goes to the sink
    constructor (`:3595`) and to `applyDownmixSettings` (`:1515`, `:3706`);
  - the in-place resolve (`PlayerRuntimeControllerSurroundResolve.kt:228-229`) reads the same set.
- **Fingerprint:** `ExoConstructionFingerprint` (`:182-212`, filled at `:1613`) gets
  `aacToDtsHdAllowed: Boolean = true` and `aacToDtsTargetOverride: String? = null`, so a player is not
  reused across a step down or a changed debug switch.
- **Own IEC failure:** this is a separate, existing chain (`onIecFailed` → `iecOffStreamUrls`, `:1483-1486`,
  `:3570-3585`). The rebuild then sends DTS-HD to the platform sink. Only an AudioTrack refusal steps D5
  down.
- **`noteAudioOutputOpened(DTS_HD)`** (`:2697-2698`): it still runs when a transcode track opens. That is
  real evidence that the route accepts a DTS-HD AudioTrack, so keep it. Note: it also stops a later real
  DTS-HD refusal in the same playback from being learned (`:3944-3946`). That is the existing rule for any
  opened group.
- **Policy groups:** `AudioPassthroughPolicy` does not change. `Group.DTS_HD` denied (by the user or
  learned) makes `getFormatSupport(DTS_HD_OUTPUT_FORMAT)` unsupported, so D1 picks the core.

### 6.3 Labels, stats, report, settings

- **Audio path row** (`PlayerRuntimeControllerInitialization.kt:2670-2682`): add a branch before the
  others, `encoding == C.ENCODING_DTS_HD && isDtsTranscode(sourceMime, AUDIO_DTS_HD)` →
  `"DTS-HD MA transcode"`. It reads, for example, "AAC → DTS-HD MA transcode (48 kHz, 2ch)".
- **Stats** (`AudioRoutingSnapshot.kt:49-71`):
  - move the `isTranscodingDts` branch above `isIecActive` (F6);
  - pick the label from `sinkMime`: `"transcode DTS-HD MA"` or `"transcode DTS"`;
  - with the own IEC active, append `" (IEC)"`;
  - keep `AudioRoutingMode.TRANSCODE_DTS`, so `PlayerViewModel.kt:824` (GOOD) does not change.
- **Issue report raw line** (`PlayerRuntimeControllerPlaybackEvents.kt:490-496`): after `aacToDts=...`,
  add `aacToDtsOut=<hd|core|none>` (from `ffmpegAudioRenderer?.getDtsTranscodeTarget()`) and
  `aacToDtsHdOff=<bool>`. The structured report stays unchanged (DESIGN.md §9).
- **Settings:**
  - the toggle exists in `app/src/main/res/values/strings.xml` only (`:918-919`), so there is one
    locale. The title stays.
  - Proposed subtitle: "Sends mono and stereo AAC to your receiver as DTS-HD Master Audio when the output
    accepts it, otherwise as DTS (1.5 Mbps), both at 48 kHz, so a DTS upmixer such as Neural:X can
    process it. Mono plays the same signal on left and right. Needs an output that accepts DTS. Bluetooth
    and speeds other than 1x stay PCM."
  - With P1 rejected, the mono sentence says "In DTS-HD MA, mono plays ...".
  - `PlaybackAudioSettings.kt:147-150` and the sync key (`core/sync/ProfileSettingsSyncService.kt:99`) do
    not change.

## 7. Tests

### 7.1 Existing unit tests that change

- **`EncodedFrameCountingAudioSinkTest`** (`app/src/test/.../player/EncodedFrameCountingAudioSinkTest.kt`):
  - keep all 6;
  - extend `otherFormats_areNotCounted` (`:56`), which must no longer treat DTS-HD as other;
  - add the DTS-HD cases in §7.2.
- **`AudioRoutingSnapshotTest`**:
  - `dtsTranscode_isADtsSinkForANonDtsSource` (`:65-69`) gains the DTS-HD rows;
  - `aacToDts_resolvesTranscodeDtsWithoutFallback` (`:48-60`) gains the HD label;
  - new: transcode plus IEC active shows the transcode (F6).
- **`DtsTranscodeDecoderRecreateTest`** (3 tests, `:10-20`): rewrite for the target signature, with the
  cases below.
- **`AacToDtsRoutingTest`**: add the target-choice cases below.
- **`Iec61937PackerTest`**: add a 2-channel period-2048 case with a 5.30 KB unit (fits) and an overflow
  case (counted, not silent).
- **`IecPassthroughAudioSinkTest`**: add a buffer of 2 and of 4 core + ExSS units. It must give 2 and 4
  bursts of 8192 B, subtype 2, with the replay bookkeeping committing the source on its last burst.

### 7.2 New unit tests

- **Frame counting:**
  - 2 and 4 units of core 2012 + ExSS 36 + XLL;
  - short and long ExSS size forms;
  - a core-only unit followed by a full unit;
  - a truncated ExSS (counts the whole units only);
  - a partly consumed buffer (same count);
  - fixture bytes: the first units of `dts-hdma/out/smoke/st.dts`, copied as a test resource with their
    provenance.
- **Routing target:**
  - HD when DTS-HD is direct;
  - CORE when only DTS is;
  - NONE;
  - `hdAllowed = false` gives CORE;
  - forced `core` gives CORE;
  - forced `hd` with DTS direct and DTS-HD not direct gives HD;
  - forced `hd` with neither direct (Bluetooth, speed) gives NONE;
  - forced `hd` with the HD-off latch gives CORE;
  - not eligible gives NONE (mono, stereo, xHE, encrypted as today).
- **Fallback chain:** a pure helper `aacToDtsStepDown(sourceMime, sinkMime): StepDown?` (HD_OFF, ALL_OFF,
  null):
  - DTS-HD refused → HD_OFF;
  - DTS refused → ALL_OFF;
  - a real DTS-HD source refused → null (learned as before);
  - PCM → null.
- **Re-creation:**
  - HD to CORE with MediaCodec first → recreate;
  - CORE to NONE with MediaCodec first → no recreate (MediaCodec takes the track);
  - NONE to HD with FFmpeg first → recreate;
  - same target → none;
  - FFmpeg not active → none.
- **Tunnel guard:** the FFmpeg renderer with an HD transcode, IEC claiming → "iec-hbr". Not Nvidia and
  `hbrTunnelAllowed` false → "hbr-format". A ledger entry for `audio/vnd.dts.hd` → "dead-tunnel-clock".
- **`TranscodeClock`:** the module has no `src/test` yet. Add JUnit to `ffmpeg-decoder-downmix` and the
  worked example in §3.1 as a test. This is optional. The clock code does not change.

### 7.3 Host-side native test (feasible)

- **Location:** `ffmpeg-decoder-downmix/src/test/native/` (Makefile, not part of the Gradle build), next to
  the `docs/aac-to-dts/swrtune` method.
- **Build:**
  - a host n7.0.2 libavcodec, libswresample and libavutil, configured as in DESIGN.md §8 with
    `--enable-encoder=dca --enable-decoder=dca,aac --enable-swresample`;
  - link `dtshd_stage.c`, `dtshdma_enc.c`, and a driver that copies the JNI's order of calls: decode AAC,
    resample, FIFO, `take_frame`, send to dcaenc, `core_packet`, end-of-stream zero frame.
- **Checks:**
  1. **n7.0.2 facts:** FSIZE is 2012 at 1509 kbps, and the dcaenc delay is 512 (impulse at 10000:
     raw-core peak at 10512). This closes the "re-check on the device build" item in
     `dts-hdma/README.md`.
  2. **Alignment:** decoded core and decoded XLL peak at 10000.
  3. **Bit-exact:**
     - the n7.0.2 FFmpeg dca decoder with `-err_detect crccheck+explode -xerror` gives exactly the
       quantized PCM;
     - the log has no "Invalid XLL sync word" (`dts-hdma/docs/WRITER_SPEC.md` §7);
     - `writer_errors == 0` and `clipped == 0`.
  4. **Dual mono:** mono AAC in gives L == R bit for bit, and the profile is DTS-HD MA stereo.
  5. **End of stream:** decoded length = input + (512 − r) mod 512, with no loss. With the flush skipped,
     the loss is ≤ 1131.
  6. **Reset:** reset in the middle, then continue. Packet 0 is dropped again, and the output after the
     reset is aligned.
  7. **Size:** the largest unit over the dts-hdma adversarial signals is ≤ the F8 bound (about 5300 B;
     the README measured 5284 B).
- **Shared decoders:** `dts-hdma/tests/run.sh` decoders (dcadec, hdmaxdec) can be pointed at the output
  as second and third oracles.
- **Sanitizers:** run under ASan and UBSan.

## 8. Steps (commit-sized)

0. **Hardware gate through Nuvio** (owner, no code). The 2026-10-22 pass played the `v2` files through
   another player (memory note). Before step 5:
   - play the stereo `v2` `.mov` (`dtsl`) through Nuvio on both routes: "System passthrough" on (the
     platform sink) and off (the own IEC, 2-channel period);
   - confirm "DTS-HD MSTR" and left and right.
   - If the own IEC route shows plain DTS or nothing, try the 8-channel period for 2-channel DTS-HD
     (`dtsHdIecPeriod`, `Iec61937Packer.kt:81-84`) before building on it.
1. **Host n7.0.2 facts** (`docs/aac-to-dts/`, no app code): the native harness skeleton and check 1 of
   §7.3. Stop if FSIZE or the delay differ from 2012 and 512.
2. **dts-hdma upstream** (that repository):
   - a licence file (F10);
   - a `DTSHDMA_MAX_UNIT_BYTES_24`-style tight bound derived as in F8, with a unit test over the
     adversarial set;
   - tag the commit. Then copy it (D4).
3. **Vendor the library** into the JNI folder: `PROVENANCE.md`, NOTICE, CMake `C_STANDARD 17` and
   warnings. It builds and nothing calls it yet.
4. **JNI core alignment** (F3):
   - `packets_seen`, the drop of packet 0, the end-of-stream zero frame, `encoded_samples` per emitted
     frame, the `aligned` log tag;
   - host checks 2, 5 and 6 for the core;
   - its own commit, since it changes shipped behaviour.
   - **4b (if P1 is accepted):** dual-mono stereo core.
5. **JNI HD target:**
   - `TRANSCODE_TARGET_DTS_HD`, `dtshd_stage`, the quantize step, mono expansion, the ring, the writer
     call, output sizing, the end of stream, reset, `ffmpegGetChannelCount`, the info line;
   - the full host test.
6. **Module Java:** `ENCODING_DTS_HD`, the output format, `dtsTranscodeTarget`, the new API and setters,
   with `transcodeAacToDtsHd` false by default (§4.2), so this AAR never emits DTS-HD on its own. Then:
   - the CI AAR via `ci/Q6-ffmpeg-aar` (DESIGN.md §8);
   - `app/libs/README.md` SHA-256 and run ID.
7. **App guards first:**
   - `isDtsTranscode` for DTS-HD, the D5 step-down sets, the fingerprint fields (F5);
   - the frame counter for DTS-HD with `DtsHdAccessUnits` (F2);
   - IEC splitting and the overflow counter (F1, F8);
   - tests.
   - Up to here nothing calls `setTranscodeAacToDtsHd(true)` and the module default is false, so neither
     the renderer nor the app picks HD, whatever order steps 6 and 7 land in.
8. **App target choice:** `AacToDtsRouting.Target`, the sink's `dtsTranscodeTarget`, the renderer setters
   (the first `setTranscodeAacToDtsHd(true)`),
   the D6 switch, the in-place resolve. Tests. HD is now live.
9. **Re-creation** for every target change (F4). Tests.
10. **Tunnel guard** for transcode output classes (F7). Tests.
11. **Labels:** HUD, stats order and label (F6), report raw line, settings subtitle.
12. **Device run** (Q6.x, owner present, M2 snapshot first):
    - stereo 48 kHz, 44.1 kHz, mono, HE-AACv2;
    - seek, pause, speed change, track switch, Bluetooth;
    - end of stream;
    - a lip-sync clip on both routes;
    - CPU on the Shield (dcaenc, resampler and writer together);
    - forced `core` and `hd`;
    - `faultInjectRejectMime=audio/vnd.dts.hd` to walk D5 to the core, then `audio/vnd.dts` to PCM.
13. **Docs:** DESIGN.md (§4 timing and end-of-stream table, §7 results), `ffmpeg-decoder-downmix/README.md`,
    the ROADMAP Q6 status.

## 9. Risks

| # | Risk | Mitigation |
|---|---|---|
| R1 | The HT-A9 accepts our 2-channel MA from the test player but not from one of Nuvio's routes (the platform HAL's DTS-HD packing, or the own IEC 2-channel period) | step 0 before any JNI work; the 8-channel IEC period as a lever; D5 covers refusals, not silent core fallback |
| R2 | Silent failure (the AVR shows DTS or plays nothing) is not an AudioTrack refusal, so D5 never fires | the D6 `core` switch; the HUD shows the target; the issue report line carries `aacToDtsOut` |
| R3 | n7.0.2 dcaenc differs from 9.0.2 (FSIZE, delay, header) | step 1 host check; the writer refuses a wrong core (`DTSHDMA_ERR_CORE_FMT`) and the JNI logs it |
| R4 | F1 not fixed: units cut off and bursts mistimed on the own IEC route | step 7 before step 8; packer overflow counter |
| R5 | F5 not fixed first: one refused HD transcode teaches the route that real DTS-HD is denied | step order (7 before 8); test |
| R6 | CPU on the Cortex-A57: dcaenc (unmeasured), the 144-tap resampler (4.1× default), the writer (about 15-19 µs per unit on M-series, so maybe 10× that) | Q6.5 measurement; the writer is under 2 % even at 10× |
| R7 | Dual mono about 3 dB louder than the old mono core | owner listening check; one constant (0.7071) if needed |
| R8 | A theoretical unit above 8172 B | F8 derivation, upstream tight bound and test, overflow counter |
| R9 | Core DSYNC cadence (ASPF 0) differs from official encoders | the hardware pass already includes it; only matters if the HT-A9 loses sync on fallback |
| R10 | One extra unit of lookahead: no buffer for the first decode call, and an end-of-stream loss of up to 23.6 ms if the last-sample flag is missing | §3.4: the same as today's real loss; flagged case now lossless |
| R11 | Tunnelled DTS-HD on non-Nvidia boxes with the FFmpeg renderer | F7 fix demotes it like real DTS-HD |
| R12 | Licence of the copied code | F10, step 2 |
| R13 | The engine AAR gains public API, and an app built against an old AAR crashes on missing methods (the first finding of the 2026-10-21 review) | step 6 ships the AAR before steps 7-11 use it; HD is off by default in the module, so the new AAR alone changes nothing; the gate build runs against `app/libs` |

## 10. Owner questions

1. **P1:** dual-mono stereo for the core-only target too (§2.5)?
2. **Mono level:** unity on both sides, or −3 dB (§2.5, R7)?
3. **Licence** for dts-hdma and its copy in the module (F10)?
4. **Order of the core alignment fix (F3):** keep it as its own commit before HD (step 4), or fold it
   into step 5?

## 11. Implementation record (2026-10-22)

The port was built in the commit order of §8, after a Codex (gpt-6.1-sol) critique of this design.
Each Codex point was checked against the code before it was designed for:

- **C1 (confirmed in the 1.8.0 bytecode).** `AudioCapabilities.getEncodingAndChannelConfigForPassthrough`
  replaces `ENCODING_DTS_HD` (8) with `ENCODING_DTS` (7) when the output lacks DTS-HD, and
  `DefaultAudioSink.getFormatSupport` still answers "directly". `hdDirect` as written in §4.2 would pick
  HD on a DTS-only output and play the core.
- **C2 (confirmed).** The error handler ran `tryAudioTrackPcmFallback` (PCM forcing) for a refused
  transcode, so HD could fall straight to PCM.
- **C3 (confirmed).** `noteAudioTrackRejection` changed the latch even when a same-configuration retry
  was scheduled, so the "same" retry ran with another target. This was already true for the core.
- **C4 (confirmed).** A splitter that needs a core would turn a coreless DTS-HD or DTS:X buffer into
  nothing.
- **C5 (confirmed).** `TranscodeClock` compared the untrimmed input time with the trimmed output
  timeline: 2112 samples of priming at 8 kHz are 264 ms, so the fourth packet re-anchored.
- **C6 (confirmed in the 1.8.0 bytecode).** `SimpleDecoder.decode()` sets `shouldBeSkipped` from the
  input time before calling the subclass; the decoder rewrites the output time afterwards.

### Deviations from §0 to §10

| # | Design | Built | Why |
|---|---|---|---|
| 1 | Step 2: licence file in dts-hdma (F10) | No licence change. dts-hdma commit `991777a` adds only the tight bound `DTSHDMA_MAX_UNIT_BYTES_24` (5369 B for stereo, measured worst 5284) with unit tests; the copy is from `991777a`, not `567cde0`. | Owner decision: no licence change. The copied library has no licence grant yet (risk). |
| 2 | Step 6: CI AAR in `app/libs` before the app steps | Not built; `app/libs` keeps AAR `a1152a6d…`. The app reaches `setTranscodeAacToDtsHd`, `setDtsTranscodeTargetOverride` and `getDtsTranscodeTarget` by reflection (`FfmpegRendererDtsHd`). Without them (`rendererWritesHd = false`) the app never allows HD. | The session may not push, so CI cannot run. Reflection lets the app build and run with both the old AAR and the module (R13), and the gate tests run against `app/libs`. Replace with direct calls once the CI AAR lands. With the old AAR the core transcode is still the unaligned one. |
| 3 | §3.1: TranscodeClock does not change | `setPrimingTrim` (C5): the discontinuity check takes the scheduled priming trim off the input time until a reset or a real re-anchor. `peekOutputTimeUs` for C6. Module JUnit tests added (§7.2 called them optional). | C5, C6. |
| 4 | §4.2/§6.1: `hdDirect = getSinkFormatSupport(DTS-HD) == DIRECTLY` | The DTS-HD query uses a marked format (`Format.id = nuvio:aac-dts-hd-transcode`, `dtsHdOutputFormat()` / `AacToDtsRouting.DTS_HD_OUTPUT_FORMAT`). `PlaybackSpeedAwareAudioSink.getFormatSupport` answers it as supported only when the own IEC engine claims it (`claimsHbr`) or the pinned capabilities list `ENCODING_DTS_HD`. Real DTS-HD tracks are not marked and keep Media3's core fallback. Without pinned capabilities (phones, the live receiver) the transcode stays on the core. | C1. One check in the sink keeps the renderer and the sink in step. |
| 5 | §6.2: `noteAudioTrackRejection` adds the URL to the step-down sets | It only logs (`DTS core next`, `PCM next`, or `same-configuration retry first`). `tryAacToDtsStepDown` sets the latch and rebuilds at the position with `scheduleOwnedPlaybackRecovery`, after the IEC-off rung and before `tryDeniedAudioFfmpegFallback` / `tryAudioTrackPcmFallback`, without `markAudioPcmFallbackTried`. A step already taken falls through to the generic recovery. `aacToDtsStepDown(source, sink, retryScheduled)` returns null while a retry is scheduled. | C2, C3. Also changes the existing core path: a transient core refusal is retried as core before PCM. |
| 6 | §5.4: split every DTS-HD buffer by `DtsHdAccessUnits` | Split only when the walker finds 2 or more units, each led by a core, at least one with an ExSS, covering the whole buffer. Anything else keeps the one-burst path. `packDtsHdInto` returns whether the unit fitted; overflows are counted (`iec_dtshd_overflow`, `dtshdOverflows=` in the health line). | C4. |
| 7 | (not in the design) | Seek discard: `ffmpegDecode` takes `skipUnits`. For the DTS targets the decoder counts the units whose TranscodeClock stamp is before the output start (`unitsBefore`, one sample of slack, at most 64) and the JNI drops them, still counting them as encoded samples. AC-3 is unchanged. | C6. |
| 8 | §2.3: `units_sent` and `packets_seen` in the stage, shared with the core | The JNI keeps `dts_packets_seen` and `dts_frames_sent` for both DTS targets; the stage keeps `units_taken`, `writer_errors`, `first_error`, `clipped` and `max_unit`. The EOS zero frame is sent when `dts_frames_sent > 0`. | The core target needs the counters without the stage. |
| 9 | §2.6: grow by `DTSHDMA_MAX_UNIT_BYTES(2, packetSize)` | As designed (8332 B per unit); the tight bound is checked by the host test, the IEC overflow counter and dts-hdma's unit tests. | The loose bound is the library's documented contract for `out_cap`. |
| 10 | §6.1: sink parameter `transcodeAacToDtsHd = true` by default | Default `false`; the build passes `aacToDtsHdAllowed` explicitly, which also needs `rendererWritesHd`. | A sink built without the flag must not ask for HD that the renderer may not write (the re-creation check would loop). |
| 11 | §7.3: driver that copies the JNI's order of calls, linked with the stage and the library | The driver compiles `ffmpeg_jni.cc` itself (#include, unchanged) and calls its JNI entry points through a fake JNIEnv; `avcodec_send_frame` is renamed for that translation unit to record the oracle. AAC comes from ADTS with the AudioSpecificConfig as extra data. Oracles: Homebrew FFmpeg 9.0.2 with `-xerror -err_detect crccheck+explode` (XLL and `-core_only 1`). dcadec and hdmaxdec were not used. | Tests the real JNI code, including the AAC decode, trim, resampler and EOS paths. |
| 12 | §7.2 fixture: first units of `dts-hdma/out/smoke/st.dts` | `app/src/test/resources/dtshd/transcode_4units.dts`, the first four units of the host test's `hd_48000_2ch` output. | `out/` is not versioned in dts-hdma; the host output is reproducible from this repository. |
| 13 | Check 1 of §7.3 as a separate step | Implied by the host checks: every core is 2012 B, and the old JNI fails the alignment check with a peak at 10512 (lag 512) on n7.0.2. | Measured before this session too (byte-identical to 9.0.2). |
| 14 | §2.5 P1, §10 questions 1, 2, 4 | P1 rejected (the core target keeps its mono core, D2); dual mono at unity; the core alignment is its own commit before HD (D3). | Owner decisions D2, D3. |
| 15 | Step 13: ROADMAP status | Not updated in this session. | Left for the owner's session that runs the device steps. |

### Commits

dts-hdma: `991777a` Tight unit size bound from the 24-bit residual cap.

This repository, in order: `799767060` core alignment (with the host test), `9e5530e46` vendored library,
`3743edcc9` JNI DTS-HD target, `bac008ced` priming trim in TranscodeClock, `fc8097a5e` seek discard,
`1487642a9` renderer and decoder API, `c31c02092` app guards (isDtsTranscode, step-down, counting, IEC
split), `920506454` app target choice (C1, D6, reflection), `f7733390f` re-creation, `32717cb2e` tunnel
guard, `2ae980d80` labels and setting text, then the docs commit.

### Still open

- Steps 0 and 12 on the Shield and the HT-A9 (both routes, mono, 44.1 kHz, HE-AACv2, seek, lip sync,
  CPU, the D5 walk with `nuvio_fault_reject_mime`).
- The CI AAR (deviation 2) and its `app/libs/README.md` entry.
- The licence grant for dts-hdma (deviation 1).

### Risks found while building it

- **Live capabilities:** without pinned capabilities (phones, tablets) DTS-HD MA is never chosen
  (deviation 4).
- **HDMI coming back with DTS-HD:** `PassthroughOutputReturn.shouldRebuild` uses `canPass`, which
  counts DTS-HD as passable when only DTS is pinned. A player built while the output listed only DTS
  stays on the core until the next playback, even when DTS-HD is listed again.
- **Seek discard and a re-anchor in the same call:** the unit count is computed on the timeline before
  the decode; if that decode also re-anchors the clock (a jump over 200 ms), the buffer is stamped on
  the new anchor but cut by the old count. Rare, one buffer.
- **HE-AAC priming:** `setPrimingTrim` divides by `Format.sampleRate`. Where that is the core rate of an
  SBR stream, the trim is overestimated up to 2x, which only widens the discontinuity slack.
- **Forced HD (`debug.nuvio.aac_dts_target=hd`)** on a DTS-only platform output writes DTS-HD MA into an
  AudioTrack Media3 opens as DTS. That is the switch's purpose (testing), not a user path.

