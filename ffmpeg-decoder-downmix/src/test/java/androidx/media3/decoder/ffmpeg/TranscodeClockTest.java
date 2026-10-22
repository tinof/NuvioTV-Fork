/*
 * SPDX-License-Identifier: GPL-3.0-only
 */
package androidx.media3.decoder.ffmpeg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TranscodeClockTest {

  private static final int OUT_RATE = 48_000;

  private static long us(long samples) {
    return samples * 1_000_000L / OUT_RATE;
  }

  /** DTSHD_PORT.md 3.1: 48 kHz AAC-LC, no trim, one unit of lookahead. */
  @Test
  public void workedExample_stampsEachBufferWithTheSamplesEncodedBeforeIt() {
    TranscodeClock clock = new TranscodeClock();
    long anchor = 5_000_000;
    // Call 1: packet 0 dropped, unit 0 out. Call 2: units 1 and 2. Call 3: units 3 and 4.
    assertEquals(anchor, clock.getOutputTimeUs(anchor, 0, OUT_RATE));
    assertEquals(anchor + us(512), clock.getOutputTimeUs(anchor + 21_333, 512, OUT_RATE));
    assertEquals(anchor + us(1536), clock.getOutputTimeUs(anchor + 42_666, 1536, OUT_RATE));
  }

  /**
   * 8 kHz AAC with 2112 samples of priming (264 ms): the first output appears while decoding the
   * third packet, and from the fourth packet on the input runs 264 ms ahead of the clock. That is
   * the trim, not a discontinuity.
   */
  @Test
  public void longPrimingTrim_doesNotReanchorOnceOutputStarts() {
    TranscodeClock clock = new TranscodeClock();
    clock.setPrimingTrim(2112, 8_000);
    long packetUs = 128_000; // 1024 samples at 8 kHz
    // Packets 0 to 2: everything trimmed or still in the FIFO and the held frame.
    assertEquals(0, clock.getOutputTimeUs(0, 0, OUT_RATE));
    assertEquals(0, clock.getOutputTimeUs(packetUs, 0, OUT_RATE));
    assertEquals(0, clock.getOutputTimeUs(2 * packetUs, 0, OUT_RATE));
    // Packet 3: 960 trimmed-in samples of packet 2 gave 5760 at 48 kHz, 10 units out so far.
    assertEquals(us(5120), clock.getOutputTimeUs(3 * packetUs, 5120, OUT_RATE));
    // Packet 4 and later stay on the same timeline: 6144 samples per packet.
    assertEquals(us(5120 + 6144), clock.getOutputTimeUs(4 * packetUs, 5120 + 6144, OUT_RATE));
    assertEquals(us(5120 + 4 * 6144), clock.getOutputTimeUs(7 * packetUs, 5120 + 4 * 6144, OUT_RATE));
  }

  /** The same 8 kHz stream without the trim taken into account would re-anchor at packet 3. */
  @Test
  public void withoutTheTrim_theSameInputIsADiscontinuity() {
    TranscodeClock clock = new TranscodeClock();
    long packetUs = 128_000;
    clock.getOutputTimeUs(0, 0, OUT_RATE);
    assertEquals(3 * packetUs, clock.getOutputTimeUs(3 * packetUs, 5120, OUT_RATE));
  }

  @Test
  public void realJump_reanchorsAndDropsTheTrim() {
    TranscodeClock clock = new TranscodeClock();
    clock.setPrimingTrim(2112, 48_000); // 44 ms
    clock.getOutputTimeUs(0, 0, OUT_RATE);
    assertEquals(us(1024), clock.getOutputTimeUs(65_333, 1024, OUT_RATE));
    // A jump of a second: re-anchored at the input.
    long jumped = 1_065_333 + 21_333;
    assertEquals(jumped, clock.getOutputTimeUs(jumped, 2048, OUT_RATE));
    // After it the input and the clock run together; the old trim no longer shifts the check.
    assertEquals(jumped + us(1024), clock.getOutputTimeUs(jumped + 21_333, 3072, OUT_RATE));
    assertEquals(jumped + us(2048), clock.getOutputTimeUs(jumped + 42_666, 4096, OUT_RATE));
  }

  @Test
  public void reset_forgetsAnchorAndTrim() {
    TranscodeClock clock = new TranscodeClock();
    clock.setPrimingTrim(2112, 8_000);
    clock.getOutputTimeUs(0, 0, OUT_RATE);
    clock.reset();
    // A resume at 10 s: no trim, so an input 264 ms ahead of the clock is a discontinuity again.
    assertEquals(10_000_000, clock.getOutputTimeUs(10_000_000, 0, OUT_RATE));
    assertEquals(10_264_000 + 128_000, clock.getOutputTimeUs(10_264_000 + 128_000, 5120, OUT_RATE));
  }

  @Test
  public void startsStream_onlyWithinTheFirstAacFrame() {
    assertTrue(TranscodeClock.startsStream(1_000_000, 1_000_000, 48_000));
    assertTrue(TranscodeClock.startsStream(1_021_333, 1_000_000, 48_000));
    assertFalse(TranscodeClock.startsStream(1_042_667, 1_000_000, 48_000));
  }
}
