"""Measure libswresample settings vs soxr for X -> 48 kHz.

usage: python3 -I swrtune.py <mode> [args]
  mode grid   : 44.1k grid over filter_size x cutoff x kaiser_beta (+ defaults, soxr)
  mode rates  : run named configs at 32k/44.1k/88.2k/96k
Outputs JSON lines to stdout and results/*.json.
"""
import json, os, subprocess, sys, itertools
import numpy as np

D = os.path.dirname(os.path.abspath(__file__))
SWRH = os.path.join(D, "swrh")            # n7.0.2 host build
SWRH_BREW = os.path.join(D, "swrh_brew")  # Homebrew 9.0.2 libswresample (for soxr)
SIG = os.path.join(D, "sig"); os.makedirs(SIG, exist_ok=True)
RES = os.path.join(D, "results"); os.makedirs(RES, exist_ok=True)
OUT = 48000
AMP = 10 ** (-1 / 20)

# 7-term Blackman-Harris (sidelobes ~ -180 dB)
BH7 = [0.27105140069342, 0.43329793923448, 0.21812299954311, 0.06592544638803,
       0.01081174209837, 0.00077658482522, 0.00001388721735]


def bh7(n):
    k = np.arange(n)
    w = np.zeros(n)
    for i, a in enumerate(BH7):
        w += (-1) ** i * a * np.cos(2 * np.pi * i * k / n)
    return w


def write(name, x):
    p = os.path.join(SIG, name)
    if not os.path.exists(p):
        np.ascontiguousarray(np.stack([x, x[::-1] if False else x], 1).astype(np.float32)).tofile(p)
    return p


def tones_for(fs):
    if fs == 44100:
        return [1000, 10000, 15000, 20000, 21000, 21800]
    if fs == 32000:
        return [1000, 10000, 14000, 15000, 15500]
    # downsampling: in-band tones + alias probes above 24 kHz
    return [1000, 10000, 20000, 21800, 25000, 28000, 30000, 40000]


def passband_top(fs):
    return 15000 if fs == 32000 else 20000


def ripple_freqs(fs):
    top = passband_top(fs)
    f = np.unique(np.concatenate([np.geomspace(20, top, 60), np.linspace(1000, top, 60), [top]]))
    return [float(v) for v in f]


SEG_T = 0.3
TONE_T = 5.0


def make_signals(fs):
    rng = np.random.default_rng(1)
    sig = {}
    # tones, concatenated
    n = int(TONE_T * fs)
    t = np.arange(n) / fs
    sig["tones"] = write(f"tones_{fs}.f32", np.concatenate([AMP * np.sin(2 * np.pi * f * t + rng.uniform(0, 6.28)) for f in tones_for(fs)]))
    # ripple segments
    n = int(SEG_T * fs)
    t = np.arange(n) / fs
    sig["ripple"] = write(f"ripple_{fs}.f32", np.concatenate([0.5 * np.sin(2 * np.pi * f * t) for f in ripple_freqs(fs)]))
    # impulse
    x = np.zeros(fs); x[1000] = 0.5
    sig["imp"] = write(f"imp_{fs}.f32", x)
    # 60 s white noise (CPU + image band)
    sig["noise"] = write(f"noise_{fs}.f32", 0.25 * rng.standard_normal(60 * fs))
    return sig


def run(exe, fs, inp, opts, out):
    cmd = [exe, str(fs), str(OUT), inp, out] + [f"{k}={v}" for k, v in opts.items()]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode:
        raise RuntimeError(r.stderr.strip())
    return json.loads(r.stdout)


def load(p):
    return np.fromfile(p, np.float32).reshape(-1, 2)[:, 0].astype(np.float64)


def fold(f, fs=OUT):
    f = f % fs
    return fs - f if f > fs / 2 else f


def analyse(exe, fs, opts, tag, cpu_runs=3):
    sig = make_signals(fs)
    tmp = os.path.join(D, "tmp_out.f32")
    res = {"tag": tag, "fs": fs, "opts": opts}
    # --- impulse: delay
    info = run(exe, fs, sig["imp"], opts, tmp)
    y = load(tmp)
    k = int(np.argmax(np.abs(y)))
    # parabolic peak interpolation
    if 0 < k < len(y) - 1:
        a, b, c = y[k - 1], y[k], y[k + 1]
        kf = k + 0.5 * (a - c) / (a - 2 * b + c)
    else:
        kf = k
    res["delay_peak_out"] = round(kf - 1000 * OUT / fs, 2)
    res["delay_get_delay_out"] = info["delay_primed_out"]
    res["eff"] = {k2: info[k2] for k2 in ("filter_size", "phase_shift", "exact_rational", "linear_interp", "cutoff", "kaiser_beta")}
    res["tail_lost_without_flush"] = info["nout"] - info["nout_noflush"]
    # --- ripple
    run(exe, fs, sig["ripple"], opts, tmp)
    y = load(tmp)
    gains = []
    for i, f in enumerate(ripple_freqs(fs)):
        s0 = int((i * SEG_T + 0.05) * OUT); s1 = int(((i + 1) * SEG_T - 0.05) * OUT)
        tt = np.arange(s0, s1) / OUT
        A = np.stack([np.sin(2 * np.pi * f * tt), np.cos(2 * np.pi * f * tt), np.ones_like(tt)], 1)
        cf, *_ = np.linalg.lstsq(A, y[s0:s1], rcond=None)
        gains.append(20 * np.log10(np.hypot(cf[0], cf[1]) / 0.5))
    gains = np.array(gains)
    res["ripple_db"] = round(float(gains.max() - gains.min()), 5)
    res["gain_top_db"] = round(float(gains[-1]), 5)
    res["passband_top"] = passband_top(fs)
    # --- tones: spurs
    run(exe, fs, sig["tones"], opts, tmp)
    y = load(tmp)
    N = 1 << 17
    w = bh7(N); wsum = w.sum()
    freqs = np.fft.rfftfreq(N, 1 / OUT)
    tones = []
    for i, f in enumerate(tones_for(fs)):
        c = int((i + 0.5) * TONE_T * OUT)
        seg = y[c - N // 2: c + N // 2]
        mag = np.abs(np.fft.rfft(seg * w)) * 2 / wsum
        db = 20 * np.log10(mag / AMP + 1e-30)   # relative to INPUT carrier amplitude
        fo = fold(f)
        carrier_out = None
        if f < OUT / 2:  # exact-frequency least-squares fit (no scalloping)
            tt = np.arange(c - N // 2, c + N // 2) / OUT
            A = np.stack([np.sin(2 * np.pi * f * tt), np.cos(2 * np.pi * f * tt)], 1)
            cf, *_ = np.linalg.lstsq(A, seg, rcond=None)
            carrier_out = float(20 * np.log10(np.hypot(*cf) / AMP))
        excl = np.zeros_like(db, bool)
        if f < OUT / 2:
            kc = fo * N / OUT
            excl |= np.abs(np.arange(len(db)) - kc) < 24
        excl[:24] = True  # DC region
        spur_k = int(np.argmax(np.where(excl, -999, db)))
        img = fold(fs - f) if f < fs / 2 else None
        img_db = None
        if img is not None:
            ki = int(round(img * N / OUT))
            img_db = float(db[max(ki - 3, 0): ki + 4].max())
        tones.append({"f": f, "carrier_db": None if carrier_out is None else round(carrier_out, 3),
                      "image_hz": img, "image_db": None if img_db is None else round(img_db, 1),
                      "worst_spur_db": round(float(db[spur_k]), 1), "worst_spur_hz": round(float(freqs[spur_k]), 1)})
    res["tones"] = tones
    # --- noise: CPU and empty-band energy (upsampling only)
    best = 1e9
    for _ in range(cpu_runs):
        info = run(exe, fs, sig["noise"], opts, tmp)
        best = min(best, info["cpu_s"])
    res["cpu_s_60s"] = round(best, 4)
    if fs < OUT:
        y = load(tmp)
        M = 1 << 14
        segs = y[OUT: OUT + (len(y) - 2 * OUT) // M * M].reshape(-1, M)
        wn = bh7(M)
        P = (np.abs(np.fft.rfft(segs * wn, axis=1)) ** 2).mean(0)
        fr = np.fft.rfftfreq(M, 1 / OUT)
        pb = P[(fr > 1000) & (fr < passband_top(fs))].mean()
        lo = fs / 2 + 300
        band = (fr > lo) & (fr < OUT / 2 - 100)
        res["noise_image_band"] = [round(lo), OUT // 2 - 100]
        res["noise_image_db"] = round(float(10 * np.log10(P[band].mean() / pb)), 1)
        res["noise_image_peak_db"] = round(float(10 * np.log10(P[band].max() / pb)), 1)
    os.remove(tmp)
    return res


SOXR = {"resampler": "soxr", "precision": 28}


def main():
    mode = sys.argv[1]
    rows = []
    if mode == "grid":
        cfgs = [("default", SWRH, {}), ("soxr28", SWRH_BREW, SOXR), ("default_brew", SWRH_BREW, {})]
        for fsz, co, kb in itertools.product([32, 64, 128, 256, 512], [0.85, 0.86, 0.87, 0.875, 0.88, 0.89, 0.91, 0.93, 0.95, 0.97], [9, 12, 16]):
            cfgs.append((f"fs{fsz}_c{co}_b{kb}", SWRH, {"filter_size": fsz, "cutoff": co, "kaiser_beta": kb}))
        out = os.path.join(RES, "grid44k.jsonl")
        with open(out, "w") as fh:
            for tag, exe, opts in cfgs:
                r = analyse(exe, 44100, opts, tag, cpu_runs=3)
                r["lib"] = "brew-9.0.2" if exe == SWRH_BREW else "n7.0.2"
                fh.write(json.dumps(r) + "\n"); fh.flush()
                print(tag, r["ripple_db"], r["gain_top_db"], [t["worst_spur_db"] for t in r["tones"]], r.get("noise_image_db"), r["cpu_s_60s"], flush=True)
    elif mode == "refine":
        out = os.path.join(RES, "refine44k.jsonl")
        cfgs = [("default", SWRH, {})]
        for fsz, co, kb in itertools.product([128, 144, 160, 192], [0.865, 0.87, 0.875, 0.88], [11, 12, 13, 14]):
            cfgs.append((f"fs{fsz}_c{co}_b{kb}", SWRH, {"filter_size": fsz, "cutoff": co, "kaiser_beta": kb}))
        with open(out, "w") as fh:
            for tag, exe, opts in cfgs:
                r = analyse(exe, 44100, opts, tag, cpu_runs=3)
                r["lib"] = "n7.0.2"
                fh.write(json.dumps(r) + "\n"); fh.flush()
    elif mode == "rates":
        cfgs = json.loads(sys.argv[2])  # {"tag": {opts}}
        out = os.path.join(RES, "rates.jsonl")
        with open(out, "w") as fh:
            for fs in (32000, 44100, 88200, 96000):
                for tag, opts in cfgs.items():
                    exe = SWRH_BREW if opts.get("resampler") == "soxr" else SWRH
                    r = analyse(exe, fs, opts, tag, cpu_runs=3)
                    r["lib"] = "brew-9.0.2" if exe == SWRH_BREW else "n7.0.2"
                    fh.write(json.dumps(r) + "\n"); fh.flush()
                    print(fs, tag, json.dumps(r), flush=True)


if __name__ == "__main__":
    main()
