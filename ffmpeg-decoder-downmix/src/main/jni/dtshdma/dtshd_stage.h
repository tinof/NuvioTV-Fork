/*
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * DTS-HD MA stage of the AAC transcode: the glue between ffmpeg_jni.cc and the dtshdma writer.
 * No JNI and no FFmpeg in here, so the host test links it as it is.
 *
 * The JNI sends 512-sample stereo frames of packed S32 to FFmpeg's dcaenc. Each frame first goes
 * through dtshd_stage_take_frame, which rounds it to 24 bits in place (so the core encoder and the
 * XLL code the same PCM) and keeps the 24-bit copy. dcaenc's packet p carries the audio of frame
 * p - 1 (its output lags one frame); the JNI drops packet 0 and hands every later packet to
 * dtshd_stage_pair, which writes core p + XLL of frame p - 1 as one access unit. Two slots are
 * enough: when packet p arrives, frame p is in slot p & 1 and its partner in slot (p - 1) & 1.
 */
#ifndef DTSHD_STAGE_H
#define DTSHD_STAGE_H

#include <stddef.h>
#include <stdint.h>

#include "dtshdma_enc.h"

#ifdef __cplusplus
extern "C" {
#endif

#define DTSHD_STAGE_CHANNELS 2

typedef struct dtshd_stage {
  dtshdma_enc* writer;
  /* [slot][channel][sample], 24-bit values */
  int32_t pcm[2][DTSHD_STAGE_CHANNELS][DTSHDMA_UNIT_SAMPLES];
  int64_t units_taken;   /* frames kept since the last reset */
  long writer_errors;    /* units written as a plain core because the writer refused */
  long first_error;      /* the first writer error code, 0 if none */
  int64_t first_error_unit;
  long clipped;          /* samples the writer clamped (stays 0: the input is already 24-bit) */
  size_t max_unit;       /* largest unit written */
} dtshd_stage;

/* Creates the writer (stereo, default grammar). Returns 0, or -1 on failure. */
int dtshd_stage_init(dtshd_stage* st);

void dtshd_stage_free(dtshd_stage* st);

/* Forgets the kept frames (a seek). The writer keeps no state between units. */
void dtshd_stage_reset(dtshd_stage* st);

/* Rounds one frame of interleaved stereo S32 (DTSHDMA_UNIT_SAMPLES frames) to 24 bits in place
 * and keeps the 24-bit values for the XLL. */
void dtshd_stage_take_frame(dtshd_stage* st, int32_t* interleaved);

/* Writes the access unit of dcaenc packet `packet_index` (>= 1): that core frame plus the XLL of
 * the frame taken before it. On a writer error the core alone is written and counted. Returns the
 * bytes written, or -1 when out_cap is too small or the frame was never taken. */
long dtshd_stage_pair(dtshd_stage* st, int64_t packet_index, const uint8_t* core, size_t core_size,
                      uint8_t* out, size_t out_cap);

/* Turns `frames` mono S32 samples at the start of buf into interleaved stereo (L = R) in place;
 * buf holds 2 * frames samples. */
void dtshd_expand_mono_s32(int32_t* buf, int frames);

/* Room one unit may need in the output, for a core of core_size bytes. */
#define DTSHD_STAGE_MAX_UNIT_BYTES(core_size) DTSHDMA_MAX_UNIT_BYTES(DTSHD_STAGE_CHANNELS, core_size)

#ifdef __cplusplus
}
#endif

#endif /* DTSHD_STAGE_H */
