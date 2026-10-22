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
package androidx.media3.decoder.ffmpeg;

import static com.google.common.base.Preconditions.checkNotNull;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoder;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.LongPredicate;

/** FFmpeg audio decoder with optional float output for active FFmpeg downmixing. */
/* package */ final class FfmpegAudioDecoder
    extends SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, FfmpegDecoderException> {

  private static final int INITIAL_OUTPUT_BUFFER_SIZE = 65535 * 2;
  /** Samples per DTS (and DTS-HD MA) unit of the transcode, at 48 kHz. */
  private static final int DTS_UNIT_SAMPLES = 512;
  /** Rounding slack when comparing unit times with the output start: under one 48 kHz sample. */
  private static final long ONE_SAMPLE_US = 20;
  /** More units than one decode call can bring out; the input itself is skipped beyond that. */
  private static final int MAX_SKIPPED_UNITS = 64;

  // LINT.IfChange
  private static final int AUDIO_DECODER_ERROR_INVALID_DATA = -1;
  private static final int AUDIO_DECODER_ERROR_OTHER = -2;
  private static final int TRANSCODE_TARGET_NONE = 0;
  private static final int TRANSCODE_TARGET_AC3 = 1;
  private static final int TRANSCODE_TARGET_DTS = 2;
  private static final int TRANSCODE_TARGET_DTS_HD = 3;
  // LINT.ThenChange(../../../../../jni/ffmpeg_jni.cc)

  // FLAC parsing constants
  private static final byte[] flacStreamMarker = {'f', 'L', 'a', 'C'};
  private static final int FLAC_METADATA_TYPE_STREAM_INFO = 0;
  private static final int FLAC_METADATA_BLOCK_HEADER_SIZE = 4;
  private static final int FLAC_STREAM_INFO_DATA_SIZE = 34;

  private final String codecName;
  @Nullable private final byte[] extraData;
  private final @C.Encoding int outputEncoding;
  private final boolean transcoding;
  private final boolean transcodingDts;
  private final int encoderDelay;
  private final int inputSampleRate;
  private final TranscodeClock transcodeClock = new TranscodeClock();
  private volatile long streamOffsetUs;
  private boolean startTrimPending = true;
  private volatile int userCenterMixLevelDb;
  private volatile boolean downmixNormalizationEnabled;
  private int outputBufferSize;

  private long nativeContext; // May be reassigned on resetting the codec.
  private boolean hasOutputFormat;
  private volatile int channelCount;
  private volatile int sampleRate;

  public FfmpegAudioDecoder(
      Format format,
      int numInputBuffers,
      int numOutputBuffers,
      int initialInputBufferSize,
      int outputChannelCount,
      @Nullable String requestedOutputLayoutName,
      @C.Encoding int outputEncoding)
      throws FfmpegDecoderException {
    super(new DecoderInputBuffer[numInputBuffers], new SimpleDecoderOutputBuffer[numOutputBuffers]);
    if (!FfmpegLibrary.isAvailable()) {
      throw new FfmpegDecoderException("Failed to load decoder native libraries.");
    }
    checkNotNull(format.sampleMimeType);
    codecName = checkNotNull(FfmpegLibrary.getCodecName(format.sampleMimeType));
    extraData = getExtraData(format.sampleMimeType, format.initializationData);
    this.outputEncoding = outputEncoding;
    transcodingDts = outputEncoding == C.ENCODING_DTS || outputEncoding == C.ENCODING_DTS_HD;
    transcoding = outputEncoding == C.ENCODING_AC3 || transcodingDts;
    encoderDelay = format.encoderDelay;
    inputSampleRate = format.sampleRate;
    outputBufferSize = INITIAL_OUTPUT_BUFFER_SIZE;
    nativeContext =
        ffmpegInitialize(
            codecName,
            extraData,
            format.sampleRate,
            format.channelCount,
            outputChannelCount,
            requestedOutputLayoutName,
            outputEncoding == C.ENCODING_PCM_FLOAT,
            transcodeTarget(outputEncoding));
    if (nativeContext == 0) {
      throw new FfmpegDecoderException("Initialization failed.");
    }
    setInitialInputBufferSize(initialInputBufferSize);
  }

  @Override
  public String getName() {
    return "ffmpeg" + FfmpegLibrary.getVersion() + "-" + codecName;
  }

  @Override
  protected DecoderInputBuffer createInputBuffer() {
    return new DecoderInputBuffer(
        DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT,
        FfmpegLibrary.getInputBufferPaddingSize());
  }

  @Override
  protected SimpleDecoderOutputBuffer createOutputBuffer() {
    return new SimpleDecoderOutputBuffer(this::releaseOutputBuffer);
  }

  @Override
  protected FfmpegDecoderException createUnexpectedDecodeException(Throwable error) {
    return new FfmpegDecoderException("Unexpected decode error", error);
  }

  @Override
  @Nullable
  protected FfmpegDecoderException decode(
      DecoderInputBuffer inputBuffer, SimpleDecoderOutputBuffer outputBuffer, boolean reset) {
    if (reset) {
      nativeContext = ffmpegReset(nativeContext, extraData);
      if (nativeContext == 0) {
        return new FfmpegDecoderException("Error resetting (see logcat).");
      }
      transcodeClock.reset();
      startTrimPending = true;
    }
    long encodedSamplesBefore = 0;
    if (transcoding) {
      // Bitstream output skips the sink's PCM trimming, so the AAC priming is dropped here, but
      // only when decoding starts at the stream start (not on a resume or track switch).
      if (startTrimPending) {
        startTrimPending = false;
        if (encoderDelay > 0
            && TranscodeClock.startsStream(inputBuffer.timeUs, streamOffsetUs, inputSampleRate)) {
          ffmpegSetStartTrim(nativeContext, encoderDelay);
          transcodeClock.setPrimingTrim(encoderDelay, inputSampleRate);
        }
      }
      encodedSamplesBefore = ffmpegGetEncodedSamples(nativeContext);
    }
    int skipUnits = 0;
    if (transcodingDts && !outputBuffer.shouldBeSkipped) {
      // SimpleDecoder skips output by the input time, but a transcode buffer starts with audio
      // that waited in the FIFO and the lookahead. After a seek the first packet at or after the
      // start position can still bring out units from before it, so they are dropped by their own
      // time on the output timeline.
      skipUnits =
          unitsBeforeOutputStart(
              transcodeClock.peekOutputTimeUs(
                  inputBuffer.timeUs, encodedSamplesBefore, FfmpegAudioRenderer.DTS_SAMPLE_RATE));
    }
    ByteBuffer inputData = Util.castNonNull(inputBuffer.data);
    int inputSize = inputData.limit();
    ByteBuffer outputData = outputBuffer.init(inputBuffer.timeUs, outputBufferSize);
    // SimpleDecoder never hands the end-of-stream buffer to decode(), so a transcode flushes its
    // decoder, resampler and encoder FIFO with the packet flagged as the stream's last sample.
    boolean endOfStream =
        inputBuffer.isEndOfStream() || (transcoding && inputBuffer.isLastSample());
    int result =
        ffmpegDecode(
            nativeContext,
            inputData,
            inputSize,
            outputBuffer,
            outputData,
            outputBufferSize,
            userCenterMixLevelDb,
            downmixNormalizationEnabled,
            endOfStream,
            skipUnits);
    if (transcoding) {
      outputBuffer.timeUs =
          transcodeClock.getOutputTimeUs(
              inputBuffer.timeUs, encodedSamplesBefore, ffmpegGetSampleRate(nativeContext));
      if (skipUnits > 0) {
        long emitted = (ffmpegGetEncodedSamples(nativeContext) - encodedSamplesBefore) / DTS_UNIT_SAMPLES;
        outputBuffer.timeUs += unitTimeUs(Math.min(skipUnits, emitted));
      }
    }
    if (result == AUDIO_DECODER_ERROR_OTHER) {
      return new FfmpegDecoderException("Error decoding (see logcat).");
    } else if (result == AUDIO_DECODER_ERROR_INVALID_DATA) {
      // Treat invalid data errors as non-fatal to match the behavior of MediaCodec. No output will
      // be produced for this buffer, so mark it as skipped to ensure that the audio sink's
      // position is reset when more audio is produced.
      outputBuffer.shouldBeSkipped = true;
      return null;
    } else if (result == 0) {
      // There's no need to output empty buffers.
      outputBuffer.shouldBeSkipped = true;
      return null;
    }
    if (!hasOutputFormat) {
      channelCount = ffmpegGetChannelCount(nativeContext);
      sampleRate = ffmpegGetSampleRate(nativeContext);
      if (sampleRate == 0 && "alac".equals(codecName)) {
        checkNotNull(extraData);
        // ALAC decoder did not set the sample rate in earlier versions of FFmpeg. See
        // https://trac.ffmpeg.org/ticket/6096.
        ParsableByteArray parsableExtraData = new ParsableByteArray(extraData);
        parsableExtraData.setPosition(extraData.length - 4);
        sampleRate = parsableExtraData.readUnsignedIntToInt();
      }
      hasOutputFormat = true;
    }
    // Get a new reference to the output ByteBuffer in case the native decode method reallocated the
    // buffer to grow its size.
    outputData = checkNotNull(outputBuffer.data);
    outputData.position(0);
    outputData.limit(result);
    return null;
  }

  /**
   * Returns how many units, the first starting at {@code firstUnitTimeUs}, start before the output
   * start time (a seek target). A unit less than one sample early counts as on time.
   */
  private int unitsBeforeOutputStart(long firstUnitTimeUs) {
    return unitsBefore(firstUnitTimeUs, this::isAtLeastOutputStartTimeUs);
  }

  /* package */ static int unitsBefore(long firstUnitTimeUs, LongPredicate atLeastOutputStart) {
    int units = 0;
    while (units < MAX_SKIPPED_UNITS
        && !atLeastOutputStart.test(firstUnitTimeUs + unitTimeUs(units) + ONE_SAMPLE_US)) {
      units++;
    }
    return units;
  }

  /* package */ static long unitTimeUs(long units) {
    return units * DTS_UNIT_SAMPLES * C.MICROS_PER_SECOND / FfmpegAudioRenderer.DTS_SAMPLE_RATE;
  }

  // Called from native code
  @SuppressWarnings("unused")
  private ByteBuffer growOutputBuffer(SimpleDecoderOutputBuffer outputBuffer, int requiredSize) {
    // Use it for new buffer so that hopefully we won't need to reallocate again
    outputBufferSize = requiredSize;
    return outputBuffer.grow(requiredSize);
  }

  @Override
  public void release() {
    super.release();
    ffmpegRelease(nativeContext);
    nativeContext = 0;
  }

  /** Returns the channel count of output audio. */
  public int getChannelCount() {
    return channelCount;
  }

  /** Returns the sample rate of output audio. */
  public int getSampleRate() {
    return sampleRate;
  }

  /** Returns the encoding of output audio. */
  public @C.Encoding int getEncoding() {
    return outputEncoding;
  }

  /** Sets the renderer's stream offset, which turns input times into media times. */
  public void setStreamOffsetUs(long streamOffsetUs) {
    this.streamOffsetUs = streamOffsetUs;
  }

  private static int transcodeTarget(@C.Encoding int outputEncoding) {
    switch (outputEncoding) {
      case C.ENCODING_AC3:
        return TRANSCODE_TARGET_AC3;
      case C.ENCODING_DTS:
        return TRANSCODE_TARGET_DTS;
      default:
        return TRANSCODE_TARGET_NONE;
    }
  }

  /** Sets the center-mix offset in dB relative to stream metadata or the default (-3 dB). */
  public void setUserCenterMixLevelDb(int userCenterMixLevelDb) {
    this.userCenterMixLevelDb = userCenterMixLevelDb;
  }

  /** Enables or disables downmix normalization. */
  public void setDownmixNormalizationEnabled(boolean downmixNormalizationEnabled) {
    this.downmixNormalizationEnabled = downmixNormalizationEnabled;
  }

  /**
   * Returns FFmpeg-compatible codec-specific initialization data ("extra data"), or {@code null} if
   * not required.
   */
  @Nullable
  private static byte[] getExtraData(String mimeType, List<byte[]> initializationData) {
    switch (mimeType) {
      case MimeTypes.AUDIO_AAC:
      case MimeTypes.AUDIO_OPUS:
        return initializationData.get(0);
      case MimeTypes.AUDIO_ALAC:
        return getAlacExtraData(initializationData);
      case MimeTypes.AUDIO_VORBIS:
        return getVorbisExtraData(initializationData);
      case MimeTypes.AUDIO_FLAC:
        return getFlacExtraData(initializationData);
      default:
        // Other codecs do not require extra data.
        return null;
    }
  }

  private static byte[] getAlacExtraData(List<byte[]> initializationData) {
    // FFmpeg's ALAC decoder expects an ALAC atom, which contains the ALAC "magic cookie", as extra
    // data. initializationData[0] contains only the magic cookie, and so we need to package it into
    // an ALAC atom. See:
    // https://ffmpeg.org/doxygen/0.6/alac_8c.html
    // https://github.com/macosforge/alac/blob/master/ALACMagicCookieDescription.txt
    byte[] magicCookie = initializationData.get(0);
    int alacAtomLength = 12 + magicCookie.length;
    ByteBuffer alacAtom = ByteBuffer.allocate(alacAtomLength);
    alacAtom.putInt(alacAtomLength);
    alacAtom.putInt(0x616c6163); // type=alac
    alacAtom.putInt(0); // version=0, flags=0
    alacAtom.put(magicCookie, /* offset= */ 0, magicCookie.length);
    return alacAtom.array();
  }

  private static byte[] getVorbisExtraData(List<byte[]> initializationData) {
    byte[] header0 = initializationData.get(0);
    byte[] header1 = initializationData.get(1);
    byte[] extraData = new byte[header0.length + header1.length + 6];
    extraData[0] = (byte) (header0.length >> 8);
    extraData[1] = (byte) (header0.length & 0xFF);
    System.arraycopy(header0, 0, extraData, 2, header0.length);
    extraData[header0.length + 2] = 0;
    extraData[header0.length + 3] = 0;
    extraData[header0.length + 4] = (byte) (header1.length >> 8);
    extraData[header0.length + 5] = (byte) (header1.length & 0xFF);
    System.arraycopy(header1, 0, extraData, header0.length + 6, header1.length);
    return extraData;
  }

  @Nullable
  private static byte[] getFlacExtraData(List<byte[]> initializationData) {
    for (int i = 0; i < initializationData.size(); i++) {
      byte[] out = extractFlacStreamInfo(initializationData.get(i));
      if (out != null) {
        return out;
      }
    }
    return null;
  }

  @Nullable
  private static byte[] extractFlacStreamInfo(byte[] data) {
    int offset = 0;
    if (arrayStartsWith(data, flacStreamMarker)) {
      offset = flacStreamMarker.length;
    }

    if (data.length - offset == FLAC_STREAM_INFO_DATA_SIZE) {
      byte[] streamInfo = new byte[FLAC_STREAM_INFO_DATA_SIZE];
      System.arraycopy(data, offset, streamInfo, 0, FLAC_STREAM_INFO_DATA_SIZE);
      return streamInfo;
    }

    if (data.length >= offset + FLAC_METADATA_BLOCK_HEADER_SIZE) {
      int type = data[offset] & 0x7F;
      int length =
          ((data[offset + 1] & 0xFF) << 16)
              | ((data[offset + 2] & 0xFF) << 8)
              | (data[offset + 3] & 0xFF);

      if (type == FLAC_METADATA_TYPE_STREAM_INFO
          && length == FLAC_STREAM_INFO_DATA_SIZE
          && data.length >= offset + FLAC_METADATA_BLOCK_HEADER_SIZE + FLAC_STREAM_INFO_DATA_SIZE) {
        byte[] streamInfo = new byte[FLAC_STREAM_INFO_DATA_SIZE];
        System.arraycopy(
            data,
            offset + FLAC_METADATA_BLOCK_HEADER_SIZE,
            streamInfo,
            0,
            FLAC_STREAM_INFO_DATA_SIZE);
        return streamInfo;
      }
    }

    return null;
  }

  private static boolean arrayStartsWith(byte[] data, byte[] prefix) {
    if (data.length < prefix.length) {
      return false;
    }
    for (int i = 0; i < prefix.length; i++) {
      if (data[i] != prefix[i]) {
        return false;
      }
    }
    return true;
  }

  private native long ffmpegInitialize(
      String codecName,
      @Nullable byte[] extraData,
      int rawSampleRate,
      int rawChannelCount,
      int outputChannelCount,
      @Nullable String requestedOutputLayoutName,
      boolean outputFloat,
      int transcodeTarget);

  private native int ffmpegDecode(
      long context,
      ByteBuffer inputData,
      int inputSize,
      SimpleDecoderOutputBuffer decoderOutputBuffer,
      ByteBuffer outputData,
      int outputSize,
      int userCenterMixLevelDb,
      boolean downmixNormalizationEnabled,
      boolean endOfStream,
      int skipUnits);

  private native int ffmpegGetChannelCount(long context);

  private native int ffmpegGetSampleRate(long context);

  private native long ffmpegReset(long context, @Nullable byte[] extraData);

  private native void ffmpegSetStartTrim(long context, int samples);

  private native long ffmpegGetEncodedSamples(long context);

  private native void ffmpegRelease(long context);
}
