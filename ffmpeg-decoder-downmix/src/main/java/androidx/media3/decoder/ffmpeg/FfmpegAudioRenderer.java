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

import android.os.Handler;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.TraceUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.CryptoConfig;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DecoderAudioRenderer;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.source.MediaSource;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Decodes and renders audio using FFmpeg. */
@UnstableApi
public final class FfmpegAudioRenderer extends DecoderAudioRenderer<FfmpegAudioDecoder> {

  private static final String TAG = "FfmpegAudioRenderer";

  /** The number of input and output buffers. */
  private static final int NUM_BUFFERS = 16;

  /** The default input buffer size. */
  private static final int DEFAULT_INPUT_BUFFER_SIZE = 960 * 6;

  /** DTS core and DTS-HD MA output of "Transcode AAC to DTS": always 48 kHz. */
  public static final int DTS_SAMPLE_RATE = 48_000;

  /**
   * {@link Format#id} of {@link #dtsHdOutputFormat()}. Media3 says a sink plays DTS-HD directly
   * when the output takes only DTS (it then opens the core); the app's sink recognises this id
   * and answers for DTS-HD itself, so the transcode never writes DTS-HD that plays as its core.
   */
  public static final String DTS_HD_TRANSCODE_FORMAT_ID = "nuvio:aac-dts-hd-transcode";

  // xHE-AAC (USAC). FFmpeg 7.0 has no decoder for it.
  private static final String XHE_AAC_CODECS_PREFIX = "mp4a.40.42";

  private volatile int userCenterMixLevelDb;
  @Nullable private volatile String requestedOutputLayoutName;
  private volatile int requestedOutputChannelCount;
  private volatile boolean downmixNormalizationEnabled;
  @Nullable private volatile FfmpegAudioDecoder activeDecoder;
  private volatile boolean rendererEnabled;
  private volatile boolean downmixActive;
  private volatile boolean forceOpticalPassthrough;
  private volatile Set<String> deniedTranscodeMimes = Collections.emptySet();
  private volatile boolean transcodeAacToDts;
  // Off by default: an app that does not know about DTS-HD MA gets today's DTS core transcode.
  private volatile boolean transcodeAacToDtsHd;
  private volatile int dtsTranscodeTargetOverride = DtsTranscodeTargets.AUTO;

  public FfmpegAudioRenderer() {
    this(/* eventHandler= */ null, /* eventListener= */ null);
  }

  /**
   * Creates a new instance.
   *
   * @param eventHandler A handler to use when delivering events to {@code eventListener}. May be
   *     null if delivery of events is not required.
   * @param eventListener A listener of events. May be null if delivery of events is not required.
   * @param audioProcessors Optional {@link AudioProcessor}s that will process audio before output.
   */
  public FfmpegAudioRenderer(
      @Nullable Handler eventHandler,
      @Nullable AudioRendererEventListener eventListener,
      AudioProcessor... audioProcessors) {
    this(
        eventHandler,
        eventListener,
        new DefaultAudioSink.Builder().setAudioProcessors(audioProcessors).build());
  }

  /**
   * Creates a new instance.
   *
   * @param eventHandler A handler to use when delivering events to {@code eventListener}. May be
   *     null if delivery of events is not required.
   * @param eventListener A listener of events. May be null if delivery of events is not required.
   * @param audioSink The sink to which audio will be output.
   */
  public FfmpegAudioRenderer(
      @Nullable Handler eventHandler,
      @Nullable AudioRendererEventListener eventListener,
      AudioSink audioSink) {
    super(eventHandler, eventListener, audioSink);
  }

  @Override
  public String getName() {
    return TAG;
  }

  @Override
  protected void onEnabled(boolean joining, boolean mayRenderStartOfStream)
      throws ExoPlaybackException {
    super.onEnabled(joining, mayRenderStartOfStream);
    rendererEnabled = true;
  }

  @Override
  protected void onDisabled() {
    try {
      super.onDisabled();
    } finally {
      rendererEnabled = false;
      downmixActive = false;
      activeDecoder = null;
    }
  }

  @Override
  protected @C.FormatSupport int supportsFormatInternal(Format format) {
    String mimeType = checkNotNull(format.sampleMimeType);
    if (!FfmpegLibrary.isAvailable() || !MimeTypes.isAudio(mimeType)) {
      return C.FORMAT_UNSUPPORTED_TYPE;
    }
    if (!FfmpegLibrary.supportsFormat(mimeType)) {
      return C.FORMAT_UNSUPPORTED_SUBTYPE;
    }
    if (forceOpticalPassthrough && MimeTypes.AUDIO_AC3.equals(mimeType)) {
      return C.FORMAT_UNSUPPORTED_SUBTYPE;
    }
    boolean transcodeToDts = dtsTranscodeTarget(format) != DtsTranscodeTargets.NONE;
    boolean transcodeToAc3 = !transcodeToDts && shouldTranscodeToAc3(format, mimeType);

    if (!transcodeToDts && !transcodeToAc3
        && (format.channelCount <= 0 || format.sampleRate <= 0)) {
      return format.cryptoType == C.CRYPTO_TYPE_NONE
          ? C.FORMAT_HANDLED
          : C.FORMAT_UNSUPPORTED_DRM;
    }
    boolean supportsConfiguredOutput;
    if (transcodeToDts) {
      supportsConfiguredOutput = true;
    } else if (transcodeToAc3) {
      supportsConfiguredOutput = sinkSupportsFormat(ac3OutputFormat(format));
    } else {
      int outputChannelCount = resolveOutputChannelCount(format.channelCount);
      boolean shouldRequestDownmix = shouldRequestDownmix(format.channelCount, outputChannelCount);
      @C.PcmEncoding int outputEncoding =
          shouldRequestDownmix ? C.ENCODING_PCM_FLOAT : C.ENCODING_PCM_16BIT;
      supportsConfiguredOutput = sinkSupportsFormat(format, outputEncoding, outputChannelCount);
    }
    if (!supportsConfiguredOutput) {
      return C.FORMAT_UNSUPPORTED_SUBTYPE;
    }
    if (format.cryptoType != C.CRYPTO_TYPE_NONE) {
      return C.FORMAT_UNSUPPORTED_DRM;
    }
    return C.FORMAT_HANDLED;
  }

  @Override
  public @AdaptiveSupport int supportsMixedMimeTypeAdaptation() {
    return ADAPTIVE_NOT_SEAMLESS;
  }

  @Override
  protected FfmpegAudioDecoder createDecoder(Format format, @Nullable CryptoConfig cryptoConfig)
      throws FfmpegDecoderException {
    TraceUtil.beginSection("createFfmpegAudioDecoder");
    String mimeType = checkNotNull(format.sampleMimeType);
    int dtsTarget = dtsTranscodeTarget(format);
    boolean transcodeToDts = dtsTarget != DtsTranscodeTargets.NONE;
    boolean transcodeToAc3 = !transcodeToDts && shouldTranscodeToAc3(format, mimeType);
    int initialInputBufferSize =
        format.maxInputSize != Format.NO_VALUE ? format.maxInputSize : DEFAULT_INPUT_BUFFER_SIZE;
    @C.PcmEncoding int outputEncoding;
    int nativeOutputChannelCount;
    @Nullable String outputLayoutName;
    if (transcodeToDts) {
      outputEncoding =
          dtsTarget == DtsTranscodeTargets.HD ? C.ENCODING_DTS_HD : C.ENCODING_DTS;
      nativeOutputChannelCount = 0;
      outputLayoutName = null;
      downmixActive = false;
    } else if (transcodeToAc3) {
      outputEncoding = C.ENCODING_AC3;
      nativeOutputChannelCount = 6;
      outputLayoutName = "5.1";
      downmixActive = false;
    } else {
      int outputChannelCount = resolveOutputChannelCount(format.channelCount);
      boolean shouldRequestDownmix = shouldRequestDownmix(format.channelCount, outputChannelCount);
      outputEncoding = shouldRequestDownmix ? C.ENCODING_PCM_FLOAT : C.ENCODING_PCM_16BIT;
      outputLayoutName = shouldRequestDownmix ? requestedOutputLayoutName : null;
      nativeOutputChannelCount = shouldRequestDownmix ? outputChannelCount : 0;
      downmixActive = shouldRequestDownmix;
    }
    FfmpegAudioDecoder decoder =
        new FfmpegAudioDecoder(
            format,
            NUM_BUFFERS,
            NUM_BUFFERS,
            initialInputBufferSize,
            nativeOutputChannelCount,
            outputLayoutName,
            outputEncoding);
    decoder.setUserCenterMixLevelDb(userCenterMixLevelDb);
    decoder.setDownmixNormalizationEnabled(downmixNormalizationEnabled);
    decoder.setStreamOffsetUs(getStreamOffsetUs());
    activeDecoder = decoder;
    TraceUtil.endSection();
    return decoder;
  }

  @Override
  protected Format getOutputFormat(FfmpegAudioDecoder decoder) {
    checkNotNull(decoder);
    int encoding = decoder.getEncoding();
    if (encoding == C.ENCODING_DTS_HD) {
      // Always stereo: a mono source is written as dual mono. No codecs string: Media3 maps
      // audio/vnd.dts.hd to ENCODING_DTS_HD whatever it says. Marked like the query, so the
      // app's sink knows the transcode from a real DTS-HD track when it configures.
      return dtsHdOutputFormat().buildUpon().setSampleRate(decoder.getSampleRate()).build();
    }
    if (encoding == C.ENCODING_DTS) {
      // A mono DTS bitstream still opens a stereo AudioTrack: passthrough bytes are opaque to the
      // track, and a stereo mask is one every HDMI DTS profile lists.
      return new Format.Builder()
          .setSampleMimeType(MimeTypes.AUDIO_DTS)
          .setChannelCount(2)
          .setSampleRate(decoder.getSampleRate())
          .build();
    }
    if (encoding == C.ENCODING_AC3) {
      return new Format.Builder()
          .setSampleMimeType(MimeTypes.AUDIO_AC3)
          .setChannelCount(decoder.getChannelCount())
          .setSampleRate(decoder.getSampleRate())
          .build();
    }
    return new Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setChannelCount(decoder.getChannelCount())
        .setSampleRate(decoder.getSampleRate())
        .setPcmEncoding(encoding)
        .build();
  }

  /** Sets the center-mix offset in dB relative to FFmpeg metadata or the default (-3 dB). */
  public void setCenterMixLevelDb(int centerMixLevelDb) {
    userCenterMixLevelDb = centerMixLevelDb;
    @Nullable FfmpegAudioDecoder decoder = activeDecoder;
    if (decoder != null) {
      decoder.setUserCenterMixLevelDb(centerMixLevelDb);
    }
  }

  /** Sets the desired FFmpeg output layout used for explicit downmix decisions. */
  public void setAudioOutputChannels(@Nullable String outputLayoutName, int outputChannelCount) {
    requestedOutputLayoutName = outputLayoutName;
    requestedOutputChannelCount = outputChannelCount;
  }

  /** Sets whether downmix normalization should be enabled. */
  public void setDownmixNormalizationEnabled(boolean downmixNormalizationEnabled) {
    this.downmixNormalizationEnabled = downmixNormalizationEnabled;
    @Nullable FfmpegAudioDecoder decoder = activeDecoder;
    if (decoder != null) {
      decoder.setDownmixNormalizationEnabled(downmixNormalizationEnabled);
    }
  }

  @Override
  protected void onStreamChanged(
      Format[] formats,
      long startPositionUs,
      long offsetUs,
      MediaSource.MediaPeriodId mediaPeriodId)
      throws ExoPlaybackException {
    super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId);
    @Nullable FfmpegAudioDecoder decoder = activeDecoder;
    if (decoder != null) {
      decoder.setStreamOffsetUs(offsetUs);
    }
  }

  public void setForceOpticalPassthrough(boolean enabled) {
    this.forceOpticalPassthrough = enabled;
  }

  /**
   * Sets whether mono and stereo AAC is encoded to DTS (48 kHz) when the sink can play DTS: DTS-HD
   * Master Audio when {@link #setTranscodeAacToDtsHd} allows it and the sink plays DTS-HD, else
   * the DTS core at 1509 kbit/s.
   */
  public void setTranscodeAacToDts(boolean enabled) {
    this.transcodeAacToDts = enabled;
  }

  /**
   * Sets whether the transcode may write DTS-HD Master Audio (the DTS core plus a lossless 24-bit
   * XLL; mono as dual mono). Off by default. The app turns it off for a stream whose DTS-HD
   * output was refused, so the next attempt writes the core.
   */
  public void setTranscodeAacToDtsHd(boolean allowed) {
    this.transcodeAacToDtsHd = allowed;
  }

  /**
   * Forces the transcode target for testing: {@link DtsTranscodeTargets#CORE}, {@link
   * DtsTranscodeTargets#HD} (skips the DTS-HD capability check, still needs DTS), or {@link
   * DtsTranscodeTargets#AUTO}.
   */
  public void setDtsTranscodeTargetOverride(int target) {
    this.dtsTranscodeTargetOverride = target;
  }

  public void setDeniedTranscodeMimes(@Nullable Set<String> mimeTypes) {
    deniedTranscodeMimes =
        mimeTypes == null || mimeTypes.isEmpty()
            ? Collections.<String>emptySet()
            : Collections.unmodifiableSet(new HashSet<>(mimeTypes));
  }

  /** Returns whether this renderer is the active playback path for FFmpeg downmix + center mix. */
  public boolean isCenterMixActive() {
    return rendererEnabled && activeDecoder != null && downmixActive;
  }

  /** Returns whether this renderer is the active playback path for FFmpeg audio decoding. */
  public boolean isAudioPathActive() {
    return rendererEnabled && activeDecoder != null;
  }

  // Read activeDecoder once: other threads call these while onDisabled() clears it.
  public boolean isTranscodingToAc3() {
    @Nullable FfmpegAudioDecoder decoder = activeDecoder;
    return rendererEnabled && decoder != null && decoder.getEncoding() == C.ENCODING_AC3;
  }

  /** Whether the active decoder transcodes to DTS: the core or DTS-HD MA. */
  public boolean isTranscodingToDts() {
    return getDtsTranscodeTarget() != DtsTranscodeTargets.NONE;
  }

  public boolean isTranscodingToDtsHd() {
    return getDtsTranscodeTarget() == DtsTranscodeTargets.HD;
  }

  /** The active decoder's {@link DtsTranscodeTargets} value. */
  public int getDtsTranscodeTarget() {
    @Nullable FfmpegAudioDecoder decoder = activeDecoder;
    if (!rendererEnabled || decoder == null) {
      return DtsTranscodeTargets.NONE;
    }
    switch (decoder.getEncoding()) {
      case C.ENCODING_DTS_HD:
        return DtsTranscodeTargets.HD;
      case C.ENCODING_DTS:
        return DtsTranscodeTargets.CORE;
      default:
        return DtsTranscodeTargets.NONE;
    }
  }

  /**
   * Channels in the DTS bitstream being written: 1 or 2 for the core, 2 for DTS-HD MA (dual mono
   * for a mono source), 0 when not transcoding to DTS.
   */
  public int getDtsTranscodeChannelCount() {
    @Nullable FfmpegAudioDecoder decoder = activeDecoder;
    if (!rendererEnabled || decoder == null) {
      return 0;
    }
    int encoding = decoder.getEncoding();
    return encoding == C.ENCODING_DTS || encoding == C.ENCODING_DTS_HD
        ? decoder.getChannelCount()
        : 0;
  }

  /** Mono or stereo AAC that the DTS transcode accepts. */
  public static boolean isDtsTranscodeSource(Format format) {
    return MimeTypes.AUDIO_AAC.equals(format.sampleMimeType)
        && format.channelCount >= 1
        && format.channelCount <= 2
        && format.cryptoType == C.CRYPTO_TYPE_NONE
        && (format.codecs == null || !format.codecs.startsWith(XHE_AAC_CODECS_PREFIX));
  }

  /** The sink format of the DTS transcode. */
  public static Format dtsOutputFormat() {
    return new Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_DTS)
        .setChannelCount(2)
        .setSampleRate(DTS_SAMPLE_RATE)
        .build();
  }

  /** The sink format of the DTS-HD MA transcode, marked with {@link #DTS_HD_TRANSCODE_FORMAT_ID}. */
  public static Format dtsHdOutputFormat() {
    return new Format.Builder()
        .setId(DTS_HD_TRANSCODE_FORMAT_ID)
        .setSampleMimeType(MimeTypes.AUDIO_DTS_HD)
        .setChannelCount(2)
        .setSampleRate(DTS_SAMPLE_RATE)
        .build();
  }

  /**
   * Returns whether the renderer's {@link AudioSink} supports the PCM format that will be output
   * from the decoder for the given input format and requested output encoding.
   */
  private boolean sinkSupportsFormat(
      Format inputFormat, @C.PcmEncoding int pcmEncoding, int channelCount) {
    if (channelCount <= 0 || inputFormat.sampleRate <= 0) {
      return false;
    }
    return sinkSupportsFormat(Util.getPcmFormat(pcmEncoding, channelCount, inputFormat.sampleRate));
  }

  // getSinkFormatSupport, not sinkSupportsFormat: the app's sink applies Bluetooth, speed and the
  // passthrough policy in getFormatSupport only, and MediaCodec's decline asks the same question.
  private int dtsTranscodeTarget(Format format) {
    return DtsTranscodeTargets.choose(
        transcodeAacToDts,
        isDtsTranscodeSource(format),
        transcodeAacToDtsHd,
        dtsTranscodeTargetOverride,
        () -> getSinkFormatSupport(dtsHdOutputFormat()) == AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY,
        () -> getSinkFormatSupport(dtsOutputFormat()) == AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY);
  }

  private boolean shouldTranscodeToAc3(Format format, String mimeType) {
    if (MimeTypes.AUDIO_AC3.equals(mimeType)) {
      return false;
    }
    boolean isDtsOrTrueHd = MimeTypes.AUDIO_DTS.equals(mimeType)
        || MimeTypes.AUDIO_DTS_HD.equals(mimeType)
        || MimeTypes.AUDIO_TRUEHD.equals(mimeType);
    boolean eligible = format.channelCount > 2 || format.channelCount <= 0 || isDtsOrTrueHd;
    if (!eligible) {
      return false;
    }
    if (forceOpticalPassthrough) {
      return true;
    }
    return deniedTranscodeMimes.contains(mimeType) && sinkSupportsFormat(ac3OutputFormat(format));
  }

  // Must stay in step with ac3TargetSampleRate in ffmpeg_jni.cc.
  public static int ac3SampleRate(int inputSampleRate) {
    if (inputSampleRate == 32000 || inputSampleRate == 44100 || inputSampleRate == 48000) {
      return inputSampleRate;
    }
    return inputSampleRate > 0 && inputSampleRate % 44100 == 0 ? 44100 : 48000;
  }

  public static int ac3TrailingSilenceSamples(int queuedSamples, int frameSize) {
    if (frameSize <= 0 || queuedSamples <= 0) {
      return 0;
    }
    int remainder = queuedSamples % frameSize;
    if (remainder == 0) {
      return 0;
    }
    return frameSize - remainder;
  }

  public static long ac3FrameDurationUs(int frameSamples, int sampleRate) {
    if (frameSamples <= 0 || sampleRate <= 0) {
      return 0L;
    }
    return frameSamples * 1_000_000L / sampleRate;
  }

  private static Format ac3OutputFormat(Format inputFormat) {
    return new Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_AC3)
        .setChannelCount(6)
        .setSampleRate(ac3SampleRate(inputFormat.sampleRate))
        .build();
  }

  private int resolveOutputChannelCount(int inputChannelCount) {
    if (inputChannelCount <= 0) {
      return inputChannelCount;
    }
    int configuredChannelCount = requestedOutputChannelCount;
    if (configuredChannelCount <= 0 || configuredChannelCount >= inputChannelCount) {
      return inputChannelCount;
    }
    return configuredChannelCount;
  }

  private boolean shouldRequestDownmix(int inputChannelCount, int outputChannelCount) {
    return requestedOutputChannelCount > 0 && outputChannelCount < inputChannelCount;
  }
}
