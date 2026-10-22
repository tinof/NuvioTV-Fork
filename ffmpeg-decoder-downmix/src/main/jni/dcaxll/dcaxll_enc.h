/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Copyright (C) 2026 tinof
 *
 * dcaxll_enc: DCA core + ExSS + XLL access-unit writer (ETSI TS 102 114)
 * for mono and stereo 48 kHz / 24-bit PCM. Its output is compatible with
 * DTS-HD Master Audio decoders.
 *
 * Portable C17, no external dependencies, no globals, no allocation after
 * dcaxll_enc_create(). One call to dcaxll_enc_encode_unit() turns 512 PCM
 * samples per channel plus one DTS core frame (produced elsewhere, e.g. by
 * FFmpeg's "dca" encoder) into one access unit:
 *
 *   [core frame, zero-padded to 4 bytes][ExSS header][XLL frame]
 *
 * The XLL is coded independently of the core (all nResidualChEncode bits
 * set), so the lossless decode does not depend on the core's content. The
 * caller is responsible for time-aligning the core with the PCM (see
 * README.md: with FFmpeg's dcaenc, pair core packet N+1 with PCM unit N).
 *
 * Field-level contract: docs/WRITER_SPEC.md.
 */
#ifndef DCAXLL_ENC_H
#define DCAXLL_ENC_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define DCAXLL_UNIT_SAMPLES 512 /* samples per channel per access unit */
#define DCAXLL_MAX_CHANNELS 2

/* Error codes (negative return values of dcaxll_enc_encode_unit). */
#define DCAXLL_ERR_ARG       (-1) /* NULL pointer or bad argument */
#define DCAXLL_ERR_CORE      (-2) /* core frame missing, bad sync or truncated */
#define DCAXLL_ERR_CORE_FMT  (-3) /* core frame is not 48 kHz / 512 samples / no LFE,
                                      or its channel mode does not match the
                                      configured channels */
#define DCAXLL_ERR_SPACE     (-4) /* out_cap too small */
#define DCAXLL_ERR_INTERNAL  (-5) /* size limit exceeded (should not happen) */

/* ExSS shape. Both decode bit-exactly in FFmpeg and foo86 dcadec. */
enum dcaxll_exss_profile {
    /* 36-byte ExSS header, 16-byte asset descriptor, extension mask 0x201
     * (core in core substream + XLL), XLL sync declared present at offset 0.
     * This is the shape of official Core+XLL streams (Swift reference notes). */
    DCAXLL_EXSS_OFFICIAL = 0,
    /* 28-byte ExSS header, 11-byte descriptor, mask 0x200, no XLL sync fields. */
    DCAXLL_EXSS_MINIMAL = 1,
};

typedef struct dcaxll_config {
    int channels;          /* 1 (centre) or 2 (L, R) */
    int sample_rate;       /* must be 48000 */
    int pcm_bits;          /* must be 24 */
    int exss_profile;      /* enum dcaxll_exss_profile, default OFFICIAL */
    /* The defaults are the grammar of official delivery streams (Swift
     * reference): fixed orders 0/1, Rice for every active segment, no hybrid
     * Rice. Orders 2/3, linear and hybrid codes are an experiment: every
     * software decoder accepts them, but no hardware evidence exists. */
    int max_fixed_order;   /* highest fixed predictor order tried, 0..3, default 1.
                              An order whose residuals need more than 24 bits
                              in a frame is skipped. */
    int allow_linear;      /* 1: linear code allowed for non-zero segments;
                              0 (default): non-zero segments always use Rice.
                              All-zero segments always use linear width 0. */
    int allow_hybrid;      /* 1: hybrid Rice allowed; 0 (default): never */
    int max_unary;         /* longest Rice unary run written, 0..32, default 22
                              (hardware readers are bounded); 0 = unbounded.
                              Values above 22 and 0 are for tests only. */
    int min_size_bits;     /* floor for nBits4FrameFsize and nBits4SSize, default 7 */
    int patch_core_header; /* 1 (default): in the copied core, set CHIST = 1 and
                              PCMR = 6 (24-bit source) as official encoders do,
                              if the core has no header CRC (CPF = 0) */
} dcaxll_config;

/* Per-unit statistics, filled by dcaxll_enc_encode_unit when info != NULL. */
typedef struct dcaxll_unit_info {
    size_t core_bytes;     /* core bytes written including 4-byte padding */
    size_t exss_bytes;     /* ExSS header bytes */
    size_t xll_bytes;      /* XLL frame bytes (nLLFrameSize) */
    int fixed_order[DCAXLL_MAX_CHANNELS];
    /* per segment and channel: 0 = zero (linear width 0), 1 = linear,
       2 = Rice, 3 = hybrid Rice */
    int code[2][DCAXLL_MAX_CHANNELS];
    int param[2][DCAXLL_MAX_CHANNELS]; /* Rice k or linear width */
    int clipped;           /* input samples clamped to the 24-bit range */
} dcaxll_unit_info;

/* Upper bound of the ExSS + XLL bytes of one access unit. Each segment of
 * each channel is coded with at most 32 bits per sample plus its parameters;
 * the bound adds every header at its largest size. */
#define DCAXLL_MAX_EXT_BYTES(nch) \
    (64 + 64 + 2 * (8 + (size_t)(nch) * (8 + 256 * 4 + 256 * 2)))

/* Upper bound of one whole access unit for a given core frame size. */
#define DCAXLL_MAX_UNIT_BYTES(nch, core_size) \
    ((((size_t)(core_size) + 3) & ~(size_t)3) + DCAXLL_MAX_EXT_BYTES(nch))

/* Tight upper bound of the ExSS + XLL bytes, valid for every configuration.
 * The input is clamped to 24 bits and every chosen predictor order has
 * residuals of at most 24 bits, so each zigzagged value is below 2^24 and
 * Rice k = 24 (all quotients 0, 25 bits per sample) is always a candidate
 * within any unary bound. The writer takes the cheapest code, so a channel
 * costs at most 2 + 2 * 5 + 256 * 25 = 6412 bits per segment, and a segment
 * at most 1 + nch * 802 bytes. The rest is the header buffers at their
 * capacity: ExSS 64, XLL common header 32, channel-set header 32, NAVI 16,
 * DWORD padding 3. A stereo unit with a 2012-byte core is at most 5369
 * bytes, inside the 8172-byte payload of a 2-channel IEC 61937 DTS-HD burst
 * (period 2048). */
#define DCAXLL_MAX_EXT_BYTES_24(nch) \
    ((size_t)(64 + 32 + 32 + 16 + 3) + 2 * (1 + (size_t)(nch) * 802))

/* Tight upper bound of one whole access unit (see DCAXLL_MAX_EXT_BYTES_24). */
#define DCAXLL_MAX_UNIT_BYTES_24(nch, core_size) \
    ((((size_t)(core_size) + 3) & ~(size_t)3) + DCAXLL_MAX_EXT_BYTES_24(nch))

typedef struct dcaxll_enc dcaxll_enc;

/* Fills *cfg with the defaults for the given channel count. */
void dcaxll_config_default(dcaxll_config *cfg, int channels);

/* Returns NULL on a bad configuration or allocation failure. */
dcaxll_enc *dcaxll_enc_create(const dcaxll_config *cfg);
void dcaxll_enc_destroy(dcaxll_enc *enc);

/*
 * Encodes one access unit.
 *   pcm[c][i]  : 512 samples for channel c (c < channels), 24-bit values
 *                (-8388608..8388607) in int32_t. Values outside are clamped
 *                and counted in info->clipped.
 *   core       : one complete DTS core frame (sync 7FFE8001), 48 kHz, 512
 *                samples (NBLKS 15), no LFE, mono (AMODE 0) or stereo
 *                (AMODE 2) matching the configuration. Its FSIZE field
 *                decides how many bytes are copied. The caller's buffer is
 *                never modified (see patch_core_header).
 *   out/out_cap: destination; DCAXLL_MAX_UNIT_BYTES() is always enough.
 * Returns the number of bytes written or a negative DCAXLL_ERR_* code.
 */
long dcaxll_enc_encode_unit(dcaxll_enc *enc, const int32_t *const pcm[],
                             const uint8_t *core, size_t core_size,
                             uint8_t *out, size_t out_cap,
                             dcaxll_unit_info *info);

/* Reads the FSIZE (bytes) of a DTS core frame, or returns a negative error. */
long dcaxll_core_frame_size(const uint8_t *core, size_t core_size);

/* CRC-16/CCITT-FALSE as used by DTS headers (exposed for tests). */
uint16_t dcaxll_crc16(const uint8_t *data, size_t len);

#ifdef __cplusplus
}
#endif

#endif /* DCAXLL_ENC_H */
