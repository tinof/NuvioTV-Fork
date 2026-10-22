/*
 * SPDX-License-Identifier: GPL-3.0-only
 */
package androidx.media3.decoder.ffmpeg;

import static androidx.media3.decoder.ffmpeg.DtsTranscodeTargets.AUTO;
import static androidx.media3.decoder.ffmpeg.DtsTranscodeTargets.CORE;
import static androidx.media3.decoder.ffmpeg.DtsTranscodeTargets.HD;
import static androidx.media3.decoder.ffmpeg.DtsTranscodeTargets.NONE;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class DtsTranscodeTargetsTest {

  private static int choose(boolean hdAllowed, int override, boolean hdDirect, boolean coreDirect) {
    return DtsTranscodeTargets.choose(true, true, hdAllowed, override, () -> hdDirect, () -> coreDirect);
  }

  @Test
  public void hdWhenTheSinkPlaysDtsHd() {
    assertEquals(HD, choose(true, AUTO, true, true));
  }

  @Test
  public void coreWhenOnlyDtsPlays() {
    assertEquals(CORE, choose(true, AUTO, false, true));
  }

  @Test
  public void noneWithoutDts() {
    assertEquals(NONE, choose(true, AUTO, false, false));
  }

  @Test
  public void hdNotAllowed_givesCore() {
    assertEquals(CORE, choose(false, AUTO, true, true));
  }

  @Test
  public void forcedCore_givesCore() {
    assertEquals(CORE, choose(true, CORE, true, true));
  }

  @Test
  public void forcedHd_skipsTheDtsHdCheck() {
    assertEquals(HD, choose(true, HD, false, true));
  }

  @Test
  public void forcedHd_withoutDts_givesNone() {
    assertEquals(NONE, choose(true, HD, false, false));
  }

  @Test
  public void forcedHd_afterAnHdRefusal_givesCore() {
    assertEquals(CORE, choose(false, HD, true, true));
  }

  @Test
  public void settingOffOrIneligible_givesNone() {
    assertEquals(NONE, DtsTranscodeTargets.choose(false, true, true, AUTO, () -> true, () -> true));
    assertEquals(NONE, DtsTranscodeTargets.choose(true, false, true, AUTO, () -> true, () -> true));
  }
}
