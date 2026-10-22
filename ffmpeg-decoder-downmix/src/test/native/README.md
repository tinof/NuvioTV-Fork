# Host test of the transcode path

`host_jni_test.cc` compiles `src/main/jni/ffmpeg_jni.cc` unchanged on macOS and calls its JNI entry
points through a fake `JNIEnv`, in the order `FfmpegAudioDecoder.decode()` calls them. The AAC
decoder, the resampler, the FIFO, dcaenc and the end-of-stream flush are the JNI's own code; the
driver only feeds ADTS AAC packets (headers stripped, the AudioSpecificConfig as extra data, as from
MP4) and records two things from outside:

- the output of every decode call, split into access units;
- every frame the JNI sends to the DTS encoder (the oracle). `avcodec_send_frame` is renamed for the
  JNI translation unit only, so the recording sits between the JNI and libavcodec.

`check.py` makes the inputs with Homebrew FFmpeg, runs the cases and decodes the outputs with
`-xerror -err_detect crccheck+explode`.

## Build and run

Needs a host FFmpeg n7.0.2 tree (the version the app links):

```sh
git clone --depth 1 --branch n7.0.2 https://git.ffmpeg.org/ffmpeg.git ffmpeg-n7.0.2-host
cd ffmpeg-n7.0.2-host
./configure --disable-everything --disable-programs --disable-doc \
  --enable-encoder=dca --enable-decoder=dca,aac --enable-swresample
make libavcodec/libavcodec.a libavutil/libavutil.a libswresample/libswresample.a
```

Then, with Homebrew `ffmpeg-full` installed:

```sh
FFMPEG_HOST_DIR=<that tree> ./run.sh [work dir]   # ends with "ALL HOST CHECKS PASSED"
```

The driver is built with ASan and UBSan. The C sources in `src/main/jni/dcaxll/` are compiled with
the JNI's own flags (`-std=c17 -Wall -Wextra -Wpedantic -Werror`).

## Cases

DTS core target (`TRANSCODE_TARGET_DTS`), 48 kHz stereo and mono, 44.1 kHz stereo:

- every core frame is 2012 bytes (n7.0.2 dcaenc at 1509 kbit/s);
- the encoded-sample count moves by 512 per emitted unit, and only then;
- dcaenc's pre-roll packet is dropped and one zero frame at the end of stream brings out the last
  real frame: units = frames sent - 1;
- alignment: the decoded core peaks at the sample where the PCM sent to the encoder peaks (an
  impulse at 10000), not 512 later, and its SNR is highest without a lag;
- end of stream with the last-sample flag: every input sample is in the output; without the flag the
  loss stays within 1131 samples (FIFO remainder, resampler, held frame);
- reset (a seek): the frame held before the reset is dropped, and after it the pre-roll is dropped
  again, so both halves decode aligned;
- seek discard: the driver plays FfmpegAudioDecoder's part (SimpleDecoder's skip by input time, the
  TranscodeClock stamps, `unitsBefore`) and passes the count to `ffmpegDecode`. With the target on a
  packet boundary the accepted packet's first unit (held from the packet before, 10.67 ms early) is
  dropped; with the target inside a unit the straddling unit goes too. Either way the output starts
  with the first unit at or after the target, the dropped units still count as encoded samples, and
  the cut stream decodes aligned (bit-exact XLL for DTS-HD MA).

DTS-HD MA target (`TRANSCODE_TARGET_DTS_HD`), 48 kHz stereo and mono, 44.1 kHz stereo, and full-scale
square waves whose AAC decode clips:

- the same unit, alignment, end-of-stream and reset checks, on the core inside the HD units
  (decoded with `-core_only 1`);
- every unit is core + ExSS, probes as `DTS-HD MA`, 2 channels, and is at most
  `DCAXLL_MAX_UNIT_BYTES_24(2, 2012)` = 5369 bytes;
- the XLL decodes bit-exactly to the PCM sent to the core encoder, which carries 24-bit values
  (low 8 bits zero); after a reset too;
- no writer errors and nothing clamped by the writer, also on clipping input;
- a mono source gives dual mono: L == R bit for bit; a stereo source keeps L and R apart;
- the decoder reports 48000 Hz and 2 channels.
