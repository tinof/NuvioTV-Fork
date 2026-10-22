/*
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This modified decoder module is distributed with NuvioTV under GPL-3.0-only.
 * It is based on AndroidX Media3 decoder_ffmpeg; the original Apache-2.0 notice
 * is preserved below. Additional downmix behavior was adapted from Kodi
 * (GPL-2.0-or-later). See this module's NOTICE.md for provenance details.
 */
/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#include <android/log.h>
#include <jni.h>
#include <math.h>
#include <stdlib.h>
#include <string.h>

extern "C" {
#ifdef __cplusplus
#define __STDC_CONSTANT_MACROS
#ifdef _STDINT_H
#undef _STDINT_H
#endif
#include <stdint.h>
#endif
#include <libavcodec/avcodec.h>
#include <libavutil/channel_layout.h>
#include <libavutil/downmix_info.h>
#include <libavutil/error.h>
#include <libavutil/opt.h>
#include <libavutil/samplefmt.h>
#include <libswresample/swresample.h>
#include <libavutil/audio_fifo.h>
}

#include "dtshdma/dtshd_stage.h"

#define LOG_TAG "ffmpeg_jni"
#define LOGE(...) \
  ((void)__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__))
#define LOGD(...) \
  ((void)__android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__))
#define LOGI(...) \
  ((void)__android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__))

#define LIBRARY_FUNC(RETURN_TYPE, NAME, ...)                               \
  extern "C" {                                                             \
  JNIEXPORT RETURN_TYPE                                                    \
  Java_androidx_media3_decoder_ffmpeg_FfmpegLibrary_##NAME(JNIEnv* env,    \
                                                           jobject thiz,   \
                                                           ##__VA_ARGS__); \
  }                                                                        \
  JNIEXPORT RETURN_TYPE                                                    \
  Java_androidx_media3_decoder_ffmpeg_FfmpegLibrary_##NAME(                \
      JNIEnv* env, jobject thiz, ##__VA_ARGS__)

#define AUDIO_DECODER_FUNC(RETURN_TYPE, NAME, ...)               \
  extern "C" {                                                   \
  JNIEXPORT RETURN_TYPE                                          \
  Java_androidx_media3_decoder_ffmpeg_FfmpegAudioDecoder_##NAME( \
      JNIEnv* env, jobject thiz, ##__VA_ARGS__);                 \
  }                                                              \
  JNIEXPORT RETURN_TYPE                                          \
  Java_androidx_media3_decoder_ffmpeg_FfmpegAudioDecoder_##NAME( \
      JNIEnv* env, jobject thiz, ##__VA_ARGS__)

#define ERROR_STRING_BUFFER_LENGTH 256

// Output formats corresponding to Android PCM encodings. Downmix-off uses
// 16-bit PCM to keep behavior aligned with the standard Media3 FFmpeg decoder.
static const AVSampleFormat OUTPUT_FORMAT_PCM_16BIT = AV_SAMPLE_FMT_S16;
static const AVSampleFormat OUTPUT_FORMAT_PCM_FLOAT = AV_SAMPLE_FMT_FLT;
// Default center level when no downmix metadata is present (-3 dB).
static const double DEFAULT_CENTER_MIX_LEVEL = M_SQRT1_2;
// LFE folded into the front channels when the output has no LFE, at -6 dB. FFmpeg drops it
// by default, which loses the bass on stereo downmixes.
static const double LFE_MIX_LEVEL = 0.5;
// Kodi AudioEngine limiter defaults. The limiter sits after rematrixing and
// catches decoded/remixed float peaks without permanently lowering the mix.
static const float LIMITER_DEFAULT_SAMPLE_RATE = 48000.0f;
static const float LIMITER_HOLD_SECONDS = 0.025f;
static const float LIMITER_RELEASE_SECONDS = 0.1f;

// LINT.IfChange
static const int AUDIO_DECODER_ERROR_INVALID_DATA = -1;
static const int AUDIO_DECODER_ERROR_OTHER = -2;
static const int TRANSCODE_TARGET_NONE = 0;
static const int TRANSCODE_TARGET_AC3 = 1;
static const int TRANSCODE_TARGET_DTS = 2;
static const int TRANSCODE_TARGET_DTS_HD = 3;
// LINT.ThenChange(../java/androidx/media3/decoder/ffmpeg/FfmpegAudioDecoder.java)

static const int64_t AC3_BIT_RATE = 640000;
// DTS core as the owner's offline converter writes it: 1509 kbit/s at 48 kHz
// gives 2012-byte frames, inside the 2040-byte payload of an IEC 61937 DTS
// type I burst.
static const int64_t DTS_BIT_RATE = 1509000;
static const int DTS_SAMPLE_RATE = 48000;
// Resampler for the DTS path, measured against soxr precision 28 on the
// n7.0.2 libswresample: images below -130 dB and ripple under 0.004 dB to
// 20 kHz for 44.1/88.2/96 kHz input, at about 4x the CPU of the default
// filter (which leaves images at -7.5 dB near 22 kHz). The -6 dB edge sits at
// cutoff x 24 kHz = 21 kHz; below 44.1 kHz input the cutoff scales with the
// input rate so the edge stays under its Nyquist frequency.
static const int DTS_RESAMPLER_FILTER_SIZE = 144;
static const double DTS_RESAMPLER_CUTOFF = 0.875;
static const double DTS_RESAMPLER_KAISER_BETA = 13.0;

struct AudioLimiter {
  float attenuation;
  float sample_rate;
  int hold_counter;
  float increase;
};

struct DecoderContext {
  AVCodecContext* codec_context;
  SwrContext* resample_context;
  // Input sample rate the resampler was configured with. The transcode path
  // may resample to a different output rate, so a change of input rate is
  // detected against this value.
  int configured_input_sample_rate;
  AVSampleFormat output_sample_format;
  AVChannelLayout input_layout;
  AVChannelLayout output_layout;
  jint requested_output_channel_count;
  char* requested_output_layout_name;
  double center_mix_level;
  bool downmix_normalization_enabled;
  bool has_input_layout;
  bool has_output_layout;
  bool has_center_mix_level;
  bool downmix_active;
  AudioLimiter limiter;

  // Transcode encoder fields (AC-3 or DTS core)
  int transcode_target;
  // DTS keeps the channel count of the first decoded frame (1 or 2) for the
  // life of the decoder, so a later layout change never reopens the encoder
  // with a different count. 0 until the first frame.
  int dts_channels;
  // Decoded samples still to drop at the start of the stream (AAC priming).
  int start_trim_samples;
  // Samples sent out by the encoder since the last reset.
  int64_t encoded_samples;
  // DTS: dcaenc packets received and frames sent since the encoder opened.
  // dcaenc's output lags its input by one frame (512 samples) and it has no
  // delay capability, so packet p carries the audio of frame p - 1: packet 0
  // (pre-roll only) is dropped, and one zero frame at the end of stream
  // brings out the last real frame.
  int64_t dts_packets_seen;
  int64_t dts_frames_sent;
  // DTS-HD MA: the 24-bit PCM of the frames sent to dcaenc and the writer that pairs each core
  // packet with the XLL of its frame (dtshdma/dtshd_stage.h).
  dtshd_stage hd;
  bool hd_error_logged;
  // DTS: units still to drop in the current decode call. After a seek the first packet at or
  // after the start position can still bring out units that belong before it; the decoder
  // knows their times and asks for them to be dropped. They still count as encoded samples.
  int skip_units;
  AVCodecContext* encoder_context;
  AVAudioFifo* fifo;
  AVFrame* encoder_frame;
  AVPacket* encoder_packet;
  bool encoder_initialized;
};

static jmethodID growOutputBufferMethod;

/**
 * Returns the AVCodec with the specified name, or NULL if it is not available.
 */
const AVCodec* getCodecByName(JNIEnv* env, jstring codecName);

/**
 * Allocates and opens a new decoder context for the specified codec.
 */
DecoderContext* createContext(JNIEnv* env, const AVCodec* codec,
                              jbyteArray extraData,
                              jint rawSampleRate, jint rawChannelCount,
                              jint outputChannelCount,
                              jstring requestedOutputLayoutName,
                              jboolean outputFloat,
                              jint transcodeTarget);

struct GrowOutputBufferCallback {
  uint8_t* operator()(int requiredSize) const;

  JNIEnv* env;
  jobject thiz;
  jobject decoderOutputBuffer;
};

/**
 * Decodes the packet into the output buffer, returning the number of bytes
 * written, or a negative AUDIO_DECODER_ERROR constant value in the case of an
 * error.
 */
int decodePacket(DecoderContext* decoderContext, AVPacket* packet,
                 uint8_t* outputBuffer, int outputSize,
                 jint userCenterMixLevelDb,
                 jboolean downmixNormalizationEnabled,
                 GrowOutputBufferCallback growBuffer, jboolean endOfStream,
                 jint skipUnits);

int ac3TrailingSilenceSamples(int queuedSamples, int frameSize);

/**
 * Configures or recreates the resampler for the current frame.
 */
int configureResampler(DecoderContext* decoderContext, AVFrame* frame,
                       jint userCenterMixLevelDb,
                       jboolean downmixNormalizationEnabled);

/**
 * Transforms ffmpeg AVERROR into a negative AUDIO_DECODER_ERROR constant value.
 */
int transformError(int errorNumber);

/**
 * Outputs a log message describing the avcodec error number.
 */
void logError(const char* functionName, int errorNumber);

/**
 * Releases the specified decoder context.
 */
void releaseContext(DecoderContext* decoderContext);

void clearResampler(DecoderContext* decoderContext);

void clearEncoder(DecoderContext* decoderContext);

bool trimFrameStart(DecoderContext* decoderContext, AVFrame* frame);

int flushResamplerToFifo(DecoderContext* decoderContext);

// AC-3 encoding only accepts 32/44.1/48 kHz. Map arbitrary input rates to the
// nearest legal target, preferring an integer resample ratio: 88.2/176.4 kHz
// -> 44.1 kHz, 96/192 kHz (and anything else) -> 48 kHz. Standard rates pass
// through untouched so the existing <=48 kHz behaviour is byte-identical.
static int ac3TargetSampleRate(int inputRate) {
  switch (inputRate) {
    case 32000:
    case 44100:
    case 48000:
      return inputRate;
    default:
      return (inputRate > 0 && inputRate % 44100 == 0) ? 44100 : 48000;
  }
}

void resetLimiter(AudioLimiter* limiter);

float getLimiterGain(AudioLimiter* limiter, float highestSample);

void limitInterleavedFloat(AudioLimiter* limiter, float* samples,
                           int frameCount, int channelCount,
                           int sampleRate);

void limitPlanarFloat(AudioLimiter* limiter, uint8_t** samples,
                      int frameCount, int channelCount, int sampleRate);

bool copyChannelLayout(const AVChannelLayout* source, AVChannelLayout* destination);

bool channelLayoutsEqual(const AVChannelLayout* left, const AVChannelLayout* right);

bool getInputChannelLayout(AVCodecContext* codecContext, AVFrame* frame,
                           AVChannelLayout* inputLayout);

bool getOutputChannelLayout(DecoderContext* decoderContext,
                            const AVChannelLayout* inputLayout,
                            AVChannelLayout* outputLayout);

bool applyRequestedOutputLayout(DecoderContext* decoderContext,
                                AVChannelLayout* outputLayout);

double getAdjustedCenterMixLevel(AVFrame* frame, jint userCenterMixLevelDb,
                                 bool isDownmixActive);

jint JNI_OnLoad(JavaVM* vm, void* reserved) {
  JNIEnv* env;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
    LOGE("JNI_OnLoad: GetEnv failed");
    return -1;
  }
  jclass clazz =
      env->FindClass("androidx/media3/decoder/ffmpeg/FfmpegAudioDecoder");
  if (!clazz) {
    LOGE("JNI_OnLoad: FindClass failed");
    return -1;
  }
  growOutputBufferMethod =
      env->GetMethodID(clazz, "growOutputBuffer",
                       "(Landroidx/media3/decoder/"
                       "SimpleDecoderOutputBuffer;I)Ljava/nio/ByteBuffer;");
  if (!growOutputBufferMethod) {
    LOGE("JNI_OnLoad: GetMethodID failed");
    return -1;
  }
  return JNI_VERSION_1_6;
}

LIBRARY_FUNC(jstring, ffmpegGetVersion) {
  return env->NewStringUTF(LIBAVCODEC_IDENT);
}

LIBRARY_FUNC(jint, ffmpegGetInputBufferPaddingSize) {
  return (jint)AV_INPUT_BUFFER_PADDING_SIZE;
}

LIBRARY_FUNC(jboolean, ffmpegHasDecoder, jstring codecName) {
  return getCodecByName(env, codecName) != NULL;
}

AUDIO_DECODER_FUNC(jlong, ffmpegInitialize, jstring codecName,
                   jbyteArray extraData,
                   jint rawSampleRate, jint rawChannelCount,
                   jint outputChannelCount,
                   jstring requestedOutputLayoutName,
                   jboolean outputFloat,
                   jint transcodeTarget) {
  const AVCodec* codec = getCodecByName(env, codecName);
  if (!codec) {
    LOGE("Codec not found.");
    return 0L;
  }
  return (jlong)createContext(env, codec, extraData, rawSampleRate,
                              rawChannelCount, outputChannelCount,
                              requestedOutputLayoutName, outputFloat,
                              transcodeTarget);
}

AUDIO_DECODER_FUNC(jint, ffmpegDecode, jlong context, jobject inputData,
                   jint inputSize, jobject decoderOutputBuffer,
                   jobject outputData, jint outputSize,
                   jint userCenterMixLevelDb,
                   jboolean downmixNormalizationEnabled,
                   jboolean endOfStream, jint skipUnits) {
  if (!context) {
    LOGE("Context must be non-NULL.");
    return -1;
  }
  if (!inputData || !decoderOutputBuffer || !outputData) {
    LOGE("Input and output buffers must be non-NULL.");
    return -1;
  }
  if (inputSize < 0) {
    LOGE("Invalid input buffer size: %d.", inputSize);
    return -1;
  }
  if (outputSize < 0) {
    LOGE("Invalid output buffer length: %d", outputSize);
    return -1;
  }
  uint8_t* inputBuffer = (uint8_t*)env->GetDirectBufferAddress(inputData);
  uint8_t* outputBuffer = (uint8_t*)env->GetDirectBufferAddress(outputData);
  AVPacket* packet = av_packet_alloc();
  if (!packet) {
    LOGE("Failed to allocate packet.");
    return -1;
  }
  packet->data = inputBuffer;
  packet->size = inputSize;
  const int ret =
      decodePacket((DecoderContext*)context, packet, outputBuffer, outputSize,
                   userCenterMixLevelDb,
                   downmixNormalizationEnabled,
                   GrowOutputBufferCallback{env, thiz, decoderOutputBuffer},
                   endOfStream, skipUnits);
  av_packet_free(&packet);
  return ret;
}

uint8_t* GrowOutputBufferCallback::operator()(int requiredSize) const {
  jobject newOutputData = env->CallObjectMethod(
      thiz, growOutputBufferMethod, decoderOutputBuffer, requiredSize);
  if (env->ExceptionCheck()) {
    LOGE("growOutputBuffer() failed");
    env->ExceptionDescribe();
    return nullptr;
  }
  return static_cast<uint8_t*>(env->GetDirectBufferAddress(newOutputData));
}

AUDIO_DECODER_FUNC(jint, ffmpegGetChannelCount, jlong context) {
  if (!context) {
    LOGE("Context must be non-NULL.");
    return -1;
  }
  DecoderContext* decoderContext = (DecoderContext*)context;
  // A transcode reports the encoded channels (DTS-HD MA: always 2, also for mono).
  if (decoderContext->transcode_target != TRANSCODE_TARGET_NONE &&
      decoderContext->encoder_context) {
    return decoderContext->encoder_context->ch_layout.nb_channels;
  }
  if (decoderContext->has_output_layout) {
    return decoderContext->output_layout.nb_channels;
  }
  if (decoderContext->requested_output_channel_count > 0) {
    return decoderContext->requested_output_channel_count;
  }
  return decoderContext->codec_context->ch_layout.nb_channels;
}

AUDIO_DECODER_FUNC(jint, ffmpegGetSampleRate, jlong context) {
  if (!context) {
    LOGE("Context must be non-NULL.");
    return -1;
  }
  DecoderContext* decoderContext = (DecoderContext*)context;
  if (decoderContext->transcode_target != TRANSCODE_TARGET_NONE &&
      decoderContext->encoder_context &&
      decoderContext->encoder_context->sample_rate > 0) {
    return decoderContext->encoder_context->sample_rate;
  }
  return decoderContext->codec_context->sample_rate;
}

AUDIO_DECODER_FUNC(void, ffmpegSetStartTrim, jlong context, jint samples) {
  if (!context) {
    LOGE("Context must be non-NULL.");
    return;
  }
  ((DecoderContext*)context)->start_trim_samples = samples > 0 ? samples : 0;
}

AUDIO_DECODER_FUNC(jlong, ffmpegGetEncodedSamples, jlong context) {
  if (!context) {
    LOGE("Context must be non-NULL.");
    return 0;
  }
  return (jlong)((DecoderContext*)context)->encoded_samples;
}

AUDIO_DECODER_FUNC(jlong, ffmpegReset, jlong jContext, jbyteArray extraData) {
  DecoderContext* decoderContext = (DecoderContext*)jContext;
  if (!decoderContext || !decoderContext->codec_context) {
    LOGE("Tried to reset without a context.");
    return 0L;
  }

  AVCodecContext* codecContext = decoderContext->codec_context;
  AVCodecID codecId = codecContext->codec_id;
  if (codecId == AV_CODEC_ID_TRUEHD) {
    jint outputChannelCount = decoderContext->requested_output_channel_count;
    jboolean outputFloat =
        decoderContext->output_sample_format == OUTPUT_FORMAT_PCM_FLOAT;
    jint transcodeTarget = decoderContext->transcode_target;
    jstring requestedOutputLayoutName = NULL;
    if (decoderContext->requested_output_layout_name) {
      requestedOutputLayoutName =
          env->NewStringUTF(decoderContext->requested_output_layout_name);
    }
    releaseContext(decoderContext);
    const AVCodec* codec = avcodec_find_decoder(codecId);
    if (!codec) {
      LOGE("Unexpected error finding codec %d.", codecId);
      return 0L;
    }
    jlong context = (jlong)createContext(env, codec, extraData,
                                         /* rawSampleRate= */ -1,
                                         /* rawChannelCount= */ -1,
                                         outputChannelCount,
                                         requestedOutputLayoutName,
                                         outputFloat,
                                         transcodeTarget);
    if (requestedOutputLayoutName) {
      env->DeleteLocalRef(requestedOutputLayoutName);
    }
    return context;
  }

  avcodec_flush_buffers(codecContext);
  clearResampler(decoderContext);
  clearEncoder(decoderContext);
  decoderContext->start_trim_samples = 0;
  resetLimiter(&decoderContext->limiter);
  return (jlong)decoderContext;
}

AUDIO_DECODER_FUNC(void, ffmpegRelease, jlong context) {
  if (context) {
    releaseContext((DecoderContext*)context);
  }
}

const AVCodec* getCodecByName(JNIEnv* env, jstring codecName) {
  if (!codecName) {
    return NULL;
  }
  const char* codecNameChars = env->GetStringUTFChars(codecName, NULL);
  const AVCodec* codec = avcodec_find_decoder_by_name(codecNameChars);
  env->ReleaseStringUTFChars(codecName, codecNameChars);
  return codec;
}

DecoderContext* createContext(JNIEnv* env, const AVCodec* codec,
                              jbyteArray extraData,
                              jint rawSampleRate, jint rawChannelCount,
                              jint outputChannelCount,
                              jstring requestedOutputLayoutName,
                              jboolean outputFloat,
                              jint transcodeTarget) {
  DecoderContext* decoderContext =
      static_cast<DecoderContext*>(calloc(1, sizeof(DecoderContext)));
  if (!decoderContext) {
    LOGE("Failed to allocate decoder context.");
    return NULL;
  }
  decoderContext->transcode_target = transcodeTarget;
  resetLimiter(&decoderContext->limiter);
  if (transcodeTarget == TRANSCODE_TARGET_DTS_HD && dtshd_stage_init(&decoderContext->hd) < 0) {
    LOGE("Failed to create the DTS-HD MA writer.");
    free(decoderContext);
    return NULL;
  }

  AVCodecContext* codecContext = avcodec_alloc_context3(codec);
  if (!codecContext) {
    LOGE("Failed to allocate codec context.");
    releaseContext(decoderContext);
    return NULL;
  }

  decoderContext->codec_context = codecContext;
  decoderContext->output_sample_format =
      outputFloat ? OUTPUT_FORMAT_PCM_FLOAT : OUTPUT_FORMAT_PCM_16BIT;
  decoderContext->requested_output_channel_count = outputChannelCount;
  if (requestedOutputLayoutName) {
    const char* outputLayoutNameChars =
        env->GetStringUTFChars(requestedOutputLayoutName, NULL);
    if (outputLayoutNameChars) {
      decoderContext->requested_output_layout_name = strdup(outputLayoutNameChars);
      env->ReleaseStringUTFChars(requestedOutputLayoutName, outputLayoutNameChars);
    }
  }
  if (extraData) {
    jsize size = env->GetArrayLength(extraData);
    codecContext->extradata_size = size;
    codecContext->extradata =
        (uint8_t*)av_malloc(size + AV_INPUT_BUFFER_PADDING_SIZE);
    if (!codecContext->extradata) {
      LOGE("Failed to allocate extradata.");
      releaseContext(decoderContext);
      return NULL;
    }
    env->GetByteArrayRegion(extraData, 0, size, (jbyte*)codecContext->extradata);
  }
  if (codecContext->codec_id == AV_CODEC_ID_PCM_MULAW ||
      codecContext->codec_id == AV_CODEC_ID_PCM_ALAW) {
    codecContext->sample_rate = rawSampleRate;
    av_channel_layout_default(&codecContext->ch_layout, rawChannelCount);
  }
  codecContext->err_recognition = AV_EF_IGNORE_ERR;
  int result = avcodec_open2(codecContext, codec, NULL);
  if (result < 0) {
    logError("avcodec_open2", result);
    releaseContext(decoderContext);
    return NULL;
  }
  return decoderContext;
}

// Both DTS targets: the DTS-HD MA units carry a DTS core.
static bool isDtsTarget(const DecoderContext* decoderContext) {
  return decoderContext->transcode_target == TRANSCODE_TARGET_DTS ||
         decoderContext->transcode_target == TRANSCODE_TARGET_DTS_HD;
}

static bool isDtsHdTarget(const DecoderContext* decoderContext) {
  return decoderContext->transcode_target == TRANSCODE_TARGET_DTS_HD;
}

// DTS-HD MA encodes dual mono for a mono source: the resampler keeps the source's channel
// count and this copies mono S32 to both channels of the stereo FIFO.
static bool expandsMono(const DecoderContext* decoderContext) {
  return isDtsHdTarget(decoderContext) && decoderContext->output_layout.nb_channels == 1;
}

int ac3TrailingSilenceSamples(int queuedSamples, int frameSize) {
  if (frameSize <= 0 || queuedSamples <= 0) {
    return 0;
  }
  int remainder = queuedSamples % frameSize;
  if (remainder == 0) {
    return 0;
  }
  return frameSize - remainder;
}

// Writes core packet `packetIndex` with the XLL of the frame before it as one DTS-HD MA unit.
static int writeDtsHdUnit(DecoderContext* decoderContext, int64_t packetIndex,
                          uint8_t** outputBuffer, int* outSize, int* outputSize,
                          GrowOutputBufferCallback growBuffer) {
  AVPacket* packet = decoderContext->encoder_packet;
  int bound = (int)DTSHD_STAGE_MAX_UNIT_BYTES(packet->size);
  if (*outSize + bound > *outputSize) {
    *outputSize = *outSize + bound;
    uint8_t* newBase = growBuffer(*outputSize);
    if (!newBase) {
      LOGE("Failed to grow output buffer during encoding.");
      return AUDIO_DECODER_ERROR_OTHER;
    }
    *outputBuffer = newBase + *outSize;
  }
  dtshd_stage* hd = &decoderContext->hd;
  long written = dtshd_stage_pair(hd, packetIndex, packet->data, (size_t)packet->size,
                                  *outputBuffer, (size_t)(*outputSize - *outSize));
  if (written < 0) {
    LOGE("DTS-HD MA: no frame for core packet %lld.", (long long)packetIndex);
    return AUDIO_DECODER_ERROR_OTHER;
  }
  if (hd->writer_errors && !decoderContext->hd_error_logged) {
    decoderContext->hd_error_logged = true;
    LOGE("DTS-HD MA writer error %ld at unit %lld: the unit goes out as a DTS core only.",
         hd->first_error, (long long)hd->first_error_unit);
  }
  *outputBuffer += written;
  *outSize += (int)written;
  return 0;
}

int receiveEncoderPackets(DecoderContext* decoderContext, uint8_t** outputBuffer,
                          int* outSize, int* outputSize,
                          GrowOutputBufferCallback growBuffer) {
  while (true) {
    int ret = avcodec_receive_packet(decoderContext->encoder_context,
                                     decoderContext->encoder_packet);
    if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
      return 0;
    }
    if (ret < 0) {
      logError("avcodec_receive_packet", ret);
      return AUDIO_DECODER_ERROR_OTHER;
    }
    int64_t packetIndex = -1;
    if (isDtsTarget(decoderContext)) {
      packetIndex = decoderContext->dts_packets_seen++;
      if (packetIndex == 0) {
        av_packet_unref(decoderContext->encoder_packet);
        continue;
      }
      if (decoderContext->skip_units > 0) {
        decoderContext->skip_units--;
        decoderContext->encoded_samples += decoderContext->encoder_context->frame_size;
        av_packet_unref(decoderContext->encoder_packet);
        continue;
      }
    }
    int packetSize = decoderContext->encoder_packet->size;
    if (isDtsHdTarget(decoderContext)) {
      int ret = writeDtsHdUnit(decoderContext, packetIndex, outputBuffer, outSize, outputSize,
                               growBuffer);
      av_packet_unref(decoderContext->encoder_packet);
      if (ret < 0) {
        return ret;
      }
      decoderContext->encoded_samples += decoderContext->encoder_context->frame_size;
      continue;
    }
    if (*outSize + packetSize > *outputSize) {
      *outputSize = *outSize + packetSize;
      uint8_t* newBase = growBuffer(*outputSize);
      if (!newBase) {
        LOGE("Failed to grow output buffer during encoding.");
        av_packet_unref(decoderContext->encoder_packet);
        return AUDIO_DECODER_ERROR_OTHER;
      }
      *outputBuffer = newBase + *outSize;
    }
    memcpy(*outputBuffer, decoderContext->encoder_packet->data, packetSize);
    *outputBuffer += packetSize;
    *outSize += packetSize;
    decoderContext->encoded_samples += decoderContext->encoder_context->frame_size;
    av_packet_unref(decoderContext->encoder_packet);
  }
}

// Sends encoder_frame to the encoder and receives what it gives back. For DTS-HD MA a frame
// with audio (`keepForXll`) is first rounded to 24 bits and kept for its XLL.
static int sendEncoderFrame(DecoderContext* decoderContext, uint8_t** outputBuffer,
                            int* outSize, int* outputSize,
                            GrowOutputBufferCallback growBuffer, bool keepForXll) {
  if (keepForXll && isDtsHdTarget(decoderContext)) {
    dtshd_stage_take_frame(&decoderContext->hd,
                           reinterpret_cast<int32_t*>(decoderContext->encoder_frame->data[0]));
  }
  int ret = avcodec_send_frame(decoderContext->encoder_context,
                               decoderContext->encoder_frame);
  if (ret < 0) {
    logError("avcodec_send_frame", ret);
    return AUDIO_DECODER_ERROR_OTHER;
  }
  if (isDtsTarget(decoderContext)) {
    decoderContext->dts_frames_sent++;
  }
  return receiveEncoderPackets(decoderContext, outputBuffer, outSize, outputSize,
                               growBuffer);
}

int encodeFullFrames(DecoderContext* decoderContext, uint8_t** outputBuffer,
                        int* outSize, int* outputSize,
                        GrowOutputBufferCallback growBuffer) {
  int frameSize = decoderContext->encoder_context->frame_size;
  if (frameSize <= 0) {
    return 0;
  }
  while (av_audio_fifo_size(decoderContext->fifo) >= frameSize) {
    av_audio_fifo_read(decoderContext->fifo,
                       (void**)decoderContext->encoder_frame->data, frameSize);
    decoderContext->encoder_frame->nb_samples = frameSize;
    int ret = sendEncoderFrame(decoderContext, outputBuffer, outSize, outputSize,
                               growBuffer, /* keepForXll= */ true);
    if (ret < 0) {
      return ret;
    }
  }
  return 0;
}

int flushResamplerToFifo(DecoderContext* decoderContext) {
  if (!decoderContext->resample_context || !decoderContext->fifo ||
      !decoderContext->encoder_context) {
    return 0;
  }
  int nbChannels = decoderContext->output_layout.nb_channels;
  if (nbChannels <= 0) {
    return 0;
  }
  int outCapacity = swr_get_out_samples(decoderContext->resample_context, 0);
  if (outCapacity < 0) {
    logError("swr_get_out_samples", outCapacity);
    return AUDIO_DECODER_ERROR_INVALID_DATA;
  }
  if (outCapacity == 0) {
    return 0;
  }
  bool expand = expandsMono(decoderContext);
  uint8_t** convertedData = NULL;
  if (av_samples_alloc_array_and_samples(&convertedData, NULL, expand ? 2 : nbChannels,
                                         outCapacity, decoderContext->output_sample_format,
                                         0) < 0) {
    return AUDIO_DECODER_ERROR_OTHER;
  }
  int converted = swr_convert(decoderContext->resample_context, convertedData,
                              outCapacity, NULL, 0);
  if (converted > 0 && expand) {
    dtshd_expand_mono_s32(reinterpret_cast<int32_t*>(convertedData[0]), converted);
  }
  if (converted > 0) {
    av_audio_fifo_write(decoderContext->fifo, (void**)convertedData, converted);
  }
  av_freep(&convertedData[0]);
  av_freep(&convertedData);
  if (converted < 0) {
    logError("swr_convert", converted);
    return AUDIO_DECODER_ERROR_INVALID_DATA;
  }
  return 0;
}

int flushEncoder(DecoderContext* decoderContext, uint8_t** outputBuffer,
                    int* outSize, int* outputSize,
                    GrowOutputBufferCallback growBuffer) {
  if (!decoderContext->encoder_initialized || !decoderContext->fifo ||
      !decoderContext->encoder_context || !decoderContext->encoder_frame) {
    return 0;
  }
  int flushed = flushResamplerToFifo(decoderContext);
  if (flushed < 0) {
    return flushed;
  }
  flushed = encodeFullFrames(decoderContext, outputBuffer, outSize, outputSize,
                                growBuffer);
  if (flushed < 0) {
    return flushed;
  }
  int frameSize = decoderContext->encoder_context->frame_size;
  int queued = av_audio_fifo_size(decoderContext->fifo);
  int silence = ac3TrailingSilenceSamples(queued, frameSize);
  if (queued > 0 && silence > 0) {
    av_audio_fifo_read(decoderContext->fifo,
                       (void**)decoderContext->encoder_frame->data, queued);
    av_samples_set_silence(decoderContext->encoder_frame->data, queued, silence,
                           decoderContext->encoder_frame->ch_layout.nb_channels,
                           (AVSampleFormat)decoderContext->encoder_frame->format);
    decoderContext->encoder_frame->nb_samples = frameSize;
    int ret = sendEncoderFrame(decoderContext, outputBuffer, outSize, outputSize,
                               growBuffer, /* keepForXll= */ true);
    if (ret < 0) {
      return ret;
    }
  }
  // DTS: the packet of one more frame, of silence, carries the last real
  // frame. The silent frame itself is never sent out.
  if (isDtsTarget(decoderContext) && decoderContext->dts_frames_sent > 0) {
    av_samples_set_silence(decoderContext->encoder_frame->data, 0, frameSize,
                           decoderContext->encoder_frame->ch_layout.nb_channels,
                           (AVSampleFormat)decoderContext->encoder_frame->format);
    decoderContext->encoder_frame->nb_samples = frameSize;
    int ret = sendEncoderFrame(decoderContext, outputBuffer, outSize, outputSize,
                               growBuffer, /* keepForXll= */ false);
    if (ret < 0) {
      return ret;
    }
  }
  int ret = avcodec_send_frame(decoderContext->encoder_context, NULL);
  if (ret < 0 && ret != AVERROR_EOF) {
    logError("avcodec_send_frame", ret);
    return AUDIO_DECODER_ERROR_OTHER;
  }
  return receiveEncoderPackets(decoderContext, outputBuffer, outSize, outputSize,
                               growBuffer);
}

int decodePacket(DecoderContext* decoderContext, AVPacket* packet,
                 uint8_t* outputBuffer, int outputSize,
                 jint userCenterMixLevelDb,
                 jboolean downmixNormalizationEnabled,
                 GrowOutputBufferCallback growBuffer, jboolean endOfStream,
                 jint skipUnits) {
  AVCodecContext* codecContext = decoderContext->codec_context;
  decoderContext->skip_units = isDtsTarget(decoderContext) && skipUnits > 0 ? skipUnits : 0;
  bool deliverInput = packet->size > 0 || !endOfStream;
  if (deliverInput) {
    int result = avcodec_send_packet(codecContext, packet);
    if (result) {
      logError("avcodec_send_packet", result);
      return transformError(result);
    }
  }

  int outSize = 0;
  int result = 0;
  bool decoderDrained = false;
  while (true) {
    AVFrame* frame = av_frame_alloc();
    if (!frame) {
      LOGE("Failed to allocate output frame.");
      return AUDIO_DECODER_ERROR_INVALID_DATA;
    }
    result = avcodec_receive_frame(codecContext, frame);
    if (result) {
      av_frame_free(&frame);
      if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) {
        if (endOfStream && decoderContext->transcode_target != TRANSCODE_TARGET_NONE &&
            !decoderDrained) {
          decoderDrained = true;
          int flushSend = avcodec_send_packet(codecContext, NULL);
          if (flushSend == 0 || flushSend == AVERROR(EAGAIN)) {
            continue;
          }
          if (flushSend != AVERROR_EOF) {
            logError("avcodec_send_packet", flushSend);
          }
        }
        break;
      }
      logError("avcodec_receive_frame", result);
      return transformError(result);
    }

    if (decoderContext->transcode_target != TRANSCODE_TARGET_NONE &&
        decoderContext->start_trim_samples > 0) {
      if (!trimFrameStart(decoderContext, frame)) {
        av_frame_free(&frame);
        continue;
      }
    }

    result =
        configureResampler(decoderContext, frame, userCenterMixLevelDb,
                           downmixNormalizationEnabled);
    if (result < 0) {
      av_frame_free(&frame);
      return transformError(result);
    }

    int sampleRate =
        frame->sample_rate > 0 ? frame->sample_rate : codecContext->sample_rate;
    if (decoderContext->transcode_target != TRANSCODE_TARGET_NONE) {
      int outSamples = swr_get_out_samples(decoderContext->resample_context, frame->nb_samples);
      int nb_channels = decoderContext->output_layout.nb_channels;
      bool expand = expandsMono(decoderContext);
      uint8_t** converted_data = NULL;
      if (outSamples < 0 ||
          av_samples_alloc_array_and_samples(&converted_data, NULL, expand ? 2 : nb_channels,
                                             outSamples > 0 ? outSamples : 1,
                                             decoderContext->output_sample_format, 0) < 0) {
        LOGE("Failed to allocate transcode buffer.");
        av_frame_free(&frame);
        return AUDIO_DECODER_ERROR_OTHER;
      }

      int convertedSamples =
          swr_convert(decoderContext->resample_context, converted_data, outSamples,
                      (const uint8_t**)frame->data, frame->nb_samples);
      av_frame_free(&frame);

      if (convertedSamples < 0) {
        logError("swr_convert", convertedSamples);
        av_freep(&converted_data[0]);
        av_freep(&converted_data);
        return AUDIO_DECODER_ERROR_INVALID_DATA;
      }

      if (expand && convertedSamples > 0) {
        dtshd_expand_mono_s32(reinterpret_cast<int32_t*>(converted_data[0]), convertedSamples);
      }

      // The limiter works on planar float; the DTS path (packed S32) never
      // downmixes.
      if (decoderContext->downmix_active &&
          decoderContext->output_sample_format == AV_SAMPLE_FMT_FLTP) {
        limitPlanarFloat(&decoderContext->limiter, converted_data,
                         convertedSamples, nb_channels,
                         decoderContext->encoder_context->sample_rate);
      }

      av_audio_fifo_write(decoderContext->fifo, (void**)converted_data, convertedSamples);

      av_freep(&converted_data[0]);
      av_freep(&converted_data);

      int encoded = encodeFullFrames(decoderContext, &outputBuffer, &outSize, &outputSize,
                                       growBuffer);
      if (encoded < 0) {
        return encoded;
      }
      codecContext->sample_rate = sampleRate;
    } else {
      int outputChannelCount = decoderContext->output_layout.nb_channels;
      int outSampleSize =
          av_get_bytes_per_sample(decoderContext->output_sample_format);
      int outSamples =
          swr_get_out_samples(decoderContext->resample_context, frame->nb_samples);
      int bufferOutSize = outSampleSize * outputChannelCount * outSamples;
      if (outSize + bufferOutSize > outputSize) {
        LOGD(
            "Output buffer size (%d) too small for output data (%d), "
            "reallocating buffer.",
            outputSize, outSize + bufferOutSize);
        outputSize = outSize + bufferOutSize;
        uint8_t* newBase = growBuffer(outputSize);
        if (!newBase) {
          LOGE("Failed to reallocate output buffer.");
          av_frame_free(&frame);
          return AUDIO_DECODER_ERROR_OTHER;
        }
        // growBuffer() returns the base address of the replacement buffer.
        // Preserve any frames already written during this decode call and
        // append the current frame after them.
        outputBuffer = newBase + outSize;
      }

      int convertedSamples =
          swr_convert(decoderContext->resample_context, &outputBuffer, outSamples,
                      (const uint8_t**)frame->data, frame->nb_samples);
      av_frame_free(&frame);
      if (convertedSamples < 0) {
        logError("swr_convert", convertedSamples);
        return AUDIO_DECODER_ERROR_INVALID_DATA;
      }
      if (decoderContext->downmix_active &&
          decoderContext->output_sample_format == AV_SAMPLE_FMT_FLT) {
        limitInterleavedFloat(
            &decoderContext->limiter,
            reinterpret_cast<float*>(outputBuffer), convertedSamples,
            outputChannelCount, sampleRate);
      }
      int writtenSize = outSampleSize * outputChannelCount * convertedSamples;
      outputBuffer += writtenSize;
      outSize += writtenSize;
      codecContext->sample_rate = sampleRate;
    }
  }
  if (endOfStream && decoderContext->transcode_target != TRANSCODE_TARGET_NONE) {
    int flushed = flushEncoder(decoderContext, &outputBuffer, &outSize, &outputSize,
                                  growBuffer);
    if (flushed < 0) {
      return flushed;
    }
  }
  return outSize;
}

int configureResampler(DecoderContext* decoderContext, AVFrame* frame,
                       jint userCenterMixLevelDb,
                       jboolean downmixNormalizationEnabled) {
  AVCodecContext* codecContext = decoderContext->codec_context;
  AVChannelLayout inputLayout = {};
  if (!getInputChannelLayout(codecContext, frame, &inputLayout)) {
    LOGE("Unable to resolve input channel layout.");
    return AUDIO_DECODER_ERROR_OTHER;
  }

  AVChannelLayout outputLayout = {};
  if (decoderContext->transcode_target == TRANSCODE_TARGET_AC3) {
    av_channel_layout_default(&outputLayout, 6);
    decoderContext->output_sample_format = AV_SAMPLE_FMT_FLTP;
  } else if (isDtsHdTarget(decoderContext)) {
    // The encoder is always stereo; a mono source stays mono up to the dual-mono copy.
    av_channel_layout_default(&outputLayout, inputLayout.nb_channels >= 2 ? 2 : 1);
    decoderContext->output_sample_format = AV_SAMPLE_FMT_S32;
  } else if (decoderContext->transcode_target == TRANSCODE_TARGET_DTS) {
    if (decoderContext->dts_channels == 0) {
      decoderContext->dts_channels = inputLayout.nb_channels >= 2 ? 2 : 1;
    }
    av_channel_layout_default(&outputLayout, decoderContext->dts_channels);
    decoderContext->output_sample_format = AV_SAMPLE_FMT_S32;
  } else {
    if (!getOutputChannelLayout(decoderContext, &inputLayout, &outputLayout)) {
      av_channel_layout_uninit(&inputLayout);
      LOGE("Unable to resolve output channel layout.");
      return AUDIO_DECODER_ERROR_OTHER;
    }
  }

  int inputSampleRate =
      frame->sample_rate > 0 ? frame->sample_rate : codecContext->sample_rate;
  // Resample hi-res sources down to an AC-3-legal rate before encoding. Once
  // the encoder is open its rate is fixed, so later input-rate changes must
  // keep feeding the encoder's rate rather than re-deriving one.
  int outputSampleRate = inputSampleRate;
  if (decoderContext->transcode_target != TRANSCODE_TARGET_NONE) {
    if (decoderContext->encoder_initialized) {
      outputSampleRate = decoderContext->encoder_context->sample_rate;
    } else if (isDtsTarget(decoderContext)) {
      outputSampleRate = DTS_SAMPLE_RATE;
    } else {
      outputSampleRate = ac3TargetSampleRate(inputSampleRate);
    }
  }
  AVSampleFormat inputSampleFormat = (AVSampleFormat)frame->format;
  bool isDownmixActive = outputLayout.nb_channels < inputLayout.nb_channels;
  bool applyNormalization = isDownmixActive && downmixNormalizationEnabled;
  double centerMixLevel =
      getAdjustedCenterMixLevel(frame, userCenterMixLevelDb, isDownmixActive);

  bool needsReconfigure =
      !decoderContext->resample_context ||
      !decoderContext->has_input_layout ||
      !decoderContext->has_output_layout ||
      !channelLayoutsEqual(&decoderContext->input_layout, &inputLayout) ||
      !channelLayoutsEqual(&decoderContext->output_layout, &outputLayout) ||
      codecContext->sample_fmt != inputSampleFormat ||
      decoderContext->configured_input_sample_rate != inputSampleRate ||
      decoderContext->downmix_normalization_enabled != applyNormalization ||
      (!decoderContext->has_center_mix_level && isDownmixActive) ||
      (decoderContext->has_center_mix_level != isDownmixActive) ||
      (isDownmixActive &&
       fabs(decoderContext->center_mix_level - centerMixLevel) > 0.000001);

  if (!needsReconfigure) {
    av_channel_layout_uninit(&inputLayout);
    av_channel_layout_uninit(&outputLayout);
    return 0;
  }

  // The encoder stays open across a resampler change, so samples the old
  // resampler still holds go to the FIFO first.
  if (decoderContext->encoder_initialized) {
    int flushed = flushResamplerToFifo(decoderContext);
    if (flushed < 0) {
      av_channel_layout_uninit(&inputLayout);
      av_channel_layout_uninit(&outputLayout);
      return flushed;
    }
  }
  clearResampler(decoderContext);

  SwrContext* resampleContext = NULL;
  int result = swr_alloc_set_opts2(&resampleContext, &outputLayout,
                                   decoderContext->output_sample_format,
                                   outputSampleRate, &inputLayout,
                                   inputSampleFormat, inputSampleRate, 0, NULL);
  if (result < 0) {
    logError("swr_alloc_set_opts2", result);
    av_channel_layout_uninit(&inputLayout);
    av_channel_layout_uninit(&outputLayout);
    return result;
  }

  if (isDtsTarget(decoderContext) &&
      inputSampleRate > 0 && inputSampleRate != outputSampleRate) {
    double cutoff = DTS_RESAMPLER_CUTOFF;
    if (inputSampleRate < 44100) {
      cutoff *= inputSampleRate / 44100.0;
    }
    av_opt_set_int(resampleContext, "filter_size", DTS_RESAMPLER_FILTER_SIZE, 0);
    av_opt_set_double(resampleContext, "cutoff", cutoff, 0);
    av_opt_set_double(resampleContext, "kaiser_beta", DTS_RESAMPLER_KAISER_BETA, 0);
  }

  if (isDownmixActive) {
    av_opt_set_double(resampleContext, "center_mix_level", centerMixLevel, 0);
    av_opt_set_double(resampleContext, "lfe_mix_level", LFE_MIX_LEVEL, 0);
    if ((decoderContext->output_sample_format == AV_SAMPLE_FMT_FLT ||
         decoderContext->output_sample_format == AV_SAMPLE_FMT_FLTP) &&
        (inputSampleFormat == AV_SAMPLE_FMT_FLT ||
         inputSampleFormat == AV_SAMPLE_FMT_FLTP) &&
        applyNormalization) {
      av_opt_set_double(resampleContext, "rematrix_maxval", 1.0, 0);
    }
  }

  result = swr_init(resampleContext);
  if (result < 0) {
    logError("swr_init", result);
    swr_free(&resampleContext);
    av_channel_layout_uninit(&inputLayout);
    av_channel_layout_uninit(&outputLayout);
    return result;
  }

  decoderContext->resample_context = resampleContext;
  decoderContext->configured_input_sample_rate = inputSampleRate;
  if (decoderContext->has_input_layout) {
    av_channel_layout_uninit(&decoderContext->input_layout);
  }
  if (decoderContext->has_output_layout) {
    av_channel_layout_uninit(&decoderContext->output_layout);
  }
  copyChannelLayout(&inputLayout, &decoderContext->input_layout);
  copyChannelLayout(&outputLayout, &decoderContext->output_layout);
  decoderContext->has_input_layout = true;
  decoderContext->has_output_layout = true;
  decoderContext->center_mix_level = centerMixLevel;
  decoderContext->downmix_normalization_enabled = applyNormalization;
  decoderContext->has_center_mix_level = isDownmixActive;
  decoderContext->downmix_active = isDownmixActive;
  decoderContext->limiter.sample_rate =
      inputSampleRate > 0 ? static_cast<float>(inputSampleRate)
                          : LIMITER_DEFAULT_SAMPLE_RATE;

  if (decoderContext->transcode_target != TRANSCODE_TARGET_NONE &&
      !decoderContext->encoder_initialized) {
    bool dts = isDtsTarget(decoderContext);
    const AVCodec* encoder = avcodec_find_encoder(dts ? AV_CODEC_ID_DTS : AV_CODEC_ID_AC3);
    if (!encoder) {
      LOGE("%s encoder not found. Check FFmpeg build options.", dts ? "DTS" : "AC3");
      av_channel_layout_uninit(&inputLayout);
      av_channel_layout_uninit(&outputLayout);
      return AUDIO_DECODER_ERROR_OTHER;
    }
    AVCodecContext* enc_ctx = avcodec_alloc_context3(encoder);
    if (!enc_ctx) {
      LOGE("Failed to allocate encoder context.");
      av_channel_layout_uninit(&inputLayout);
      av_channel_layout_uninit(&outputLayout);
      return AUDIO_DECODER_ERROR_OTHER;
    }
    enc_ctx->sample_fmt = decoderContext->output_sample_format;
    enc_ctx->sample_rate = outputSampleRate;
    if (isDtsHdTarget(decoderContext)) {
      av_channel_layout_default(&enc_ctx->ch_layout, 2);
    } else {
      av_channel_layout_copy(&enc_ctx->ch_layout, &outputLayout);
    }
    enc_ctx->bit_rate = dts ? DTS_BIT_RATE : AC3_BIT_RATE;
    if (dts) {
      // FFmpeg flags its DTS encoder experimental; avcodec_open2 refuses it
      // without this ("-strict -2").
      enc_ctx->strict_std_compliance = FF_COMPLIANCE_EXPERIMENTAL;
    }

    int ret = avcodec_open2(enc_ctx, encoder, NULL);
    if (ret < 0) {
      logError("avcodec_open2 (encoder)", ret);
      avcodec_free_context(&enc_ctx);
      av_channel_layout_uninit(&inputLayout);
      av_channel_layout_uninit(&outputLayout);
      return AUDIO_DECODER_ERROR_OTHER;
    }
    decoderContext->encoder_context = enc_ctx;

    decoderContext->fifo = av_audio_fifo_alloc(enc_ctx->sample_fmt, enc_ctx->ch_layout.nb_channels, 1);
    if (!decoderContext->fifo) {
      LOGE("Failed to allocate AVAudioFifo.");
      clearEncoder(decoderContext);
      av_channel_layout_uninit(&inputLayout);
      av_channel_layout_uninit(&outputLayout);
      return AUDIO_DECODER_ERROR_OTHER;
    }

    decoderContext->encoder_frame = av_frame_alloc();
    if (!decoderContext->encoder_frame) {
      LOGE("Failed to allocate encoder frame.");
      clearEncoder(decoderContext);
      av_channel_layout_uninit(&inputLayout);
      av_channel_layout_uninit(&outputLayout);
      return AUDIO_DECODER_ERROR_OTHER;
    }
    decoderContext->encoder_frame->nb_samples = enc_ctx->frame_size;
    decoderContext->encoder_frame->format = enc_ctx->sample_fmt;
    av_channel_layout_copy(&decoderContext->encoder_frame->ch_layout, &enc_ctx->ch_layout);
    ret = av_frame_get_buffer(decoderContext->encoder_frame, 0);
    if (ret < 0) {
      logError("av_frame_get_buffer (encoder)", ret);
      clearEncoder(decoderContext);
      av_channel_layout_uninit(&inputLayout);
      av_channel_layout_uninit(&outputLayout);
      return AUDIO_DECODER_ERROR_OTHER;
    }

    decoderContext->encoder_packet = av_packet_alloc();
    if (!decoderContext->encoder_packet) {
      LOGE("Failed to allocate encoder packet.");
      clearEncoder(decoderContext);
      av_channel_layout_uninit(&inputLayout);
      av_channel_layout_uninit(&outputLayout);
      return AUDIO_DECODER_ERROR_OTHER;
    }
    decoderContext->encoder_initialized = true;
    if (isDtsHdTarget(decoderContext)) {
      LOGI("DTS-HD MA transcode: %d Hz %d ch -> %d Hz 2 ch (%s), core %lld bit/s, XLL 24-bit, "
           "unit %d samples, lookahead 1 unit",
           inputSampleRate, inputLayout.nb_channels, enc_ctx->sample_rate,
           outputLayout.nb_channels == 1 ? "dual mono" : "stereo", (long long)enc_ctx->bit_rate,
           enc_ctx->frame_size);
    } else if (dts) {
      LOGI("DTS transcode: %d Hz %d ch -> %d Hz %d ch, %lld bit/s, frame %d samples, aligned",
           inputSampleRate, inputLayout.nb_channels, enc_ctx->sample_rate,
           enc_ctx->ch_layout.nb_channels, (long long)enc_ctx->bit_rate,
           enc_ctx->frame_size);
    }
  }

  av_channel_layout_uninit(&inputLayout);
  av_channel_layout_uninit(&outputLayout);
  return 0;
}

int transformError(int errorNumber) {
  return errorNumber == AVERROR_INVALIDDATA ? AUDIO_DECODER_ERROR_INVALID_DATA
                                            : AUDIO_DECODER_ERROR_OTHER;
}

void logError(const char* functionName, int errorNumber) {
  char* buffer = (char*)malloc(ERROR_STRING_BUFFER_LENGTH * sizeof(char));
  av_strerror(errorNumber, buffer, ERROR_STRING_BUFFER_LENGTH);
  LOGE("Error in %s: %s", functionName, buffer);
  free(buffer);
}

void clearResampler(DecoderContext* decoderContext) {
  if (!decoderContext) {
    return;
  }
  decoderContext->configured_input_sample_rate = 0;
  if (decoderContext->resample_context) {
    swr_free(&decoderContext->resample_context);
  }
  if (decoderContext->has_input_layout) {
    av_channel_layout_uninit(&decoderContext->input_layout);
    decoderContext->has_input_layout = false;
  }
  if (decoderContext->has_output_layout) {
    av_channel_layout_uninit(&decoderContext->output_layout);
    decoderContext->has_output_layout = false;
  }
  decoderContext->downmix_normalization_enabled = false;
  decoderContext->has_center_mix_level = false;
  decoderContext->downmix_active = false;
}

// Frees the transcode encoder and its FIFO. A reset (seek) or release calls
// this; a resampler change keeps the encoder so queued samples survive.
void clearEncoder(DecoderContext* decoderContext) {
  if (!decoderContext) {
    return;
  }
  if (decoderContext->encoder_context) {
    avcodec_free_context(&decoderContext->encoder_context);
    decoderContext->encoder_context = nullptr;
  }
  if (decoderContext->fifo) {
    av_audio_fifo_free(decoderContext->fifo);
    decoderContext->fifo = nullptr;
  }
  if (decoderContext->encoder_frame) {
    av_frame_free(&decoderContext->encoder_frame);
    decoderContext->encoder_frame = nullptr;
  }
  if (decoderContext->encoder_packet) {
    av_packet_free(&decoderContext->encoder_packet);
    decoderContext->encoder_packet = nullptr;
  }
  decoderContext->encoder_initialized = false;
  decoderContext->encoded_samples = 0;
  decoderContext->dts_packets_seen = 0;
  decoderContext->dts_frames_sent = 0;
  // A seek drops the frame held for the next core packet with the rest of the FIFO.
  dtshd_stage_reset(&decoderContext->hd);
}

// Drops decoded samples from the start of the frame while start_trim_samples
// is positive. Returns false when the whole frame is dropped.
bool trimFrameStart(DecoderContext* decoderContext, AVFrame* frame) {
  int trim = decoderContext->start_trim_samples;
  if (trim >= frame->nb_samples) {
    decoderContext->start_trim_samples -= frame->nb_samples;
    return false;
  }
  AVSampleFormat format = (AVSampleFormat)frame->format;
  int channels = frame->ch_layout.nb_channels;
  bool planar = av_sample_fmt_is_planar(format);
  int planes = planar ? channels : 1;
  int step = trim * av_get_bytes_per_sample(format) * (planar ? 1 : channels);
  for (int plane = 0; plane < planes; plane++) {
    frame->extended_data[plane] += step;
    if (frame->extended_data != frame->data && plane < AV_NUM_DATA_POINTERS) {
      frame->data[plane] += step;
    }
  }
  frame->nb_samples -= trim;
  decoderContext->start_trim_samples = 0;
  return true;
}

void resetLimiter(AudioLimiter* limiter) {
  if (!limiter) {
    return;
  }
  limiter->attenuation = 1.0f;
  limiter->sample_rate = LIMITER_DEFAULT_SAMPLE_RATE;
  limiter->hold_counter = 0;
  limiter->increase = 0.0f;
}

float getLimiterGain(AudioLimiter* limiter, float highestSample) {
  if (!limiter) {
    return 1.0f;
  }

  if (highestSample * limiter->attenuation > 1.0f) {
    limiter->attenuation = 1.0f / highestSample;
    limiter->hold_counter =
        static_cast<int>(lroundf(limiter->sample_rate * LIMITER_HOLD_SECONDS));
    limiter->increase = powf(
        fminf(highestSample, 10000.0f),
        1.0f / (LIMITER_RELEASE_SECONDS * limiter->sample_rate));
  }

  const float gain = limiter->attenuation;
  if (limiter->hold_counter > 0) {
    --limiter->hold_counter;
  } else if (limiter->increase > 0.0f) {
    limiter->attenuation *= limiter->increase;
    if (limiter->attenuation > 1.0f) {
      limiter->increase = 0.0f;
      limiter->attenuation = 1.0f;
    }
  }
  return gain;
}

void limitInterleavedFloat(AudioLimiter* limiter, float* samples,
                           int frameCount, int channelCount,
                           int sampleRate) {
  if (!limiter || !samples || frameCount <= 0 || channelCount <= 0) {
    return;
  }
  if (sampleRate > 0) {
    limiter->sample_rate = static_cast<float>(sampleRate);
  }

  for (int frame = 0; frame < frameCount; ++frame) {
    float* frameSamples = samples + frame * channelCount;
    float highest = 0.0f;
    for (int channel = 0; channel < channelCount; ++channel) {
      highest = fmaxf(highest, fabsf(frameSamples[channel]));
    }
    const float gain = getLimiterGain(limiter, highest);
    if (gain < 1.0f) {
      for (int channel = 0; channel < channelCount; ++channel) {
        frameSamples[channel] *= gain;
      }
    }
  }
}

void limitPlanarFloat(AudioLimiter* limiter, uint8_t** samples,
                      int frameCount, int channelCount, int sampleRate) {
  if (!limiter || !samples || frameCount <= 0 || channelCount <= 0) {
    return;
  }
  if (sampleRate > 0) {
    limiter->sample_rate = static_cast<float>(sampleRate);
  }

  for (int frame = 0; frame < frameCount; ++frame) {
    float highest = 0.0f;
    for (int channel = 0; channel < channelCount; ++channel) {
      float* channelSamples = reinterpret_cast<float*>(samples[channel]);
      highest = fmaxf(highest, fabsf(channelSamples[frame]));
    }
    const float gain = getLimiterGain(limiter, highest);
    if (gain < 1.0f) {
      for (int channel = 0; channel < channelCount; ++channel) {
        float* channelSamples = reinterpret_cast<float*>(samples[channel]);
        channelSamples[frame] *= gain;
      }
    }
  }
}

void releaseContext(DecoderContext* decoderContext) {
  if (!decoderContext) {
    return;
  }
  clearResampler(decoderContext);
  clearEncoder(decoderContext);
  if (decoderContext->requested_output_layout_name) {
    free(decoderContext->requested_output_layout_name);
    decoderContext->requested_output_layout_name = NULL;
  }
  if (decoderContext->codec_context) {
    avcodec_free_context(&decoderContext->codec_context);
  }
  dtshd_stage_free(&decoderContext->hd);
  free(decoderContext);
}

bool copyChannelLayout(const AVChannelLayout* source, AVChannelLayout* destination) {
  if (!source || !destination) {
    return false;
  }
  return av_channel_layout_copy(destination, source) >= 0;
}

bool channelLayoutsEqual(const AVChannelLayout* left, const AVChannelLayout* right) {
  if (!left || !right) {
    return false;
  }
  return av_channel_layout_compare(left, right) == 0;
}

bool getInputChannelLayout(AVCodecContext* codecContext, AVFrame* frame,
                           AVChannelLayout* inputLayout) {
  if (frame->ch_layout.nb_channels > 0) {
    return copyChannelLayout(&frame->ch_layout, inputLayout);
  }
  if (codecContext->ch_layout.nb_channels > 0) {
    return copyChannelLayout(&codecContext->ch_layout, inputLayout);
  }
  int channelCount = codecContext->ch_layout.nb_channels;
  if (channelCount <= 0) {
    return false;
  }
  av_channel_layout_default(inputLayout, channelCount);
  return inputLayout->nb_channels == channelCount;
}

bool getOutputChannelLayout(DecoderContext* decoderContext,
                            const AVChannelLayout* inputLayout,
                            AVChannelLayout* outputLayout) {
  if (decoderContext->requested_output_channel_count > 0 &&
      inputLayout->nb_channels >
          decoderContext->requested_output_channel_count) {
    return applyRequestedOutputLayout(decoderContext, outputLayout);
  }
  return copyChannelLayout(inputLayout, outputLayout);
}

bool applyRequestedOutputLayout(DecoderContext* decoderContext,
                                AVChannelLayout* outputLayout) {
  if (!decoderContext || !outputLayout) {
    return false;
  }

  if (decoderContext->requested_output_layout_name &&
      av_channel_layout_from_string(
          outputLayout, decoderContext->requested_output_layout_name) >= 0) {
    return outputLayout->nb_channels ==
        decoderContext->requested_output_channel_count;
  }

  av_channel_layout_default(outputLayout,
                            decoderContext->requested_output_channel_count);
  return outputLayout->nb_channels ==
      decoderContext->requested_output_channel_count;
}

double getAdjustedCenterMixLevel(AVFrame* frame, jint userCenterMixLevelDb,
                                 bool isDownmixActive) {
  if (!isDownmixActive) {
    return DEFAULT_CENTER_MIX_LEVEL;
  }

  double centerMixLevel = DEFAULT_CENTER_MIX_LEVEL;
  AVFrameSideData* sideData =
      av_frame_get_side_data(frame, AV_FRAME_DATA_DOWNMIX_INFO);
  if (sideData && sideData->size >= sizeof(AVDownmixInfo)) {
    AVDownmixInfo* downmixInfo = (AVDownmixInfo*)sideData->data;
    centerMixLevel = downmixInfo->center_mix_level;
  }

  if (centerMixLevel <= 0.0) {
    return centerMixLevel;
  }

  double currentDb = 20.0 * log10(centerMixLevel);
  return pow(10.0, (currentDb + userCenterMixLevelDb) / 20.0);
}
