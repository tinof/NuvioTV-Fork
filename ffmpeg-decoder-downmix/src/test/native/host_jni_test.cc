/*
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Host test driver for the transcode path of ffmpeg_jni.cc.
 *
 * The JNI source is compiled unchanged (it is #included below) against a host FFmpeg n7.0.2
 * build, and its JNI entry points are called through a fake JNIEnv in the order
 * FfmpegAudioDecoder.decode() calls them: initialize, set start trim, get encoded samples,
 * decode (with the end-of-stream flag on the last packet), reset. Nothing of the transcode is
 * re-implemented here. Two things are observed from outside:
 *   - every frame the JNI sends to the DTS encoder is copied to the oracle file (avcodec_send_frame
 *     is renamed for the JNI translation unit only), so a decoded XLL can be compared bit for bit
 *     with the PCM the core encoder saw;
 *   - every decode call's output is written to the output file and split into access units.
 *
 * Input: an ADTS AAC file. The driver strips the ADTS headers and passes the AudioSpecificConfig
 * as extra data, as the MP4 path does. check.py runs the cases and decodes the outputs.
 */
#include <jni.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <string>
#include <vector>

#define avcodec_send_frame hosttest_avcodec_send_frame
#include "../../main/jni/ffmpeg_jni.cc"
#undef avcodec_send_frame

extern "C" int avcodec_send_frame(AVCodecContext* avctx, const AVFrame* frame);

namespace {

FILE* g_oracle = nullptr;
long g_encoder_frames = 0;

}  // namespace

extern "C" int hosttest_avcodec_send_frame(AVCodecContext* avctx, const AVFrame* frame) {
  if (frame && avctx->codec_id == AV_CODEC_ID_DTS) {
    g_encoder_frames++;
    if (g_oracle) {
      fwrite(frame->data[0], 4 * (size_t)avctx->ch_layout.nb_channels, (size_t)frame->nb_samples,
             g_oracle);
    }
  }
  return avcodec_send_frame(avctx, frame);
}

namespace {

// ---------------------------------------------------------------------------------------------
// Fake JNI: direct ByteBuffers, byte arrays and strings are plain structs behind jobject.

struct FakeDirectBuffer {
  uint8_t* data;
  jint capacity;
};

struct FakeByteArray {
  const uint8_t* data;
  jsize length;
};

FakeDirectBuffer g_input = {nullptr, 0};
FakeDirectBuffer g_output = {nullptr, 0};  // SimpleDecoderOutputBuffer.data
int g_decoderOutputBuffer = 0;             // stands for the SimpleDecoderOutputBuffer itself
int g_output_buffer_size = 65535 * 2;      // FfmpegAudioDecoder.INITIAL_OUTPUT_BUFFER_SIZE
long g_grow_calls = 0;

void ensureOutputCapacity(int size) {
  if (g_output.capacity >= size) return;
  g_output.data = static_cast<uint8_t*>(realloc(g_output.data, (size_t)size));
  if (!g_output.data) {
    fprintf(stderr, "out of memory\n");
    exit(2);
  }
  g_output.capacity = size;
}

void* JNICALL fakeGetDirectBufferAddress(JNIEnv*, jobject buffer) {
  return reinterpret_cast<FakeDirectBuffer*>(buffer)->data;
}

// growOutputBuffer(SimpleDecoderOutputBuffer, int): SimpleDecoderOutputBuffer.grow keeps the
// bytes written so far, and the decoder remembers the size for the next buffers.
jobject JNICALL fakeCallObjectMethodV(JNIEnv*, jobject, jmethodID, va_list args) {
  (void)va_arg(args, jobject);
  jint required = va_arg(args, jint);
  g_grow_calls++;
  g_output_buffer_size = required;
  ensureOutputCapacity(required);
  return reinterpret_cast<jobject>(&g_output);
}

jboolean JNICALL fakeExceptionCheck(JNIEnv*) { return JNI_FALSE; }

void JNICALL fakeExceptionDescribe(JNIEnv*) {}

jstring JNICALL fakeNewStringUTF(JNIEnv*, const char* utf) {
  return reinterpret_cast<jstring>(strdup(utf));
}

const char* JNICALL fakeGetStringUTFChars(JNIEnv*, jstring string, jboolean* isCopy) {
  if (isCopy) *isCopy = JNI_FALSE;
  return reinterpret_cast<const char*>(string);
}

void JNICALL fakeReleaseStringUTFChars(JNIEnv*, jstring, const char*) {}

jsize JNICALL fakeGetArrayLength(JNIEnv*, jarray array) {
  return reinterpret_cast<FakeByteArray*>(array)->length;
}

void JNICALL fakeGetByteArrayRegion(JNIEnv*, jbyteArray array, jsize start, jsize len,
                                    jbyte* buf) {
  memcpy(buf, reinterpret_cast<FakeByteArray*>(array)->data + start, (size_t)len);
}

void JNICALL fakeDeleteLocalRef(JNIEnv*, jobject) {}

JNINativeInterface_ g_functions;
JNIEnv g_env;

JNIEnv* fakeEnv() {
  memset(&g_functions, 0, sizeof(g_functions));
  g_functions.GetDirectBufferAddress = fakeGetDirectBufferAddress;
  g_functions.CallObjectMethodV = fakeCallObjectMethodV;
  g_functions.ExceptionCheck = fakeExceptionCheck;
  g_functions.ExceptionDescribe = fakeExceptionDescribe;
  g_functions.NewStringUTF = fakeNewStringUTF;
  g_functions.GetStringUTFChars = fakeGetStringUTFChars;
  g_functions.ReleaseStringUTFChars = fakeReleaseStringUTFChars;
  g_functions.GetArrayLength = fakeGetArrayLength;
  g_functions.GetByteArrayRegion = fakeGetByteArrayRegion;
  g_functions.DeleteLocalRef = fakeDeleteLocalRef;
  g_env.functions = &g_functions;
  return &g_env;
}

// ---------------------------------------------------------------------------------------------
// ADTS input

struct AdtsStream {
  std::vector<std::vector<uint8_t>> packets;
  uint8_t asc[2];
  int sampleRate;
  int channels;
};

const int kAdtsRates[] = {96000, 88200, 64000, 48000, 44100, 32000, 24000,
                          22050, 16000, 12000, 11025, 8000,  7350};

bool readAdts(const char* path, AdtsStream* out) {
  FILE* f = fopen(path, "rb");
  if (!f) return false;
  std::vector<uint8_t> all;
  uint8_t chunk[65536];
  size_t n;
  while ((n = fread(chunk, 1, sizeof(chunk), f)) > 0) all.insert(all.end(), chunk, chunk + n);
  fclose(f);
  size_t pos = 0;
  while (pos + 7 <= all.size()) {
    const uint8_t* h = &all[pos];
    if (h[0] != 0xFF || (h[1] & 0xF0) != 0xF0) return false;
    bool protectionAbsent = h[1] & 1;
    int profile = h[2] >> 6;
    int rateIndex = (h[2] >> 2) & 0xF;
    int channelConfig = ((h[2] & 1) << 2) | (h[3] >> 6);
    size_t frameLength = ((size_t)(h[3] & 3) << 11) | ((size_t)h[4] << 3) | (h[5] >> 5);
    size_t header = protectionAbsent ? 7 : 9;
    if (frameLength < header || pos + frameLength > all.size() || rateIndex > 12) return false;
    if (out->packets.empty()) {
      int objectType = profile + 1;
      out->asc[0] = (uint8_t)((objectType << 3) | (rateIndex >> 1));
      out->asc[1] = (uint8_t)(((rateIndex & 1) << 7) | (channelConfig << 3));
      out->sampleRate = kAdtsRates[rateIndex];
      out->channels = channelConfig;
    }
    out->packets.emplace_back(all.begin() + (long)(pos + header), all.begin() + (long)(pos + frameLength));
    pos += frameLength;
  }
  return !out->packets.empty();
}

// ---------------------------------------------------------------------------------------------
// Output units: [core, padded to 4][ExSS] or a plain core.

uint32_t bits(const uint8_t* p, size_t pos, unsigned n) {
  uint32_t v = 0;
  for (unsigned i = 0; i < n; i++) {
    size_t bit = pos + i;
    v = (v << 1) | ((p[bit >> 3] >> (7 - (bit & 7))) & 1u);
  }
  return v;
}

struct UnitStats {
  long units = 0;
  long hdUnits = 0;
  long coreOnlyUnits = 0;
  long maxUnit = 0;
  long badCoreSize = 0;
  long unparsed = 0;
};

void splitUnits(const uint8_t* p, long size, UnitStats* stats) {
  long off = 0;
  while (off < size) {
    if (size - off < 16 || bits(p + off, 0, 32) != 0x7FFE8001u) {
      stats->unparsed += size - off;
      return;
    }
    long core = (long)bits(p + off, 46, 14) + 1;
    if (core != 2012) stats->badCoreSize++;
    long unit = (core + 3) & ~3L;
    if (off + unit + 12 <= size && bits(p + off + unit, 0, 32) == 0x64582025u) {
      const uint8_t* x = p + off + unit;
      bool longHeader = bits(x, 42, 1);
      long exss = (long)bits(x, longHeader ? 55 : 51, longHeader ? 20 : 16) + 1;
      unit += exss;
      stats->hdUnits++;
    } else {
      stats->coreOnlyUnits++;
    }
    if (off + unit > size) {
      stats->unparsed += size - off;
      return;
    }
    if (unit > stats->maxUnit) stats->maxUnit = unit;
    stats->units++;
    off += unit;
  }
}

// ---------------------------------------------------------------------------------------------

struct Options {
  const char* input = nullptr;
  int target = TRANSCODE_TARGET_DTS;
  int trim = 0;
  bool eosFlag = true;
  long resetAt = -1;      // packet index before which ffmpegReset runs (a seek)
  long resumeFrom = -1;   // packet index the seek resumes at
  long stopAt = -1;       // packets fed (end of input when < 0)
  const char* out = nullptr;
  const char* oracle = nullptr;
  const char* outAfterReset = nullptr;
  const char* oracleAfterReset = nullptr;
};

void usage() {
  fprintf(stderr,
          "usage: host_jni_test --in in.aac --target N --out out.dts --oracle oracle.s32\n"
          "       [--trim samples] [--no-eos-flag] [--reset-at P --resume-from Q\n"
          "        --out-after-reset f --oracle-after-reset f] [--stop-at P]\n");
  exit(2);
}

Options parse(int argc, char** argv) {
  Options o;
  for (int i = 1; i < argc; i++) {
    std::string a = argv[i];
    auto next = [&]() -> const char* {
      if (i + 1 >= argc) usage();
      return argv[++i];
    };
    if (a == "--in") o.input = next();
    else if (a == "--target") o.target = atoi(next());
    else if (a == "--trim") o.trim = atoi(next());
    else if (a == "--no-eos-flag") o.eosFlag = false;
    else if (a == "--reset-at") o.resetAt = atol(next());
    else if (a == "--resume-from") o.resumeFrom = atol(next());
    else if (a == "--stop-at") o.stopAt = atol(next());
    else if (a == "--out") o.out = next();
    else if (a == "--oracle") o.oracle = next();
    else if (a == "--out-after-reset") o.outAfterReset = next();
    else if (a == "--oracle-after-reset") o.oracleAfterReset = next();
    else usage();
  }
  if (!o.input || !o.out || !o.oracle) usage();
  if (o.resetAt >= 0 && (o.resumeFrom < 0 || !o.outAfterReset || !o.oracleAfterReset)) usage();
  return o;
}

}  // namespace

#define DECODER_FUNC(name) Java_androidx_media3_decoder_ffmpeg_FfmpegAudioDecoder_##name

int main(int argc, char** argv) {
  Options opt = parse(argc, argv);
  AdtsStream stream;
  if (!readAdts(opt.input, &stream)) {
    fprintf(stderr, "cannot read ADTS input %s\n", opt.input);
    return 2;
  }
  JNIEnv* env = fakeEnv();
  jobject thiz = nullptr;
  FakeByteArray extraData = {stream.asc, 2};
  jlong context = DECODER_FUNC(ffmpegInitialize)(
      env, thiz, reinterpret_cast<jstring>(const_cast<char*>("aac")),
      reinterpret_cast<jbyteArray>(&extraData), stream.sampleRate, stream.channels,
      /* outputChannelCount= */ 0, /* requestedOutputLayoutName= */ nullptr,
      /* outputFloat= */ JNI_FALSE, opt.target);
  if (!context) {
    fprintf(stderr, "ffmpegInitialize failed\n");
    return 1;
  }

  FILE* out = fopen(opt.out, "wb");
  g_oracle = fopen(opt.oracle, "wb");
  if (!out || !g_oracle) return 2;

  UnitStats stats[2];
  int segment = 0;
  long decodeCalls = 0, emptyCalls = 0, frameMismatch = 0, failures = 0;
  long framesBefore[2] = {0, 0};
  bool startTrimPending = true;
  long end = opt.stopAt >= 0 ? opt.stopAt : (long)stream.packets.size();
  int channelCount = 0, sampleRate = 0;

  for (long i = 0; i < end; i++) {
    if (i == opt.resetAt) {
      // A seek: Media3 flushes the decoder, which resets the native context on the next decode.
      fclose(out);
      fclose(g_oracle);
      framesBefore[0] = g_encoder_frames;
      out = fopen(opt.outAfterReset, "wb");
      g_oracle = fopen(opt.oracleAfterReset, "wb");
      if (!out || !g_oracle) return 2;
      context = DECODER_FUNC(ffmpegReset)(env, thiz, context, reinterpret_cast<jbyteArray>(&extraData));
      if (!context) {
        fprintf(stderr, "ffmpegReset failed\n");
        return 1;
      }
      startTrimPending = true;
      segment = 1;
      i = opt.resumeFrom;
    }
    if (startTrimPending) {
      startTrimPending = false;
      // TranscodeClock.startsStream: only a decode that starts at the stream start trims.
      if (opt.trim > 0 && i == 0) DECODER_FUNC(ffmpegSetStartTrim)(env, thiz, context, opt.trim);
    }
    const std::vector<uint8_t>& packet = stream.packets[(size_t)i];
    if (g_input.capacity < (jint)packet.size() + AV_INPUT_BUFFER_PADDING_SIZE) {
      g_input.capacity = (jint)packet.size() + AV_INPUT_BUFFER_PADDING_SIZE;
      g_input.data = static_cast<uint8_t*>(realloc(g_input.data, (size_t)g_input.capacity));
    }
    memcpy(g_input.data, packet.data(), packet.size());
    memset(g_input.data + packet.size(), 0, AV_INPUT_BUFFER_PADDING_SIZE);
    ensureOutputCapacity(g_output_buffer_size);
    jlong samplesBefore = DECODER_FUNC(ffmpegGetEncodedSamples)(env, thiz, context);
    bool endOfStream = opt.eosFlag && i == end - 1;
    jint result = DECODER_FUNC(ffmpegDecode)(
        env, thiz, context, reinterpret_cast<jobject>(&g_input), (jint)packet.size(),
        /* decoderOutputBuffer= */ reinterpret_cast<jobject>(&g_decoderOutputBuffer),
        reinterpret_cast<jobject>(&g_output),
        g_output_buffer_size, /* userCenterMixLevelDb= */ 0,
        /* downmixNormalizationEnabled= */ JNI_FALSE, endOfStream ? JNI_TRUE : JNI_FALSE);
    decodeCalls++;
    if (result < 0) {
      fprintf(stderr, "ffmpegDecode failed at packet %ld: %d\n", i, result);
      failures++;
      continue;
    }
    jlong samplesAfter = DECODER_FUNC(ffmpegGetEncodedSamples)(env, thiz, context);
    if (result == 0) {
      emptyCalls++;
      if (samplesAfter != samplesBefore) frameMismatch++;
      continue;
    }
    if (!channelCount) {
      channelCount = DECODER_FUNC(ffmpegGetChannelCount)(env, thiz, context);
      sampleRate = DECODER_FUNC(ffmpegGetSampleRate)(env, thiz, context);
    }
    fwrite(g_output.data, 1, (size_t)result, out);
    long unitsBefore = stats[segment].units;
    splitUnits(g_output.data, result, &stats[segment]);
    // Every emitted unit, and nothing else, advances the encoded-sample count by 512.
    if (samplesAfter - samplesBefore != (stats[segment].units - unitsBefore) * 512) frameMismatch++;
  }
  fclose(out);
  fclose(g_oracle);
  g_oracle = nullptr;
  framesBefore[1] = g_encoder_frames - framesBefore[0];
  if (opt.resetAt < 0) framesBefore[0] = g_encoder_frames;

  DecoderContext* decoderContext = reinterpret_cast<DecoderContext*>(context);
  printf("input_rate=%d input_channels=%d packets=%zu decode_calls=%ld empty_calls=%ld "
         "failures=%ld frame_mismatch=%ld grow_calls=%ld channel_count=%d sample_rate=%d\n",
         stream.sampleRate, stream.channels, stream.packets.size(), decodeCalls, emptyCalls,
         failures, frameMismatch, g_grow_calls, channelCount, sampleRate);
  for (int s = 0; s <= (opt.resetAt >= 0 ? 1 : 0); s++) {
    printf("segment=%d encoder_frames=%ld units=%ld hd_units=%ld core_only_units=%ld "
           "max_unit=%ld bad_core_size=%ld unparsed=%ld\n",
           s, framesBefore[s], stats[s].units, stats[s].hdUnits, stats[s].coreOnlyUnits,
           stats[s].maxUnit, stats[s].badCoreSize, stats[s].unparsed);
  }
  printf("hd_writer_errors=%ld hd_clipped=%ld hd_max_unit=%zu hd_unit_bound=%zu\n",
         decoderContext->hd.writer_errors, decoderContext->hd.clipped, decoderContext->hd.max_unit,
         (size_t)DTSHDMA_MAX_UNIT_BYTES_24(2, 2012));
  DECODER_FUNC(ffmpegRelease)(env, thiz, context);
  free(g_input.data);
  free(g_output.data);
  return failures ? 1 : 0;
}
