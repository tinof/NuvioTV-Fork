package com.nuvio.tv.core.player.dvmkv;

import android.util.Log;
import androidx.annotation.Nullable;

/**
 * Fork (Q1.1): MKV_RESYNC detail beside upstream's line. A resync logs how many bytes it skipped; the
 * time of the Cluster it landed on is known only once that Cluster's Timestamp element is read, so a
 * second line follows then.
 */
final class MatroskaResyncLog {
  private static final long NONE = -1;

  private final String tag;
  private long pendingSkippedBytes = NONE;

  MatroskaResyncLog(String tag) {
    this.tag = tag;
  }

  /** Logs a resync from {@code failPosition} to the Cluster ID at {@code clusterPosition}. */
  void onSkipped(long failPosition, long clusterPosition) {
    pendingSkippedBytes = clusterPosition - failPosition;
    Log.w(tag, skippedLine(failPosition, clusterPosition));
  }

  /** Logs the first Cluster time after a resync, once; returns the line, or null when no resync is pending. */
  @Nullable
  String onClusterTime(long clusterTimeUs) {
    if (pendingSkippedBytes == NONE) {
      return null;
    }
    String line = clusterTimeLine(pendingSkippedBytes, clusterTimeUs);
    pendingSkippedBytes = NONE;
    Log.w(tag, line);
    return line;
  }

  /** A seek reads a new place: the pending Cluster time no longer belongs to the resync. */
  void onSeek() {
    pendingSkippedBytes = NONE;
  }

  static String skippedLine(long failPosition, long clusterPosition) {
    return "MKV_RESYNC: skipped "
        + (clusterPosition - failPosition)
        + " bytes from byte "
        + failPosition
        + " to the cluster at byte "
        + clusterPosition;
  }

  static String clusterTimeLine(long skippedBytes, long clusterTimeUs) {
    return "MKV_RESYNC: first cluster after the "
        + skippedBytes
        + "-byte skip starts at "
        + (clusterTimeUs / 1000)
        + " ms";
  }
}
