/*
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This modified decoder module is distributed with NuvioTV under GPL-3.0-only.
 * See this module's NOTICE.md for provenance details.
 */
package androidx.media3.decoder.ffmpeg;

import java.util.function.BooleanSupplier;

/**
 * Which DTS format the "Transcode AAC to DTS" setting writes for one track. Kept in step with
 * {@code AacToDtsRouting.chooseTarget} in the app, which asks the same sink the same questions.
 */
public final class DtsTranscodeTargets {

  /** No transcode: the track decodes to PCM. */
  public static final int NONE = 0;
  /** DTS core, 1509 kbit/s. */
  public static final int CORE = 1;
  /** DTS-HD Master Audio: the DTS core plus a lossless 24-bit XLL. */
  public static final int HD = 2;
  /** {@link FfmpegAudioRenderer#setDtsTranscodeTargetOverride}: no override. */
  public static final int AUTO = -1;

  private DtsTranscodeTargets() {}

  /**
   * Picks the target. DTS-HD MA needs the sink to play the DTS-HD transcode format directly (the
   * app's sink answers that only when DTS-HD itself would be opened, not its core) and the HD
   * target to be allowed for the stream. A forced {@link #HD} skips the DTS-HD check but still
   * needs DTS to play at all, so Bluetooth, other speeds, the PCM latch and a policy that denies
   * DTS still give PCM; it also honours a disallowed HD, so a refused HD cannot loop.
   *
   * @param enabled The setting is on.
   * @param eligible The track is mono or stereo AAC the transcode accepts.
   * @param hdAllowed DTS-HD MA may be written (the renderer switch and the stream's step-down).
   * @param override {@link #AUTO}, {@link #CORE} or {@link #HD}.
   * @param hdDirect Whether the sink plays the DTS-HD transcode format directly.
   * @param coreDirect Whether the sink plays the DTS core format directly.
   */
  public static int choose(
      boolean enabled,
      boolean eligible,
      boolean hdAllowed,
      int override,
      BooleanSupplier hdDirect,
      BooleanSupplier coreDirect) {
    if (!enabled || !eligible) {
      return NONE;
    }
    boolean hdPossible = hdAllowed && override != CORE;
    if (hdPossible
        && (override == HD ? coreDirect.getAsBoolean() || hdDirect.getAsBoolean()
            : hdDirect.getAsBoolean())) {
      return HD;
    }
    return coreDirect.getAsBoolean() ? CORE : NONE;
  }
}
