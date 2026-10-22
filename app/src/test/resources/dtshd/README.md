# DTS-HD MA test fixture

`transcode_4units.dts` (16804 bytes) is the first four access units the AAC-to-DTS-HD MA transcode
wrote in the host test of `ffmpeg-decoder-downmix` (`src/test/native`, case `hd_48000_2ch`: a 48 kHz
stereo AAC tone, the JNI as of commit fc8097a5e with dtshdma 991777a). Each unit is a 2012-byte DTS core
followed by an ExSS (short header form) with the XLL: 4188, 4216, 4200 and 4200 bytes. FFmpeg decodes
it as DTS-HD MA, 2 channels.
