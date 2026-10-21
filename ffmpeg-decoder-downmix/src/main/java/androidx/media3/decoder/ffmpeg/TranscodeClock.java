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
 */
public final class TranscodeClock {

  public static final long DISCONTINUITY_THRESHOLD_US = 200_000;

  /** Input frames per AAC access unit (AAC-LC); a stream starts within one of them. */
  private static final int AAC_FRAME_SAMPLES = 1024;

  private long anchorTimeUs = C.TIME_UNSET;
  private long anchorSamples;

  /** Forgets the anchor; the next input packet sets a new one. */
  public void reset() {
    anchorTimeUs = C.TIME_UNSET;
    anchorSamples = 0;
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
        && Math.abs(inputTimeUs - timeUs) > DISCONTINUITY_THRESHOLD_US) {
      anchorTimeUs = inputTimeUs;
      anchorSamples = samplesBefore;
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
