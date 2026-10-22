/*
 * SPDX-License-Identifier: GPL-3.0-only
 */
package androidx.media3.decoder.ffmpeg;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** FfmpegAudioDecoder.unitsBefore: which DTS units of a decode call start before a seek target. */
public final class SeekUnitDiscardTest {

  private static int unitsBefore(long firstUnitTimeUs, long startTimeUs) {
    // SimpleDecoder.isAtLeastOutputStartTimeUs
    return FfmpegAudioDecoder.unitsBefore(firstUnitTimeUs, timeUs -> timeUs >= startTimeUs);
  }

  @Test
  public void unitTime_is512SamplesAt48kHz() {
    assertEquals(10_666, FfmpegAudioDecoder.unitTimeUs(1));
    assertEquals(32_000, FfmpegAudioDecoder.unitTimeUs(3));
  }

  @Test
  public void heldUnitBeforeTheTarget_isDropped() {
    // The first accepted packet brings out the held unit, 10.67 ms before the target, and the next.
    assertEquals(1, unitsBefore(1_000_000 - 10_666, 1_000_000));
  }

  @Test
  public void unitStraddlingTheTarget_isDropped() {
    assertEquals(2, unitsBefore(1_000_000 - 13_000, 1_000_000));
  }

  @Test
  public void unitAtTheTarget_isKept_withinOneSample() {
    assertEquals(0, unitsBefore(1_000_000, 1_000_000));
    assertEquals(0, unitsBefore(1_000_000 - 15, 1_000_000));
    assertEquals(1, unitsBefore(1_000_000 - 25, 1_000_000));
  }

  @Test
  public void noStartTime_dropsNothing() {
    assertEquals(0, FfmpegAudioDecoder.unitsBefore(0, timeUs -> true));
  }

  @Test
  public void farBehind_isBounded() {
    assertEquals(64, unitsBefore(0, 10_000_000));
  }
}
