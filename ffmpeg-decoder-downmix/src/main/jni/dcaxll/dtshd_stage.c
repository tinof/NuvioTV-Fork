/*
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * DTS-HD MA stage of the AAC transcode. See dtshd_stage.h.
 */
#include "dtshd_stage.h"

#include <string.h>

int dtshd_stage_init(dtshd_stage* st) {
  memset(st, 0, sizeof(*st));
  dcaxll_config cfg;
  /* The default grammar is the one the owner's HT-A9 played as DTS-HD MA: official ExSS shape,
   * fixed orders 0 and 1, Rice only, unary runs up to 22, patched core header. */
  dcaxll_config_default(&cfg, DTSHD_STAGE_CHANNELS);
  st->writer = dcaxll_enc_create(&cfg);
  return st->writer ? 0 : -1;
}

void dtshd_stage_free(dtshd_stage* st) {
  dcaxll_enc_destroy(st->writer);
  st->writer = NULL;
}

void dtshd_stage_reset(dtshd_stage* st) { st->units_taken = 0; }

void dtshd_stage_take_frame(dtshd_stage* st, int32_t* interleaved) {
  const int slot = (int)(st->units_taken & 1);
  for (int i = 0; i < DCAXLL_UNIT_SAMPLES; i++) {
    for (int c = 0; c < DTSHD_STAGE_CHANNELS; c++) {
      /* Round half up to 24 bits; the arithmetic shift of a negative value is what clang does
       * on every target (the host test checks the result bit for bit). (-2^31 + 128) >> 8 is
       * -2^23, so only the top needs a clamp. */
      int64_t v = ((int64_t)interleaved[DTSHD_STAGE_CHANNELS * i + c] + 128) >> 8;
      if (v > 8388607) v = 8388607;
      interleaved[DTSHD_STAGE_CHANNELS * i + c] = (int32_t)((uint32_t)v << 8);
      st->pcm[slot][c][i] = (int32_t)v;
    }
  }
  st->units_taken++;
}

long dtshd_stage_pair(dtshd_stage* st, int64_t packet_index, const uint8_t* core, size_t core_size,
                      uint8_t* out, size_t out_cap) {
  if (packet_index < 1 || packet_index > st->units_taken) return -1;
  const int slot = (int)((packet_index - 1) & 1);
  const int32_t* const pcm[DTSHD_STAGE_CHANNELS] = {st->pcm[slot][0], st->pcm[slot][1]};
  dcaxll_unit_info info;
  long n = dcaxll_enc_encode_unit(st->writer, pcm, core, core_size, out, out_cap, &info);
  if (n > 0) {
    st->clipped += info.clipped;
  } else {
    /* Should never happen: the core comes from our own encoder and out_cap is the library's
     * bound. A plain core still decodes (the receiver plays the core for 10.67 ms), where a
     * decoder error would stop playback. */
    if (!st->writer_errors) {
      st->first_error = n;
      st->first_error_unit = packet_index - 1;
    }
    st->writer_errors++;
    if (core_size > out_cap) return -1;
    memcpy(out, core, core_size);
    n = (long)core_size;
  }
  if ((size_t)n > st->max_unit) st->max_unit = (size_t)n;
  return n;
}

void dtshd_expand_mono_s32(int32_t* buf, int frames) {
  for (int i = frames - 1; i >= 0; i--) {
    const int32_t v = buf[i];
    buf[2 * i] = v;
    buf[2 * i + 1] = v;
  }
}
