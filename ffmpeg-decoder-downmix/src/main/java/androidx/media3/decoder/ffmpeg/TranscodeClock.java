/*
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This modified decoder module is distributed with NuvioTV under GPL-3.0-only.
 * See this module's NOTICE.md for provenance details.
 */
package androidx.media3.decoder.ffmpeg;

import androidx.media3.common.C;

/**
 * Output timestamps for AC-3/DTS transcode buffers.
 *
 * <p>An encoded buffer starts with samples that waited in the encoder FIFO and the resampler, and
 * after a priming trim the first audible sample belongs at the stream start, not at the time of
 * the packet that completed it. So a buffer is stamped with anchor + samples encoded before it,
 * where the anchor is the first input packet after a reset. An input packet more than {@link
 * #DISCONTINUITY_THRESHOLD_US} away from that clock is a real stream discontinuity and becomes the
 * new anchor.
 *
 * <p>A priming trim shortens the output timeline against the input: after trimming {@code T}
 * samples, input packets run {@code T / rate} ahead of the clock for good. The comparison takes
 * that trim off the input time, so a long trim (2112 samples at 8 kHz is 264 ms) is not mistaken
 * for a discontinuity once output starts.
 */
public final class TranscodeClock {

  public static final long DISCONTINUITY_THRESHOLD_US = 200_000;

  /** Input frames per AAC access unit (AAC-LC); a stream starts within one of them. */
  private static final int AAC_FRAME_SAMPLES = 1024;

  private long anchorTimeUs = C.TIME_UNSET;
  private long anchorSamples;
  private long primingTrimUs;

  /** Forgets the anchor and the priming trim; the next input packet sets a new anchor. */
  public void reset() {
    anchorTimeUs = C.TIME_UNSET;
    anchorSamples = 0;
    primingTrimUs = 0;
  }

  /**
   * Notes the priming the decoder drops at the start of the timeline that begins with the next
   * anchor: {@code samples} decoded samples at {@code sampleRate}.
   */
  public void setPrimingTrim(int samples, int sampleRate) {
    primingTrimUs =
        samples > 0 && sampleRate > 0 ? samples * C.MICROS_PER_SECOND / sampleRate : 0;
  }

  /**
   * Returns the time the next output unit will be stamped with, if decoding the packet at {@code
   * inputTimeUs} brings out any, and sets the anchor as {@link #getOutputTimeUs} would. It does not
   * look for discontinuities.
   */
  public long peekOutputTimeUs(long inputTimeUs, long samplesBefore, int sampleRate) {
    if (anchorTimeUs == C.TIME_UNSET) {
      anchorTimeUs = inputTimeUs;
      anchorSamples = samplesBefore;
    }
    return anchorTimeUs + (samplesBefore - anchorSamples) * C.MICROS_PER_SECOND / sampleRate;
  }

  /**
   * Returns the time of the first sample of the output produced while decoding the packet at
   * {@code inputTimeUs}.
   *
   * @param inputTimeUs Time of the packet being decoded.
   * @param samplesBefore Encoded samples sent out since the reset, before this packet's output.
   * @param sampleRate Encoder sample rate, or 0 when the encoder is not open yet.
   */
  public long getOutputTimeUs(long inputTimeUs, long samplesBefore, int sampleRate) {
    if (anchorTimeUs == C.TIME_UNSET) {
      anchorTimeUs = inputTimeUs;
      anchorSamples = samplesBefore;
    }
    if (sampleRate <= 0) {
      return anchorTimeUs;
    }
    long timeUs = anchorTimeUs + (samplesBefore - anchorSamples) * C.MICROS_PER_SECOND / sampleRate;
    // Until the first output after the anchor (a long priming trim produces none), the input
    // runs ahead of the clock by design and is not a discontinuity.
    if (samplesBefore != anchorSamples
        && Math.abs(inputTimeUs - primingTrimUs - timeUs) > DISCONTINUITY_THRESHOLD_US) {
      anchorTimeUs = inputTimeUs;
      anchorSamples = samplesBefore;
      // The new timeline starts at its input packet; the trim belonged to the old one.
      primingTrimUs = 0;
      timeUs = inputTimeUs;
    }
    return timeUs;
  }

  /**
   * Returns whether a packet at {@code inputTimeUs} (renderer time) is the first access unit of
   * its stream, so the encoder delay (priming) at its start is to be dropped.
   */
  public static boolean startsStream(long inputTimeUs, long streamOffsetUs, int inputSampleRate) {
    int rate = inputSampleRate > 0 ? inputSampleRate : 48_000;
    long mediaTimeUs = inputTimeUs - streamOffsetUs;
    return mediaTimeUs <= AAC_FRAME_SAMPLES * C.MICROS_PER_SECOND / rate;
  }
}
