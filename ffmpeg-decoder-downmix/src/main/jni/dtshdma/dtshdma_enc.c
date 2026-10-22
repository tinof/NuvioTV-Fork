/*
 * dtshdma_enc: DTS-HD Master Audio access-unit writer (core + ExSS + XLL).
 * See dtshdma_enc.h for the API and docs/WRITER_SPEC.md for every field.
 * Section numbers (§) below refer to docs/WRITER_SPEC.md.
 */
#include "dtshdma_enc.h"

#include <stdlib.h>
#include <string.h>

#define NSEG         2                 /* segments per frame */
#define SEG_LOG2     8
#define SEG_SAMPLES  (1 << SEG_LOG2)   /* 256 */
#define NABITS       5                 /* storage width 24, 1 set, 1 band */
#define MAX_ORDER    3
#define MAX_ISO      255               /* nIsoSamples is SEG_LOG2 bits */
#define CHMASK_BITS  8                 /* nBits4ChMask */

#define SYNC_EXSS 0x64582025u
#define SYNC_XLL  0x41A29547u
#define SYNC_CORE 0x7FFE8001u

enum { CODE_ZERO = 0, CODE_LINEAR = 1, CODE_RICE = 2, CODE_HYBRID = 3 };

typedef struct segcode {
    int type;
    int param;            /* Rice k, or linear width (0 for CODE_ZERO) */
    int hw;               /* hybrid linear width */
    int niso;
    uint32_t bits;        /* parameter + sample bits of this channel */
    uint8_t iso[MAX_ISO]; /* isolated positions (hybrid only) */
} segcode;

/* worst case per segment: 2 flag bits + per channel (params + iso list +
 * 256 samples * 32 bits), rounded up generously */
#define SEGBUF_BYTES (8 + DTSHDMA_MAX_CHANNELS * (8 + MAX_ISO + 1 + SEG_SAMPLES * 4))

struct dtshdma_enc {
    dtshdma_config cfg;
    int32_t x[DTSHDMA_MAX_CHANNELS][DTSHDMA_UNIT_SAMPLES];
    int32_t res[MAX_ORDER + 1][DTSHDMA_UNIT_SAMPLES];
    uint32_t u[DTSHDMA_UNIT_SAMPLES];
    segcode cand[NSEG];                         /* scratch for one order */
    segcode code[DTSHDMA_MAX_CHANNELS][NSEG];   /* chosen codes */
    int32_t best_res[DTSHDMA_MAX_CHANNELS][DTSHDMA_UNIT_SAMPLES];
    int order[DTSHDMA_MAX_CHANNELS];
    uint8_t seg[NSEG][SEGBUF_BYTES];
    size_t seg_bytes[NSEG];
};

/* ------------------------------------------------------------------ */
/* Bit writer (MSB first)                                              */

typedef struct bw {
    uint8_t *buf;
    size_t cap, pos;   /* bytes */
    uint64_t acc;
    unsigned nacc;     /* pending bits in acc (< 8 between calls) */
    int err;
} bw;

static void bw_init(bw *b, uint8_t *buf, size_t cap)
{
    b->buf = buf; b->cap = cap; b->pos = 0; b->acc = 0; b->nacc = 0; b->err = 0;
}

static void bw_put(bw *b, unsigned n, uint32_t v)
{
    if (!n)
        return;
    if (n < 32)
        v &= (1u << n) - 1;
    b->acc = (b->acc << n) | v;
    b->nacc += n;
    while (b->nacc >= 8) {
        b->nacc -= 8;
        if (b->pos < b->cap)
            b->buf[b->pos++] = (uint8_t)(b->acc >> b->nacc);
        else
            b->err = 1;
    }
}

static void bw_zeros(bw *b, uint32_t n)
{
    while (n >= 32) { bw_put(b, 32, 0); n -= 32; }
    bw_put(b, n, 0);
}

static void bw_align(bw *b)
{
    if (b->nacc)
        bw_put(b, 8 - b->nacc, 0);
}

static size_t bw_bits(const bw *b) { return b->pos * 8 + b->nacc; }

/* Overwrites n bits at bit position pos of an already flushed buffer. */
static void patch_bits(uint8_t *buf, size_t pos, unsigned n, uint32_t v)
{
    for (unsigned i = 0; i < n; i++) {
        size_t bit = pos + i;
        unsigned shift = 7 - (unsigned)(bit & 7);
        uint8_t m = (uint8_t)(1u << shift);
        if ((v >> (n - 1 - i)) & 1)
            buf[bit >> 3] |= m;
        else
            buf[bit >> 3] &= (uint8_t)~m;
    }
}

static uint32_t read_bits(const uint8_t *buf, size_t pos, unsigned n)
{
    uint32_t v = 0;
    for (unsigned i = 0; i < n; i++) {
        size_t bit = pos + i;
        v = (v << 1) | ((buf[bit >> 3] >> (7 - (bit & 7))) & 1u);
    }
    return v;
}

/* ------------------------------------------------------------------ */
/* CRC-16/CCITT-FALSE (§0)                                             */

uint16_t dtshdma_crc16(const uint8_t *data, size_t len)
{
    uint16_t crc = 0xFFFF;
    for (size_t i = 0; i < len; i++) {
        crc ^= (uint16_t)(data[i] << 8);
        for (int j = 0; j < 8; j++)
            crc = (crc & 0x8000) ? (uint16_t)((crc << 1) ^ 0x1021) : (uint16_t)(crc << 1);
    }
    return crc;
}

static void put_crc(uint8_t *p, uint16_t crc)
{
    p[0] = (uint8_t)(crc >> 8);
    p[1] = (uint8_t)crc;
}

/* ------------------------------------------------------------------ */

static unsigned bitlen(uint32_t v)
{
    unsigned n = 0;
    while (v) { n++; v >>= 1; }
    return n;
}

static uint32_t zigzag(int32_t v)
{
    return ((uint32_t)v << 1) ^ (v < 0 ? 0xFFFFFFFFu : 0u);
}

void dtshdma_config_default(dtshdma_config *cfg, int channels)
{
    memset(cfg, 0, sizeof(*cfg));
    cfg->channels = channels;
    cfg->sample_rate = 48000;
    cfg->pcm_bits = 24;
    cfg->exss_profile = DTSHDMA_EXSS_OFFICIAL;
    /* Swift-like delivery grammar (Swift notes section 7, rows 5-6): fixed
     * orders 0/1, Rice for every active segment, no hybrid Rice. */
    cfg->max_fixed_order = 1;
    cfg->allow_linear = 0;
    cfg->allow_hybrid = 0;
    cfg->max_unary = 22;
    cfg->min_size_bits = 7;
    cfg->patch_core_header = 1;
}

dtshdma_enc *dtshdma_enc_create(const dtshdma_config *cfg)
{
    if (!cfg || cfg->channels < 1 || cfg->channels > DTSHDMA_MAX_CHANNELS ||
        cfg->sample_rate != 48000 || cfg->pcm_bits != 24 ||
        (cfg->exss_profile != DTSHDMA_EXSS_OFFICIAL &&
         cfg->exss_profile != DTSHDMA_EXSS_MINIMAL) ||
        cfg->max_fixed_order < 0 || cfg->max_fixed_order > MAX_ORDER ||
        cfg->max_unary < 0 || cfg->max_unary > 32 || cfg->min_size_bits < 1 || cfg->min_size_bits > 16)
        return NULL;
    dtshdma_enc *e = calloc(1, sizeof(*e));
    if (e)
        e->cfg = *cfg;
    return e;
}

void dtshdma_enc_destroy(dtshdma_enc *enc)
{
    free(enc);
}

/* ------------------------------------------------------------------ */
/* Entropy code choice for one channel in one segment (§4.6)          */

static void choose_code(const dtshdma_config *cfg, const uint32_t *u, int seg, segcode *sc)
{
    const uint32_t alloc_bits = seg == 0 ? 2 * NABITS : NABITS; /* part A + part B */
    const uint32_t cap = (uint32_t)cfg->max_unary;
    uint32_t maxu = 0;
    for (int i = 0; i < SEG_SAMPLES; i++)
        if (u[i] > maxu)
            maxu = u[i];

    sc->niso = 0;
    sc->hw = 0;
    if (maxu == 0) {                       /* linear, width 0: all zero */
        sc->type = CODE_ZERO;
        sc->param = 0;
        sc->bits = 1 + alloc_bits;
        return;
    }

    const unsigned mbl = bitlen(maxu);
    uint32_t best = UINT32_MAX;
    int best_type = CODE_RICE, best_k = 31;
    uint32_t best_t = 0;

    if (cfg->allow_linear) {
        unsigned w = mbl < 2 ? 2 : mbl;    /* width 1 cannot be signalled */
        best = 1 + alloc_bits + (uint32_t)SEG_SAMPLES * w;
        best_type = CODE_LINEAR;
        best_k = (int)w;
    }

    /* k beyond mbl only adds bits; k = mbl gives every quotient 0. */
    for (unsigned k = 0; k <= mbl && k <= 31; k++) {
        const uint32_t maxq = maxu >> k;
        /* hybrid: isolate every sample whose quotient exceeds t, coded with
         * width hw = mbl (the largest sample is always isolated first). */
        uint32_t t = maxq;
        if (cfg->allow_hybrid) {
            uint32_t gain_t = (uint32_t)mbl + 8 > k + 1 ? (uint32_t)mbl + 8 - (k + 1) : 0;
            t = gain_t;                    /* q + 1 + k > hw + 8 -> isolate */
            if (cap && cap < t)
                t = cap;
        }
        uint64_t sum_all = 0, sum_keep = 0;
        uint32_t niso = 0;
        for (int i = 0; i < SEG_SAMPLES; i++) {
            uint32_t q = u[i] >> k;
            sum_all += q;
            if (q > t)
                niso++;
            else
                sum_keep += q;
        }
        if (!cap || maxq <= cap) {
            uint64_t c = 2 + alloc_bits + sum_all + (uint64_t)SEG_SAMPLES * (1 + k);
            if (c < best) {
                best = (uint32_t)c; best_type = CODE_RICE; best_k = (int)k;
            }
        }
        if (cfg->allow_hybrid && niso > 0 && niso <= MAX_ISO) {
            uint64_t c = 2 + NABITS + alloc_bits + SEG_LOG2 + (uint64_t)niso * (SEG_LOG2 + mbl) +
                         sum_keep + (uint64_t)(SEG_SAMPLES - niso) * (1 + k);
            if (c < best) {
                best = (uint32_t)c; best_type = CODE_HYBRID; best_k = (int)k; best_t = t;
            }
        }
    }

    sc->type = best_type;
    sc->param = best_k;
    sc->bits = best;
    if (best_type == CODE_HYBRID) {
        sc->hw = (int)mbl;
        for (int i = 0; i < SEG_SAMPLES; i++)
            if ((u[i] >> best_k) > best_t)
                sc->iso[sc->niso++] = (uint8_t)i;
    }
}

/* Writes the samples of one channel. Returns 0, or -1 if a Rice quotient
 * would exceed the configured unary bound (cannot happen by construction). */
static int write_samples(bw *b, const uint32_t *u, const segcode *sc, uint32_t cap)
{
    switch (sc->type) {
    case CODE_ZERO:
        break;
    case CODE_LINEAR:
        for (int i = 0; i < SEG_SAMPLES; i++)
            bw_put(b, (unsigned)sc->param, u[i]);
        break;
    case CODE_RICE:
    case CODE_HYBRID: {
        const unsigned k = (unsigned)sc->param;
        int next_iso = 0;
        if (sc->type == CODE_HYBRID) {
            bw_put(b, SEG_LOG2, (uint32_t)sc->niso);
            for (int j = 0; j < sc->niso; j++)
                bw_put(b, SEG_LOG2, sc->iso[j]);
        }
        for (int i = 0; i < SEG_SAMPLES; i++) {
            if (next_iso < sc->niso && sc->iso[next_iso] == i) {
                bw_put(b, (unsigned)sc->hw, u[i]);
                next_iso++;
                continue;
            }
            if (cap && (u[i] >> k) > cap)
                return -1;
            bw_zeros(b, u[i] >> k);
            bw_put(b, 1, 1);
            bw_put(b, k, u[i]);
        }
        break;
    }
    }
    return 0;
}

/* Chooses the fixed predictor order and the entropy codes of channel c. */
static void analyse_channel(dtshdma_enc *e, int c)
{
    const int maxo = e->cfg.max_fixed_order;
    uint64_t best = UINT64_MAX;

    memcpy(e->res[0], e->x[c], sizeof(e->res[0]));
    for (int o = 1; o <= maxo; o++) {
        e->res[o][0] = e->res[o - 1][0];
        for (int k = 1; k < DTSHDMA_UNIT_SAMPLES; k++)
            e->res[o][k] = e->res[o - 1][k] - e->res[o - 1][k - 1];
    }
    for (int o = 0; o <= maxo; o++) {
        /* The stream declares 24-bit resolution and storage width. Prediction
         * restarts every frame, so the warm-up residuals of orders >= 1 can
         * need 25 bits or more (a full-scale step): skip such an order. Order
         * 0 always fits, because the input is clamped to 24 bits. */
        if (o > 0) {
            int wide = 0;
            for (int k = 0; k < DTSHDMA_UNIT_SAMPLES && !wide; k++)
                wide = bitlen(zigzag(e->res[o][k])) > 24;
            if (wide)
                continue;
        }
        uint64_t total = 0;
        for (int s = 0; s < NSEG; s++) {
            for (int i = 0; i < SEG_SAMPLES; i++)
                e->u[i] = zigzag(e->res[o][s * SEG_SAMPLES + i]);
            choose_code(&e->cfg, e->u, s, &e->cand[s]);
            total += e->cand[s].bits;
        }
        if (total < best) {
            best = total;
            e->order[c] = o;
            memcpy(e->code[c], e->cand, sizeof(e->cand));
            memcpy(e->best_res[c], e->res[o], sizeof(e->best_res[c]));
        }
    }
}

static int write_segment(dtshdma_enc *e, int s)
{
    const int nch = e->cfg.channels;
    bw b;
    bw_init(&b, e->seg[s], sizeof(e->seg[s]));
    if (s)
        bw_put(&b, 1, 0);           /* bReuseParams = 0 (H4/H5) */
    bw_put(&b, 1, 0);               /* seg_common = 0: per-channel params */
    for (int c = 0; c < nch; c++) {
        const segcode *sc = &e->code[c][s];
        int rice = sc->type == CODE_RICE || sc->type == CODE_HYBRID;
        bw_put(&b, 1, (uint32_t)rice);
        if (rice) {
            bw_put(&b, 1, sc->type == CODE_HYBRID);
            if (sc->type == CODE_HYBRID)
                bw_put(&b, NABITS, (uint32_t)(sc->hw - 1));
        }
    }
    for (int c = 0; c < nch; c++) {
        const segcode *sc = &e->code[c][s];
        uint32_t alloc;
        if (s == 0)
            bw_put(&b, NABITS, 0);  /* part A: adaptive order 0 -> no samples */
        if (sc->type == CODE_LINEAR)
            alloc = (uint32_t)sc->param - 1;
        else
            alloc = (uint32_t)sc->param; /* Rice k, or 0 for all-zero */
        bw_put(&b, NABITS, alloc);
    }
    for (int c = 0; c < nch; c++) {
        for (int i = 0; i < SEG_SAMPLES; i++)
            e->u[i] = zigzag(e->best_res[c][s * SEG_SAMPLES + i]);
        if (write_samples(&b, e->u, &e->code[c][s], (uint32_t)e->cfg.max_unary) < 0)
            return DTSHDMA_ERR_INTERNAL;
    }
    bw_align(&b);
    if (b.err)
        return DTSHDMA_ERR_INTERNAL;
    /* cross-check against the cost model */
    size_t expect = (s ? 2 : 1);
    for (int c = 0; c < nch; c++)
        expect += e->code[c][s].bits;
    if (b.pos != (expect + 7) / 8)
        return DTSHDMA_ERR_INTERNAL;
    e->seg_bytes[s] = b.pos;
    return 0;
}

/* ------------------------------------------------------------------ */
/* Headers                                                             */

/* XLL channel-set sub-header (§4.2). Returns its size B. */
static size_t write_subheader(const dtshdma_enc *e, uint8_t *dst, size_t cap)
{
    const int nch = e->cfg.channels;
    bw b;
    bw_init(&b, dst, cap);
    bw_put(&b, 10, 0);                          /* size-1, patched */
    bw_put(&b, 4, (uint32_t)(nch - 1));
    bw_put(&b, (unsigned)nch, (1u << nch) - 1); /* nResidualChEncode: all original */
    bw_put(&b, 5, 23);                          /* bit resolution 24 */
    bw_put(&b, 5, 23);                          /* storage width 24 */
    bw_put(&b, 4, 12);                          /* 48 kHz */
    bw_put(&b, 2, 0);                           /* nFsInterpolate */
    bw_put(&b, 2, 0);                           /* nReplacementSet */
    bw_put(&b, 1, 1);                           /* bPrimaryChSet */
    bw_put(&b, 1, 0);                           /* bDownmixCoeffCodeEmbedded */
    bw_put(&b, 1, 0);                           /* bHierChSet: 0 as in official single-set streams (1 also decodes) */
    bw_put(&b, 1, 1);                           /* bChMaskEnabled */
    bw_put(&b, CHMASK_BITS, nch == 1 ? 0x01 : 0x06); /* C | L,R (per speaker, §5) */
    bw_put(&b, 1, 0);                           /* bPWChDecorEnabled */
    for (int c = 0; c < nch; c++)
        bw_put(&b, 4, 0);                       /* nAdaptPredOrder */
    for (int c = 0; c < nch; c++)
        bw_put(&b, 2, (uint32_t)e->order[c]);   /* nFixedPredOrder */
    bw_align(&b);
    size_t size = b.pos + 2;
    if (b.err || size > cap)
        return 0;
    patch_bits(dst, 0, 10, (uint32_t)(size - 1));
    put_crc(dst + b.pos, dtshdma_crc16(dst, b.pos));
    return size;
}

/* NAVI (§4.3). Returns its size N. */
static size_t write_navi(const dtshdma_enc *e, unsigned sbits, uint8_t *dst, size_t cap)
{
    bw b;
    bw_init(&b, dst, cap);
    for (int s = 0; s < NSEG; s++)
        bw_put(&b, sbits, (uint32_t)(e->seg_bytes[s] - 1));
    bw_align(&b);
    if (b.err || b.pos + 2 > cap)
        return 0;
    put_crc(dst + b.pos, dtshdma_crc16(dst, b.pos));
    return b.pos + 2;
}

/* XLL common header (§4.1). Returns its size C. */
static size_t write_common(unsigned fbits, size_t frame_size, unsigned sbits,
                           uint8_t *dst, size_t cap)
{
    bw b;
    bw_init(&b, dst, cap);
    bw_put(&b, 32, SYNC_XLL);
    bw_put(&b, 4, 0);                          /* version 1 */
    bw_put(&b, 8, 0);                          /* header size-1, patched */
    bw_put(&b, 5, fbits - 1);
    bw_put(&b, fbits, (uint32_t)(frame_size - 1));
    bw_put(&b, 4, 0);                          /* one channel set */
    bw_put(&b, 4, 1);                          /* log2 segments = 1 */
    bw_put(&b, 4, SEG_LOG2);                   /* log2 samples per segment */
    bw_put(&b, 5, sbits - 1);
    bw_put(&b, 2, 0);                          /* nBandDataCRCEn */
    bw_put(&b, 1, 0);                          /* bScalableLSBs */
    bw_put(&b, 5, CHMASK_BITS - 1);
    bw_align(&b);
    size_t size = b.pos + 2;
    if (b.err || size > cap)
        return 0;
    patch_bits(dst, 36, 8, (uint32_t)(size - 1));
    put_crc(dst + b.pos, dtshdma_crc16(dst + 4, b.pos - 4));
    return size;
}

/* ExSS header with one asset descriptor (§3). Returns its size H. */
static size_t write_exss(const dtshdma_config *cfg, size_t xll_size, uint8_t *dst, size_t cap)
{
    const int official = cfg->exss_profile == DTSHDMA_EXSS_OFFICIAL;
    const int nch = cfg->channels;
    bw b;
    bw_init(&b, dst, cap);
    bw_put(&b, 32, SYNC_EXSS);
    bw_put(&b, 8, 0);                          /* UserDefinedBits */
    bw_put(&b, 2, 0);                          /* nExtSSIndex */
    bw_put(&b, 1, 0);                          /* bHeaderSizeType: short */
    bw_put(&b, 8, 0);                          /* header size-1 (bit 43), patched */
    bw_put(&b, 16, 0);                         /* ExSS size-1 (bit 51), patched */
    bw_put(&b, 1, 1);                          /* bStaticFieldsPresent */
    bw_put(&b, 2, 2);                          /* nuRefClockCode: 48 kHz */
    bw_put(&b, 3, 0);                          /* frame duration 512 */
    bw_put(&b, 1, 0);                          /* bTimeStampFlag */
    bw_put(&b, 3, 0);                          /* presentations-1 */
    bw_put(&b, 3, 0);                          /* assets-1 */
    bw_put(&b, 1, 1);                          /* nuActiveExSSMask */
    bw_put(&b, 8, 1);                          /* nuActiveAssetMask */
    bw_put(&b, 1, 0);                          /* bMixMetadataEnbl */
    bw_put(&b, 16, (uint32_t)(xll_size - 1));  /* nuAssetFsize-1 */

    /* asset descriptor (§3.2) */
    const size_t dpos = bw_bits(&b);
    bw_put(&b, 9, 0);                          /* descriptor size-1, patched */
    bw_put(&b, 3, 0);                          /* nuAssetIndex */
    bw_put(&b, 1, 0);                          /* bAssetTypeDescrPresent */
    bw_put(&b, 1, 0);                          /* bLanguageDescrPresent */
    bw_put(&b, 1, 0);                          /* bInfoTextPresent */
    bw_put(&b, 5, 23);                         /* bit resolution 24 */
    bw_put(&b, 4, 12);                         /* max sample rate 48 kHz */
    bw_put(&b, 8, (uint32_t)(nch - 1));        /* nuTotalNumChs-1 */
    bw_put(&b, 1, 1);                          /* bOne2OneMapChannels2Speakers */
    bw_put(&b, 1, 1);                          /* bSpkrMaskEnabled */
    bw_put(&b, 2, 1);                          /* 8-bit speaker mask */
    bw_put(&b, 8, nch == 1 ? 0x01 : 0x02);     /* C | LR pair (pair-based, §5) */
    bw_put(&b, 3, 0);                          /* nuNumSpkrRemapSets */
    bw_put(&b, 1, 0);                          /* bDRCCoefPresent */
    bw_put(&b, 1, 0);                          /* bDialNormPresent */
    bw_put(&b, 2, 0);                          /* nuCodingMode 0 */
    bw_put(&b, 12, official ? 0x201 : 0x200);  /* nuCoreExtensionMask */
    bw_put(&b, 16, (uint32_t)(xll_size - 1));  /* nuExSSXLLFsize-1 */
    if (official) {
        bw_put(&b, 1, 1);                      /* bExSSXLLSyncPresent */
        bw_put(&b, 4, 0);                      /* nuPeakBRCntrlBuffSzkB */
        bw_put(&b, 5, 0);                      /* nuBitsInitDecDly-1 */
        bw_put(&b, 1, 0);                      /* nuInitLLDecDlyFrames */
        bw_put(&b, 16, 0);                     /* nuExSSXLLSyncOffset */
    } else {
        bw_put(&b, 1, 0);
    }
    bw_put(&b, 3, 0);                          /* nuDTSHDStreamID */
    bw_put(&b, 1, 0);                          /* bDecodeAssetInSecondaryDecoder */
    bw_put(&b, 1, 0);                          /* bDRCMetadataRev2Present */
    size_t dbits = bw_bits(&b) - dpos;
    size_t dbytes = (dbits + 7) / 8;
    if (official)
        dbytes = (dbytes + 3) & ~(size_t)3;     /* 16 bytes */
    bw_zeros(&b, (uint32_t)(dbytes * 8 - dbits));

    bw_put(&b, 1, 1);                          /* bBcCorePresent */
    bw_put(&b, 2, 0);                          /* nuBcCoreExtSSIndex */
    bw_put(&b, 3, 0);                          /* nuBcCoreAssetIndex */
    bw_align(&b);
    const size_t target = official ? 36 : 0;
    while ((b.pos + 2) % 4 || b.pos + 2 < target)
        bw_put(&b, 8, 0);
    const size_t size = b.pos + 2;
    if (b.err || size > cap)
        return 0;
    patch_bits(dst, dpos, 9, (uint32_t)(dbytes - 1));
    patch_bits(dst, 43, 8, (uint32_t)(size - 1));
    patch_bits(dst, 51, 16, (uint32_t)(size + xll_size - 1));
    put_crc(dst + b.pos, dtshdma_crc16(dst + 5, b.pos - 5));
    return size;
}

long dtshdma_core_frame_size(const uint8_t *core, size_t core_size)
{
    if (!core || core_size < 16)
        return DTSHDMA_ERR_CORE;
    if (read_bits(core, 0, 32) != SYNC_CORE)
        return DTSHDMA_ERR_CORE;
    long fsize = (long)read_bits(core, 46, 14) + 1;
    if (fsize < 96 || (size_t)fsize > core_size)
        return DTSHDMA_ERR_CORE;
    return fsize;
}

/* ------------------------------------------------------------------ */

long dtshdma_enc_encode_unit(dtshdma_enc *e, const int32_t *const pcm[],
                             const uint8_t *core, size_t core_size,
                             uint8_t *out, size_t out_cap,
                             dtshdma_unit_info *info)
{
    if (!e || !pcm || !out)
        return DTSHDMA_ERR_ARG;
    const int nch = e->cfg.channels;
    for (int c = 0; c < nch; c++)
        if (!pcm[c])
            return DTSHDMA_ERR_ARG;

    /* core frame (§2) */
    long fsize = dtshdma_core_frame_size(core, core_size);
    if (fsize < 0)
        return fsize;
    uint32_t amode = read_bits(core, 60, 6), sfreq = read_bits(core, 66, 4);
    uint32_t nblks = read_bits(core, 39, 7), lff = read_bits(core, 85, 2);
    /* 48 kHz, matching channel mode, 512 samples ((NBLKS + 1) * 32), no LFE */
    if (sfreq != 13 || amode != (nch == 1 ? 0u : 2u) || nblks != 15 || lff != 0)
        return DTSHDMA_ERR_CORE_FMT;
    const size_t core_out = ((size_t)fsize + 3) & ~(size_t)3;

    /* input, clamped to 24 bits (T15) */
    int clipped = 0;
    for (int c = 0; c < nch; c++) {
        for (int i = 0; i < DTSHDMA_UNIT_SAMPLES; i++) {
            int32_t v = pcm[c][i];
            if (v > 8388607) { v = 8388607; clipped++; }
            else if (v < -8388608) { v = -8388608; clipped++; }
            e->x[c][i] = v;
        }
    }

    for (int c = 0; c < nch; c++)
        analyse_channel(e, c);
    for (int s = 0; s < NSEG; s++) {
        int r = write_segment(e, s);
        if (r < 0)
            return r;
    }

    /* sizes (§4.7) */
    const unsigned minbits = (unsigned)e->cfg.min_size_bits;
    size_t gmax = e->seg_bytes[0] > e->seg_bytes[1] ? e->seg_bytes[0] : e->seg_bytes[1];
    unsigned sbits = bitlen((uint32_t)(gmax - 1));
    if (sbits < minbits)
        sbits = minbits;

    uint8_t sub[32], navi[16], hdr[32];
    size_t B = write_subheader(e, sub, sizeof(sub));
    size_t N = write_navi(e, sbits, navi, sizeof(navi));
    if (!B || !N)
        return DTSHDMA_ERR_INTERNAL;
    const size_t body = B + N + e->seg_bytes[0] + e->seg_bytes[1];

    unsigned fbits = minbits;
    size_t C, L;
    for (;;) {
        C = write_common(fbits, 1, sbits, hdr, sizeof(hdr));
        if (!C)
            return DTSHDMA_ERR_INTERNAL;
        L = (C + body + 3) & ~(size_t)3;
        unsigned need = bitlen((uint32_t)(L - 1));
        if (need <= fbits)
            break;
        fbits = need;
    }
    if (L > 65535 - 64)
        return DTSHDMA_ERR_INTERNAL;
    C = write_common(fbits, L, sbits, hdr, sizeof(hdr));

    /* assemble */
    uint8_t exss[64];
    size_t H = write_exss(&e->cfg, L, exss, sizeof(exss));
    if (!H)
        return DTSHDMA_ERR_INTERNAL;
    const size_t total = core_out + H + L;
    if (total > out_cap)
        return DTSHDMA_ERR_SPACE;

    uint8_t *p = out;
    memcpy(p, core, (size_t)fsize);
    memset(p + fsize, 0, core_out - (size_t)fsize);
    /* Core header: CHIST = 1 (bits 93-94) and PCMR = 6 (24-bit source, bits
     * 95-97) as official encoders write them; FFmpeg's dcaenc writes 0/0.
     * Only without a header CRC (CPF, bit 38 = 0): with CPF = 1 the fields
     * move by 16 bits and the CRC would have to be redone. */
    if (e->cfg.patch_core_header && read_bits(p, 38, 1) == 0) {
        patch_bits(p, 93, 2, 1);
        patch_bits(p, 95, 3, 6);
    }
    p += core_out;
    memcpy(p, exss, H); p += H;
    memcpy(p, hdr, C); p += C;
    memcpy(p, sub, B); p += B;
    memcpy(p, navi, N); p += N;
    for (int s = 0; s < NSEG; s++) {
        memcpy(p, e->seg[s], e->seg_bytes[s]);
        p += e->seg_bytes[s];
    }
    memset(p, 0, (size_t)(out + total - p));   /* DWORD padding (T17: zeros) */

    if (info) {
        memset(info, 0, sizeof(*info));
        info->core_bytes = core_out;
        info->exss_bytes = H;
        info->xll_bytes = L;
        info->clipped = clipped;
        for (int c = 0; c < nch; c++) {
            info->fixed_order[c] = e->order[c];
            for (int s = 0; s < NSEG; s++) {
                info->code[s][c] = e->code[c][s].type;
                info->param[s][c] = e->code[c][s].param;
            }
        }
    }
    return (long)total;
}
